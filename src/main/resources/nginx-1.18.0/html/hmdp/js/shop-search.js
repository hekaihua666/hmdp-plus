/**
 * 分类、附近和组合筛选统一调用/shop/search，同一响应提供列表、总数与聚合。
 * 原来的“纯分类走Redis GEO，其余走ES，再额外请求ES统计”双通道已经删除。
 * ES不可用时保留错误/重试，不退回过滤与分页语义不同的另一份列表。
 */
const app = new Vue({
  el: '#app',
  data: {
    util, types: [], shops: [], total: null, loading: false, finished: false,
    // version标记一次完整筛选会话，防止快速切换条件后较慢的旧响应覆盖新结果。
    // aggState区分“尚在统计/统计可用/统计失败”，不能用空字典把失败展示成0家。
    error: '', panel: null, version: 0, aggState: 'loading', aggPrice: {}, aggScore: {}, aggDistance: {},
    priceOptions: [], scoreOptions: [], priceRange: null,
    radiusOptions: [], // 与后端距离聚合使用同一份半径定义。
    // x为经度、y为纬度；当前仍使用固定杭州参考点，不表示已取得设备定位。
    // radius=null表示不限半径；“按距离排序”和“限定半径”是相互独立的条件。
    params: {typeId: 0, current: 1, sort: '', keyword: '', minPrice: null, maxPrice: null,
      minScore: null, maxScore: null, radius: null, x: 120.149993, y: 30.334229}
  },
  computed: {
    selectedTypeName() {
      const type = this.types.find(t => t.id === this.params.typeId);
      return type ? type.name : '全部分类';
    },
    panelTitle() {return {type: '选择分类', price: '人均价格', score: '商户评分', radius: '距离范围', sort: '排序方式'}[this.panel];},
    tabs() {
      const score = this.scoreOptions.find(s => s.min === this.params.minScore);
      const radius = this.radiusOptions.find(r => r.value === this.params.radius);
      return [
        {key: 'type', label: this.selectedTypeName, active: !!this.params.typeId},
        {key: 'price', label: this.priceRange || '价格', active: this.params.minPrice != null},
        {key: 'score', label: score ? score.label : '评分', active: this.params.minScore != null},
        {key: 'radius', label: radius ? radius.label : '距离', active: this.params.radius != null},
        {key: 'sort', label: {'': '排序', distance: '最近', price: '低价优先', score: '高分优先'}[this.params.sort], active: !!this.params.sort}
      ];
    },
    locationHint() {
      if (this.params.radius != null) return '杭州参考位置 · '+this.params.radius / 1000+' 公里内';
      return this.params.sort === 'distance' ? '杭州参考位置 · 按距离排序' : '杭州商户 · 可组合筛选';
    }
  },
  created() {
    this.params.typeId = parseInt(util.getUrlParam('type')) || 0;
    this.params.keyword = util.getUrlParam('keyword') || '';
    // 分类默认距离排序但不暗中限制半径；“附近”导航明确指定5公里。
    // 因此分类页可看到该分类所有已索引商户，不再因旧GEO的固定5公里而悄悄少几家。
    this.params.sort = util.getUrlParam('sort') || (this.params.keyword ? '' : 'distance');
    const radius = Number(util.getUrlParam('radius'));
    if ([1000,3000,5000,10000].includes(radius)) this.params.radius = radius;
    axios.get('/shop-type/list').then(({data}) => this.types = data).catch(() => this.$message.error('分类加载失败'));
    axios.get('/shop/search/options').then(({data}) => {
      this.priceOptions = data.price; this.scoreOptions = data.score;
      this.radiusOptions = (data.distance || []).map(r => ({label:r.label, value:r.max}));
    }).catch(() => this.$message.error('筛选选项加载失败，请刷新重试'));
    this.resetAndSearch();
  },
  methods: {
    /** 列表与聚合共用一套筛选条件，不再单独请求第二份统计。 */
    esParams() {
      const p = this.params;
      return {keyword: p.keyword, typeId: p.typeId || null, minPrice: p.minPrice, maxPrice: p.maxPrice,
        minScore: p.minScore, maxScore: p.maxScore, current: p.current, pageSize: 10,
        // 每次都传参考坐标供距离统计使用；只有radius有值才限制搜索范围。
        // pageSize=10与ES列表分页一致；列表与聚合共用keyword/type/价格/评分/半径条件。
        sort: p.sort || 'default', x: p.x, y: p.y,
        radius: p.radius};
    },
    applyAggregations(data) {
      // 数组桶转为label->count字典，模板可按后端返回的同名筛选选项取值。
      // total是当前全部条件下的总数，不是全站商户数；距离桶为累计范围，不能相加。
      this.total = data.total;
      const aggs = data.aggregations || {};
      this.aggPrice = Object.fromEntries((aggs.priceAgg || []).map(b => [b.label, b.count]));
      this.aggScore = Object.fromEntries((aggs.scoreAgg || []).map(b => [b.label, b.count]));
      this.aggDistance = Object.fromEntries((aggs.distanceAgg || []).map(b => [b.label, b.count]));
      this.aggState = 'ready';
    },
    /**
     * 每次请求捕获version和page。若等待期间resetAndSearch创建了新version，
     * 旧then/catch/finally都不再写页面，特别是旧finally不能把新请求的loading提前清掉。
     * 成功才追加本页并推进页码；失败不增加current，点击重试仍查询同一页。
     */
    queryShops() {
      if (this.loading || this.finished) return;
      const version = this.version, page = this.params.current;
      this.loading = true; this.error = '';
      const request = axios.get('/shop/search', {params: this.esParams()});
      request.then(({data}) => {
        if (version !== this.version) return;
        this.applyAggregations(data);
        const list = data.list || [];
        // 名称/商圈/地址的高亮和HTML编码仍由既有ES解析逻辑负责，此处只截取封面图。
        list.forEach(s => {
          s.images = (s.images || '').split(',')[0];
        });
        this.shops = this.shops.concat(list);
        this.finished = this.shops.length >= data.total;
        this.params.current = page + 1;
      }).catch(() => {
        if (version !== this.version) return;
        this.error = '加载失败，请重试';
        this.aggState = 'error';
      }).finally(() => {if (version === this.version) this.loading = false;});
    },
    // 缺少聚合桶不能当成0家；真正为0的桶仍正常显示，统计失败也保留明确提示。
    countLabel(map, label) {return this.aggState === 'ready' ? (Object.prototype.hasOwnProperty.call(map,label) ? map[label]+' 家' : '暂不可用') : this.aggState === 'error' ? '暂不可用' : '统计中…';},
    barWidth(map, label) {return this.total ? Math.min(100, (map[label] || 0) * 100 / this.total)+'%' : '0%';},
    togglePanel(key) {this.panel = this.panel === key ? null : key;},
    selectType(id) {this.params.typeId = id || 0; this.resetAndSearch();},
    selectPrice(p) {this.params.minPrice = p ? p.min : null; this.params.maxPrice = p ? p.max : null; this.priceRange = p ? p.label : null; this.resetAndSearch();},
    selectScore(s) {this.params.minScore = s ? s.min : null; this.params.maxScore = s ? s.max : null; this.resetAndSearch();},
    selectRadius(r) {this.params.radius = r ? r.value : null; this.resetAndSearch();},
    selectSort(sort) {this.params.sort = sort; this.resetAndSearch();},
    clearFilters() {Object.assign(this.params, {minPrice:null, maxPrice:null, minScore:null, maxScore:null, radius:null, sort:''}); this.priceRange=null; this.resetAndSearch();},
    resetAndSearch() {
      // 筛选条件改变后，旧列表/总数/聚合一起失效，避免“新列表配旧统计”。
      // 此处只发一次查询；不再额外调用queryStatistics去维护第二份统计响应。
      this.version++; this.params.current=1; this.shops=[]; this.total=null;
      this.finished=false; this.loading=false; this.panel=null; this.aggState='loading'; this.aggPrice={}; this.aggScore={}; this.aggDistance={};
      this.$nextTick(() => {if (this.$refs.list) this.$refs.list.scrollTop=0;});
      this.queryShops();
    },
    querySearch(text, cb) {
      if (!text.trim()) return cb([]);
      axios.get('/shop/suggest', {params:{keyword:text}}).then(({data}) => cb(data.map(value => ({value:util.displayText(value)})))).catch(() => cb([]));
    },
    handleSuggest(item) {this.params.keyword=item.value; this.resetAndSearch();},
    onScroll(e) {const el=e.target; if(el.scrollTop+el.clientHeight>=el.scrollHeight-40 && !this.error) this.queryShops();},
    toDetail(id) {location.href='/shop-detail.html?id='+id;},
    goBack() {history.back();}
  }
});
