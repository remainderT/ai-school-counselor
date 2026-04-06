package org.buaa.rag.core.offline.parser;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

/**
 * 解析器选择器：根据 MIME 类型和文件名选出最匹配的 {@link DocumentParser}，
 * 匹配失败时降级到 Tika 通用解析器。
 */
@Component
public class DocumentParserSelector {

    private final List<DocumentParser> registeredParsers;
    private final Map<String, DocumentParser> parserByType;

    public DocumentParserSelector(List<DocumentParser> parsers) {
        this.registeredParsers = (parsers != null) ? List.copyOf(parsers) : List.of();
        this.parserByType = registeredParsers.stream()
            .collect(Collectors.toMap(
                DocumentParser::getParserType,
                Function.identity(),
                (existing, duplicate) -> existing
            ));
    }

    /**
     * 从已注册的解析器中选出最适合处理该文件的解析器。
     */
    public DocumentParser selectByMimeType(String mimeType, String fileName) {
        return registeredParsers.stream()
            .filter(p -> p.supports(mimeType, fileName))
            .min(Comparator.comparingInt(DocumentParser::getPriority))
            .orElseGet(this::fallbackParser);
    }

    private DocumentParser fallbackParser() {
        return parserByType.get(ParserType.TIKA.getType());
    }
}
