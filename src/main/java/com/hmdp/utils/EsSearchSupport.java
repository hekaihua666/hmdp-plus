package com.hmdp.utils;

import org.elasticsearch.action.bulk.BulkItemResponse;
import org.elasticsearch.action.bulk.BulkResponse;

import java.io.IOException;

/** 商户 ES 搜索的边界检查：分页参数与 Bulk 单项失败。 */
public final class EsSearchSupport {
    private EsSearchSupport() { }

    /** ES 默认结果窗口为 10000；先检查参数，避免负偏移、整数溢出和深分页报错。 */
    public static void validatePage(int current, int pageSize) {
        if (current < 1 || pageSize < 1 || pageSize > 100
                || (long) current * pageSize > 10000) {
            throw new IllegalArgumentException("页码须大于0，每页1至100条，分页范围不得超过10000条");
        }
    }

    /**
     * Bulk 的 HTTP 请求成功不代表每条写入都成功。
     * 逐条统计，出现失败就报告数量和首条原因，禁止把部分导入包装成“重建完成”。
     */
    public static int checkBulk(BulkResponse response) throws IOException {
        int success = 0;
        int failed = 0;
        String firstFailure = null;
        for (BulkItemResponse item : response.getItems()) {
            if (item.isFailed()) {
                failed++;
                if (firstFailure == null) {
                    firstFailure = "索引=" + item.getIndex() + "，ID=" + item.getId()
                            + "，原因=" + item.getFailureMessage();
                }
            } else {
                success++;
            }
        }
        if (failed > 0) {
            throw new IOException("批量导入部分失败：成功" + success + "条，失败" + failed
                    + "条；" + firstFailure);
        }
        return success;
    }
}
