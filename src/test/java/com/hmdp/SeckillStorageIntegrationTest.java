package com.hmdp;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.hmdp.entity.SeckillRequest;
import com.hmdp.mapper.SeckillRequestMapper;
import com.hmdp.mq.OrderCreateMessage;
import com.hmdp.mq.SeckillRecovery;
import com.hmdp.service.SeckillRequestService;
import com.hmdp.service.SeckillReservationService;
import com.hmdp.utils.RedisIdWorker;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.annotation.Resource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

/**
 * 显式连接本机 MySQL/Redis/MQ，使用独立临时券并在结束后删除自己的夹具。
 * 调度器被替换为 mock 以固定故障窗口；真实后台调度与 MQ 消费另做接口联调。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@EnabledIfSystemProperty(named = "hmdp.mq.integration", matches = "true")
class SeckillStorageIntegrationTest {
    @Resource private JdbcTemplate jdbc;
    @Resource private StringRedisTemplate redis;
    @Resource private RedisIdWorker ids;
    @Resource private SeckillRequestService lifecycle;
    @Resource private SeckillRequestMapper requests;
    @SpyBean private SeckillReservationService reservation;
    @MockBean private SeckillRecovery recovery;

    private final List<Long> vouchers = new ArrayList<>();
    private final List<Long> applications = new ArrayList<>();
    private final long userId = 8_000_000_000_000_000_000L + System.nanoTime() % 1_000_000_000L;

    /** 自动生成券主键，避免大号显式主键抬高真实券表的自增序列。 */
    private long coupon(int stock) {
        KeyHolder generated = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO tb_voucher(shop_id,title,pay_value,actual_value,type,status) "
                    + "VALUES(1,'MQ可靠性验收临时券',100,100,1,1)", Statement.RETURN_GENERATED_KEYS);
            return statement;
        }, generated);
        long id = generated.getKey().longValue();
        vouchers.add(id);
        jdbc.update("INSERT INTO tb_seckill_voucher(voucher_id,stock,begin_time,end_time) VALUES(?,?,?,?)",
                id, stock, LocalDateTime.now().minusHours(1), LocalDateTime.now().plusHours(1));
        redis.opsForValue().set("seckill:stock:" + id, Integer.toString(stock));
        return id;
    }

    private SeckillRequest prepared(long voucherId) {
        long id = ids.nextId("order");
        applications.add(id);
        lifecycle.create(id, userId, voucherId);
        lifecycle.prepare(id);
        SeckillRequest request = requests.selectById(id);
        assertEquals("READY", request.getState());
        assertEquals("HELD", request.getReservationState());
        return request;
    }

    @AfterEach
    void cleanup() {
        // 只删除本测试登记的主键和券对应的 Redis key，不重置库存、自增序列或全局 key 空间。
        for (Long orderId : applications) {
            jdbc.update("DELETE FROM tb_voucher_order WHERE id=?", orderId);
            redis.delete("seckill:request:" + orderId);
        }
        for (Long voucherId : vouchers) {
            jdbc.update("DELETE FROM tb_seckill_request WHERE voucher_id=?", voucherId);
            jdbc.update("DELETE FROM tb_seckill_voucher WHERE voucher_id=?", voucherId);
            jdbc.update("DELETE FROM tb_voucher WHERE id=?", voucherId);
            redis.delete("seckill:stock:" + voucherId);
            redis.delete("seckill:owners:" + voucherId);
        }
    }

    @Test
    void lostBothCredentialsCannotCompleteCompensationAndEvidenceCanBeRestored() {
        long voucherId = coupon(1);
        SeckillRequest request = prepared(voucherId);
        redis.delete("seckill:owners:" + voucherId);
        redis.delete("seckill:request:" + request.getOrderId());
        lifecycle.cancel(request.getOrderId(), "验收凭据丢失");
        assertThrows(IllegalStateException.class, () -> lifecycle.compensate(request.getOrderId()));
        assertEquals("CANCELLING", requests.selectById(request.getOrderId()).getState());
        assertEquals("0", redis.opsForValue().get("seckill:stock:" + voucherId));

        redis.opsForHash().put("seckill:owners:" + voucherId, userId + "", request.getOrderId() + "");
        lifecycle.compensate(request.getOrderId());
        lifecycle.compensate(request.getOrderId());
        assertEquals("FAILED", requests.selectById(request.getOrderId()).getState());
        assertEquals("1", redis.opsForValue().get("seckill:stock:" + voucherId));
    }

    @Test
    void unknownRetryWithMissingEvidenceNeverDeductsAgainOrDeclaresFailure() {
        long voucherId = coupon(2);
        SeckillRequest request = prepared(voucherId);
        jdbc.update("UPDATE tb_seckill_request SET state='INIT',reservation_state='UNKNOWN' WHERE order_id=?",
                request.getOrderId());
        redis.delete("seckill:owners:" + voucherId);
        redis.delete("seckill:request:" + request.getOrderId());
        assertThrows(IllegalStateException.class, () -> lifecycle.prepare(request.getOrderId()));
        assertEquals("1", redis.opsForValue().get("seckill:stock:" + voucherId));
        assertEquals("INIT", requests.selectById(request.getOrderId()).getState());
    }

    @Test
    void networkFailureKeepsPreviouslyCommittedUnknownIntent() {
        long voucherId = coupon(1);
        long orderId = ids.nextId("order");
        applications.add(orderId);
        lifecycle.create(orderId, userId, voucherId);
        doThrow(new IllegalStateException("验收网络确认丢失")).when(reservation).reserve(any(), any(), eq(true));
        assertThrows(IllegalStateException.class, () -> lifecycle.prepare(orderId));
        assertEquals("UNKNOWN", requests.selectById(orderId).getReservationState());
        assertEquals("INIT", requests.selectById(orderId).getState());
    }

    @Test
    void concurrentDuplicateEventsOnlyDeductOnce() throws Exception {
        SeckillRequest request = prepared(coupon(1));
        runTogether(() -> lifecycle.consume(OrderCreateMessage.of(request)),
                () -> lifecycle.consume(OrderCreateMessage.of(request)));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM tb_voucher_order WHERE id=?",
                Integer.class, request.getOrderId()));
        assertEquals("SUCCEEDED", requests.selectById(request.getOrderId()).getState());
    }

    @Test
    void sameUserCanConsumeDifferentVouchersConcurrently() throws Exception {
        SeckillRequest first = prepared(coupon(1));
        SeckillRequest second = prepared(coupon(1));
        runTogether(() -> lifecycle.consume(OrderCreateMessage.of(first)),
                () -> lifecycle.consume(OrderCreateMessage.of(second)));
        assertEquals("SUCCEEDED", requests.selectById(first.getOrderId()).getState());
        assertEquals("SUCCEEDED", requests.selectById(second.getOrderId()).getState());
    }

    /** 使用只针对临时券的触发器，在扣库存之后注入插单失败。 */
    @Test
    void insertFailureRollsBackStockAndSuccessClearsStoredError() {
        SeckillRequest request = prepared(coupon(1));
        String trigger = "qa_mq_review_" + request.getOrderId();
        jdbc.execute("CREATE TRIGGER " + trigger + " BEFORE INSERT ON tb_voucher_order FOR EACH ROW "
                + "BEGIN IF NEW.voucher_id=" + request.getVoucherId()
                + " THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='MQ_REVIEW_INSERT_FAILURE'; END IF; END");
        try {
            assertThrows(RuntimeException.class, () -> lifecycle.consume(OrderCreateMessage.of(request)));
            assertEquals(1, jdbc.queryForObject("SELECT stock FROM tb_seckill_voucher WHERE voucher_id=?",
                    Integer.class, request.getVoucherId()));
            assertEquals("READY", requests.selectById(request.getOrderId()).getState());
        } finally {
            jdbc.execute("DROP TRIGGER " + trigger);
        }
        jdbc.update("UPDATE tb_seckill_request SET last_error='等待恢复' WHERE order_id=?", request.getOrderId());
        lifecycle.consume(OrderCreateMessage.of(request));
        assertEquals("SUCCEEDED", requests.selectById(request.getOrderId()).getState());
        assertNull(requests.selectById(request.getOrderId()).getLastError());
    }

    /** 两千条终态记录与少量在途记录混合，核对恢复查询实际选用索引且不额外排序。 */
    @Test
    void recoveryQueriesUseTheirRangeIndexes() {
        long voucherId = coupon(1);
        long base = -System.currentTimeMillis() * 10000L;
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < 2000; i++) {
            rows.add(new Object[] {base - i, userId, voucherId});
        }
        jdbc.batchUpdate("INSERT INTO tb_seckill_request(order_id,user_id,voucher_id,state,reservation_state,"
                + "send_attempts,next_send_time,deadline,create_time,update_time) "
                + "VALUES(?,?,?,'SUCCEEDED','HELD',0,NOW(),NOW(),NOW(),NOW())", rows);
        lifecycle.create(ids.nextId("order"), userId, voucherId);
        Map<String, Object> due = jdbc.queryForMap(
                "EXPLAIN SELECT * FROM tb_seckill_request WHERE state='INIT' AND next_send_time<=NOW() "
                + "ORDER BY next_send_time,order_id LIMIT 20");
        Map<String, Object> expired = jdbc.queryForMap(
                "EXPLAIN SELECT * FROM tb_seckill_request WHERE state='READY' AND deadline<=NOW() "
                + "ORDER BY deadline,order_id LIMIT 20");
        assertEquals("idx_request_recovery", due.get("key"));
        assertEquals("idx_request_deadline", expired.get("key"));
        assertFalse(String.valueOf(due.get("Extra")).contains("filesort"));
        assertFalse(String.valueOf(expired.get("Extra")).contains("filesort"));
        System.out.println("MQ_REVIEW_QUERY_PLANS due=" + due.get("key")
                + ", expired=" + expired.get("key") + ", filesort=false");
    }

    private void runTogether(Runnable first, Runnable second) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CyclicBarrier start = new CyclicBarrier(2);
        try {
            Callable<Void> one = () -> { start.await(); first.run(); return null; };
            Callable<Void> two = () -> { start.await(); second.run(); return null; };
            Future<Void> a = executor.submit(one);
            Future<Void> b = executor.submit(two);
            a.get(15, TimeUnit.SECONDS);
            b.get(15, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
    }
}
