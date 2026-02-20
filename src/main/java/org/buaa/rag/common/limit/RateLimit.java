package org.buaa.rag.common.limit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 方法级限流注解
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Repeatable(RateLimits.class)
public @interface RateLimit {

    /**
     * 业务键
     */
    String key();

    /**
     * 限流维度
     */
    LimitScope scope() default LimitScope.GLOBAL;

    /**
     * 时间窗内最大请求数
     */
    int maxRequests();

    /**
     * 时间窗（秒）
     */
    int windowSeconds();

    /**
     * 命中限流时提示文案
     */
    String message() default "";
}
