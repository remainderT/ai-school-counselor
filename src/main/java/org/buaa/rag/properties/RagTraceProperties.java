package org.buaa.rag.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Data;

/**
 * RAG 全链路 Trace 配置属性
 */
@Data
@Component
@ConfigurationProperties(prefix = "rag.trace")
public class RagTraceProperties {

    /** 是否启用全链路 Trace（false 时切面直接 proceed，零开销） */
    private boolean enabled = true;

    /** 错误信息最大长度（超出截断，防止超 VARCHAR 限制） */
    private int maxErrorLength = 800;
}
