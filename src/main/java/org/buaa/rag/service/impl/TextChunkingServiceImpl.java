package org.buaa.rag.service.impl;

import java.util.ArrayList;
import java.util.List;

import org.buaa.rag.service.TextChunkingService;
import org.springframework.stereotype.Service;

import com.hankcs.hanlp.seg.common.Term;
import com.hankcs.hanlp.tokenizer.StandardTokenizer;

import lombok.extern.slf4j.Slf4j;

/**
 * 三级智能分块实现：段落 → 句子 → 分词
 * <p>
 * 策略：
 * <ol>
 *   <li>优先按双换行分段，将短段落合并到同一 chunk</li>
 *   <li>超长段落按中英文标点拆句后聚合</li>
 *   <li>超长句子使用 HanLP 分词拆分，最终兜底字符切割</li>
 * </ol>
 */
@Slf4j
@Service
public class TextChunkingServiceImpl implements TextChunkingService {

    private static final String PARAGRAPH_SEPARATOR = "\n\n";
    private static final String SENTENCE_SPLIT_REGEX = "(?<=[。！？；])|(?<=[.!?;])\\s+";

    @Override
    public List<String> chunk(String fullText, int maxChunkSize) {
        if (fullText == null || fullText.isBlank()) {
            return List.of();
        }
        int limit = Math.max(64, maxChunkSize);
        List<String> result = new ArrayList<>();
        mergeShortParagraphs(fullText.split("\\n\\n+"), limit, result);
        return result;
    }

    // ───────── 第一级：段落合并 ─────────

    private void mergeShortParagraphs(String[] paragraphs, int limit, List<String> out) {
        StringBuilder buf = new StringBuilder();

        for (String para : paragraphs) {
            if (para.length() > limit) {
                flushBuffer(buf, out);
                splitBySentences(para, limit, out);
            } else if (buf.length() + para.length() + 2 > limit) {
                flushBuffer(buf, out);
                buf.append(para);
            } else {
                if (!buf.isEmpty()) {
                    buf.append(PARAGRAPH_SEPARATOR);
                }
                buf.append(para);
            }
        }
        flushBuffer(buf, out);
    }

    // ───────── 第二级：句子聚合 ─────────

    private void splitBySentences(String paragraph, int limit, List<String> out) {
        String[] sentences = paragraph.split(SENTENCE_SPLIT_REGEX);
        StringBuilder buf = new StringBuilder();

        for (String sentence : sentences) {
            if (sentence.length() > limit) {
                flushBuffer(buf, out);
                splitByTokens(sentence, limit, out);
            } else if (buf.length() + sentence.length() > limit) {
                flushBuffer(buf, out);
                buf.append(sentence);
            } else {
                buf.append(sentence);
            }
        }
        flushBuffer(buf, out);
    }

    // ───────── 第三级：分词 / 字符切割 ─────────

    private void splitByTokens(String sentence, int limit, List<String> out) {
        try {
            List<Term> terms = StandardTokenizer.segment(sentence);
            StringBuilder buf = new StringBuilder();

            for (Term term : terms) {
                String word = term.word;
                if (buf.length() + word.length() > limit && !buf.isEmpty()) {
                    out.add(buf.toString());
                    buf.setLength(0);
                }
                buf.append(word);
            }
            if (!buf.isEmpty()) {
                out.add(buf.toString());
            }

            log.debug("HanLP 分词 — 原句: {} 字符, 词数: {}, 片段: {}",
                sentence.length(), terms.size(), out.size());
        } catch (Exception e) {
            log.warn("HanLP 分词失败，回退字符切割: {}", e.getMessage());
            fallbackCharSplit(sentence, limit, out);
        }
    }

    private void fallbackCharSplit(String text, int limit, List<String> out) {
        for (int pos = 0; pos < text.length(); pos += limit) {
            out.add(text.substring(pos, Math.min(pos + limit, text.length())));
        }
    }

    // ───────── 工具 ─────────

    private static void flushBuffer(StringBuilder buf, List<String> out) {
        if (buf.isEmpty()) {
            return;
        }
        String trimmed = buf.toString().trim();
        if (!trimmed.isEmpty()) {
            out.add(trimmed);
        }
        buf.setLength(0);
    }
}
