package org.buaa.rag.tool;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import lombok.extern.slf4j.Slf4j;

/**
 * MCP 天气查询客户端
 */
@Slf4j
@Component
public class McpWeatherClient {

    private static final List<String> CITY_ALIASES = List.of(
        "北京", "上海", "广州", "深圳", "杭州", "成都", "武汉", "南京", "西安", "重庆",
        "长沙", "天津", "苏州", "郑州", "青岛", "大连", "厦门", "昆明", "哈尔滨", "三亚"
    );
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final List<String> WEATHER_TYPES = List.of("晴", "多云", "阴", "小雨", "阵雨", "雷阵雨");
    private static final List<String> WIND_DIRECTIONS = List.of("东风", "南风", "西风", "北风", "东北风", "西北风");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicLong rpcId = new AtomicLong(1L);

    @Value("${mcp.weather.enabled:true}")
    private boolean enabled;

    @Value("${mcp.weather.server-url:http://localhost:18080/mcp}")
    private String serverUrl;

    @Value("${mcp.weather.tool-name:weather_query}")
    private String toolName;

    @Value("${mcp.weather.connect-timeout-ms:1500}")
    private long connectTimeoutMs;

    @Value("${mcp.weather.read-timeout-ms:3000}")
    private long readTimeoutMs;

    @Value("${mcp.weather.fallback-enabled:true}")
    private boolean fallbackEnabled;

    public WeatherResponse queryWeather(String city,
                                        String queryType,
                                        Integer days,
                                        String userId,
                                        String userQuestion) {
        String resolvedCity = normalizeCity(city);
        if (!StringUtils.hasText(resolvedCity)) {
            return null;
        }
        String resolvedType = normalizeQueryType(queryType);
        int resolvedDays = normalizeDays(days);

        if (enabled) {
            String mcpResult = callMcpTool(resolvedCity, resolvedType, resolvedDays, userId, userQuestion);
            if (StringUtils.hasText(mcpResult)) {
                return new WeatherResponse(mcpResult, "mcp");
            }
        }

        if (!fallbackEnabled) {
            return null;
        }
        return new WeatherResponse(
            buildMockWeather(resolvedCity, resolvedType, resolvedDays),
            "mock"
        );
    }

    public String resolveCityFromText(String text) {
        if (!StringUtils.hasText(text)) {
            return null;
        }
        String normalized = text.trim();
        for (String alias : CITY_ALIASES) {
            if (normalized.contains(alias)) {
                return alias;
            }
            if (normalized.contains(alias + "市")) {
                return alias;
            }
        }
        return null;
    }

    private String normalizeCity(String city) {
        if (!StringUtils.hasText(city)) {
            return null;
        }
        String normalized = city.trim();
        if (normalized.endsWith("市") && normalized.length() > 1) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private String normalizeQueryType(String queryType) {
        if (!StringUtils.hasText(queryType)) {
            return "current";
        }
        String normalized = queryType.trim().toLowerCase(Locale.ROOT);
        if (!"forecast".equals(normalized)) {
            return "current";
        }
        return normalized;
    }

    private int normalizeDays(Integer days) {
        if (days == null) {
            return 3;
        }
        return Math.max(1, Math.min(7, days));
    }

    private String callMcpTool(String city,
                               String queryType,
                               int days,
                               String userId,
                               String userQuestion) {
        try {
            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("jsonrpc", "2.0");
            payload.put("id", rpcId.getAndIncrement());
            payload.put("method", "tools/call");

            ObjectNode params = payload.putObject("params");
            params.put("name", toolName);
            ObjectNode arguments = params.putObject("arguments");
            arguments.put("city", city);
            arguments.put("queryType", queryType);
            arguments.put("days", days);
            if (StringUtils.hasText(userId)) {
                arguments.put("userId", userId.trim());
            }
            if (StringUtils.hasText(userQuestion)) {
                arguments.put("question", userQuestion.trim());
            }

            HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.max(200L, connectTimeoutMs)))
                .build();
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(resolveServerUrl()))
                .timeout(Duration.ofMillis(Math.max(500L, readTimeoutMs)))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn("天气 MCP 调用失败，HTTP 状态码={}", response.statusCode());
                return null;
            }
            return parseMcpResponse(response.body());
        } catch (Exception e) {
            log.warn("天气 MCP 调用异常: {}", e.getMessage());
            return null;
        }
    }

    private String parseMcpResponse(String body) {
        if (!StringUtils.hasText(body)) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode errorNode = root.get("error");
            if (errorNode != null && !errorNode.isNull()) {
                log.warn("天气 MCP 返回错误: {}", errorNode);
                return null;
            }
            JsonNode result = root.path("result");
            if (result.path("isError").asBoolean(false)) {
                return null;
            }
            JsonNode contentNode = result.path("content");
            if (!contentNode.isArray()) {
                return null;
            }
            StringBuilder builder = new StringBuilder();
            for (JsonNode item : contentNode) {
                String text = item.path("text").asText(null);
                if (!StringUtils.hasText(text)) {
                    continue;
                }
                if (!builder.isEmpty()) {
                    builder.append('\n');
                }
                builder.append(text.trim());
            }
            return builder.isEmpty() ? null : builder.toString();
        } catch (Exception e) {
            log.warn("解析天气 MCP 响应失败: {}", e.getMessage());
            return null;
        }
    }

    private String resolveServerUrl() {
        if (!StringUtils.hasText(serverUrl)) {
            return "http://localhost:18080/mcp";
        }
        return serverUrl.endsWith("/mcp") ? serverUrl : serverUrl + "/mcp";
    }

    private String buildMockWeather(String city, String queryType, int days) {
        if ("forecast".equals(queryType)) {
            return buildMockForecast(city, days);
        }
        return buildMockCurrent(city, LocalDate.now());
    }

    private String buildMockCurrent(String city, LocalDate date) {
        WeatherData data = generateWeather(city, date);
        return "【" + city + " 今日天气】\n"
            + "日期: " + DATE_FORMATTER.format(date) + "\n"
            + "天气: " + data.weatherType + "\n"
            + "温度: " + data.lowTemp + "°C ~ " + data.highTemp + "°C\n"
            + "湿度: " + data.humidity + "%\n"
            + "风向: " + data.windDirection + " " + data.windLevel + "\n"
            + "提示: " + data.tip;
    }

    private String buildMockForecast(String city, int days) {
        StringBuilder builder = new StringBuilder();
        builder.append("【").append(city).append(" 未来").append(days).append("天天气预报】\n");
        LocalDate today = LocalDate.now();
        for (int i = 0; i < days; i++) {
            LocalDate date = today.plusDays(i);
            WeatherData data = generateWeather(city, date);
            builder.append("- ").append(DATE_FORMATTER.format(date))
                .append("：").append(data.weatherType)
                .append("，").append(data.lowTemp).append("°C~").append(data.highTemp).append("°C")
                .append("，").append(data.windDirection).append(data.windLevel)
                .append('\n');
        }
        return builder.toString().trim();
    }

    private WeatherData generateWeather(String city, LocalDate date) {
        long seed = (long) city.hashCode() * 31 + date.toEpochDay();
        Random random = new Random(seed);

        String weatherType = WEATHER_TYPES.get(random.nextInt(WEATHER_TYPES.size()));
        int lowTemp = random.nextInt(16) + 2;
        int highTemp = lowTemp + random.nextInt(12) + 4;
        int humidity = random.nextInt(40) + 40;
        String windDirection = WIND_DIRECTIONS.get(random.nextInt(WIND_DIRECTIONS.size()));
        String windLevel = (random.nextInt(3) + 2) + "-" + (random.nextInt(3) + 4) + "级";
        String tip = weatherType.contains("雨")
            ? "可能有降雨，建议携带雨具。"
            : "天气整体平稳，可正常出行。";

        return new WeatherData(weatherType, highTemp, lowTemp, humidity, windDirection, windLevel, tip);
    }

    public record WeatherResponse(String text, String source) {
    }

    private record WeatherData(String weatherType,
                               int highTemp,
                               int lowTemp,
                               int humidity,
                               String windDirection,
                               String windLevel,
                               String tip) {
    }
}
