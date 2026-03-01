package org.buaa.rag.core.parser;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DocumentParserSelectorTest {

    @Test
    void shouldSelectMarkdownParserForMarkdownMime() {
        DocumentParserSelector selector = new DocumentParserSelector(List.of(
            new TikaDocumentParser(),
            new MarkdownDocumentParser()
        ));

        DocumentParser parser = selector.selectByMimeType("text/markdown", "policy.md");

        assertEquals(ParserType.MARKDOWN.getType(), parser.getParserType());
    }

    @Test
    void shouldFallbackToTikaForBinaryFiles() {
        DocumentParserSelector selector = new DocumentParserSelector(List.of(
            new TikaDocumentParser(),
            new MarkdownDocumentParser()
        ));

        DocumentParser parser = selector.selectByMimeType(
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "rule.docx"
        );

        assertEquals(ParserType.TIKA.getType(), parser.getParserType());
    }
}
