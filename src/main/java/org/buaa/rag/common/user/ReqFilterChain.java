package org.buaa.rag.common.user;

import org.buaa.rag.config.FlowControlConfiguration;
import org.buaa.rag.dao.mapper.UserMapper;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 请求校验过滤链
 */
@Configuration
public class ReqFilterChain {

    /**
     * 刷新 Token 过滤器
     */
    @Bean
    public FilterRegistrationBean<RefreshTokenFilter> globalUserTransmitFilter(StringRedisTemplate stringRedisTemplate, UserMapper userMapper) {
        FilterRegistrationBean<RefreshTokenFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new RefreshTokenFilter(stringRedisTemplate, userMapper));
        registration.addUrlPatterns("/*");
        registration.setOrder(0);
        return registration;
    }

    /**
     * 登录校验拦截器
     */
    @Bean
    public FilterRegistrationBean<LoginCheckFilter> globalLoginInterceptor(StringRedisTemplate stringRedisTemplate) {
        FilterRegistrationBean<LoginCheckFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new LoginCheckFilter(stringRedisTemplate));
        registration.addUrlPatterns("/*");
        registration.setOrder(1);
        return registration;
    }

    /**
     * 用户操作流量风控过滤器
     */
    @Bean
    public FilterRegistrationBean<FlowControlFilter> globalUserFlowRiskControlFilter(
            StringRedisTemplate stringRedisTemplate,
            FlowControlConfiguration flowControlConfiguration) {
        FilterRegistrationBean<FlowControlFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new FlowControlFilter(stringRedisTemplate, flowControlConfiguration));
        registration.addUrlPatterns("/*");
        registration.setOrder(2);
        return registration;
    }

}