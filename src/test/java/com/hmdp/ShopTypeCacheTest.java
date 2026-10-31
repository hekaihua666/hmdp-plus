package com.hmdp;

import cn.hutool.json.JSONUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.controller.user.ShopTypeController;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class ShopTypeCacheTest {
    @Test
    void jacksonReadsExistingHutoolCacheWithoutChangingResponse() throws Exception {
        // 复用当前缓存写入方式，覆盖中文、顺序及旧缓存中的时间字段。
        String cached = JSONUtil.toJsonStr(Arrays.asList(
                new ShopType().setId(1L).setName("美食").setIcon("/imgs/a.png").setSort(1)
                        .setCreateTime(LocalDateTime.of(2026, 10, 31, 12, 0)),
                new ShopType().setId(2L).setName("酒店").setIcon("/imgs/b.png").setSort(2)));
        ObjectMapper mapper = new ObjectMapper();
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get("cache:shop:type:list")).thenReturn(cached);

        ShopTypeController controller = new ShopTypeController();
        ReflectionTestUtils.setField(controller, "stringRedisTemplate", redis);
        ReflectionTestUtils.setField(controller, "objectMapper", mapper);

        // 以旧实现实际返回的 JSON 为基准，确保前端契约不变。
        String previous = mapper.writeValueAsString(Result.ok(JSONUtil.toList(cached, ShopType.class)));
        assertEquals(previous, mapper.writeValueAsString(controller.queryTypeList()));
        verify(values).get("cache:shop:type:list");
    }
}
