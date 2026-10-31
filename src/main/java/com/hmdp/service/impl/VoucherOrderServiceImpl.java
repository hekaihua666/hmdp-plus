package com.hmdp.service.impl;

import static com.hmdp.constants.SeckillConstants.CANCELLING;
import static com.hmdp.constants.SeckillConstants.FAILED;
import static com.hmdp.constants.SeckillConstants.SUCCEEDED;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillRequest;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.SeckillRequestMapper;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.service.SeckillRequestService;
import com.hmdp.service.SeckillReservationService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import javax.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** HTTP 层的受理入口，只负责校验、预检和落一条申请记录，然后立刻返回。
 *  真正扣库存（预占）、发 MQ、落订单都在后台由 SeckillRequestService 和消费端完成，所以这里不用同步等 Broker。 */
@Service
@Slf4j
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder>
        implements IVoucherOrderService {
    @Resource private ISeckillVoucherService seckillVoucherService;
    @Resource private RedisIdWorker redisIdWorker;
    @Resource private SeckillRequestService lifecycle;
    @Resource private SeckillRequestMapper requests;
    @Resource private SeckillReservationService reservation;

    /** 秒杀抢购的受理入口：校验参数和时间窗，做一次只读预检筛掉明显不行的请求，然后生成订单号、落一条申请记录就返回。
     *  正常受理只查一次券、插入一次申请，不等待 MQ；预检是为了减少售罄/重复请求白写 MySQL，正式预占在申请提交后重新裁决。 */
    @Override
    public Result seckillVoucher(Long voucherId) {
        if (voucherId == null || voucherId <= 0) {
            return Result.fail("优惠券编号不合法");
        }
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        if (voucher == null) {
            return Result.fail("优惠券不存在");
        }
        LocalDateTime now = LocalDateTime.now();
        if (now.isBefore(voucher.getBeginTime())) {
            return Result.fail("秒杀尚未开始");
        }
        if (now.isAfter(voucher.getEndTime())) {
            return Result.fail("秒杀已经结束");
        }
        Long userId = UserHolder.getUser().getId();
        try {
            // 先保证 Redis 库存键存在（丢了就补），再预检，避免库存键缺失被当成系统异常打断。
            lifecycle.initializeInventory(voucher);
            long code = reservation.check(userId, voucher);
            if (code == 5) {
                throw new IllegalStateException("库存预检发现库存键缺失");
            }
            if (code != 0) {
                return Result.fail(SeckillRequestService.rejectionReason(code));
            }
        } catch (RuntimeException error) {
            log.warn("抢购库存校验失败，voucherId={}", voucherId, error);
            return Result.fail("抢购库存暂不可用，请稍后重试");
        }
        // 全局唯一订单号，申请、订单、消息共用同一个身份。
        long orderId = redisIdWorker.nextId("order");
        lifecycle.create(orderId, userId, voucherId);
        // 返回就表示已经持久化受理，后台会在一个扫描周期内推进，这里不用等 Broker 确认。
        return Result.ok(Long.toString(orderId));
    }

    /** 查询单条申请的进度。为了防别人枚举订单号，别人的记录和根本不存在的记录都返回同一句“无权查看”。
     *  ID 始终转成字符串给浏览器，避免前端遇到 64 位长整型精度丢失。 */
    @Override
    public Result queryRequest(Long orderId) {
        SeckillRequest request = requests.selectById(orderId);
        if (request == null || !request.getUserId().equals(UserHolder.getUser().getId())) {
            return Result.fail("申请不存在或无权查看");
        }
        return Result.ok(view(request));
    }

    @Override
    public Result queryRequests(Long shopId) {
        if (shopId == null || shopId <= 0) {
            return Result.fail("店铺编号不合法");
        }
        return Result.ok(requests.recent(UserHolder.getUser().getId(), shopId).stream()
                .map(this::view).collect(Collectors.toList()));
    }

    /** 把一条申请组装成前端要看的视图：订单号、券号、状态、提示语。
     *  提示语按状态给不同文案，失败时直接回显后端记录的错误原因。 */
    private Map<String, Object> view(SeckillRequest request) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("orderId", request.getOrderId().toString());
        view.put("voucherId", request.getVoucherId());
        view.put("state", request.getState());
        view.put("voucherTitle", request.getVoucherTitle());
        String message = "请求已受理，正在处理";
        if (SUCCEEDED.equals(request.getState())) {
            message = "抢购成功，订单已创建";
        } else if (FAILED.equals(request.getState())) {
            message = request.getLastError();
        } else if (CANCELLING.equals(request.getState())) {
            message = "正在恢复库存，请稍后查询";
        }
        view.put("message", message);
        return view;
    }
}
