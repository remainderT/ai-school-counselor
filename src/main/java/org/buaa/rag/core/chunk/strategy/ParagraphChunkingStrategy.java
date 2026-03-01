package org.buaa.rag.core.chunk.strategy;

import java.util.ArrayList;
import java.util.List;

import org.buaa.rag.core.chunk.ChunkingMode;
import org.buaa.rag.core.chunk.ChunkingOptions;
import org.buaa.rag.core.chunk.ChunkingStrategy;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 段落分块策略
 */
@Component
public class ParagraphChunkingStrategy implements ChunkingStrategy {

    @Override
    public ChunkingMode getType() {
        return ChunkingMode.PARAGRAPH;
    }

    @Override
    public List<String> chunk(String text, ChunkingOptions options) {
        if (!StringUtils.hasText(text)) {
            return List.of();
        }
        int chunkSize = Math.max(1, options.getChunkSize());
        int overlap = Math.max(0, options.getOverlapSize());

        List<Span> paragraphs = splitParagraphs(text);
        if (paragraphs.isEmpty()) {
            return List.of(text.trim());
        }

        List<String> chunks = new ArrayList<>();
        int index = 0;
        int nextStart = paragraphs.get(0).start();
        while (index < paragraphs.size()) {
            Span first = paragraphs.get(index);
            int chunkStart = Math.max(nextStart, first.start());
            int chunkEnd = chunkStart;

            int cursor = index;
            while (cursor < paragraphs.size()) {
                Span span = paragraphs.get(cursor);
                int candidateEnd = span.end();
                int candidateSize = candidateEnd - chunkStart;
                if (candidateSize > chunkSize && chunkEnd > chunkStart) {
                    break;
                }
                chunkEnd = candidateEnd;
                cursor++;
                if (candidateSize >= chunkSize) {
                    break;
                }
            }

            String content = text.substring(chunkStart, chunkEnd).trim();
            if (StringUtils.hasText(content)) {
                chunks.add(content);
            }
            if (chunkEnd >= text.length()) {
                break;
            }
            nextStart = Math.max(chunkEnd - overlap, chunkStart);
            index = findParagraphIndex(paragraphs, nextStart);
        }
        return chunks;
    }

    private List<Span> splitParagraphs(String text) {
        List<Span> spans = new ArrayList<>();
        int len = text.length();
        int start = 0;
        int i = 0;
        while (i < len) {
            if (text.charAt(i) == '\n') {
                int j = i;
                while (j < len && text.charAt(j) == '\n') {
                    j++;
                }
                if (j - i >= 2) {
                    spans.add(new Span(start, i));
                    start = j;
                }
                i = j;
            } else {
                i++;
            }
        }
        if (start < len) {
            spans.add(new Span(start, len));
        }
        return spans;
    }

    private int findParagraphIndex(List<Span> spans, int start) {
        for (int i = 0; i < spans.size(); i++) {
            if (spans.get(i).end() > start) {
                return i;
            }
        }
        return spans.size();
    }

    private record Span(int start, int end) {
    }
}

