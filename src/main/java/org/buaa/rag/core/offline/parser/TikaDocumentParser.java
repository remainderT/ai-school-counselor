package org.buaa.rag.core.offline.parser;

import java.io.InputStream;
import java.util.Map;

import org.apache.tika.extractor.EmbeddedDocumentExtractor;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.parser.pdf.PDFParserConfig;
import org.apache.tika.sax.BodyContentHandler;
import org.buaa.rag.common.enums.ParserType;
import org.springframework.stereotype.Component;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;

/**
 * Tika 通用解析器
 */
@Component
public class TikaDocumentParser implements DocumentParser {

    private static final int DEFAULT_WRITE_LIMIT = 800000;


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
        BodyContentHandler handler = new BodyContentHandler(Math.max(2048, writeLimit));
        Metadata metadata = new Metadata();
        AutoDetectParser parser = new AutoDetectParser();
        ParseContext context = new ParseContext();
        context.set(Parser.class, parser);
        context.set(EmbeddedDocumentExtractor.class, new NoOpEmbeddedExtractor());
        context.set(PDFParserConfig.class, buildPdfParserConfig(options));

        try {
            parser.parse(stream, handler, metadata, context);
        } catch (SAXException e) {
            if (!isWriteLimitReached(e)) {
                throw e;
            }
        }

        Map<String, Object> meta = Map.of(
            "resourceName", metadata.get("resourceName") == null ? "" : metadata.get("resourceName"),
            "contentType", metadata.get(Metadata.CONTENT_TYPE) == null ? "" : metadata.get(Metadata.CONTENT_TYPE)
        );
        return DocumentParseResult.withMetadata(handler.toString(), meta);
    }

    @Override
    public boolean supports(String mimeType, String fileName) {
        return true;
    }

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
