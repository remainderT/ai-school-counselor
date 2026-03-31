package org.buaa.rag.common.user;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.List;

import org.buaa.rag.common.convention.result.Results;

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

    private static final String NO_ADMIN_CODE = "A000205";
    private static final String NO_ADMIN_MSG = "无管理员权限";

    private static final List<String> ADMIN_ONLY_PREFIX = List.of(
            "/api/rag/knowledge",
            "/api/rag/document",
            "/api/rag/intent-tree",
            "/api/rag/chat/search"
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
            returnJson((HttpServletResponse) servletResponse, JSON.toJSONString(Results.failure(NO_ADMIN_CODE, NO_ADMIN_MSG)));
            return;
        }

        filterChain.doFilter(servletRequest, servletResponse);
    }

    private void returnJson(HttpServletResponse response, String json) throws IOException {
        response.setCharacterEncoding("UTF-8");
        response.setContentType("application/json; charset=utf-8");
        PrintWriter writer = response.getWriter();
        writer.print(json);
        writer.close();
    }
}
