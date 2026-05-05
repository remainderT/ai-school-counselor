package org.buaa.rag.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Data;

/**
 * 意图路由配置
 */
@Data
@Component
@ConfigurationProperties(prefix = "rag.intent-routing")
public class IntentRoutingProperties {
}
