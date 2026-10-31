/* 商户发券页专用逻辑：复用 merchant_token，不加载 C 端 common.js。 */
const api = axios.create({ baseURL: '/api', timeout: 10000 });
api.interceptors.request.use(config => {
  config.headers.authorization = sessionStorage.getItem('merchant_token') || '';
  return config;
});
api.interceptors.response.use(response => {
  if (!response.data.success) return Promise.reject(new Error(response.data.errorMsg || '操作失败'));
  return response.data.data;
}, error => {
  if (error.response && error.response.status === 401) {
    sessionStorage.removeItem('merchant_token');
    location.replace('./login.html');
  }
  return Promise.reject(new Error((error.response && error.response.data.errorMsg) || '服务连接失败，请稍后重试'));
});

new Vue({
  el: '#app',
  data: {
    merchant: {},
    shop: null,
    loading: true,
    submitting: false,
    pageError: '',
    form: { title: '', subTitle: '', rules: '', payValue: 0, actualValue: 0, type: 0, stock: null, beginTime: '', endTime: '' },
    rules: {
      title: [{ required: true, message: '请填写优惠券标题', trigger: 'blur' }],
      payValue: [{ required: true, message: '请填写支付金额', trigger: 'change' }],
      actualValue: [{ required: true, message: '请填写抵扣金额', trigger: 'change' }],
      type: [{ required: true, message: '请选择优惠券类型', trigger: 'change' }],
      stock: [{ required: true, message: '请填写库存', trigger: 'change' }],
      beginTime: [{ required: true, message: '请选择生效时间', trigger: 'change' }],
      endTime: [{ required: true, message: '请选择失效时间', trigger: 'change' }]
    }
  },
  created() {
    if (!sessionStorage.getItem('merchant_token')) { location.replace('./login.html'); return; }
    this.load();
  },
  methods: {
    async load() {
      this.loading = true;
      this.pageError = '';
      try {
        const [merchant, shop] = await Promise.all([api.get('/admin/merchant/me'), api.get('/admin/shop')]);
        this.merchant = merchant;
        this.shop = shop;
      } catch (e) {
        this.pageError = e.message;
      } finally {
        this.loading = false;
      }
    },
    reset() {
      this.form = { title: '', subTitle: '', rules: '', payValue: 0, actualValue: 0, type: 0, stock: null, beginTime: '', endTime: '' };
      this.$nextTick(() => this.$refs.form.clearValidate());
    },
    submit() {
      if (this.submitting) return;
      this.$refs.form.validate(async valid => {
        if (!valid) return;
        this.submitting = true;
        try {
          await api.post('/admin/voucher', this.form);
          this.$message.success('优惠券发布成功');
          this.reset();
        } catch (e) {
          this.$message.error(e.message);
        } finally {
          this.submitting = false;
        }
      });
    },
    async logout() {
      // 无论服务端撤销是否成功，都先清本地登录态并跳转，避免接口异常导致"退不出去"。
      try {
        await api.post('/admin/merchant/logout');
      } catch (e) { /* 撤销失败不阻塞退出：token 稍后自然过期 */ }
      sessionStorage.removeItem('merchant_token');
      location.replace('./login.html');
    }
  }
});
