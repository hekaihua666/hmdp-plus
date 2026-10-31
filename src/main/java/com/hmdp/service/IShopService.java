package com.hmdp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IShopService extends IService<Shop> {

    // 分类、半径和距离排序已收敛到IShopSearchService，不再保留queryShopByType的GEO分支。
    // 当前接口继续负责店铺详情缓存、后台归属编辑和内部受控写入，不承担第二套附近搜索。

    Result queryById(Long id);

    Result update(Shop shop);

    /** 基于当前商户身份读取自己的店铺，不使用消费者详情缓存。 */
    Result queryOwnShop();

    /**
     * 基于当前商户身份读取自己的店铺实体（供后台内部业务取 shopId 等字段用）。
     * 未绑定店铺返回 null；与 queryOwnShop 不同，它直接返回 Shop 而非 Result 包装。
     */
    Shop queryOwnShopEntity();

    /** 白名单编辑、归属约束和提交后同步处于同一业务事务中。 */
    Result updateOwnShop(com.hmdp.dto.ShopUpdateDTO input);

    /** 新增店铺并发布提交后缓存失效及ES索引同步事件。 */
    Result saveShop(Shop shop);
}
