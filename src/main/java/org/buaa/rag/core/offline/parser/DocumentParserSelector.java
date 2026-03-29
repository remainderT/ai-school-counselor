package org.buaa.rag.core.offline.parser;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

/**
 * 解析器选择器（策略模式）
 */
@Component
public class DocumentParserSelector {

    private final List<DocumentParser> parsers;
    private final Map<String, DocumentParser> parserMap;

    public DocumentParserSelector(List<DocumentParser> parsers) {
        this.parsers = parsers == null ? List.of() : List.copyOf(parsers);
        this.parserMap = this.parsers.stream().collect(Collectors.toMap(
            DocumentParser::getParserType,
            Function.identity(),
            (left, right) -> left
        ));
    }

    public DocumentParser selectByMimeType(String mimeType, String fileName) {
        return parsers.stream()
            .filter(parser -> parser.supports(mimeType, fileName))
            .min(Comparator.comparingInt(DocumentParser::getPriority))
            .orElseGet(this::defaultParser);
    }

    private DocumentParser defaultParser() {
        return parserMap.get(ParserType.TIKA.getType());
    }
}
