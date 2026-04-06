package org.buaa.rag.common.user;

import static org.buaa.rag.common.enums.ServiceErrorCodeEnum.FLOW_LIMIT_ERROR;

import java.io.IOException;
import java.util.List;

import org.buaa.rag.common.convention.exception.ClientException;
import org.buaa.rag.common.convention.result.Results;
import org.buaa.rag.common.util.FilterResponseUtils;
import org.buaa.rag.properties.FlowControlProperties;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scripting.support.ResourceScriptSource;

import com.alibaba.fastjson2.JSON;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;


/**
 * 用户操作流量风控过滤器
 */
@Slf4j
@RequiredArgsConstructor
public class FlowControlFilter implements Filter {

    private final StringRedisTemplate stringRedisTemplate;
    private final FlowControlProperties flowControlProperties;

    /** 预加载并缓存 Lua 脚本，避免每次请求都重新加载类路径资源 */
    private static final DefaultRedisScript<Long> RATE_LIMIT_SCRIPT;

    static {
        RATE_LIMIT_SCRIPT = new DefaultRedisScript<>();
        RATE_LIMIT_SCRIPT.setScriptSource(
                new ResourceScriptSource(new ClassPathResource("rate_limit.lua")));
        RATE_LIMIT_SCRIPT.setResultType(Long.class);
    }

    /** 不需要限流的轻量级 URI 前缀/路径（认证校验、用户信息等） */
    private static final List<String> RATE_LIMIT_EXEMPT_PREFIXES = List.of(
            "/api/rag/user/check-login",
            "/api/rag/user/info/",
            "/api/rag/user/login",
            "/api/rag/user/send-code",
            "/api/rag/conversations/sessions",
            "/api/rag/conversations/history"
    );

    /** 只对 /api/ 开头的业务请求进行限流，静态资源、认证与会话读取类接口直接放行 */
    private boolean shouldRateLimit(ServletRequest request) {
        if (request instanceof HttpServletRequest httpReq) {
            String uri = httpReq.getRequestURI();
            if (uri == null || !uri.startsWith("/api/")) {
                return false;
            }
            for (String exempt : RATE_LIMIT_EXEMPT_PREFIXES) {
                if (uri.startsWith(exempt)) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    @SneakyThrows
    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain filterChain) throws IOException, ServletException {
        if (!Boolean.TRUE.equals(flowControlProperties.getEnable()) || !shouldRateLimit(request)) {
            filterChain.doFilter(request, response);
            return;
        }
        String ipAddress = request.getRemoteAddr();
        Long result;
        try {
            result = stringRedisTemplate.execute(RATE_LIMIT_SCRIPT, List.of(ipAddress), flowControlProperties.getTimeWindow());
        } catch (Throwable ex) {
            // Redis 执行异常时放行请求，避免 Redis 瞬时故障导致所有请求被拒
            log.error("执行用户请求流量限制LUA脚本出错", ex);
            filterChain.doFilter(request, response);
            return;
        }
        if (result == null || result > flowControlProperties.getMaxAccessCount()) {
            log.warn("用户请求流量超限: {} {}", ipAddress, UserContext.getUsername());
            FilterResponseUtils.writeJsonResponse((HttpServletResponse) response, JSON.toJSONString(Results.failure(new ClientException(FLOW_LIMIT_ERROR))));
            return;
        }
        filterChain.doFilter(request, response);
    }

}
