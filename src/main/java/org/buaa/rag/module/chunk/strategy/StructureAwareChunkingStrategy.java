package org.buaa.rag.module.chunk.strategy;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.buaa.rag.module.chunk.ChunkingMode;
import org.buaa.rag.module.chunk.ChunkingOptions;
import org.buaa.rag.module.chunk.ChunkingStrategy;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 结构感知分块（标题/代码块/段落）
 */
@Component
public class StructureAwareChunkingStrategy implements ChunkingStrategy {

    private static final Pattern HEADING = Pattern.compile("^#{1,6}\\s+.*$");
    private static final Pattern CODE_FENCE = Pattern.compile("^```.*$");

    @Override
    public ChunkingMode getType() {
        return ChunkingMode.STRUCTURE_AWARE;
    }

    @Override
    public List<String> chunk(String text, ChunkingOptions options) {
        if (!StringUtils.hasText(text)) {
            return List.of();
        }

        int targetChars = options.getMetadata("targetChars", 1400);
        int maxChars = options.getMetadata("maxChars", 1800);
        int minChars = options.getMetadata("minChars", 600);
        int overlapChars = options.getMetadata("overlapChars", 0);

        List<Block> blocks = segmentBlocks(text);
        if (blocks.isEmpty()) {
            return List.of(text.trim());
        }
        List<int[]> ranges = packBlocks(blocks, minChars, targetChars, maxChars);
        return materialize(text, ranges, overlapChars);
    }

    private List<Block> segmentBlocks(String text) {
        List<Block> blocks = new ArrayList<>();
        int n = text.length();
        int pos = 0;
        boolean inFence = false;
        int fenceStart = -1;
        boolean inPara = false;
        int paraStart = -1;

        while (pos < n) {
            int lineEnd = indexOfNl(text, pos);
            int lineEndNl = lineEnd < n && text.charAt(lineEnd) == '\n' ? lineEnd + 1 : lineEnd;
            String line = text.substring(pos, lineEnd);
            String trimmed = trimRight(line);

            if (!inFence && CODE_FENCE.matcher(trimmed).matches()) {
                if (inPara) {
                    blocks.add(new Block(paraStart, pos));
                    inPara = false;
                }
                inFence = true;
                fenceStart = pos;
                pos = lineEndNl;
                continue;
            }

            if (inFence) {
                if (CODE_FENCE.matcher(trimmed).matches()) {
                    blocks.add(new Block(fenceStart, lineEndNl));
                    inFence = false;
                }
                pos = lineEndNl;
                continue;
            }

            if (trimmed.isEmpty()) {
                if (inPara) {
                    blocks.add(new Block(paraStart, pos));
                    inPara = false;
                }
                pos = lineEndNl;
                continue;
            }

            if (HEADING.matcher(trimmed).matches()) {
                if (inPara) {
                    blocks.add(new Block(paraStart, pos));
                    inPara = false;
                }
                blocks.add(new Block(pos, lineEndNl));
                pos = lineEndNl;
                continue;
            }

            if (!inPara) {
                inPara = true;
                paraStart = pos;
            }
            pos = lineEndNl;
        }

        if (inFence) {
            blocks.add(new Block(fenceStart, n));
        } else if (inPara) {
            blocks.add(new Block(paraStart, n));
        }
        return blocks;
    }

    private List<int[]> packBlocks(List<Block> blocks, int minChars, int targetChars, int maxChars) {
        List<int[]> ranges = new ArrayList<>();
        int i = 0;
        while (i < blocks.size()) {
            int chunkStart = blocks.get(i).start();
            int chunkEnd = blocks.get(i).end();
            int size = chunkEnd - chunkStart;

            int j = i + 1;
            while (j < blocks.size()) {
                int candidateEnd = blocks.get(j).end();
                int candidateSize = candidateEnd - chunkStart;
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

    private List<String> materialize(String text, List<int[]> ranges, int overlapChars) {
        List<String> chunks = new ArrayList<>();
        String previousTail = null;
        for (int[] range : ranges) {
            String body = text.substring(range[0], range[1]).trim();
            if (!StringUtils.hasText(body)) {
                continue;
            }
            if (overlapChars > 0 && previousTail != null && !previousTail.isBlank()) {
                body = previousTail + body;
            }
            chunks.add(body);
            if (overlapChars > 0) {
                previousTail = tail(body, overlapChars);
            }
        }
        return chunks;
    }

    private int indexOfNl(String text, int from) {
        int idx = text.indexOf('\n', from);
        return idx < 0 ? text.length() : idx;
    }

    private String trimRight(String text) {
        int end = text.length();
        while (end > 0 && Character.isWhitespace(text.charAt(end - 1)) && text.charAt(end - 1) != '\n') {
            end--;
        }
        return text.substring(0, end);
    }

    private String tail(String text, int chars) {
        if (chars <= 0 || text.length() <= chars) {
            return text;
        }
        return text.substring(text.length() - chars);
    }

    private record Block(int start, int end) {
    }
}

