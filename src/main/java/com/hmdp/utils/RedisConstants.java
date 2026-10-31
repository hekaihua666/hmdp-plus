package com.hmdp.utils;

public class RedisConstants {
    public static final String LOGIN_CODE_KEY = "login:code:";
    public static final Long LOGIN_CODE_TTL = 2L;
    public static final String LOGIN_USER_KEY = "login:token:";
    public static final Long LOGIN_USER_TTL = 36000L;

    public static final Long CACHE_NULL_TTL = 2L;

    public static final Long CACHE_SHOP_TTL = 30L;
    public static final String CACHE_SHOP_KEY = "cache:shop:";

    public static final String LOCK_SHOP_KEY = "lock:shop:";
    public static final Long LOCK_SHOP_TTL = 10L;

    public static final String SECKILL_STOCK_KEY = "seckill:stock:";
    public static final String SECKILL_OWNERS_KEY = "seckill:owners:";
    public static final String SECKILL_REQUEST_KEY = "seckill:request:";
    public static final String SECKILL_INITIALIZE_LOCK = "lock:seckill:initialize:";

    // 单篇笔记的点赞ZSet：member=用户ID，score=点赞时间；本轮保留的轻社交Redis结构。
    public static final String BLOG_LIKED_KEY = "blog:liked:";
    // FEED_KEY、FOLLOW_KEY、SHOP_GEO_KEY、USER_SIGN_KEY已退役。
    // 删除常量不会删除Redis中的历史数据；新代码不再写入这些命名空间。
}
