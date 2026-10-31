package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HtmlUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.constants.EsConstants;
import com.hmdp.constants.ShopSearchRanges;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hmdp.dto.ShopSearchDTO;
import com.hmdp.dto.ShopSearchResult;
import com.hmdp.entity.Shop;
import com.hmdp.entity.ShopDoc;
import com.hmdp.mapper.ShopSyncEventMapper;
import org.elasticsearch.ElasticsearchStatusException;
import org.elasticsearch.index.VersionType;
import org.elasticsearch.rest.RestStatus;
import com.hmdp.service.IShopSearchService;
import com.hmdp.service.IShopService;
import com.hmdp.utils.EsSearchSupport;
import com.hmdp.utils.PinyinUtil;
import org.elasticsearch.action.bulk.BulkRequest;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.support.WriteRequest;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.common.unit.DistanceUnit;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.MultiMatchQueryBuilder;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.SearchHits;
import org.elasticsearch.search.aggregations.bucket.range.Range;
import org.elasticsearch.search.aggregations.bucket.filter.Filters;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.elasticsearch.search.fetch.subphase.highlight.HighlightBuilder;
import org.elasticsearch.search.fetch.subphase.highlight.HighlightField;
import org.elasticsearch.search.sort.SortBuilders;
import org.elasticsearch.search.sort.SortOrder;
import org.elasticsearch.xcontent.XContentType;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 商户搜索服务实现
 */
@Service
public class ShopSearchServiceImpl implements IShopSearchService {

    @Resource
    private RestHighLevelClient client;

    @Resource
    private IShopService shopService;

    @Resource
    private ShopSyncEventMapper shopSyncEvents;

    /**
     * ES 组合搜索主方法（总指挥）：把所有零件组装好，发给 ES 执行，再解析结果返回。
     *
     * <p>执行流程分五步：</p>
     * <ol>
     *   <li>buildQuery 造查询条件（关键词 + 分类/价格/评分/地理过滤）；</li>
     *   <li>按 sort 加排序（含 id 兜底排序，保证分页顺序稳定）；</li>
     *   <li>有关键词时加高亮（命中的词包 &lt;em&gt; 标签）；</li>
     *   <li>加聚合（价格、评分，以及有坐标时各距离范围内的商户数量）；</li>
     *   <li>分页 from/size，最后 client.search() 真正执行。</li>
     * </ol>
     *
     * <p>关键对象分工：SearchRequest 是「信封」（查哪个索引），SearchSourceBuilder
     * 是「信纸」（查询/排序/高亮/聚合/分页都写它上面），BoolQueryBuilder 是「文件夹」
     * （用 must/filter 组合多张查询纸条）。</p>
     */
    @Override
    public ShopSearchResult search(ShopSearchDTO dto) throws IOException {
        // ① 造「信封」和「信纸」
        SearchRequest request = new SearchRequest(EsConstants.SHOP_INDEX);
        SearchSourceBuilder sourceBuilder = new SearchSourceBuilder();

        // ② 把查询条件集中构建（关键词 must + 各筛选 filter），后续步骤只负责排序、高亮、聚合和分页。
        BoolQueryBuilder bool = buildQuery(dto);
        boolean hasGeo = dto.getX() != null && dto.getY() != null;
        String sortBy = dto.getSort();
        sourceBuilder.query(bool);   // 把查询条件装进「信纸」的 query 栏

        // ③ 排序：四种主排序 + 一个 id 兜底排序
        if ("distance".equals(sortBy)) {
            // 距离排序只需要坐标；半径仅决定是否限制搜索范围。
            if (!hasGeo) throw new IllegalArgumentException("距离排序需要同时提供经度x和纬度y");
            // 按 location 字段到 (x,y) 的距离升序，单位米
            sourceBuilder.sort(SortBuilders.geoDistanceSort("location", dto.getY(), dto.getX())
                    .order(SortOrder.ASC).unit(DistanceUnit.METERS));
        } else if ("price".equals(sortBy)) {
            sourceBuilder.sort("avgPrice", SortOrder.ASC);   // 价格从低到高
        } else if ("score".equals(sortBy)) {
            sourceBuilder.sort("score", SortOrder.DESC);     // 评分从高到低
        } else {
            sourceBuilder.sort("_score", SortOrder.DESC);    // 默认按相关性从高到低
        }
        // 主排序值相同时按唯一 ID 排序，避免分页时商户位置随机变化（跳来跳去）。
        sourceBuilder.sort("id", SortOrder.ASC);

        // ④ 高亮：只有搜了关键词才做，把命中的词用 <em> 包起来（前端 v-html 渲染成黄色）。
        //    高亮字符串会交给前端 v-html，所以 encoder("html") 先转义原文，只保留 ES 加的 em 标签，防 XSS。
        if (StrUtil.isNotBlank(dto.getKeyword())) {
            HighlightBuilder highlightBuilder = new HighlightBuilder();
            highlightBuilder.field("name").field("area").field("address");
            highlightBuilder.preTags("<em>").postTags("</em>").encoder("html");
            // 商户名称、地址是短字段，numOfFragments(0) 返回完整字段，避免名称被截成片段。
            highlightBuilder.numOfFragments(0);
            highlightBuilder.requireFieldMatch(false);
            sourceBuilder.highlighter(highlightBuilder);
        }

        // ⑤ 聚合：让 ES 顺便统计每个价格档/评分档各有多少家（scale：价格=1，评分×10 因为存的是整数）。
        sourceBuilder.aggregation(ShopSearchRanges.aggregation("priceAgg", "avgPrice", ShopSearchRanges.PRICE, 1));
        // 数据库和索引将评分放大 10 倍，界面和区间定义仍使用 0~5 分。
        sourceBuilder.aggregation(ShopSearchRanges.aggregation("scoreAgg", "score", ShopSearchRanges.SCORE, 10));
        // 距离统计需要参考坐标；沿用当前query的全部条件，不影响原列表排序或分页。
        if (hasGeo) {
            sourceBuilder.aggregation(ShopSearchRanges.distanceAggregation(dto.getX(), dto.getY()));
        }

        // ⑥ 分页设置：from 是跳过条数，size 是每页条数，trackTotalHits 精确统计总数。
        int current = dto.getCurrent() == null ? 1 : dto.getCurrent();// 当前页码，默认第1页
        int pageSize = dto.getPageSize() == null ? 10 : dto.getPageSize();// 每页大小，默认10条
        EsSearchSupport.validatePage(current, pageSize);   // 分页安检，防 ES 10000 窗口报错
        sourceBuilder.from((current - 1) * pageSize).size(pageSize).trackTotalHits(true);

        // ⑦ 把信纸装进信封，真正发给 ES 执行（前面都是攒条件，到这才查询）
        request.source(sourceBuilder);// 设置查询条件
        SearchResponse response = client.search(request, RequestOptions.DEFAULT);

        // ⑧ 解析结果：列表（含高亮/距离）+ 总数 + 聚合
        ShopSearchResult result = new ShopSearchResult();
        result.setList(parseResponse(response, "distance".equals(sortBy)));
        result.setTotal(response.getHits().getTotalHits().value);
        result.setAggregations(parseAggregations(response));
        return result;
    }

    /**
     * 查询条件「总装车间」：先安检参数，再把关键词 + 各筛选条件都装进同一个 bool 大文件夹。
     *
     * <p>核心设计：must 和 filter 分开用——</p>
     * <ul>
     *   <li>关键词用 <b>must</b>：参与相关性打分（名字命中 > 商圈 > 地址），决定排序；</li>
     *   <li>分类/价格/评分/地理用 <b>filter</b>：只做硬过滤、不打分，ES 还会缓存结果更快。</li>
     * </ul>
     */
    private BoolQueryBuilder buildQuery(ShopSearchDTO dto) {
        validateFilters(dto);                       // ① 先安检参数
        BoolQueryBuilder bool = QueryBuilders.boolQuery();   // ② 造空文件夹

        // ③ 关键词（must，参与打分）：非空才加。名称权重最高，拼音次之，商圈次之，地址最低。
        if (StrUtil.isNotBlank(dto.getKeyword())) {
            bool.must(buildKeywordQuery(dto.getKeyword().trim()));
        }

        // ④ 分类过滤（filter，精确匹配 typeId）
        if (dto.getTypeId() != null) {
            bool.filter(QueryBuilders.termQuery("typeId", dto.getTypeId()));
        }

        // ⑤ 价格范围过滤（filter）：区间左闭右开 [min, max)，所以下界 gte、上界 lt。
        if (dto.getMinPrice() != null || dto.getMaxPrice() != null) {
            BoolQueryBuilder priceBool = QueryBuilders.boolQuery();   // 价格专用子文件夹
            if (dto.getMinPrice() != null) {
                priceBool.filter(QueryBuilders.rangeQuery("avgPrice").gte(dto.getMinPrice())); // >= 最低价
            }
            if (dto.getMaxPrice() != null) {
                priceBool.filter(QueryBuilders.rangeQuery("avgPrice").lt(dto.getMaxPrice()));  // < 最高价
            }
            bool.filter(priceBool);   // 子文件夹整体装进总文件夹
        }

        // ⑥ 评分过滤（filter）：数据库/索引存的是乘10后的整数（45=4.5分），所以 * 10 对齐。
        if (dto.getMinScore() != null) {
            bool.filter(QueryBuilders.rangeQuery("score").gte(dto.getMinScore() * 10));
        }
        if (dto.getMaxScore() != null) {
            bool.filter(QueryBuilders.rangeQuery("score").lt(dto.getMaxScore() * 10));
        }

        // ⑦ 地理范围过滤（filter）：只保留「离用户坐标 radius 米以内」的店。
        //    注意 geo_point 的坐标顺序是 (纬度 y, 经度 x)，所以先 y 后 x。
        if (dto.getRadius() != null) {
            bool.filter(QueryBuilders.geoDistanceQuery("location")
                    .point(dto.getY(), dto.getX())
                    .distance(dto.getRadius(), DistanceUnit.METERS));
        }

        return bool;
    }

    /**
     * 关键词「分词 + 组合」层：处理用户输入的完整关键词。
     *
     * <ul>
     *   <li>纯中文（海底捞）或纯字母（hdl）→ 直接整个交给 keywordPart，不分段；</li>
     *   <li>混合输入（杭州 hdl）→ 用正则切成中文段和字母段，每段分别生成条件，
     *       再用 must（每一段都必须命中）拼起来，避免「杭州」被丢掉。</li>
     * </ul>
     */
    private QueryBuilder buildKeywordQuery(String keyword) {
        // 纯中文（不含字母数字）或纯字母数字 → 不分段，直接整个当一段处理
        if (!keyword.matches(".*[a-zA-Z0-9].*") || keyword.matches("[a-zA-Z0-9\\s]+")) {
            return keywordPart(keyword);
        }
        // 走到这说明是「混合输入」（既有中文又有字母），需要分段
        BoolQueryBuilder mixed = QueryBuilders.boolQuery();   // 造一个大纸条
        // 正则拆段：[a-zA-Z0-9]+ 匹配连续字母/数字，[^a-zA-Z0-9\s]+ 匹配连续中文，空格被忽略
        Matcher parts = Pattern.compile("[a-zA-Z0-9]+|[^a-zA-Z0-9\\s]+").matcher(keyword);
        while (parts.find()) {
            // 每段都用 keywordPart 生成查询条件，再用 must（必须都命中）拼进大纸条
            mixed.must(keywordPart(parts.group()));
        }
        return mixed;
    }

    /**
     * 把「一个关键词片段」翻译成一条 ES 查询条件（QueryBuilder 对象），但不执行。
     *
     * <p>可以把它理解为「写一张查东西的纸条」：只负责把条件写下来返回给上层拼接，
     * 真正执行要等上层最后调 client.search()。</p>
     *
     * <p>规则：</p>
     * <ul>
     *   <li>中文片段（如「海底」）→ 只查原文（店名/商圈/地址），因为拼音字段里不会出现中文；</li>
     *   <li>字母片段（如「hdl」）→ 除了查原文，还查「名称全拼前缀」和「名称首字母前缀」，
     *       三者满足任意一个即可，这就是输拼音首字母也能搜到店名的原因。</li>
     * </ul>
     *
     * @param keyword 一个纯净的关键词片段（中文段或字母段，不混合）
     * @return 一张「查询纸条」，交给上层继续拼装，此处不查 ES
     */
    private QueryBuilder keywordPart(String keyword) {
        // ① 先写一张「查原文」的纸条：同一个词同时去 name/area/address 三个字段里找。
        //    boost 是权重，越大匹配得分越高：店名(3) > 商圈(2) > 地址(1)，
        //    这样「名字命中」的店会排在「地址沾边」的店前面。
        MultiMatchQueryBuilder text = QueryBuilders.multiMatchQuery(keyword, "address")
                .field("name", 3.0f).field("area", 2.0f);

        // ② 如果这个片段不是纯字母（即含中文），直接返回上面那张「查原文」纸条。
        //    因为拼音字段(namePinyin/nameInitials)里存的是拼音，中文根本匹配不上，没必要查。
        if (!keyword.matches("[a-zA-Z0-9\\s]+")) return text;

        // ③ 走到这里说明是纯字母片段，先把大小写、空格归一化：HDL → hdl、Hai Di → haidi，
        //    因为 ES 里的拼音字段存的是「小写连写」。
        String pinyin = PinyinUtil.normalize(keyword);

        // ④ 再造一张更大的纸条，把三个「或」条件写上去（should = 或，命中任意一个即可）：
        //    1) 原文匹配（第①步那张纸条）
        //    2) 名称全拼以 pinyin 开头（如输入 haidi 能匹配 haidilao）
        //    3) 名称首字母以 pinyin 开头（如输入 hdl 能匹配 hdlhg）
        return QueryBuilders.boolQuery().should(text)
                .should(QueryBuilders.prefixQuery("namePinyin", pinyin).boost(2.0f))
                .should(QueryBuilders.prefixQuery("nameInitials", pinyin).boost(2.0f))
                .minimumShouldMatch(1);
    }

    /**
     * 参数「安检员」：在真正发 ES 请求之前，把用户传来的筛选参数全部检查一遍，
     * 发现不合法就直接抛异常拦截，不让坏参数进入 ES。
     *
     * <p>为什么要提前拦：ES 很底层，直接把「经度=99999」「最低价=100 且最高价=50」
     * 这种参数发过去，ES 只会抛一堆难懂的英文堆栈异常。这里在应用层先拦一道，
     * 返回「经纬度须同时提供…」这种人类能看懂的中文提示。</p>
     *
     * <p>一共四道关卡：经纬度、半径、价格、评分。</p>
     */
    private void validateFilters(ShopSearchDTO dto) {
        // ① 经纬度检查：
        //    - (x==null) != (y==null)：一个有一个没有 → 报错（必须成对提供）
        //    - 经度 x 超过 ±180、纬度 y 超过 ±90、或不是有限数字 → 报错
        if ((dto.getX() == null) != (dto.getY() == null)
                || (dto.getX() != null && (!Double.isFinite(dto.getX()) || Math.abs(dto.getX()) > 180))
                || (dto.getY() != null && (!Double.isFinite(dto.getY()) || Math.abs(dto.getY()) > 90))) {
            throw new IllegalArgumentException("经纬度须同时提供，经度范围[-180,180]，纬度范围[-90,90]");
        }

        // ② 半径检查：半径必须 > 0，而且必须有经纬度才能圈范围（没中心点，光有半径没意义）
        if (dto.getRadius() != null && (dto.getRadius() <= 0 || dto.getX() == null)) {
            throw new IllegalArgumentException("范围过滤需要经纬度和正数半径（米）");
        }

        // ③ 价格检查：不能为负；上下限都传时，最高价必须大于最低价（区间不能为空或反了）
        if ((dto.getMinPrice() != null && dto.getMinPrice() < 0)
                || (dto.getMaxPrice() != null && dto.getMaxPrice() < 0)
                || (dto.getMinPrice() != null && dto.getMaxPrice() != null
                    && dto.getMinPrice() >= dto.getMaxPrice())) {
            throw new IllegalArgumentException("价格须非负，最高价格须大于最低价格");
        }

        // ④ 评分检查：minScore、maxScore 都必须是 0~5 之间的有限数字（用循环避免重复代码）
        for (Double score : new Double[]{dto.getMinScore(), dto.getMaxScore()}) {
            if (score != null && (!Double.isFinite(score) || score < 0 || score > 5)) {
                throw new IllegalArgumentException("评分范围为0至5分，支持小数");
            }
        }
        // 上下限都传时，最高分必须大于最低分
        if (dto.getMinScore() != null && dto.getMaxScore() != null
                && dto.getMinScore() >= dto.getMaxScore()) {
            throw new IllegalArgumentException("最高评分须大于最低评分");
        }
    }

    /**
     * 增量同步：把 MySQL 里的「单个商户」最新数据写进 ES。
     *
     * <p>商户新增/更新时，由 MQ 消费者调用，数据和 Outbox 版本通过同一条 SELECT 读取。
     * 用 IndexRequest 是「全量覆盖写」（PUT /index/_doc/id 语义）：id 已存在就整篇覆盖，
     * 不存在就新建；external_gte 允许同版本重试并拒绝旧版本覆盖。</p>
     */
    @Override
    public void syncById(Long shopId) throws IOException {
        // ① 从 MySQL 查这个商户的完整数据（数据库是权威源头，不能查 ES/Redis）
        Shop shop = shopSyncEvents.shopForSync(shopId);
        // ② 商户已被删 → 直接跳过，避免把 null 写进索引
        if (shop == null) {
            return;
        }
        // ③ 翻译：MySQL 的 Shop → ES 的 ShopDoc（顺带用 pinyin4j 生成全拼/首字母/补全词）
        ShopDoc doc = ShopDoc.from(shop);

        // ④ 造「写入请求」：写进 hmdp_shop 索引，文档 id = 商户 id，内容是 doc 转 JSON
        IndexRequest request = new IndexRequest(EsConstants.SHOP_INDEX)
                .id(shopId.toString())
                .source(JSONUtil.toJsonStr(doc), XContentType.JSON)
                .version(shop.getSyncRevision()).versionType(VersionType.EXTERNAL_GTE);
        // ⑤ 真正执行写入（全量覆盖写）
        try {
            client.index(request, RequestOptions.DEFAULT);
        } catch (ElasticsearchStatusException error) {
            // 同版本重试由 external_gte 接受；旧版本返回 409 表示新数据已在 ES，直接确认。
            if (error.status() != RestStatus.CONFLICT) {
                throw error;
            }
        }
    }

    /**
     * 全量导入：把 MySQL 里「所有商户」分批读出来、批量写进 ES，返回成功条数。
     *
     * <p>建索引/重建索引时用（ShopIndexMaintenance.rebuild 调用）。
     * 关键：用 lastId 游标（WHERE id > lastId LIMIT 500）代替深 OFFSET 分页，
     * 每次从上次结束位置继续，性能恒定，不会因翻页越来越慢。</p>
     */
    @Override
    public int bulkImportAll() throws IOException {
        int total = 0;          // 累计成功导入的条数
        Long lastId = null;     // 游标：记录上一批最后一条 id，下一批从它后面继续取
        while (true) {          // 死循环，直到取不到数据才 return
            // ① 按主键从 lastId 往后取 500 条；第一轮 lastId 为 null 则不加条件（从头取）。
            //    避免一次全表加载，也避免深 OFFSET 分页。
            List<Shop> shops = shopService.list(new LambdaQueryWrapper<Shop>()
                    .gt(lastId != null, Shop::getId, lastId)   // id > lastId（条件开关：lastId 为空时跳过）
                    .orderByAsc(Shop::getId).last("LIMIT 500"));
            // ② 取不到数据说明导完了，返回累计条数
            if (shops.isEmpty()) return total;
            // ③ 造「批量写入请求」；WAIT_UNTIL 让 ES 写完立即 refresh，保证写完就能搜到
            BulkRequest bulk = new BulkRequest().setRefreshPolicy(WriteRequest.RefreshPolicy.WAIT_UNTIL);
            // ④ 把这一批每条都翻译成 IndexRequest 装进 bulk（500 条一次请求，省网络开销）
            for (Shop shop : shops) {
                bulk.add(new IndexRequest(EsConstants.SHOP_INDEX).id(shop.getId().toString())
                        .source(JSONUtil.toJsonStr(ShopDoc.from(shop)), XContentType.JSON));
            }
            // ⑤ client.bulk() 真正执行批量写入；checkBulk 逐条验收（Bulk 返回 200 不代表每条都成功）。
            //    任意一项失败即终止，不把部分导入当作全量成功；成功项不会自动撤销。
            total += EsSearchSupport.checkBulk(client.bulk(bulk, RequestOptions.DEFAULT));
            // ⑥ 更新游标：记录这一批最后一条 id，下一轮从它后面继续
            lastId = shops.get(shops.size() - 1).getId();
        }
    }

    /**
     * 解析搜索结果：把 ES 返回的原始命中结果翻译成 ShopDoc 列表。
     *
     * <p>每条做三件事：</p>
     * <ol>
     *   <li>JSON → ShopDoc 对象；</li>
     *   <li>对 name/area/address 先做 HTML 转义（防 XSS）；</li>
     *   <li>命中关键词的字段用带 &lt;em&gt; 的高亮片段覆盖（实现标黄）；</li>
     * </ol>
     * 距离排序时，再从 sortValues[0] 取出距离（米）填进 doc.distance。
     *
     * @param response     ES 返回的响应
     * @param withDistance 是否需要解析距离（只有「距离排序」时为 true）
     */
    private List<ShopDoc> parseResponse(SearchResponse response, boolean withDistance) {
        List<ShopDoc> list = new ArrayList<>();
        SearchHits searchHits = response.getHits();

        for (SearchHit hit : searchHits.getHits()) {
            // ① 拿到这条文档的原始 JSON，反序列化成 ShopDoc
            String source = hit.getSourceAsString();
            ShopDoc doc = JSONUtil.toBean(source, ShopDoc.class);

            // ② HTML 转义：没有高亮的字段也需要转义，不能直接把用户原文作为 HTML 渲染（防 XSS）。
            doc.setName(HtmlUtil.escape(doc.getName()));
            doc.setArea(HtmlUtil.escape(doc.getArea()));
            doc.setAddress(HtmlUtil.escape(doc.getAddress()));

            // ③ 高亮处理：命中关键词的字段，用带 <em> 的片段替换掉上面转义后的原始值。
            Map<String, HighlightField> highlightFields = hit.getHighlightFields();
            if (highlightFields != null) {
                if (highlightFields.containsKey("name")) {
                    doc.setName(highlightFields.get("name").getFragments()[0].string());
                }
                if (highlightFields.containsKey("area")) {
                    doc.setArea(highlightFields.get("area").getFragments()[0].string());
                }
                if (highlightFields.containsKey("address")) {
                    doc.setAddress(highlightFields.get("address").getFragments()[0].string());
                }
            }

            // ④ 解析距离：距离排序时，ES 把距离放在 sortValues 第一个位置，单位米。
            if (withDistance) {
                Object[] sortValues = hit.getSortValues();
                if (sortValues != null && sortValues.length > 0) {
                    doc.setDistance(((Number) sortValues[0]).doubleValue());
                }
            }

            list.add(doc);
        }
        return list;
    }

    /**
     * 解析聚合结果：把 ES 返回的「桶」翻译成 AggItem 列表，供前端显示「每档 X 家」。
     *
     * <p>处理 priceAgg、scoreAgg 和有坐标时的 distanceAgg；每个桶用 key 作为标签（key 就是
     * ShopSearchRanges 里定义的区间 label，不能用数组下标推断标签），docCount 作为数量。</p>
     */
    private Map<String, List<ShopSearchResult.AggItem>> parseAggregations(SearchResponse response) {
        Map<String, List<ShopSearchResult.AggItem>> aggMap = new HashMap<>();

        // 两个聚合：priceAgg（价格档）、scoreAgg（评分档），用循环避免重复代码
        for (String name : new String[]{"priceAgg", "scoreAgg"}) {
            // 从响应里取出对应聚合（Range 类型，因为 /search 里用的是 range 分桶聚合）
            Range range = response.getAggregations().get(name);
            if (range == null) continue;   // 聚合不存在则跳过
            List<ShopSearchResult.AggItem> items = new ArrayList<>();
            // 遍历每个桶：桶 key 就是后端定义的标签（如"￥50-100"），docCount 是该区间数量
            for (Range.Bucket bucket : range.getBuckets()) {
                // 桶 key 就是后端定义的标签，不能用数组下标推断标签。
                String label = bucket.getKeyAsString();
                // AggItem 的 key 和 label 在本项目里取值相同，所以同一个 label 传两次
                items.add(new ShopSearchResult.AggItem(label, label, bucket.getDocCount()));
            }
            aggMap.put(name, items);
        }

        // 累计距离使用filters桶，0家也会返回；无坐标时不虚构距离统计。
        Filters distance = response.getAggregations().get("distanceAgg");
        if (distance != null) {
            List<ShopSearchResult.AggItem> items = new ArrayList<>();
            for (Filters.Bucket bucket : distance.getBuckets()) {
                String label = bucket.getKeyAsString();
                items.add(new ShopSearchResult.AggItem(label, label, bucket.getDocCount()));
            }
            aggMap.put("distanceAgg", items);
        }

        return aggMap;
    }

    /**
     * 输入补全：用 completion suggester 在 suggestion 字段上做前缀匹配。
     * 命中拼音/首字母后统一返回中文商户名，供前端下拉提示。
     */
    @Override
    public List<String> suggest(String keyword) throws IOException {
        if (StrUtil.isBlank(keyword)) return new ArrayList<>();
        String prefix = keyword.trim();
        if (prefix.matches("[a-zA-Z0-9\\s]+")) prefix = PinyinUtil.normalize(prefix);
        SearchRequest request = new SearchRequest(EsConstants.SHOP_INDEX);
        SearchSourceBuilder sourceBuilder = new SearchSourceBuilder();
        sourceBuilder.size(0);

        // completion 补全查询
        sourceBuilder.suggest(new org.elasticsearch.search.suggest.SuggestBuilder()
                .addSuggestion("shopSuggestion",
                        new org.elasticsearch.search.suggest.completion.CompletionSuggestionBuilder("suggestion")
                                .prefix(prefix)
                                .skipDuplicates(true)
                                .size(10)));

        request.source(sourceBuilder);
        SearchResponse response = client.search(request, RequestOptions.DEFAULT);

        Set<String> suggestions = new LinkedHashSet<>();
        if (response.getSuggest() != null) {
            org.elasticsearch.search.suggest.completion.CompletionSuggestion suggestion =
                    response.getSuggest().getSuggestion("shopSuggestion");
            if (suggestion != null) {
                for (org.elasticsearch.search.suggest.completion.CompletionSuggestion.Entry entry : suggestion.getEntries()) {
                    for (org.elasticsearch.search.suggest.completion.CompletionSuggestion.Entry.Option option : entry.getOptions()) {
                        // 拼音命中后显示中文商户名，选中后仍能用中文进行正式搜索。
                        ShopDoc doc = JSONUtil.toBean(option.getHit().getSourceAsString(), ShopDoc.class);
                        if (StrUtil.isNotBlank(doc.getName())) suggestions.add(doc.getName());
                    }
                }
            }
        }
        // 去重
        return suggestions.stream().distinct().limit(8).collect(java.util.stream.Collectors.toList());
    }
}
