package com.hmdp.constants;

/**
 * 商户索引名和 Mapping；仅保留查询、排序、展示使用的字段。
 */
public class EsConstants {

    /** 商户索引名 */
    public static final String SHOP_INDEX = "hmdp_shop";

    /** 商户索引定义；multi_match 分别搜索名称、商圈、地址，因此不再维护 all 字段。 */
    public static final String SHOP_INDEX_JSON =
            "{\"mappings\":{\"properties\":{" +
            "\"id\":{\"type\":\"keyword\"}," +
            "\"name\":{\"type\":\"text\",\"analyzer\":\"ik_max_word\"}," +
            // 连写拼音作为完整词保存，查询时用 prefix 支持 hai / haidi 等前缀。
            "\"namePinyin\":{\"type\":\"keyword\"}," +
            "\"nameInitials\":{\"type\":\"keyword\"}," +
            "\"typeId\":{\"type\":\"long\"}," +
            "\"area\":{\"type\":\"text\",\"analyzer\":\"ik_max_word\"}," +
            "\"address\":{\"type\":\"text\",\"analyzer\":\"ik_max_word\"}," +
            "\"avgPrice\":{\"type\":\"long\"}," +
            "\"score\":{\"type\":\"integer\"}," +
            "\"sold\":{\"type\":\"integer\"}," +
            "\"comments\":{\"type\":\"integer\"}," +
            "\"images\":{\"type\":\"keyword\",\"index\":false}," +
            "\"location\":{\"type\":\"geo_point\"}," +
            // 中文、全拼、首字母由应用生成；keyword 保留每个输入的完整前缀。
            "\"suggestion\":{\"type\":\"completion\",\"analyzer\":\"keyword\"}" +
            "}}}";
}
