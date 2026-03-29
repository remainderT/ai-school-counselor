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

    public static String load(String fileName) {
        if (!StringUtils.hasText(fileName)) {
            return "";
        }
        return CACHE.computeIfAbsent(fileName.trim(), PromptTemplateLoader::readPrompt);
    }

    private static String readPrompt(String fileName) {
        ClassPathResource resource = new ClassPathResource(PROMPT_BASE_PATH + fileName);
        if (!resource.exists()) {
            log.warn("prompt 模板不存在: {}", fileName);
            return "";
        }
        try (InputStream inputStream = resource.getInputStream()) {
            String content = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8).trim();
            return StringUtils.hasText(content) ? content : "";
        } catch (IOException e) {
            log.warn("读取 prompt 模板失败: {}", fileName, e);
            return "";
        }
    }
}
