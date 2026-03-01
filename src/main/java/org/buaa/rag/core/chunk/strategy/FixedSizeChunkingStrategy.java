package org.buaa.rag.core.chunk.strategy;

import java.util.ArrayList;
import java.util.List;

import org.buaa.rag.core.chunk.ChunkingMode;
import org.buaa.rag.core.chunk.ChunkingOptions;
import org.buaa.rag.core.chunk.ChunkingStrategy;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 固定窗口分块（带重叠和边界对齐）
 */
@Component
public class FixedSizeChunkingStrategy implements ChunkingStrategy {

    @Override
    public ChunkingMode getType() {
        return ChunkingMode.FIXED_SIZE;
    }

    @Override
    public List<String> chunk(String text, ChunkingOptions options) {
        if (!StringUtils.hasText(text)) {
            return List.of();
        }

        String normalized = normalizeText(text);
        int chunkSize = Math.max(1, options.getChunkSize());
        int overlap = Math.max(0, Math.min(options.getOverlapSize(), Math.max(0, chunkSize - 1)));
        if (chunkSize == Integer.MAX_VALUE) {
            return List.of(normalized);
        }

        List<String> chunks = new ArrayList<>();
        int start = 0;
        int lastEnd = -1;
        while (start < normalized.length()) {
            int targetEnd = Math.min(start + chunkSize, normalized.length());
            int end = adjustToBoundary(normalized, start, targetEnd, overlap);
            if (end <= start || end <= lastEnd) {
                end = targetEnd;
            }
            String content = normalized.substring(start, end).trim();
            if (StringUtils.hasText(content)) {
                chunks.add(content);
            }
            lastEnd = end;
            if (end >= normalized.length()) {
                break;
            }
            int nextStart = Math.max(0, end - overlap);
            if (nextStart <= start) {
                nextStart = end;
            }
            start = nextStart;
        }
        return chunks;
    }

    private int adjustToBoundary(String text, int start, int targetEnd, int overlap) {
        if (targetEnd <= start) {
            return targetEnd;
        }
        int maxLookback = Math.min(overlap, targetEnd - start);
        if (maxLookback <= 0) {
            return targetEnd;
        }

        for (int i = 0; i <= maxLookback; i++) {
            int pos = targetEnd - i - 1;
            if (pos <= start) {
                break;
            }
            char c = text.charAt(pos);
            if (c == '\n' || c == '。' || c == '！' || c == '？' || c == '；') {
                return pos + 1;
            }
        }
        for (int i = 0; i <= maxLookback; i++) {
            int pos = targetEnd - i - 1;
            if (pos <= start) {
                break;
            }
            char c = text.charAt(pos);
            if ((c == '.' || c == '!' || c == '?') && pos + 1 < text.length() && Character.isWhitespace(text.charAt(pos + 1))) {
                return pos + 1;
            }
        }
        return targetEnd;
    }

    private String normalizeText(String text) {
        if (!StringUtils.hasText(text)) {
            return "";
        }
        return text
            .replace("\r", "")
            .replaceAll("(?<=https?://[^\\s]{1,200})\\n(?=[A-Za-z0-9/_?&=#%\\-\\.])", "")
            .replaceAll("(?<=[\\u4e00-\\u9fff])\\n(?=[\\u4e00-\\u9fff])", "")
            .replaceAll("\\n{3,}", "\n\n");
    }
}

