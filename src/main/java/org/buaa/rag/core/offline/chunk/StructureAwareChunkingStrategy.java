package org.buaa.rag.core.offline.chunk;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.springframework.util.StringUtils;

/**
 * 结构感知分块策略。
 *
 * <p>本策略面向高校政策文件（PDF / Word 经 Tika 结构化提取后）与 Markdown
 * 两类来源，通过识别三类语义边界将文本切分为粒度适中的语义块：
 *
 * <ol>
 *   <li><b>HEADING（标题行）</b>：Markdown {@code # ...} 前缀行，以及
 *       高校体制文本常见的"第X条/章/节"、"（一）"、"一、"等编号格式。
 *       每个标题行作为独立块，在打包时始终保留在所属切片的开头，
 *       确保每个切片具备明确的主题归属（语义锚点）。</li>
 *   <li><b>PARA（普通段落）</b>：相邻非空文本行累积成段落块，遇到空行时收尾。</li>
 *   <li><b>TABLE_ROW（表格行）</b>：由 Tika 提取的以制表符分隔的行，
 *       整体作为独立块，防止跨行切分破坏表格语义。</li>
 * </ol>
 *
 * <p>识别完毕后，分块器以贪心策略将相邻块合并打包，确保每个切片的长度
 * 落在 [minChars, maxChars] 区间内。最后对相邻切片注入重叠文本，
 * 降低边界处信息截断的影响。
 *
 * <p>注意：原有的 Markdown 代码围栏（{@code ```}）识别已移除。
 * 高校文档中代码块极少出现，且 Tika XHTML 提取后 {@code <pre>} 内容
 * 会以普通段落形式输出，无需单独处理。
 */
public class StructureAwareChunkingStrategy {

    // Markdown 风格标题：# ~ ######
    private static final Pattern MARKDOWN_HEADING =
        Pattern.compile("^#{1,6}\\s+.+$");

    // 高校体制文本：第X条 / 第X章 / 第X节（支持中文数字和阿拉伯数字）
    private static final Pattern CHINESE_ARTICLE =
        Pattern.compile("^第[一二三四五六七八九十百千零\\d]+[条章节]\\s*.+$");

    // 带括号的中文序号：（一）（二）…（十）
    private static final Pattern CHINESE_BRACKET_NUM =
        Pattern.compile("^（[一二三四五六七八九十百千]+）.+$");

    // 顿号序号：一、二、…（行首，后接非空内容）
    private static final Pattern CHINESE_DANG_NUM =
        Pattern.compile("^[一二三四五六七八九十百千]+、.+$");

    // 阿拉伯数字多级编号：1. / 1.1 / 1.1.1（行首）
    private static final Pattern NUMERIC_HEADING =
        Pattern.compile("^\\d+(\\.\\d+){0,2}\\s+.{2,}$");

    // Tika 表格行：包含制表符的行
    private static final Pattern TABLE_ROW =
        Pattern.compile(".*\\t.*");

    public List<String> chunk(String text, ChunkingOptions options) {
        if (!StringUtils.hasText(text)) {
            return List.of();
        }

        int targetChars = options.param("targetChars", 1400);
        int maxChars    = options.param("maxChars",    1800);
        int minChars    = options.param("minChars",     600);
        int overlapChars = options.param("overlapChars",  0);

        List<Block> blocks = segmentBlocks(text);
        if (blocks.isEmpty()) {
            return List.of(text.trim());
        }
        List<int[]> ranges = packBlocks(blocks, minChars, targetChars, maxChars);
        return materialize(text, ranges, overlapChars);
    }

    // -------------------------------------------------------------------------
    // 阶段一：逐行扫描，识别 HEADING / TABLE_ROW / PARA 三类结构块
    // -------------------------------------------------------------------------

    private List<Block> segmentBlocks(String text) {
        List<Block> blocks = new ArrayList<>();
        int n = text.length();
        int pos = 0;
        boolean inPara = false;
        int paraStart = -1;

        while (pos < n) {
            int lineEnd   = indexOfNl(text, pos);
            int lineEndNl = (lineEnd < n && text.charAt(lineEnd) == '\n') ? lineEnd + 1 : lineEnd;
            String line    = text.substring(pos, lineEnd);
            String trimmed = line.strip();

            if (trimmed.isEmpty()) {
                // 空行：终止当前段落块
                if (inPara) {
                    blocks.add(new Block(paraStart, pos, BlockType.PARA));
                    inPara = false;
                }
                pos = lineEndNl;
                continue;
            }

            if (isHeading(trimmed)) {
                // 标题行：先收尾段落，再单独成块（语义锚点）
                if (inPara) {
                    blocks.add(new Block(paraStart, pos, BlockType.PARA));
                    inPara = false;
                }
                blocks.add(new Block(pos, lineEndNl, BlockType.HEADING));
                pos = lineEndNl;
                continue;
            }

            if (TABLE_ROW.matcher(trimmed).matches()) {
                // 表格行：先收尾段落，再单独成块（不跨行切分）
                if (inPara) {
                    blocks.add(new Block(paraStart, pos, BlockType.PARA));
                    inPara = false;
                }
                blocks.add(new Block(pos, lineEndNl, BlockType.TABLE_ROW));
                pos = lineEndNl;
                continue;
            }

            // 普通文本：累积进当前段落
            if (!inPara) {
                inPara = true;
                paraStart = pos;
            }
            pos = lineEndNl;
        }

        // 文件末尾未闭合的段落
        if (inPara) {
            blocks.add(new Block(paraStart, n, BlockType.PARA));
        }
        return blocks;
    }

    /** 判断一行是否为任意类型的标题。 */
    private boolean isHeading(String trimmed) {
        return MARKDOWN_HEADING.matcher(trimmed).matches()
            || CHINESE_ARTICLE.matcher(trimmed).matches()
            || CHINESE_BRACKET_NUM.matcher(trimmed).matches()
            || CHINESE_DANG_NUM.matcher(trimmed).matches()
            || NUMERIC_HEADING.matcher(trimmed).matches();
    }

    // -------------------------------------------------------------------------
    // 阶段二：贪心打包——相邻小块合并至目标体量
    // -------------------------------------------------------------------------

    private List<int[]> packBlocks(List<Block> blocks, int minChars, int targetChars, int maxChars) {
        List<int[]> ranges = new ArrayList<>();
        int i = 0;
        while (i < blocks.size()) {
            int chunkStart = blocks.get(i).start();
            int chunkEnd   = blocks.get(i).end();
            int size       = chunkEnd - chunkStart;

            int j = i + 1;
            while (j < blocks.size()) {
                // HEADING 块作为语义边界：若下一块是标题且当前已达到最小体量，则停止
                if (blocks.get(j).type() == BlockType.HEADING
                    && size >= Math.max(minChars, targetChars / 3)) {
                    break;
                }
                int candidateEnd  = blocks.get(j).end();
                int candidateSize = candidateEnd - chunkStart;
                // 超过上限时，仅在已满足最小体量的情况下停止
                if (candidateSize > maxChars && size >= Math.max(minChars, targetChars / 3)) {
                    break;
                }
                chunkEnd = candidateEnd;
                size = candidateSize;
                j++;
                if (size >= targetChars) {
                    break;
                }
            }
            ranges.add(new int[]{chunkStart, chunkEnd});
            i = j;
        }

        // 尾块过短时合并到前一块，减少"碎片 chunk"
        if (ranges.size() >= 2) {
            int[] last = ranges.get(ranges.size() - 1);
            if (last[1] - last[0] < Math.max(200, minChars / 2)) {
                int[] prev = ranges.get(ranges.size() - 2);
                if (last[1] - prev[0] <= maxChars * 2) {
                    prev[1] = last[1];
                    ranges.remove(ranges.size() - 1);
                }
            }
        }
        return ranges;
    }

    // -------------------------------------------------------------------------
    // 阶段三：物化切片，注入重叠文本
    // -------------------------------------------------------------------------

    private List<String> materialize(String text, List<int[]> ranges, int overlapChars) {
        List<String> chunks = new ArrayList<>();
        String previousTail = null;
        for (int[] range : ranges) {
            String body = text.substring(range[0], range[1]).trim();
            if (!StringUtils.hasText(body)) {
                continue;
            }
            if (overlapChars > 0 && previousTail != null && !previousTail.isBlank()) {
                body = previousTail + "\n" + body;
            }
            chunks.add(body);
            if (overlapChars > 0) {
                previousTail = tail(body, overlapChars);
            }
        }
        return chunks;
    }

    // -------------------------------------------------------------------------
    // 工具方法
    // -------------------------------------------------------------------------

    private int indexOfNl(String text, int from) {
        int idx = text.indexOf('\n', from);
        return idx < 0 ? text.length() : idx;
    }

    private String tail(String text, int chars) {
        if (chars <= 0 || text.length() <= chars) return text;
        return text.substring(text.length() - chars);
    }

    // -------------------------------------------------------------------------
    // 数据结构
    // -------------------------------------------------------------------------

    private enum BlockType { HEADING, PARA, TABLE_ROW }

    private record Block(int start, int end, BlockType type) {}
}
