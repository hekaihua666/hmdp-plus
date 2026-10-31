package com.hmdp.constants;

import lombok.Getter;
import org.elasticsearch.search.aggregations.AggregationBuilders;
import org.elasticsearch.search.aggregations.bucket.range.RangeAggregationBuilder;
import org.elasticsearch.search.aggregations.bucket.filter.FiltersAggregationBuilder;
import org.elasticsearch.search.aggregations.bucket.filter.FiltersAggregator;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.common.unit.DistanceUnit;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 唯一的商户筛选区间定义：界面选项和 ES 聚合均使用这一份数据。
 * 价格/评分区间包含下界、不包含上界；距离选项是累计半径，单位米。
 */
public final class ShopSearchRanges {
    private ShopSearchRanges() { }

    public static final List<Option> PRICE = Collections.unmodifiableList(Arrays.asList(
            new Option("￥50以下", 0, 50.0),
            new Option("￥50-100", 50, 100.0),
            new Option("￥100-200", 100, 200.0),
            new Option("￥200以上", 200, null)));
    public static final List<Option> SCORE = Collections.unmodifiableList(Arrays.asList(
            new Option("3分以下", 0, 3.0),
            new Option("3-4分", 3, 4.0),
            new Option("4-4.5分", 4, 4.5),
            new Option("4.5分以上", 4.5, null)));
    /** 与“公里内”筛选对应的累计范围，不是互斥分段，数量不能相加。 */
    public static final List<Option> DISTANCE = Collections.unmodifiableList(Arrays.asList(
            new Option("1 公里内", 0, 1000.0),
            new Option("3 公里内", 0, 3000.0),
            new Option("5 公里内", 0, 5000.0),
            new Option("10 公里内", 0, 10000.0)));

    /** 不查询 ES，纯分类页面也能获取同一份筛选选项。 */
    public static Map<String, List<Option>> options() {
        Map<String, List<Option>> result = new LinkedHashMap<>();
        result.put("price", PRICE);
        result.put("score", SCORE);
        result.put("distance", DISTANCE);
        return result;
    }

    /** 使用标签作为显式桶 key，解析结果无需依赖桶的返回位置。 */
    public static RangeAggregationBuilder aggregation(String name, String field, List<Option> options, double scale) {
        RangeAggregationBuilder builder = AggregationBuilders.range(name).field(field);
        for (Option option : options) {
            if (option.getMax() == null) {
                builder.addUnboundedFrom(option.getLabel(), option.getMin() * scale);
            } else {
                builder.addRange(option.getLabel(), option.getMin() * scale, option.getMax() * scale);
            }
        }
        return builder;
    }

    /** 每个桶复用实际半径筛选的geo_distance条件，保证统计与筛选的边界一致。 */
    public static FiltersAggregationBuilder distanceAggregation(double longitude, double latitude) {
        FiltersAggregator.KeyedFilter[] filters = new FiltersAggregator.KeyedFilter[DISTANCE.size()];
        for (int i = 0; i < DISTANCE.size(); i++) {
            Option option = DISTANCE.get(i);
            // ES point接收纬度在前、经度在后；桶标签同时作为前端的匹配key。
            filters[i] = new FiltersAggregator.KeyedFilter(option.getLabel(),
                    QueryBuilders.geoDistanceQuery("location").point(latitude, longitude)
                            .distance(option.getMax(), DistanceUnit.METERS));
        }
        return AggregationBuilders.filters("distanceAgg", filters);
    }

    @Getter
    public static final class Option {
        private final String label;
        private final double min;
        private final Double max;

        public Option(String label, double min, Double max) {
            this.label = label;
            this.min = min;
            this.max = max;
        }
    }
}
