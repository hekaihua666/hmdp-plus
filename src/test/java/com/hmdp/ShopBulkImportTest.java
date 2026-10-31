package com.hmdp;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.hmdp.entity.Shop;
import com.hmdp.service.IShopService;
import com.hmdp.service.impl.ShopSearchServiceImpl;
import com.sun.net.httpserver.HttpServer;
import org.apache.http.HttpHost;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestHighLevelClient;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 使用真实 HLRC 请求和响应解析，本地 HTTP 替身模拟 ES。
 * 验证分批读取、失败终止以及 Bulk 成功项不会回滚，不需要真实 ES 运行。
 */
class ShopBulkImportTest {
    private HttpServer server;
    private RestHighLevelClient client;
    private ShopSearchServiceImpl importer;
    private IShopService shops;
    private Set<String> persisted;
    private boolean failSecond;
    private List<Integer> batchSizes;

    @BeforeEach
    void setup() throws IOException {
        persisted = new HashSet<>();
        batchSizes = new ArrayList<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String response;
            if (exchange.getRequestURI().getPath().equals("/_bulk")) {
                // NDJSON 每个操作包含 metadata 和 document 两行。
                Scanner scanner = new Scanner(exchange.getRequestBody(), "UTF-8").useDelimiter("\\A");
                String body = scanner.hasNext() ? scanner.next() : "";
                String[] lines = body.split("\\n");
                List<Map<String, Object>> items = new ArrayList<>();
                for (int i = 0; i < lines.length; i += 2) {
                    JSONObject index = JSONUtil.parseObj(lines[i]).getJSONObject("index");
                    String id = index.getStr("_id");
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("_index", "hmdp_shop"); result.put("_id", id); result.put("_type", "_doc");
                    if (failSecond && id.equals("2")) {
                        result.put("status", 400);
                        result.put("error", new JSONObject().set("type", "mapper_parsing_exception")
                                .set("reason", "invalid location"));
                    } else {
                        persisted.add(id);
                        result.put("status", 201); result.put("result", "created"); result.put("_version", 1);
                        result.put("_seq_no", 1); result.put("_primary_term", 1);
                        result.put("_shards", new JSONObject().set("total", 1).set("successful", 1).set("failed", 0));
                    }
                    items.add(Collections.singletonMap("index", result));
                }
                batchSizes.add(items.size());
                response = new JSONObject().set("took", 1).set("errors", failSecond).set("items", items).toString();
            } else {
                response = "{\"name\":\"fixture\",\"cluster_name\":\"fixture\",\"cluster_uuid\":\"fixture\","
                        + "\"version\":{\"number\":\"7.17.24\",\"build_flavor\":\"default\",\"build_type\":\"zip\","
                        + "\"build_hash\":\"test\",\"build_date\":\"2024-01-01T00:00:00Z\",\"build_snapshot\":false,"
                        + "\"lucene_version\":\"8.11.3\",\"minimum_wire_compatibility_version\":\"6.8.0\","
                        + "\"minimum_index_compatibility_version\":\"6.0.0-beta1\"},\"tagline\":\"You Know, for Search\"}";
            }
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.getResponseHeaders().add("X-Elastic-Product", "Elasticsearch");
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        client = new RestHighLevelClient(RestClient.builder(new HttpHost("127.0.0.1", server.getAddress().getPort())));
        shops = mock(IShopService.class);
        importer = new ShopSearchServiceImpl();
        ReflectionTestUtils.setField(importer, "client", client);
        ReflectionTestUtils.setField(importer, "shopService", shops);
        // Lambda 条件需要 MyBatis 的字段元数据；此处不建立数据库连接。
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), "test"), Shop.class);
    }

    @AfterEach
    void close() throws IOException {
        client.close();
        server.stop(0);
    }

    @Test
    @SuppressWarnings("unchecked")
    void importsMultiplePagesUsingLastId() throws Exception {
        when(shops.list(any(Wrapper.class))).thenReturn(page(1, 500), page(501, 501), Collections.emptyList());
        assertEquals(501, importer.bulkImportAll());
        assertEquals(Arrays.asList(500, 1), batchSizes);
        assertEquals(501, persisted.size());
        ArgumentCaptor<Wrapper<Shop>> queries = ArgumentCaptor.forClass(Wrapper.class);
        verify(shops, times(3)).list(queries.capture());
        Wrapper<Shop> second = queries.getAllValues().get(1);
        assertTrue(second.getSqlSegment().contains("id >"));
        assertTrue(second.getSqlSegment().contains("LIMIT 500"));
        assertTrue(((com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Shop>) second)
                .getParamNameValuePairs().containsValue(500L));
        verify(shops, never()).list();
    }

    @Test
    @SuppressWarnings("unchecked")
    void partialFailureStopsImportButPreservesSuccessfulItem() {
        failSecond = true;
        when(shops.list(any(Wrapper.class))).thenReturn(page(1, 2), page(3, 3));
        IOException error = assertThrows(IOException.class, () -> importer.bulkImportAll());
        assertTrue(error.getMessage().contains("成功1条，失败1条"));
        assertTrue(error.getMessage().contains("ID=2"));
        assertEquals(Collections.singleton("1"), persisted);
        verify(shops, times(1)).list(any(Wrapper.class));
    }

    private List<Shop> page(long start, long end) {
        return LongStream.rangeClosed(start, end).mapToObj(id -> {
            Shop shop = new Shop();
            shop.setId(id); shop.setName("测试商户" + id);
            return shop;
        }).collect(Collectors.toList());
    }
}
