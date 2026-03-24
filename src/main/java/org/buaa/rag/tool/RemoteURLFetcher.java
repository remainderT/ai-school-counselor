package org.buaa.rag.tool;

import static org.buaa.rag.common.enums.DocumentErrorCodeEnum.DOCUMENT_SIZE_EXCEEDED;
import static org.buaa.rag.common.enums.DocumentErrorCodeEnum.DOCUMENT_UPLOAD_FAILED;
import static org.buaa.rag.common.enums.DocumentErrorCodeEnum.DOCUMENT_URL_INVALID;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

import org.buaa.rag.common.convention.exception.ClientException;
import org.buaa.rag.common.convention.exception.ServiceException;
import org.springframework.http.ContentDisposition;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 远程URL抓取器
 */
@Component
public class RemoteURLFetcher {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final String USER_AGENT = "ai-school-counselor/1.0";

    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(CONNECT_TIMEOUT)
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();

    public FetchedRemoteDocument fetch(String sourceUrl, long maxBytes) {
        URI uri = parseSourceUri(sourceUrl);
        HttpRequest request = HttpRequest.newBuilder(uri)
            .timeout(REQUEST_TIMEOUT)
            .header("User-Agent", USER_AGENT)
            .GET()
            .build();
        try {
            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream bodyStream = response.body()) {
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new ServiceException("URL 下载失败: HTTP " + response.statusCode(), DOCUMENT_UPLOAD_FAILED);
                }
                Long contentLength = response.headers().firstValue("Content-Length")
                    .map(this::parseContentLength)
                    .orElse(null);
                if (contentLength != null && maxBytes > 0 && contentLength > maxBytes) {
                    throw new ClientException(DOCUMENT_SIZE_EXCEEDED);
                }
                byte[] bytes = readWithLimit(bodyStream, maxBytes);
                String contentType = normalizeContentType(response.headers().firstValue("Content-Type").orElse(null));
                String fileName = resolveFileName(
                    response.headers().firstValue("Content-Disposition"),
                    uri
                );
                return new FetchedRemoteDocument(sourceUrl, fileName, contentType, bytes);
            }
        } catch (ClientException | ServiceException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ServiceException("URL 下载被中断", e, DOCUMENT_UPLOAD_FAILED);
        } catch (IOException e) {
            throw new ServiceException("URL 下载失败: " + e.getMessage(), e, DOCUMENT_UPLOAD_FAILED);
        }
    }

    private URI parseSourceUri(String sourceUrl) {
        if (!StringUtils.hasText(sourceUrl)) {
            throw new ClientException(DOCUMENT_URL_INVALID);
        }
        try {
            URI uri = URI.create(sourceUrl.trim());
            String scheme = uri.getScheme();
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
                throw new ClientException(DOCUMENT_URL_INVALID);
            }
            if (!StringUtils.hasText(uri.getHost())) {
                throw new ClientException(DOCUMENT_URL_INVALID);
            }
            return uri;
        } catch (IllegalArgumentException e) {
            throw new ClientException("URL 格式不合法", e, DOCUMENT_URL_INVALID);
        }
    }

    private String resolveFileName(Optional<String> dispositionHeader, URI uri) {
        String headerFileName = dispositionHeader.map(this::parseContentDispositionFilename).orElse(null);
        if (StringUtils.hasText(headerFileName)) {
            return headerFileName;
        }
        String path = uri.getPath();
        if (!StringUtils.hasText(path)) {
            return null;
        }
        int index = path.lastIndexOf('/');
        String candidate = index >= 0 ? path.substring(index + 1) : path;
        if (!StringUtils.hasText(candidate)) {
            return null;
        }
        return decode(candidate);
    }

    private String parseContentDispositionFilename(String header) {
        try {
            ContentDisposition disposition = ContentDisposition.parse(header);
            return decode(disposition.getFilename());
        } catch (Exception ignore) {
            return null;
        }
    }

    private String decode(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (Exception ignore) {
            return value;
        }
    }

    private byte[] readWithLimit(InputStream inputStream, long maxBytes) throws IOException {
        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            long total = 0;
            int len;
            while ((len = inputStream.read(buffer)) != -1) {
                total += len;
                if (maxBytes > 0 && total > maxBytes) {
                    throw new ClientException(DOCUMENT_SIZE_EXCEEDED);
                }
                outputStream.write(buffer, 0, len);
            }
            return outputStream.toByteArray();
        }
    }

    private Long parseContentLength(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignore) {
            return null;
        }
    }

    private String normalizeContentType(String contentType) {
        if (!StringUtils.hasText(contentType)) {
            return null;
        }
        int separator = contentType.indexOf(';');
        return separator >= 0 ? contentType.substring(0, separator).trim() : contentType.trim();
    }

    public record FetchedRemoteDocument(String sourceUrl, String fileName, String contentType, byte[] body) {
    }
}
