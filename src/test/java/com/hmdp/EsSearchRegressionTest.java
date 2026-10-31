package com.hmdp;

import com.hmdp.controller.user.ShopController;
import com.hmdp.dto.ShopSearchDTO;
import com.hmdp.dto.ShopSearchResult;
import com.hmdp.entity.Shop;
import com.hmdp.entity.ShopDoc;
import com.hmdp.service.IShopSearchService;
import com.hmdp.service.impl.ShopSearchServiceImpl;
import com.hmdp.utils.EsSearchSupport;
import com.hmdp.utils.PinyinUtil;
import org.elasticsearch.action.bulk.BulkItemResponse;
import org.elasticsearch.action.bulk.BulkResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 覆盖这次遇到的失败路径；不启动 Spring、MySQL 或 ES。 */
class EsSearchRegressionTest {
    @Test
    void pinyinHandlesCaseSpacesAndUmlaut() {
        assertEquals("haidilao", PinyinUtil.toPinyin("海底捞"));
        assertEquals("hdl", PinyinUtil.toFirstChar("海底捞"));
        assertEquals("lv", PinyinUtil.toPinyin("绿"));
        assertEquals("haidi", PinyinUtil.normalize("HAI DI"));
    }

    @Test
    void completionInputsIncludePinyinAndIgnoreNullArea() {
        Shop shop = new Shop();
        shop.setName("海底捞");
        shop.setX(120.1); shop.setY(30.2);
        ShopDoc doc = ShopDoc.from(shop);
        assertEquals("30.2,120.1", doc.getLocation());
        assertTrue(doc.getSuggestion().contains("海底捞"));
        assertTrue(doc.getSuggestion().contains("haidilao"));
        assertTrue(doc.getSuggestion().contains("hdl"));
        assertFalse(doc.getSuggestion().contains(null));
        assertFalse(doc.getSuggestion().contains(""));
    }

    @Test
    void completionInputsDeduplicateSameNameAndArea() {
        Shop shop = new Shop();
        shop.setName("海底捞"); shop.setArea("海底捞");
        assertEquals(3, ShopDoc.from(shop).getSuggestion().size());
    }

    @Test
    void bulkPartialFailureReportsActualCounts() {
        BulkItemResponse success = mock(BulkItemResponse.class);
        BulkItemResponse failure = mock(BulkItemResponse.class);
        when(failure.isFailed()).thenReturn(true);
        when(failure.getIndex()).thenReturn("hmdp_shop");
        when(failure.getId()).thenReturn("2");
        when(failure.getFailureMessage()).thenReturn("location format invalid");
        BulkResponse response = mock(BulkResponse.class);
        when(response.getItems()).thenReturn(new BulkItemResponse[]{success, failure, success});
        IOException error = assertThrows(IOException.class, () -> EsSearchSupport.checkBulk(response));
        assertTrue(error.getMessage().contains("成功2条，失败1条"));
        assertTrue(error.getMessage().contains("ID=2"));
    }

    @Test
    void bulkSuccessCountsEveryItem() throws IOException {
        BulkResponse response = mock(BulkResponse.class);
        when(response.getItems()).thenReturn(new BulkItemResponse[]{mock(BulkItemResponse.class), mock(BulkItemResponse.class)});
        assertEquals(2, EsSearchSupport.checkBulk(response));
    }

    @Test
    void paginationRejectsInvalidAndOverflowingOffsets() {
        assertDoesNotThrow(() -> EsSearchSupport.validatePage(100, 100));
        assertThrows(IllegalArgumentException.class, () -> EsSearchSupport.validatePage(0, 10));
        assertThrows(IllegalArgumentException.class, () -> EsSearchSupport.validatePage(1, 101));
        assertThrows(IllegalArgumentException.class, () -> EsSearchSupport.validatePage(Integer.MAX_VALUE, 100));
    }

    @Test
    void shopEndpointAcceptsDecimalRating() throws Exception {
        IShopSearchService service = mock(IShopSearchService.class);
        when(service.search(any())).thenReturn(new ShopSearchResult());
        ShopController controller = new ShopController();
        ReflectionTestUtils.setField(controller, "shopSearchService", service);
        MockMvcBuilders.standaloneSetup(controller).build()
                .perform(get("/shop/search").param("minScore", "4.5").param("maxScore", "5"))
                .andExpect(status().isOk());
        ArgumentCaptor<ShopSearchDTO> argument = ArgumentCaptor.forClass(ShopSearchDTO.class);
        verify(service).search(argument.capture());
        assertEquals(4.5, argument.getValue().getMinScore());
        assertEquals(5.0, argument.getValue().getMaxScore());
    }

    @Test
    void keywordQueryDoesNotGenerateDuplicateFieldBoosts() {
        ShopSearchDTO dto = new ShopSearchDTO();
        dto.setKeyword("hai");
        Object query = ReflectionTestUtils.invokeMethod(new ShopSearchServiceImpl(), "buildQuery", dto);
        String json = query.toString();
        // 错误的 name^3 入参会被序列化为 name^3^1.0，ES 随后报数字解析错误。
        assertTrue(json.contains("name^3.0"));
        assertFalse(json.contains("^3^"));
        assertTrue(json.contains("nameInitials"));
    }

}
