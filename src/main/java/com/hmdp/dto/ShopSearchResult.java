package com.hmdp.dto;

import com.hmdp.entity.ShopDoc;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 商户搜索结果（含列表、总数、聚合数据）
 */
@Data
public class ShopSearchResult {

    /** 商户列表 */
    private List<ShopDoc> list;

    /** 总数 */
    private long total;

    /** 前端聚合数据：priceAgg / scoreAgg；有参考坐标时还返回distanceAgg累计数量。 */
    private Map<String, List<AggItem>> aggregations;

    @Data
    public static class AggItem {
        private String key;
        private String label;
        private long count;

        public AggItem(String key, String label, long count) {
            this.key = key;
            this.label = label;
            this.count = count;
        }
    }
}
