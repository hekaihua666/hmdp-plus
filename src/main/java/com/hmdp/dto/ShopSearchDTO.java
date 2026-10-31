package com.hmdp.dto;

import lombok.Data;

/**
 * 商户搜索条件
 */
@Data
public class ShopSearchDTO {

    /** 关键词 */
    private String keyword;

    /** 分类ID */
    private Long typeId;

    /** 最低价格 */
    private Long minPrice;

    /** 最高价格，不含上界；与价格聚合的 [from, to) 区间一致。 */
    private Long maxPrice;

    /** 最低评分，包含下界；实际分数，如4.5表示4.5分。 */
    private Double minScore;

    /** 最高评分，不含上界；用于“3-4分”“4-4.5分”等区间。 */
    private Double maxScore;

    /** 经度 */
    private Double x;

    /** 纬度 */
    private Double y;

    /** 搜索半径（米） */
    private Integer radius;

    /** 排序：default-相关性，distance-距离，price-价格，score-评分 */
    private String sort;

    /** 页码，从1开始 */
    private Integer current = 1;

    /** 每页大小 */
    private Integer pageSize = 10;
}
