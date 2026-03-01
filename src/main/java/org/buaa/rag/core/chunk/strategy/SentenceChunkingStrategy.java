package org.buaa.rag.core.chunk.strategy;

import java.util.ArrayList;
import java.util.List;

import org.buaa.rag.core.chunk.ChunkingMode;
import org.buaa.rag.core.chunk.ChunkingOptions;
import org.buaa.rag.core.chunk.ChunkingStrategy;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 句子边界优先分块策略
 */
@Component
public class SentenceChunkingStrategy implements ChunkingStrategy {

    @Override
    public ChunkingMode getType() {
        return ChunkingMode.SENTENCE;
    }

    @Override
    public List<String> chunk(String text, ChunkingOptions options) {
        if (!StringUtils.hasText(text)) {
            return List.of();
        }
        int chunkSize = Math.max(1, options.getChunkSize());
        int overlap = Math.max(0, options.getOverlapSize());

        List<Span> sentences = splitSentences(text);
        if (sentences.isEmpty()) {
            return List.of(text.trim());
        }

        List<String> chunks = new ArrayList<>();
        int sentenceIndex = 0;
        int nextStart = sentences.get(0).start();
        while (sentenceIndex < sentences.size()) {
            Span first = sentences.get(sentenceIndex);
            int chunkStart = Math.max(nextStart, first.start());
            int chunkEnd = chunkStart;

            int cursor = sentenceIndex;
            while (cursor < sentences.size()) {
                Span span = sentences.get(cursor);
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
            sentenceIndex = findSentenceIndex(sentences, nextStart);
        }
        return chunks;
    }

    private List<Span> splitSentences(String text) {
        List<Span> spans = new ArrayList<>();
        int len = text.length();
        int start = 0;
        for (int i = 0; i < len; i++) {
            char c = text.charAt(i);
            if (!isBoundary(text, i, c)) {
                continue;
            }
            int end = i + 1;
            spans.add(new Span(start, end));
            start = end;
        }
        if (start < len) {
            spans.add(new Span(start, len));
        }
        return spans;
    }

    private boolean isBoundary(String text, int index, char c) {
        if (c == '。' || c == '！' || c == '？' || c == '；' || c == '\n') {
            return true;
        }
        if (c == '.' || c == '!' || c == '?' || c == ';') {
            return index + 1 >= text.length() || Character.isWhitespace(text.charAt(index + 1));
        }
        return false;
    }

    private int findSentenceIndex(List<Span> spans, int start) {
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
