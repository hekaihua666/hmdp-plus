package com.hmdp;

import com.hmdp.service.IShopSearchService;
import com.hmdp.service.IShopService;
import com.hmdp.service.IVoucherOrderService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import javax.annotation.Resource;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 演示数据写入 MySQL 后，显式复用正式 ShopDoc 转换链路补齐 ES。
 * 不删索引，不重写拼音规则；分类、附近和聚合均复用这份ES索引。
 * 普通 test/package 默认跳过：mvn '-Dtest=DemoIndexSyncTest' '-Dhmdp.demo.sync=true' test。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@EnabledIfSystemProperty(named = "hmdp.demo.sync", matches = "true")
class DemoIndexSyncTest {
    /** 同步商户时不启动与本任务无关的秒杀消费线程。 */
    @MockBean private IVoucherOrderService voucherOrderService;
    @Resource private IShopSearchService searchService;
    @Resource private IShopService shopService;

    @Test
    void synchronizeWithoutRecreatingIndex() throws Exception {
        assertEquals(shopService.count(), searchService.bulkImportAll(), "每家商户都应完成索引导入");
    }
}
