package org.buaa.rag.common.user;

import java.io.IOException;
import java.util.List;

import org.buaa.rag.common.convention.exception.ClientException;
import org.buaa.rag.common.convention.result.Results;
import org.buaa.rag.common.enums.UserErrorCodeEnum;
import org.buaa.rag.common.util.FilterResponseUtils;

import com.alibaba.fastjson2.JSON;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 管理员权限过滤器
 */
public class AdminOnlyFilter implements Filter {

    private static final List<String> ADMIN_ONLY_PREFIX = List.of(
            "/api/rag/knowledge",
            "/api/rag/document",
            "/api/rag/intent-tree",
            "/api/rag/conversations/search"
    );

    private boolean requireAdmin(String requestUri) {
        if (requestUri == null) {
            return false;
        }
        return ADMIN_ONLY_PREFIX.stream().anyMatch(requestUri::startsWith);
    }

    @Override
    public void doFilter(ServletRequest servletRequest,
                         ServletResponse servletResponse,
                         FilterChain filterChain) throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) servletRequest;
        String requestUri = request.getRequestURI();

        if (requireAdmin(requestUri) && !UserContext.isAdmin()) {
            FilterResponseUtils.writeJsonResponse((HttpServletResponse) servletResponse,
                JSON.toJSONString(Results.failure(new ClientException(UserErrorCodeEnum.USER_NO_ADMIN))));
            return;
        }

        filterChain.doFilter(servletRequest, servletResponse);
    }

}
