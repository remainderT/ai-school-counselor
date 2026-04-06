package org.buaa.rag.common.user;

import static org.buaa.rag.common.consts.CacheConstants.USER_INFO_KEY;
import static org.buaa.rag.common.consts.CacheConstants.USER_LOGIN_KEY;
import static org.buaa.rag.common.enums.UserErrorCodeEnum.USER_TOKEN_ERROR;
import static org.buaa.rag.common.enums.UserErrorCodeEnum.USER_TOKEN_NULL;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

import org.buaa.rag.common.convention.exception.ClientException;
import org.buaa.rag.common.convention.result.Results;
import org.buaa.rag.common.util.FilterResponseUtils;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.alibaba.fastjson2.JSON;

import cn.hutool.core.util.StrUtil;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;

/**
 * 登录检验过滤器
 */
@RequiredArgsConstructor
public class LoginCheckFilter implements Filter {

    private final StringRedisTemplate stringRedisTemplate;

    private static final List<String> IGNORE_URI = List.of(
            "/api/rag/user/login", //登录
            "/api/rag/user/send-code" //注册时发送验证码
    );

    private boolean requireLogin(String URI, String method) {
        // 非 API 请求（静态资源等）不需要登录校验
        if (!URI.startsWith("/api/")) {
            return false;
        }
        if (IGNORE_URI.contains(URI)) {
            return false;
        }
        // 注册用户
        if (URI.equals("/api/rag/user") && method.equals("POST")) {
            return false;
        }
        return true;
    }

    @SneakyThrows
    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain filterChain) throws IOException, ServletException {
        HttpServletRequest httpServletRequest = (HttpServletRequest) servletRequest;
        String requestURI = httpServletRequest.getRequestURI();
        String method = httpServletRequest.getMethod();
        if (requireLogin(requestURI, method) && UserContext.getUsername() == null) {
            FilterResponseUtils.writeJsonResponse((HttpServletResponse) servletResponse, JSON.toJSONString(Results.failure(new ClientException(USER_TOKEN_NULL))));
            return;
        }

        filterChain.doFilter(servletRequest, servletResponse);
    }


}
