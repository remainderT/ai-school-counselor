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

    /**
     * 加载模板并将 {@code {key}} 占位符替换为 {@code variables} 中的对应值。
     *
     * <p>示例模板内容：{@code 你想了解{topic}吗？选项：{options}}
     *
     * @param fileName  prompts/ 目录下的文件名
     * @param variables 占位符名称 -> 替换值 的映射
     * @return 完成替换后的字符串；若模板不存在则返回空字符串
     */
    public static String render(String fileName, Map<String, String> variables) {
        String template = load(fileName);
        if (!StringUtils.hasText(template) || variables == null || variables.isEmpty()) {
            return template;
        }
        String result = template;
        for (Map.Entry<String, String> entry : variables.entrySet()) {
            if (entry.getKey() == null) {
                continue;
            }
            String placeholder = "{" + entry.getKey() + "}";
            String value = entry.getValue() != null ? entry.getValue() : "";
            result = result.replace(placeholder, value);
        }
        return result;
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
