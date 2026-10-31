package com.hmdp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hmdp.entity.SeckillRequest;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 状态裁决使用事务行锁，发送租期使用条件 UPDATE；由数据库协调多个实例。 */
public interface SeckillRequestMapper extends BaseMapper<SeckillRequest> {
    /** 以排他行锁读取申请，使预占、落单、终止与补偿对同一行串行裁决。
     *  【大白话】把这条申请锁住，我事务没做完之前别人只能等着。 */
    @Select("SELECT * FROM tb_seckill_request WHERE order_id=#{id} FOR UPDATE")
    SeckillRequest lock(@Param("id") Long id);

    /** 按用户与店铺检索近 10 条申请，联表带出券标题供前端找回。
     *  【大白话】查我这个用户在这家店最近 10 条申请，给前端找回用。 */
    @Select("SELECT r.*,v.title AS voucher_title FROM tb_seckill_request r JOIN tb_voucher v "
            + "ON v.id=r.voucher_id WHERE r.user_id=#{userId} AND v.shop_id=#{shopId} "
            + "ORDER BY r.create_time DESC,r.order_id DESC LIMIT 10")
    List<SeckillRequest> recent(@Param("userId") Long userId, @Param("shopId") Long shopId);

    /** 单一状态、单一到期条件，匹配 (state,next_send_time) 索引及其主键后缀。
     *  【大白话】扫“到点了”的任务：指定状态 + 下次处理时间已到，一批 20 条。 */
    @Select("SELECT * FROM tb_seckill_request WHERE state=#{state} AND next_send_time<=NOW() "
            + "ORDER BY next_send_time,order_id LIMIT 20")
    List<SeckillRequest> due(@Param("state") String state);

    /** 超时独立查询，匹配 (state,deadline) 索引，避开原扫描中的多分支 OR。
     *  【大白话】扫“超期了”的申请，交给超时终止收尾。 */
    @Select("SELECT * FROM tb_seckill_request WHERE state=#{state} AND deadline<=NOW() "
            + "ORDER BY deadline,order_id LIMIT 20")
    List<SeckillRequest> expired(@Param("state") String state);

    /** 以条件 UPDATE 领取发送租期，影响行数为 1 才表示本次获得发送权。
     *  10 秒租期减少并发重复，消费事务仍负责业务幂等。
     *  【大白话】抢发送权，谁把“下次处理时间”成功往后推谁才发；返回 1 才是我的。 */
    @Update("UPDATE tb_seckill_request SET send_attempts=send_attempts+1, "
            + "next_send_time=DATE_ADD(NOW(),INTERVAL 10 SECOND),update_time=NOW() "
            + "WHERE order_id=#{id} AND state='READY' AND next_send_time<=NOW() AND deadline>NOW()")
    int claimSend(@Param("id") Long id);

    /** 记录发送成功并把下次核对时间推进至不超过业务期限。
     *  【大白话】发成功了记一笔，然后把下次检查时间往后推到 deadline 为止。 */
    @Update("UPDATE tb_seckill_request SET last_sent_time=NOW(),last_error=NULL, "
            + "next_send_time=LEAST(DATE_ADD(NOW(),INTERVAL #{seconds} SECOND),deadline),update_time=NOW() "
            + "WHERE order_id=#{id} AND state='READY'")
    int sent(@Param("id") Long id, @Param("seconds") int seconds);

    /** 发送结果未知时只记录错误并退避重试，不改状态、不归还预占。
     *  【大白话】发送结果说不清，只记错误、10 秒后再试，绝不判失败也不退库存。 */
    @Update("UPDATE tb_seckill_request SET last_error=#{reason}, "
            + "next_send_time=DATE_ADD(NOW(),INTERVAL 10 SECOND),update_time=NOW() "
            + "WHERE order_id=#{id} AND state='READY'")
    int sendFailed(@Param("id") Long id, @Param("reason") String reason);

    /** 处理异常时整体退避，仅对非终态生效，避免复活已结束的申请。
     *  【大白话】这条暂时处理不了，推 10 秒后再试，别拖累同批其他申请。 */
    @Update("UPDATE tb_seckill_request SET next_send_time=DATE_ADD(NOW(),INTERVAL 10 SECOND), "
            + "update_time=NOW() WHERE order_id=#{id} AND state IN ('INIT','READY','CANCELLING')")
    int defer(@Param("id") Long id);

    /** 显式写 NULL 避免实体默认非空更新策略留下历史错误；WHERE 限 READY 作为状态 CAS 兜底。
     *  【大白话】订单落好了，把申请收尾成成功，顺手把历史错误清空。 */
    @Update("UPDATE tb_seckill_request SET state='SUCCEEDED',last_error=NULL,update_time=NOW() "
            + "WHERE order_id=#{id} AND state='READY'")
    int succeeded(@Param("id") Long id);
}
