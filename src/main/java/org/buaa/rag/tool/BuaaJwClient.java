package org.buaa.rag.tool;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.buaa.rag.properties.BuaaJwProperties;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class BuaaJwClient {

    private final BuaaJwProperties properties;
    private final ObjectMapper objectMapper;

    public JsonNode queryScores(String termCode) {
        ensureCookieConfigured();
        String uri = properties.getBaseUrl() + properties.getScorePath() + "?termCode="
            + URLEncoder.encode(termCode, StandardCharsets.UTF_8);
        HttpRequest request = baseRequest(uri)
            .header("accept", "*/*")
            .header("fetch-api", "true")
            .GET()
            .build();
        return send(request);
    }

    public JsonNode queryExams(String termCode) {
        ensureCookieConfigured();
        String uri = properties.getBaseUrl() + properties.getExamPath() + "?termCode="
            + URLEncoder.encode(termCode, StandardCharsets.UTF_8);
        HttpRequest request = baseRequest(uri)
            .header("accept", "*/*")
            .header("fetch-api", "true")
            .GET()
            .build();
        return send(request);
    }

    public JsonNode querySchedule(String termCode, int week) {
        ensureCookieConfigured();
        String body = "termCode=" + URLEncoder.encode(termCode, StandardCharsets.UTF_8)
            + "&campusCode=&type=week&week=" + week;
        HttpRequest request = baseRequest(properties.getBaseUrl() + properties.getSchedulePath())
            .header("accept", "application/json")
            .header("content-type", "application/x-www-form-urlencoded;charset=UTF-8")
            .header("origin", properties.getBaseUrl())
            .header("fetch-api", "true")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
        return send(request);
    }

    public JsonNode querySchoolCalendars() {
        ensureCookieConfigured();
        HttpRequest request = baseRequest(properties.getBaseUrl() + properties.getSchoolCalendarsPath())
            .header("accept", "*/*")
            .header("fetch-api", "true")
            .GET()
            .build();
        return send(request);
    }

    private HttpRequest.Builder baseRequest(String uri) {
        return HttpRequest.newBuilder(URI.create(uri))
            .timeout(Duration.ofSeconds(properties.getRequestTimeoutSeconds()))
            .header("cookie", properties.getCookie().trim())
            .header("referer", properties.getReferer())
            .header("user-agent", properties.getUserAgent())
            .header("cache-control", "no-cache")
            .header("pragma", "no-cache");
    }

    private JsonNode send(HttpRequest request) {
        HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(properties.getConnectTimeoutSeconds()))
            .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                String location = response.headers()
                    .firstValue("location")
                    .map(value -> "，Location=" + value)
                    .orElse("");
                throw new IllegalStateException("教务接口响应异常，HTTP " + response.statusCode() + location);
            }
            JsonNode root = objectMapper.readTree(response.body());
            if (!"0".equals(root.path("code").asText())) {
                String msg = root.path("msg").asText();
                throw new IllegalStateException(StringUtils.hasText(msg) ? msg : "教务接口返回失败");
            }
            return root;
        } catch (IOException e) {
            throw new IllegalStateException("教务接口解析失败: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("教务接口调用被中断", e);
        }
    }

    private void ensureCookieConfigured() {
        if (!StringUtils.hasText(properties.getCookie())) {
            throw new IllegalStateException("未配置 BUAA 教务 Cookie，请在 buaa.jw.cookie 或环境变量 BUAA_JW_COOKIE 中设置");
        }
    }
}
