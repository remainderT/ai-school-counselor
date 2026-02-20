package org.buaa.rag.common.prompt;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StringUtils;

/**
 * 类路径 Prompt 模板加载器
 */
public final class PromptTemplateLoader {

    private static final Logger log = LoggerFactory.getLogger(PromptTemplateLoader.class);
    private static final String PROMPT_BASE_PATH = "prompts/";
    private static final Map<String, String> CACHE = new ConcurrentHashMap<>();

    private PromptTemplateLoader() {
    }

    public static String load(String fileName, String fallback) {
        if (!StringUtils.hasText(fileName)) {
            return fallback;
        }
        return CACHE.computeIfAbsent(fileName.trim(), key -> readPrompt(key, fallback));
    }

    private static String readPrompt(String fileName, String fallback) {
        ClassPathResource resource = new ClassPathResource(PROMPT_BASE_PATH + fileName);
        if (!resource.exists()) {
            return fallback;
        }
        try (InputStream inputStream = resource.getInputStream()) {
            String content = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8).trim();
            return StringUtils.hasText(content) ? content : fallback;
        } catch (IOException e) {
            log.warn("读取 prompt 模板失败: {}", fileName, e);
            return fallback;
        }
    }
}
