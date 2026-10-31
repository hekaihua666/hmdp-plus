package com.hmdp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.entity.Shop;
import com.hmdp.entity.ShopSyncEvent;
import com.hmdp.mapper.ShopSyncEventMapper;
import com.hmdp.mq.ShopSyncConsumer;
import com.hmdp.mq.ShopSyncDeadLetterConsumer;
import com.hmdp.mq.ShopSyncMessage;
import com.hmdp.service.IShopSearchService;
import com.hmdp.service.impl.ShopSearchServiceImpl;
import com.sun.net.httpserver.HttpServer;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestHighLevelClient;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 验证消费异常传播、SENT 死信落错，以及真实 HLRC 的版本参数和冲突处理。 */
class ShopSyncReliabilityTest {
    @Test
    void ioFailureMustReachMqRetry() throws Exception {
        IShopSearchService search = mock(IShopSearchService.class);
        doThrow(new IOException("ES unavailable")).when(search).syncById(1L);
        ShopSyncConsumer consumer = new ShopSyncConsumer();
        ReflectionTestUtils.setField(consumer, "shopSearchService", search);
        assertThrows(IllegalStateException.class, () -> consumer.onMessage(ShopSyncMessage.of(1L, 2L)));
    }

    @Test
    void deadLetterRecordsFailureEvenWhenAlreadySent() throws Exception {
        ShopSyncEventMapper events = mock(ShopSyncEventMapper.class);
        ShopSyncEvent sent = new ShopSyncEvent();
        sent.setShopId(1L); sent.setState("SENT"); sent.setRevision(2L);
        when(events.selectById(1L)).thenReturn(sent);
        ShopSyncDeadLetterConsumer consumer = new ShopSyncDeadLetterConsumer();
        ObjectMapper json = new ObjectMapper();
        ReflectionTestUtils.setField(consumer, "json", json);
        ReflectionTestUtils.setField(consumer, "events", events);
        consumer.onMessage(json.writeValueAsString(ShopSyncMessage.of(1L, 2L)));
        verify(events).consumeFailed(eq(1L), eq(2L), anyString());
        verify(events, never()).sendFailed(anyLong(), anyLong(), anyString());
    }

    @Test
    void lateOldWriteIsRejectedAndEqualRevisionCanRepeat() throws Exception {
        // HTTP 替身执行 ES external_gte 规则；使用真实 HLRC 发请求和解析 409。
        AtomicLong stored = new AtomicLong();
        AtomicLong writes = new AtomicLong();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String query = exchange.getRequestURI().getRawQuery();
            String response;
            int status = 200;
            if ("PUT".equals(exchange.getRequestMethod())) {
                if (query == null || !query.contains("version_type=external_gte")) {
                    status = 400; response = "{\"error\":{\"type\":\"illegal_argument_exception\",\"reason\":\"missing version\"},\"status\":400}";
                } else {
                    long revision = Long.parseLong(query.replaceAll(".*(?:^|&)version=([0-9]+).*", "$1"));
                    if (revision < stored.get()) {
                        status = 409; response = "{\"error\":{\"type\":\"version_conflict_engine_exception\",\"reason\":\"old revision\"},\"status\":409}";
                    } else {
                        stored.set(revision); writes.incrementAndGet();
                        response = "{\"_index\":\"hmdp_shop\",\"_type\":\"_doc\",\"_id\":\"1\",\"_version\":" + revision
                                + ",\"result\":\"updated\",\"_shards\":{\"total\":1,\"successful\":1,\"failed\":0},\"_seq_no\":1,\"_primary_term\":1}";
                    }
                }
            } else {
                response = "{\"name\":\"fixture\",\"cluster_name\":\"fixture\",\"cluster_uuid\":\"fixture\",\"version\":{\"number\":\"7.17.24\",\"build_flavor\":\"default\",\"build_type\":\"zip\",\"build_hash\":\"test\",\"build_date\":\"2024-01-01T00:00:00Z\",\"build_snapshot\":false,\"lucene_version\":\"8.11.3\",\"minimum_wire_compatibility_version\":\"6.8.0\",\"minimum_index_compatibility_version\":\"6.0.0-beta1\"},\"tagline\":\"You Know, for Search\"}";
            }
            exchange.getRequestBody().close();
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.getResponseHeaders().add("X-Elastic-Product", "Elasticsearch");
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        try (RestHighLevelClient client = new RestHighLevelClient(RestClient.builder(new HttpHost("127.0.0.1", server.getAddress().getPort())))) {
            ShopSyncEventMapper events = mock(ShopSyncEventMapper.class);
            Shop latest = new Shop().setId(1L).setName("new").setSyncRevision(1000000000002L);
            Shop old = new Shop().setId(1L).setName("old").setSyncRevision(1000000000001L);
            when(events.shopForSync(1L)).thenReturn(latest, old, latest);
            ShopSearchServiceImpl service = new ShopSearchServiceImpl();
            ReflectionTestUtils.setField(service, "client", client);
            ReflectionTestUtils.setField(service, "shopSyncEvents", events);
            service.syncById(1L);
            assertDoesNotThrow(() -> service.syncById(1L));
            service.syncById(1L);
            assertEquals(1000000000002L, stored.get());
            assertEquals(2L, writes.get(), "旧版本必须被拒绝，同版本补发可以成功");
        } finally {
            server.stop(0);
        }
    }
}
