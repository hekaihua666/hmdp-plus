package com.hmdp.service;

import com.hmdp.constants.EsConstants;
// ES 7 无类型请求；不能导入 action.admin.indices.create 中的旧版请求类。
import org.elasticsearch.client.indices.CreateIndexRequest;
import org.elasticsearch.client.indices.GetIndexRequest;
import org.elasticsearch.action.admin.indices.delete.DeleteIndexRequest;
import org.elasticsearch.client.IndicesClient;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.xcontent.XContentType;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.io.IOException;
import java.util.UUID;

/**
 * 商户 ES 本地维护工具，只维护 hmdp_shop。
 * 不映射 HTTP 接口；通过显式启用的维护测试运行，普通访问者无法调用删建索引操作。
 */
@Service
public class ShopIndexMaintenance {

    @Resource
    private RestHighLevelClient client;

    @Resource
    private IShopSearchService shopSearchService;

    /**
     * 先检查 Mapping，再重建商户索引并从 MySQL 全量导入。
     */
    public int rebuild() throws IOException {
        // Mapping 验证失败时保留已有索引；删建并非原子迁移，维护时会短暂不可搜。
        validateMapping();
        rebuildIndex();
        return shopSearchService.bulkImportAll();
    }

    /**
     * 重建单个索引
     */
    private void rebuildIndex() throws IOException {
        String indexName = EsConstants.SHOP_INDEX;
        IndicesClient indices = client.indices();
        // 1. 删除旧索引（如果存在）
        if (indices.exists(new GetIndexRequest(indexName), RequestOptions.DEFAULT)) {
            indices.delete(new DeleteIndexRequest(indexName), RequestOptions.DEFAULT);
        }
        // 连接、权限等真实异常向上传递，不能当作“索引不存在”吞掉。
        // 2. 创建新索引
        CreateIndexRequest createRequest = new CreateIndexRequest(indexName);
        createRequest.source(EsConstants.SHOP_INDEX_JSON, XContentType.JSON);
        indices.create(createRequest, RequestOptions.DEFAULT);
    }

    /** 使用临时索引让 ES 校验分词器和 Mapping，成功后立即清理，不存业务数据。 */
    private void validateMapping() throws IOException {
        String temporaryIndex = "hmdp_mapping_check_" + UUID.randomUUID().toString().replace("-", "");
        CreateIndexRequest request = new CreateIndexRequest(temporaryIndex);
        request.source(EsConstants.SHOP_INDEX_JSON, XContentType.JSON);
        // 本地单节点检查无需副本，避免创建检查索引时等待副本分配。
        request.settings(org.elasticsearch.common.settings.Settings.builder().put("number_of_replicas", 0));
        client.indices().create(request, RequestOptions.DEFAULT);
        client.indices().delete(new DeleteIndexRequest(temporaryIndex), RequestOptions.DEFAULT);
    }
}
