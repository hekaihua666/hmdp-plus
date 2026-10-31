package com.hmdp.config;

import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestHighLevelClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Elasticsearch 配置类
 */
@Configuration
public class EsConfig {

    /** ES 服务地址，来自配置文件中的 elasticsearch.host。 */
    @Value("${elasticsearch.host}")
    private String host;

    /** ES HTTP 端口，来自配置文件中的 elasticsearch.port。 */
    @Value("${elasticsearch.port}")
    private int port;

    /**
     * 创建 ES 高级 REST 客户端（RestHighLevelClient）。
     * 全局单例，项目里所有 ES 的索引、查询、写入都通过它发起。
     */
    @Bean
    public RestHighLevelClient restHighLevelClient() {
        return new RestHighLevelClient(
                RestClient.builder(HttpHost.create("http://" + host + ":" + port))
        );
    }
}
