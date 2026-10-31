package com.hmdp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hmdp.entity.ShopSyncEvent;
import com.hmdp.entity.Shop;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** 商户同步 Outbox 的存取，发送租期用条件 UPDATE 由数据库协调多实例，避免重复发送。 */
public interface ShopSyncEventMapper extends BaseMapper<ShopSyncEvent> {

    /** 写入或重置一条待发送记录：同一商户再次更新时，把旧记录重新置回 PENDING，保证最新一次改动不会被漏发。 */
    @Insert("INSERT INTO tb_shop_sync_event(shop_id,state,next_send_time,last_error,create_time,update_time) "
            + "VALUES(#{shopId},'PENDING',NOW(),NULL,NOW(),NOW()) "
            + "ON DUPLICATE KEY UPDATE revision=revision+1,state='PENDING',next_send_time=NOW(),last_error=NULL,update_time=NOW()")
    int upsert(@Param("shopId") Long shopId);

    /** 扫"到点了"的待发送记录：PENDING 且下次发送时间已到，按到期先后取一批。 */
    @Select("SELECT * FROM tb_shop_sync_event WHERE state='PENDING' AND next_send_time<=NOW() "
            + "ORDER BY next_send_time,shop_id LIMIT 50")
    List<ShopSyncEvent> due();

    /** 抢发送租期：把下次发送时间往后推 10 秒，影响行数为 1 才表示本次拿到发送权。 */
    @Update("UPDATE tb_shop_sync_event SET next_send_time=DATE_ADD(NOW(),INTERVAL 10 SECOND),"
            + "update_time=NOW() WHERE shop_id=#{id} AND state='PENDING' AND next_send_time<=NOW() AND revision=#{revision}")
    int claimSend(@Param("id") Long id, @Param("revision") Long revision);

    /** 确认发送成功，把状态置 SENT 并清掉历史错误。 */
    @Update("UPDATE tb_shop_sync_event SET state='SENT',last_error=NULL,update_time=NOW() "
            + "WHERE shop_id=#{id} AND state='PENDING' AND revision=#{revision}")
    int sent(@Param("id") Long id, @Param("revision") Long revision);

    /** 发送结果未知时只记错误、退避重试，不改状态，等下一轮再抢发送权。 */
    @Update("UPDATE tb_shop_sync_event SET last_error=#{reason},"
            + "next_send_time=DATE_ADD(NOW(),INTERVAL 10 SECOND),update_time=NOW() "
            + "WHERE shop_id=#{id} AND state='PENDING' AND revision=#{revision}")
    int sendFailed(@Param("id") Long id, @Param("revision") Long revision, @Param("reason") String reason);

    /** 死信记录允许更新 SENT；旧批次的死信不覆盖新版本的状态或错误。 */
    @Update("UPDATE tb_shop_sync_event SET last_error=#{reason},update_time=NOW() "
            + "WHERE shop_id=#{id} AND revision=#{revision}")
    int consumeFailed(@Param("id") Long id, @Param("revision") Long revision, @Param("reason") String reason);

    /** 一条 SELECT 在同一快照内取得数据和版本，避免旧数据误配新版本。无历史事件的商户使用基线版本。 */
    @Select("SELECT s.*,COALESCE(e.revision,1000000000000) AS sync_revision "
            + "FROM tb_shop s LEFT JOIN tb_shop_sync_event e ON e.shop_id=s.id WHERE s.id=#{id}")
    Shop shopForSync(@Param("id") Long id);
}
