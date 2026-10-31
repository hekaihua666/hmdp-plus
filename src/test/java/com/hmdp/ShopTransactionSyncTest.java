package com.hmdp;

import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.mapper.ShopSyncEventMapper;
import com.hmdp.listener.ShopChangedEventListener;
import com.hmdp.service.IShopSearchService;
import com.hmdp.service.IShopService;
import com.hmdp.service.ShopSyncEventService;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.dto.MerchantDTO;
import com.hmdp.dto.ShopUpdateDTO;
import com.hmdp.utils.MerchantHolder;
import com.hmdp.service.IShopTypeService;
import com.hmdp.entity.ShopType;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 使用真实 Spring 事务代理、事务事件和提交/回滚回调。
 * 数据库 CRUD 与 Redis/ES 使用替身，验证副作用时机，普通测试无需启动外部服务。
 * Outbox 使用真实服务与 Mapper 替身，ES 不再由事务事件直接调用。
 */
class ShopTransactionSyncTest {
    private AnnotationConfigApplicationContext context;
    private IShopService service;
    private ShopServiceImpl target;
    private StringRedisTemplate redis;
    private IShopSearchService es;
    private TransactionTemplate transaction;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void openContext() {
        context = new AnnotationConfigApplicationContext(Config.class);
        // 与应用的 MyBatis-Plus 驼峰映射一致；原生 Configuration 默认不会把 ownerId 映射为 owner_id。
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new com.baomidou.mybatisplus.core.MybatisConfiguration(), "shop"), Shop.class);
        service = context.getBean(IShopService.class);
        target = AopTestUtils.getTargetObject(service);
        redis = context.getBean(StringRedisTemplate.class);
        clearInvocations(redis);
        es = context.getBean(IShopSearchService.class);
        transaction = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
        when(context.getBean(ShopSyncEventMapper.class).upsert(anyLong())).thenAnswer(call -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive(), "Outbox 必须在商户事务内记录");
            return 1;
        });
        Shop old = shop(1L);
        Shop latest = shop(2L);
        doReturn(old, latest).when(target).getById(1L);
        doReturn(true).when(target).updateById(any(Shop.class));
        doReturn(true).when(target).save(any(Shop.class));
    }

    @AfterEach
    void closeContext() {
        MerchantHolder.remove();
        context.close();
    }

    /** 新的商户编辑也经过真实事务事件：归属写条件和提交后副作用一起验证。 */
    @Test
    @SuppressWarnings("unchecked")
    void merchantUpdateUsesOwnerConditionAndExistingAfterCommitChain() throws Exception {
        prepareMerchantEdit();
        ShopMapper mapper = context.getBean(ShopMapper.class);
        when(mapper.selectOne(any())).thenReturn(shop(1L));
        when(mapper.update(isNull(), any())).thenReturn(1);
        doReturn(shop(2L)).when(target).getById(1L);
        transaction.execute(status -> {
            assertTrue(service.updateOwnShop(edit()).getSuccess());
            verifyNoInteractions(redis, es);
            return null;
        });
        org.mockito.ArgumentCaptor<Wrapper<Shop>> sql = org.mockito.ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).update(isNull(), sql.capture());
        String condition = sql.getValue().getSqlSegment();
        assertTrue(condition.contains("owner_id"), condition); assertTrue(condition.contains("id"), condition);
        verify(redis).delete("cache:shop:1");
        verifyNoMoreInteractions(redis);
        verify(context.getBean(ShopSyncEventMapper.class)).upsert(1L);
        verifyNoInteractions(es);
    }

    @Test
    void merchantUpdateRollbackDoesNotTouchExternalStores() {
        prepareMerchantEdit();
        ShopMapper mapper = context.getBean(ShopMapper.class);
        when(mapper.selectOne(any())).thenReturn(shop(1L));
        when(mapper.update(isNull(), any())).thenReturn(1);
        transaction.execute(status -> {
            service.updateOwnShop(edit()); status.setRollbackOnly(); return null;
        });
        verifyNoInteractions(redis, es);
    }

    @Test
    void unboundMerchantAndInvalidFormCannotWriteOrPublish() {
        prepareMerchantEdit();
        ShopMapper mapper = context.getBean(ShopMapper.class);
        assertFalse(service.updateOwnShop(edit()).getSuccess());
        ShopUpdateDTO invalid = edit(); invalid.setX(Double.NaN);
        assertThrows(IllegalArgumentException.class, () -> service.updateOwnShop(invalid));
        verify(mapper, never()).update(any(), any());
        verifyNoInteractions(redis, es);
    }

    private void prepareMerchantEdit() {
        MerchantDTO merchant = new MerchantDTO(); merchant.setId(9L); MerchantHolder.save(merchant);
        when(context.getBean(IShopTypeService.class).getById(2L)).thenReturn(new ShopType());
    }

    private ShopUpdateDTO edit() {
        ShopUpdateDTO input = new ShopUpdateDTO();input.setName("测试店铺");input.setTypeId(2L);
        input.setAddress("测试地址");input.setImages("/imgs/test.jpg");input.setAvgPrice(100L);
        input.setX(120.1);input.setY(30.2);return input;
    }

    /** 更新事务内记录 Outbox，提交后失效详情缓存；写请求不直接访问 ES。 */
    @Test
    void updateTouchesRedisAndEsOnlyAfterCommit() throws Exception {
        transaction.execute(status -> {
            assertTrue(service.update(shop(2L)).getSuccess());
            verifyNoInteractions(redis, es);
            return null;
        });
        verify(redis).delete("cache:shop:1");
        verifyNoMoreInteractions(redis);
        verify(context.getBean(ShopSyncEventMapper.class)).upsert(1L);
        verifyNoInteractions(es);
    }

    @Test
    void updateRollbackLeavesExternalStoresUntouched() {
        transaction.execute(status -> {
            service.update(shop(2L));
            status.setRollbackOnly();
            return null;
        });
        verifyNoInteractions(redis, es);
    }

    /** 新增也记录 Outbox，提交后清理空值缓存。 */
    @Test
    void insertCommitSyncsEsAndClearsNegativeCache() throws Exception {
        doReturn(shop(2L)).when(target).getById(1L);
        transaction.execute(status -> {
            service.saveShop(shop(2L));
            verifyNoInteractions(redis, es);
            return null;
        });
        verify(redis).delete("cache:shop:1");
        verifyNoMoreInteractions(redis);
        verify(context.getBean(ShopSyncEventMapper.class)).upsert(1L);
        verifyNoInteractions(es);
    }

    @Test
    void insertRollbackDoesNotLeaveGhostEsDocument() {
        transaction.execute(status -> {
            service.saveShop(shop(2L));
            status.setRollbackOnly();
            return null;
        });
        verifyNoInteractions(redis, es);
    }

    /** Redis 删除失败不影响事务内已经记录的 Outbox。 */
    @Test
    void redisFailureStillAllowsEsSync() throws Exception {
        when(redis.delete(anyString())).thenThrow(new IllegalStateException("redis unavailable"));
        assertDoesNotThrow(() -> transaction.execute(status -> {
            service.update(shop(2L));
            return null;
        }));
        verify(context.getBean(ShopSyncEventMapper.class)).upsert(1L);
        verifyNoInteractions(es);
    }

    /** Outbox 写入失败必须让商户事务失败，不能先删缓存或静默漏掉同步通知。 */
    @Test
    void outboxFailureRollsBackWithoutDeletingCache() {
        doThrow(new IllegalStateException("outbox unavailable"))
                .when(context.getBean(ShopSyncEventMapper.class)).upsert(1L);
        assertThrows(IllegalStateException.class, () -> transaction.execute(status -> {
            service.update(shop(2L));
            return null;
        }));
        verifyNoInteractions(redis, es);
    }

    private Shop shop(Long type) {
        Shop shop = new Shop();
        shop.setId(1L); shop.setTypeId(type);
        shop.setX(120.1); shop.setY(30.2);
        return shop;
    }

    @org.springframework.boot.test.context.TestConfiguration
    @EnableTransactionManagement
    @Import(ShopChangedEventListener.class)
    static class Config {
        @Bean
        ShopServiceImpl shopService() { return spy(new ShopServiceImpl()); }
        @Bean
        ShopSyncEventService shopSyncEventService() { return new ShopSyncEventService(); }
        @Bean
        ShopSyncEventMapper shopSyncEventMapper() { return mock(ShopSyncEventMapper.class); }
        @Bean
        IShopSearchService shopSearchService() { return mock(IShopSearchService.class); }
        @Bean
        IShopTypeService shopTypeService() { return mock(IShopTypeService.class); }
        @Bean
        StringRedisTemplate stringRedisTemplate() { return mock(StringRedisTemplate.class); }
        @Bean
        CacheClient cacheClient() { return mock(CacheClient.class); }
        @Bean
        ShopMapper shopMapper() { return mock(ShopMapper.class); }
        @Bean
        PlatformTransactionManager transactionManager() {
            // 由父类处理事务挂起、同步和回调；此测试只替代底层 JDBC 提交操作。
            return new AbstractPlatformTransactionManager() {
                @Override protected Object doGetTransaction() { return new Object(); }
                @Override protected void doBegin(Object tx, org.springframework.transaction.TransactionDefinition def) { }
                @Override protected void doCommit(DefaultTransactionStatus status) { }
                @Override protected void doRollback(DefaultTransactionStatus status) { }
                // 此夹具不捕获业务异常：异常始终向外传播，由最外层事务执行 doRollback。
                @Override protected void doSetRollbackOnly(DefaultTransactionStatus status) { }
                // service 的 REQUIRED 加入外层 TransactionTemplate，避免误创建嵌套事务。
                @Override protected boolean isExistingTransaction(Object tx) {
                    return TransactionSynchronizationManager.isActualTransactionActive();
                }
            };
        }
    }
}
