package org.buaa.rag.common.limit;

import static org.buaa.rag.common.enums.ServiceErrorCodeEnum.FLOW_LIMIT_ERROR;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.buaa.rag.common.convention.exception.ClientException;
import org.buaa.rag.common.user.UserContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 基于 Redis Lua 的原子限流切面
 */
@Aspect
@Component
public class RateLimitAspect {

    private static final Logger log = LoggerFactory.getLogger(RateLimitAspect.class);
    private static final String KEY_PREFIX = "rag:rate-limit";
    private static final DefaultRedisScript<Long> RATE_LIMIT_SCRIPT;

    static {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("scripts/rate_limit.lua"));
        script.setResultType(Long.class);
        RATE_LIMIT_SCRIPT = script;
    }

    private final StringRedisTemplate stringRedisTemplate;

    public RateLimitAspect(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Around("@annotation(org.buaa.rag.common.limit.RateLimit) "
        + "|| @annotation(org.buaa.rag.common.limit.RateLimits)")
    public Object limit(ProceedingJoinPoint joinPoint) throws Throwable {
        MethodSignature methodSignature = (MethodSignature) joinPoint.getSignature();
        Method originalMethod = methodSignature.getMethod();
        Method method = AopUtils.getMostSpecificMethod(originalMethod, joinPoint.getTarget().getClass());
        RateLimit[] rules = method.getAnnotationsByType(RateLimit.class);
        if (rules == null || rules.length == 0) {
            return joinPoint.proceed();
        }

        HttpServletRequest request = currentRequest();
        String methodKey = method.getDeclaringClass().getSimpleName() + ":" + method.getName();
        for (RateLimit rule : rules) {
            enforceLimit(rule, methodKey, request);
        }
        return joinPoint.proceed();
    }

    private void enforceLimit(RateLimit rule, String methodKey, HttpServletRequest request) {
        String businessKey = StringUtils.hasText(rule.key()) ? rule.key().trim() : methodKey;
        String dimension = resolveDimension(rule.scope(), request);
        String redisKey = buildRedisKey(rule.scope(), businessKey, dimension);
        Long allowed = stringRedisTemplate.execute(
            RATE_LIMIT_SCRIPT,
            List.of(redisKey),
            String.valueOf(rule.maxRequests()),
            String.valueOf(rule.windowSeconds())
        );
        if (allowed == null || allowed <= 0) {
            log.warn("触发限流: key={}, scope={}, value={}, limit={}/{}s",
                businessKey, rule.scope(), dimension, rule.maxRequests(), rule.windowSeconds());
            if (StringUtils.hasText(rule.message())) {
                throw new ClientException(rule.message(), FLOW_LIMIT_ERROR);
            }
            throw new ClientException(FLOW_LIMIT_ERROR);
        }
    }

    private String buildRedisKey(LimitScope scope, String businessKey, String dimension) {
        return KEY_PREFIX + ":" + scope.name().toLowerCase(Locale.ROOT)
            + ":" + sanitizeKey(businessKey) + ":" + sanitizeKey(dimension);
    }

    private String resolveDimension(LimitScope scope, HttpServletRequest request) {
        return switch (scope) {
            case GLOBAL -> "all";
            case IP -> resolveClientIp(request);
            case USER -> resolveUserId(request);
        };
    }

    private String resolveUserId(HttpServletRequest request) {
        Long userId = UserContext.getUserId();
        if (userId != null) {
            return String.valueOf(userId);
        }
        if (request == null) {
            return "anonymous";
        }
        String paramUserId = request.getParameter("userId");
        if (StringUtils.hasText(paramUserId)) {
            return paramUserId.trim();
        }
        String mailHeader = request.getHeader("mail");
        if (StringUtils.hasText(mailHeader)) {
            return mailHeader.trim();
        }
        return "anonymous";
    }

    private String resolveClientIp(HttpServletRequest request) {
        if (request == null) {
            return "unknown";
        }
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (StringUtils.hasText(forwardedFor)) {
            String firstIp = forwardedFor.split(",")[0].trim();
            if (StringUtils.hasText(firstIp)) {
                return firstIp;
            }
        }
        String realIp = request.getHeader("X-Real-IP");
        if (StringUtils.hasText(realIp)) {
            return realIp.trim();
        }
        String remoteAddr = request.getRemoteAddr();
        return StringUtils.hasText(remoteAddr) ? remoteAddr.trim() : "unknown";
    }

    private HttpServletRequest currentRequest() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servletRequestAttributes) {
            return servletRequestAttributes.getRequest();
        }
        return null;
    }

    private String sanitizeKey(String value) {
        if (!StringUtils.hasText(value)) {
            return "unknown";
        }
        return value.trim().replaceAll("[^a-zA-Z0-9:_-]", "_");
    }
}
