package com.hmdp;

import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import com.hmdp.service.IShopService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.EsSearchSupport;
import com.hmdp.utils.RedisConstants;
import org.elasticsearch.action.bulk.BulkRequest;
import org.elasticsearch.action.get.GetRequest;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.admin.indices.delete.DeleteIndexRequest;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.client.indices.CreateIndexRequest;
import org.elasticsearch.xcontent.XContentType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.annotation.Resource;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 显式启用的本机集成验收：需要 MySQL、Redis、ES，默认跳过。
 * 更新现有商户后用 finally 恢复；新增只验证回滚；Bulk 使用独立临时索引。
 * 运行：mvn -Dtest=ShopSyncIntegrationTest -Dhmdp.es.integration=true test
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@EnabledIfSystemProperty(named = "hmdp.es.integration", matches = "true")
class ShopSyncIntegrationTest {
    /** 本验收只处理商户，避免启动与测试无关的秒杀消费者。 */
    @MockBean private IVoucherOrderService voucherOrderService;
    @Resource private IShopService shopService;
    @Resource private StringRedisTemplate redis;
    @Resource private RestHighLevelClient client;
    @Resource private PlatformTransactionManager transactionManager;

    @Test
    void actualUpdateRollbackPreservesDatabaseCacheAndEs() throws Exception {
        Shop original = shopService.getById(1L);
        assertNotNull(original, "学习数据须包含商户1");
        String cacheKey = RedisConstants.CACHE_SHOP_KEY + original.getId();
        String cache = redis.opsForValue().get(cacheKey);
        String oldEs = esSource(original.getId());
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.execute(status -> {
            shopService.update(new Shop().setId(original.getId()).setTypeId(original.getTypeId() + 100)
                    .setName("回滚验证商户").setX(121.0).setY(31.0));
            status.setRollbackOnly();
            return null;
        });
        assertEquals(original, shopService.getById(original.getId()));
        assertEquals(cache, redis.opsForValue().get(cacheKey));
        assertEquals(oldEs, esSource(original.getId()));
    }

    @Test
    void actualInsertRollbackLeavesNoEsDocument() throws Exception {
        Shop temporary = JSONUtil.toBean(JSONUtil.toJsonStr(shopService.getById(1L)), Shop.class);
        // 复制课程店铺验证新增回滚，不复制账号归属，否则会触发“一账号一店”唯一约束。
        temporary.setId(null).setOwnerId(null).setName("新增回滚验证商户");
        new TransactionTemplate(transactionManager).execute(status -> {
            assertTrue(shopService.saveShop(temporary).getSuccess());
            status.setRollbackOnly();
            return null;
        });
        assertNotNull(temporary.getId());
        assertNull(shopService.getById(temporary.getId()));
        assertFalse(client.get(new GetRequest("hmdp_shop", temporary.getId().toString()), RequestOptions.DEFAULT).isExists());
    }

    @Test
    void actualCommitUpdatesBothStoresAndRestoresOriginalShop() throws Exception {
        Shop original = shopService.getById(1L);
        Long newType = original.getTypeId() + 100;
        String cacheKey = RedisConstants.CACHE_SHOP_KEY + original.getId();
        String originalCache = redis.opsForValue().get(cacheKey);
        Long originalTtl = redis.getExpire(cacheKey, TimeUnit.SECONDS);
        try {
            redis.opsForValue().set(cacheKey, JSONUtil.toJsonStr(original), 60, TimeUnit.SECONDS);
            assertTrue(shopService.update(new Shop().setId(original.getId()).setTypeId(newType)
                    .setName("提交同步验证商户").setX(121.0).setY(31.0)).getSuccess());
            assertEquals(newType, shopService.getById(original.getId()).getTypeId());
            assertNull(redis.opsForValue().get(cacheKey));
            awaitName(original.getId(), "提交同步验证商户");
        } finally {
            // 包括断言失败也恢复，避免把验收数据留在学习项目中。
            assertTrue(shopService.update(original).getSuccess());
            awaitName(original.getId(), original.getName());
            if (originalCache != null) {
                redis.opsForValue().set(cacheKey, originalCache);
                if (originalTtl != null && originalTtl > 0) redis.expire(cacheKey, originalTtl, TimeUnit.SECONDS);
            }
        }
        assertEquals(original, shopService.getById(original.getId()));
    }

    /** 真 ES 部分失败后，确认成功项仍在索引中，失败项没有写入。 */
    @Test
    void actualBulkFailureDoesNotRollbackSuccessfulItem() throws Exception {
        String index = "hmdp_bulk_verify_" + UUID.randomUUID().toString().replace("-", "");
        client.indices().create(new CreateIndexRequest(index).source(
                "{\"settings\":{\"number_of_replicas\":0},\"mappings\":{\"properties\":{\"location\":{\"type\":\"geo_point\"}}}}",
                XContentType.JSON), RequestOptions.DEFAULT);
        try {
            BulkRequest bulk = new BulkRequest()
                    .add(new IndexRequest(index).id("good").source("{\"location\":\"30,120\"}", XContentType.JSON))
                    .add(new IndexRequest(index).id("bad").source("{\"location\":\"1000,120\"}", XContentType.JSON));
            IOException error = assertThrows(IOException.class,
                    () -> EsSearchSupport.checkBulk(client.bulk(bulk, RequestOptions.DEFAULT)));
            assertTrue(error.getMessage().contains("成功1条，失败1条"));
            assertTrue(client.get(new GetRequest(index, "good"), RequestOptions.DEFAULT).isExists());
            assertFalse(client.get(new GetRequest(index, "bad"), RequestOptions.DEFAULT).isExists());
        } finally {
            client.indices().delete(new DeleteIndexRequest(index), RequestOptions.DEFAULT);
        }
    }

    private String esSource(Long id) throws IOException {
        return client.get(new GetRequest("hmdp_shop", id.toString()), RequestOptions.DEFAULT).getSourceAsString();
    }

    private void awaitName(Long id, String name) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            String source = esSource(id);
            if (source != null && name.equals(JSONUtil.parseObj(source).getStr("name"))) return;
            Thread.sleep(100);
        }
        fail("等待异步 ES 同步超时，shopId=" + id);
    }
}
