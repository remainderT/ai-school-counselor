package org.buaa.rag.core.offline.parser;

import java.io.InputStream;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.tika.extractor.EmbeddedDocumentExtractor;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.parser.pdf.PDFParserConfig;
import org.apache.tika.sax.ToXMLContentHandler;
import org.buaa.rag.common.enums.ParserType;
import org.springframework.stereotype.Component;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;

/**
 * Tika 通用解析器（结构化 XHTML 输出版）。
 * <p>
 * 相较于原始的 {@code BodyContentHandler} 纯文本输出，本实现改用
 * {@code ToXMLContentHandler} 获取带标签的 XHTML，再通过
 * {@link #convertXhtmlToStructuredText} 将 h1-h6 标题映射为 Markdown 风格的
 * {@code #} 前缀，将列表项映射为缩进文本，将表格单元格展开为独立行。
 * 经过结构还原后，PDF / Word / HTML 等二进制格式的文档能够携带语义层级信息，
 * 交给下游 {@code StructureAwareChunkingStrategy} 进行结构感知分块。
 */
@Component
public class TikaDocumentParser implements DocumentParser {

    private static final int DEFAULT_WRITE_LIMIT = 800000;

    // 匹配 XHTML 标签（不含嵌套属性中的 >）
    private static final Pattern TAG_PATTERN =
        Pattern.compile("<(/?)([a-zA-Z0-9]+)([^>]*)>");
    // 匹配 HTML 实体（&amp; &lt; &gt; &nbsp; &#x...;）
    private static final Pattern ENTITY_PATTERN =
        Pattern.compile("&(amp|lt|gt|quot|apos|nbsp|#x[0-9a-fA-F]+|#[0-9]+);");

    @Override
    public String getParserType() {
        return ParserType.TIKA.getType();
    }

    @Override
    public int getPriority() {
        return 100;
    }

    @Override
    public DocumentParseResult parse(InputStream stream,
                                     String fileName,
                                     String mimeType,
                                     Map<String, Object> options) throws Exception {
        int writeLimit = resolveIntOption(options, "maxExtractedChars", DEFAULT_WRITE_LIMIT);

        ToXMLContentHandler xmlHandler = new ToXMLContentHandler();

        Metadata metadata = new Metadata();
        AutoDetectParser parser = new AutoDetectParser();
        ParseContext context = new ParseContext();
        context.set(Parser.class, parser);
        context.set(EmbeddedDocumentExtractor.class, new NoOpEmbeddedExtractor());
        context.set(PDFParserConfig.class, buildPdfParserConfig(options));

        try {
            parser.parse(stream, xmlHandler, metadata, context);
        } catch (SAXException e) {
            if (!isWriteLimitReached(e)) {
                throw e;
            }
        }

        String xhtml = xmlHandler.toString();
        // 字符上限截断：超过 writeLimit 时截取（XHTML 模式无内置限制）
        if (xhtml.length() > writeLimit) {
            xhtml = xhtml.substring(0, writeLimit);
        }

        String structuredText = convertXhtmlToStructuredText(xhtml);

        Map<String, Object> meta = Map.of(
            "resourceName", metadata.get("resourceName") == null ? "" : metadata.get("resourceName"),
            "contentType", metadata.get(Metadata.CONTENT_TYPE) == null ? "" : metadata.get(Metadata.CONTENT_TYPE)
        );
        return DocumentParseResult.withMetadata(structuredText, meta);
    }

    @Override
    public boolean supports(String mimeType, String fileName) {
        return true;
    }

    // -------------------------------------------------------------------------
    // XHTML → 结构化文本转换
    // -------------------------------------------------------------------------

    /**
     * 将 Tika 输出的 XHTML 转换为带 Markdown 风格标题前缀的结构化纯文本。
     *
     * <p>转换规则：
     * <ul>
     *   <li>{@code <h1>…</h1>} → {@code # …}（一级标题）</li>
     *   <li>{@code <h2>…</h2>} → {@code ## …}（二级标题）</li>
     *   <li>…以此类推直到 {@code h6}</li>
     *   <li>{@code <li>…</li>} → {@code - …}（列表项）</li>
     *   <li>{@code <td>/<th>} 内容 → 独立行，单元格间以 {@code \t} 分隔</li>
     *   <li>{@code <p>/<div>} → 段落，前后插入空行</li>
     *   <li>其余块级标签 → 段落换行</li>
     *   <li>行内标签忽略，仅保留文本内容</li>
     * </ul>
     */
    String convertXhtmlToStructuredText(String xhtml) {
        if (xhtml == null || xhtml.isBlank()) {
            return "";
        }

        StringBuilder sb = new StringBuilder(xhtml.length());
        // 当前解析上下文
        String currentTag = "";
        // 是否在表格行内累积单元格
        boolean inTableRow = false;
        StringBuilder rowBuf = new StringBuilder();
        // 当前块级标签的文本缓冲（用于 h1-h6）
        boolean inHeading = false;
        int headingLevel = 0;
        StringBuilder headingBuf = new StringBuilder();

        int i = 0;
        int n = xhtml.length();

        while (i < n) {
            char c = xhtml.charAt(i);

            if (c == '<') {
                // 找到 '>' 的位置
                int closeAngle = xhtml.indexOf('>', i);
                if (closeAngle < 0) {
                    break;
                }
                String tagFull = xhtml.substring(i, closeAngle + 1);
                Matcher m = TAG_PATTERN.matcher(tagFull);

                if (m.matches()) {
                    boolean isClosing = "/".equals(m.group(1));
                    String tag = m.group(2).toLowerCase();

                    if (!isClosing) {
                        // 开标签
                        switch (tag) {
                            case "h1", "h2", "h3", "h4", "h5", "h6" -> {
                                inHeading = true;
                                headingLevel = tag.charAt(1) - '0';
                                headingBuf.setLength(0);
                                ensureNewline(sb);
                            }
                            case "p", "div" -> {
                                if (!inHeading) ensureBlankLine(sb);
                            }
                            case "br" -> sb.append('\n');
                            case "li" -> {
                                ensureNewline(sb);
                                sb.append("- ");
                            }
                            case "tr" -> {
                                inTableRow = true;
                                rowBuf.setLength(0);
                            }
                            case "td", "th" -> {
                                if (inTableRow && rowBuf.length() > 0) {
                                    rowBuf.append('\t');
                                }
                            }
                            default -> {
                                // 其他块级标签（table, ul, ol, blockquote, section, article）换行
                                if (isBlockTag(tag) && !inHeading) {
                                    ensureNewline(sb);
                                }
                            }
                        }
                    } else {
                        // 闭标签
                        switch (tag) {
                            case "h1", "h2", "h3", "h4", "h5", "h6" -> {
                                if (inHeading) {
                                    String headingText = headingBuf.toString().trim();
                                    if (!headingText.isEmpty()) {
                                        ensureNewline(sb);
                                        sb.append("#".repeat(headingLevel)).append(' ').append(headingText);
                                        sb.append('\n');
                                    }
                                    inHeading = false;
                                    headingLevel = 0;
                                }
                            }
                            case "p", "div" -> {
                                if (!inHeading) ensureBlankLine(sb);
                            }
                            case "li" -> sb.append('\n');
                            case "tr" -> {
                                if (inTableRow) {
                                    String row = rowBuf.toString().trim();
                                    if (!row.isEmpty()) {
                                        ensureNewline(sb);
                                        sb.append(row);
                                        sb.append('\n');
                                    }
                                    inTableRow = false;
                                }
                            }
                            default -> {
                                if (isBlockTag(tag) && !inHeading) {
                                    ensureNewline(sb);
                                }
                            }
                        }
                    }
                }
                i = closeAngle + 1;
            } else {
                // 文本节点：收集直到下一个 '<'
                int textEnd = xhtml.indexOf('<', i);
                if (textEnd < 0) textEnd = n;
                String raw = xhtml.substring(i, textEnd);
                String decoded = decodeEntities(raw);

                if (inHeading) {
                    headingBuf.append(decoded);
                } else if (inTableRow) {
                    rowBuf.append(decoded);
                } else {
                    sb.append(decoded);
                }
                i = textEnd;
            }
        }

        return normalizeWhitespace(sb.toString());
    }

    /** 确保当前位置是新行开头（若末尾不是换行则追加一个）。 */
    private void ensureNewline(StringBuilder sb) {
        if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '\n') {
            sb.append('\n');
        }
    }

    /** 确保当前位置前有空白行（段落间隔）。 */
    private void ensureBlankLine(StringBuilder sb) {
        int len = sb.length();
        if (len == 0) return;
        // 至少需要两个连续换行
        boolean hasOne = sb.charAt(len - 1) == '\n';
        boolean hasTwo = len >= 2 && sb.charAt(len - 2) == '\n';
        if (!hasOne) sb.append('\n');
        if (!hasTwo) sb.append('\n');
    }

    /** 判断是否为块级 HTML 标签。 */
    private boolean isBlockTag(String tag) {
        return switch (tag) {
            case "table", "ul", "ol", "blockquote", "section",
                 "article", "header", "footer", "nav", "aside",
                 "pre", "figure", "figcaption" -> true;
            default -> false;
        };
    }

    /** 解码常见 HTML 实体。 */
    private String decodeEntities(String text) {
        if (text.indexOf('&') < 0) return text;
        return text
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&nbsp;", " ");
    }

    /**
     * 后处理：清理提取文本中的噪音。
     * <ul>
     *   <li>去除行首尾多余空格，保留换行结构</li>
     *   <li>将多个连续空行压缩为最多两个（保留段落间距）</li>
     *   <li>去除孤立的页码行（纯数字行，如 
     *   PDF \u9875\u7801\u3001\u9875\u7709\u7b49\uff09</li>
     * </ul>
     */
    private String normalizeWhitespace(String text) {
        if (text == null) return "";
        String[] lines = text.split("\n", -1);
        StringBuilder out = new StringBuilder(text.length());
        int blankCount = 0;
        for (String line : lines) {
            String trimmed = line.stripTrailing();
            if (trimmed.isEmpty()) {
                blankCount++;
                // 最多保留一个空行（段落分隔）
                if (blankCount <= 1) {
                    out.append('\n');
                }
            } else {
                // 过滤孤立页码行：纯数字且不超过4位，去掉
                if (trimmed.matches("^\\d{1,4}$")) {
                    continue;
                }
                blankCount = 0;
                out.append(trimmed).append('\n');
            }
        }
        return out.toString().trim();
    }

    // -------------------------------------------------------------------------
    // Tika 配置辅助方法
    // -------------------------------------------------------------------------

    private PDFParserConfig buildPdfParserConfig(Map<String, Object> options) {
        PDFParserConfig config = new PDFParserConfig();
        config.setExtractInlineImages(false);
        config.setSortByPosition(true);
        boolean enableOcr = resolveBooleanOption(options, "enableOcr", false);
        config.setOcrStrategy(enableOcr
            ? PDFParserConfig.OCR_STRATEGY.AUTO
            : PDFParserConfig.OCR_STRATEGY.NO_OCR);
        return config;
    }

    private int resolveIntOption(Map<String, Object> options, String key, int defaultValue) {
        if (options == null || !options.containsKey(key) || options.get(key) == null) {
            return defaultValue;
        }
        Object value = options.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    private boolean resolveBooleanOption(Map<String, Object> options, String key, boolean defaultValue) {
        if (options == null || !options.containsKey(key) || options.get(key) == null) {
            return defaultValue;
        }
        Object value = options.get(key);
        if (value instanceof Boolean bool) {
            return bool;
        }
        return Boolean.parseBoolean(value.toString());
    }

    private boolean isWriteLimitReached(SAXException e) {
        if (e == null || e.getMessage() == null) {
            return false;
        }
        String msg = e.getMessage().toLowerCase();
        return msg.contains("write limit") || msg.contains("more than");
    }

    private static class NoOpEmbeddedExtractor implements EmbeddedDocumentExtractor {
        @Override
        public boolean shouldParseEmbedded(Metadata metadata) {
            return false;
        }

        @Override
        public void parseEmbedded(InputStream stream,
                                  ContentHandler handler,
                                  Metadata metadata,
                                  boolean outputHtml) {
            // no-op
        }
    }
}
