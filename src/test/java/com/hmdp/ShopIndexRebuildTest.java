package com.hmdp;

import com.hmdp.service.ShopIndexMaintenance;
import com.hmdp.service.IVoucherOrderService;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import javax.annotation.Resource;

/**
 * 本地维护命令，会删除并重建 hmdp_shop，执行前应确认这是学习环境。
 * 默认跳过，普通 mvn test / package 不会重建业务索引。
 * 显式运行：mvn -Dtest=ShopIndexRebuildTest -Dhmdp.es.rebuild=true test
 */
@Slf4j
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@EnabledIfSystemProperty(named = "hmdp.es.rebuild", matches = "true")
class ShopIndexRebuildTest {
    /** 维护只处理商户，不启动原秒杀订单后台消费者。 */
    @MockBean private IVoucherOrderService voucherOrderService;
    @Resource
    private ShopIndexMaintenance maintenance;

    @Test
    void rebuildShopIndex() throws Exception {
        // 失败直接让命令失败，不把部分导入报告为成功。
        log.info("商户索引重建完成，成功导入 {} 条", maintenance.rebuild());
    }
}
