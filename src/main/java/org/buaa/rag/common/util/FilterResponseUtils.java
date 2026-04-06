package org.buaa.rag.common.util;

import java.io.IOException;
import java.io.PrintWriter;

import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;

/**
 * Filter 层统一的 JSON 响应写出工具
 */
@Slf4j
public final class FilterResponseUtils {

    private FilterResponseUtils() {
    }

    public static void writeJsonResponse(HttpServletResponse response, String json) {
        PrintWriter writer = null;
        response.setCharacterEncoding("UTF-8");
        response.setContentType("application/json; charset=utf-8");
        try {
            writer = response.getWriter();
            writer.print(json);
        } catch (IOException e) {
            log.warn("响应写入失败", e);
        } finally {
            if (writer != null) {
                writer.close();
            }
        }
    }
}
