package org.buaa.rag.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Data;

/**
 * 用户操作流量风控配置属性
 */
@Data
@Component
@ConfigurationProperties(prefix = "flow-limit")
public class FlowControlProperties {

    /** 是否开启用户流量风控验证 */
    private Boolean enable;

    /** 流量风控时间窗口，单位：秒 */
    private String timeWindow;

    /** 流量风控时间窗口内可访问次数 */
    private Long maxAccessCount;
}
