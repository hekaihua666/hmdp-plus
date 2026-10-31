package com.hmdp;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.SeckillRequest;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.mapper.SeckillRequestMapper;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.mq.*;
import com.hmdp.service.*;
import com.hmdp.service.impl.VoucherOrderServiceImpl;
import com.hmdp.utils.UserHolder;

import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.Message;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.LocalDateTime;
import java.util.Map;

/** 默认只测故障语义，不连接本机存储。真实MySQL事务、Lua和MQ由显式联调另行验证。 */
class SeckillMqContractTest {
    @Test
    void applicationRefusesToAcceptOrdersWithoutDatabaseUniqueConstraint() {
        SeckillRequestService service = new SeckillRequestService();
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ReflectionTestUtils.setField(service, "jdbc", jdbc);
        when(jdbc.queryForList(anyString())).thenReturn(java.util.Collections.emptyList());
        when(jdbc.queryForObject(anyString(), eq(Integer.class))).thenReturn(0);
        IllegalStateException error =
                assertThrows(IllegalStateException.class, service::verifySchema);
        assertTrue(error.getMessage().contains("setup_seckill_mq.py"));
    }

    private SeckillRequestMapper requests;
    private SeckillRequestService lifecycle;
    private SeckillReservationService reservation;
    private VoucherOrderMapper orders;
    private SeckillRequest request;

    @BeforeEach
    void setup() {
        requests = mock(SeckillRequestMapper.class);
        reservation = mock(SeckillReservationService.class);
        orders = mock(VoucherOrderMapper.class);
        lifecycle = new SeckillRequestService();
        ReflectionTestUtils.setField(lifecycle, "requests", requests);
        ReflectionTestUtils.setField(lifecycle, "reservation", reservation);
        ReflectionTestUtils.setField(lifecycle, "orders", orders);
        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenAnswer(call -> new SimpleTransactionStatus());
        ReflectionTestUtils.setField(lifecycle, "transactionManager", transactions);
        request = new SeckillRequest();
        request.setOrderId(9007199254740993L);
        request.setUserId(7L);
        request.setVoucherId(8L);
        request.setState("READY");
        request.setReservationState("HELD");
        request.setDeadline(LocalDateTime.now().plusMinutes(10));
        when(requests.lock(request.getOrderId())).thenReturn(request);
        when(requests.selectById(request.getOrderId())).thenReturn(request);
    }

    @AfterEach
    void clean() {
        UserHolder.removeUser();
    }

    @Test
    void duplicateCompletedMessageNeverTouchesStockOrOrderAgain() {
        request.setState("SUCCEEDED");
        lifecycle.consume(OrderCreateMessage.of(request));
        verifyNoInteractions(reservation, orders);
        verify(requests, never()).updateById(any());
    }

    @Test
    void cancelledRequestCannotBeRevivedByAnOldMessage() {
        request.setState("FAILED");
        lifecycle.consume(OrderCreateMessage.of(request));
        verifyNoInteractions(reservation, orders);
    }

    @Test
    void cancellingMessageRequestsIdempotentCompensationInsteadOfCreatingOrder() {
        request.setState("CANCELLING");
        lifecycle.consume(OrderCreateMessage.of(request));
        verifyNoInteractions(reservation, orders);
    }

    @Test
    void mismatchedIdentityAndVersionAreNeverTreatedAsDuplicateSuccess() {
        OrderCreateMessage message = OrderCreateMessage.of(request);
        message.setUserId(99L);
        assertThrows(IllegalArgumentException.class, () -> lifecycle.consume(message));
        message.setUserId(request.getUserId());
        message.setVersion(2);
        assertThrows(IllegalArgumentException.class, () -> lifecycle.consume(message));
        verifyNoInteractions(reservation, orders);
    }

    @Test
    void missingReservationDoesNotBecomeFreeOrderOrImmediateRefund() {
        when(reservation.held(request)).thenReturn(false);
        assertThrows(
                IllegalStateException.class,
                () -> lifecycle.consume(OrderCreateMessage.of(request)));
        verifyNoInteractions(orders);
        verify(requests, never()).updateById(any());
    }

    @Test
    void cancellationDoesNotReverseSuccessfulOrder() {
        request.setState("SUCCEEDED");
        lifecycle.cancel(request.getOrderId(), "发送超时");
        verify(requests, never()).updateById(any());
        verifyNoInteractions(reservation, orders);
    }

    @Test
    void cancellationCommitsIntermediateStateBeforeRedisRelease() {
        lifecycle.cancel(request.getOrderId(), "消费失败");
        assertEquals("CANCELLING", request.getState());
        verify(requests).updateById(request);
        verifyNoInteractions(reservation, orders);
    }

    @Test
    void unknownRedisResultLeavesInitForRecoveryInsteadOfDeclaringFailure() {
        request.setState("INIT");
        request.setReservationState("UNKNOWN");
        ISeckillVoucherService vouchers = mock(ISeckillVoucherService.class);
        SeckillVoucher voucher = new SeckillVoucher();
        when(vouchers.getById(8L)).thenReturn(voucher);
        ReflectionTestUtils.setField(lifecycle, "vouchers", vouchers);
        when(reservation.reserve(request, voucher, false)).thenThrow(new IllegalStateException("结果未知"));
        assertThrows(IllegalStateException.class, () -> lifecycle.prepare(request.getOrderId()));
        assertEquals("INIT", request.getState());
        verify(requests, never()).updateById(any());
    }

    @Test
    void sendTimeoutRetainsReadyRequestForResend() {
        SeckillOrderPublisher publisher = new SeckillOrderPublisher();
        RocketMQTemplate mq = mock(RocketMQTemplate.class);
        ReflectionTestUtils.setField(publisher, "template", mq);
        ReflectionTestUtils.setField(publisher, "requests", requests);
        ReflectionTestUtils.setField(publisher, "topic", "test-order");
        when(requests.claimSend(request.getOrderId())).thenReturn(1);
        when(mq.syncSend(eq("test-order"), any(Message.class)))
                .thenThrow(new IllegalStateException("确认丢失"));
        publisher.publish(request);
        assertEquals("READY", request.getState());
        verify(requests).sendFailed(eq(request.getOrderId()), anyString());
        verify(requests, never()).sent(anyLong(), anyInt());
        verifyNoInteractions(reservation, orders);
    }

    @Test
    void consumerPropagatesTransactionFailureToSdk() {
        SeckillOrderConsumer consumer = new SeckillOrderConsumer();
        SeckillRequestService tx = mock(SeckillRequestService.class);
        ReflectionTestUtils.setField(consumer, "lifecycle", tx);
        OrderCreateMessage message = OrderCreateMessage.of(request);
        doThrow(new IllegalStateException("事务失败")).when(tx).consume(message);
        assertThrows(IllegalStateException.class, () -> consumer.onMessage(message));
        verify(tx).consume(message);
    }

    @Test
    void resultQueryPreservesLargeIdsAndRejectsOtherUsers() {
        VoucherOrderServiceImpl service = new VoucherOrderServiceImpl();
        ReflectionTestUtils.setField(service, "requests", requests);
        UserDTO user = new UserDTO();
        user.setId(7L);
        UserHolder.saveUser(user);
        Result result = service.queryRequest(request.getOrderId());
        assertTrue(result.getSuccess());
        assertEquals("9007199254740993", ((Map<?, ?>) result.getData()).get("orderId"));
        user.setId(99L);
        assertFalse(service.queryRequest(request.getOrderId()).getSuccess());
    }
}
