package com.hmdp.service;

import com.hmdp.dto.ShopSearchDTO;
import com.hmdp.dto.ShopSearchResult;
import com.hmdp.entity.ShopDoc;

import java.io.IOException;
import java.util.List;

/**
 * 商户搜索服务
 */
public interface IShopSearchService {

    /**
     * 搜索商户（含高亮、聚合）
     *
     * @param dto 搜索条件
     * @return 搜索结果
     */
    ShopSearchResult search(ShopSearchDTO dto) throws IOException;

    /**
     * 按ID同步单个商户到ES（新增或更新）
     */
    void syncById(Long shopId) throws IOException;

    /**
     * 全量导入所有商户
     *
     * @return 成功条数
     */
    int bulkImportAll() throws IOException;

    /**
     * 自动补全建议
     *
     * @param keyword 输入关键词
     * @return 建议的商户名称列表
     */
    List<String> suggest(String keyword) throws IOException;
}
