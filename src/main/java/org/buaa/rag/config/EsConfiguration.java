package org.buaa.rag.config;

import java.io.IOException;

import org.apache.http.HttpHost;
import org.buaa.rag.properties.EsProperties;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestClientBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.indices.ExistsRequest;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.endpoints.BooleanResponse;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Elasticsearch搜索引擎配置
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class EsConfiguration {

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
     * 构建Elasticsearch客户端实例
     *
     * @return ES客户端
     */
    @Bean
    public ElasticsearchClient elasticsearchClient(RestClient restClient) {
        RestClientTransport transport = new RestClientTransport(
                restClient,
                new JacksonJsonpMapper()
        );
        ElasticsearchClient client = new ElasticsearchClient(transport);
        esIndexValidator(client);

        return client;
    }

    /**
     * 应用启动时校验 Elasticsearch 索引是否存在
     */
    public void  esIndexValidator(ElasticsearchClient esClient) {
        String indexName = esProperties.getIndex();
        try {
            BooleanResponse response = esClient.indices().exists(
                    ExistsRequest.of(e -> e.index(indexName))
            );
            if (response.value()) {
                log.info("Elasticsearch 索引 [{}] 已存在，校验通过", indexName);
            } else {
                log.warn("Elasticsearch 索引 [{}] 不存在，请创建", indexName);
            }
        } catch (IOException ex) {
            log.error("Elasticsearch 索引校验失败，无法连接到 ES 集群: {}", ex.getMessage(), ex);
        }
    }
}
