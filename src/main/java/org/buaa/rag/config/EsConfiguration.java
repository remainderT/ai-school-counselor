package org.buaa.rag.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.apache.http.HttpHost;
import org.buaa.rag.properties.EsProperties;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.RestClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.indices.ExistsRequest;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.endpoints.BooleanResponse;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Elasticsearch 搜索引擎配置。
 * <p>
 * 负责创建 ES 客户端 Bean，并在启动阶段校验 / 自动创建索引。
 * 索引 mapping 从 classpath 的 {@code datasource/knowledge.json} 加载，
 * 避免在 Java 代码中硬编码 mapping 定义。
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class EsConfiguration {

    private static final String MAPPING_RESOURCE = "datasource/knowledge.json";

    private final EsProperties esProperties;

    /**
     * 创建底层 RestClient Bean，由 Spring 管理生命周期并在容器关闭时自动释放连接池。
     */
    @Bean(destroyMethod = "close")
    public RestClient restClient() {
        return RestClient.builder(
                new HttpHost(esProperties.getHost(), esProperties.getPort(), esProperties.getScheme())
        ).build();
    }

    /**
     * 构建 Elasticsearch 客户端实例。
     *
     * @return ES 客户端
     */
    @Bean
    public ElasticsearchClient elasticsearchClient(RestClient restClient) {
        RestClientTransport transport = new RestClientTransport(
                restClient,
                new JacksonJsonpMapper()
        );
        return new ElasticsearchClient(transport);
    }

    /**
     * 应用启动时校验并自动创建 ES 索引。
     * <p>
     * 依赖 {@code ElasticsearchClient} 和 {@code RestClient} Bean 均就绪后执行。
     * 如果索引不存在，则从 {@code datasource/knowledge.json} 读取完整索引定义自动创建。
     */
    @Bean
    public boolean esIndexReady(ElasticsearchClient esClient, RestClient restClient) {
        String indexName = esProperties.getIndex();
        try {
            BooleanResponse exists = esClient.indices().exists(
                    ExistsRequest.of(e -> e.index(indexName))
            );
            if (exists.value()) {
                log.info("Elasticsearch 索引 [{}] 已存在，校验通过", indexName);
                return true;
            }

            return createIndexFromResource(indexName, restClient);
        } catch (IOException e) {
            log.error("Elasticsearch 索引校验失败: {}", e.getMessage(), e);
            return false;
        }
    }

    // ======================== private helpers ========================

    /**
     * 从 classpath 资源文件读取完整的索引定义（含 mappings），
     * 通过 Low-Level REST 客户端发送 PUT 请求创建索引，完整保留 knowledge.json 中的定义。
     */
    private boolean createIndexFromResource(String indexName, RestClient restClient) {
        log.info("Elasticsearch 索引 [{}] 不存在，从 {} 加载 mapping 自动创建", indexName, MAPPING_RESOURCE);
        try {
            String mappingJson = loadResource(MAPPING_RESOURCE);

            Request request = new Request("PUT", "/" + indexName);
            request.setJsonEntity(mappingJson);

            Response response = restClient.performRequest(request);

            int statusCode = response.getStatusLine().getStatusCode();
            if (statusCode == 200) {
                log.info("Elasticsearch 索引 [{}] 创建成功", indexName);
                return true;
            } else {
                log.warn("Elasticsearch 索引 [{}] 创建返回非预期状态码: {}", indexName, statusCode);
                return false;
            }
        } catch (Exception e) {
            log.error("Elasticsearch 索引 [{}] 创建失败: {}", indexName, e.getMessage(), e);
            return false;
        }
    }

    private String loadResource(String path) throws IOException {
        ClassPathResource resource = new ClassPathResource(path);
        try (InputStream is = resource.getInputStream()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
