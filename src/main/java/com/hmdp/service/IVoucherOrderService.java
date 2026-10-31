package com.hmdp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;

/**
 * 服务类
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IVoucherOrderService extends IService<VoucherOrder> {

    Result seckillVoucher(Long voucherId);

    /** 只允许查询当前消费者自己的申请；字符串订单ID在DTO中保持精度。 */
    Result queryRequest(Long orderId);

    /** 本店最近十次申请，弥补提交响应丢失或刷新页面后不知道订单ID的场景。 */
    Result queryRequests(Long shopId);
}
