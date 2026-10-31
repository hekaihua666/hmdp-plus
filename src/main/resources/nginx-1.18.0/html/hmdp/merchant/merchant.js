/* 桌面后台专用请求实例：不加载 C 端 common.js，防止 token 和跳转逻辑互相覆盖。 */
const merchantApi = axios.create({baseURL:'/api',timeout:10000});
// 登录态失效后已经无法保存修改，未保存提醒不应再阻止跳回登录页。
let merchantSessionActive = true;
merchantApi.interceptors.request.use(config=>{
  config.headers.authorization=sessionStorage.getItem('merchant_token') || '';
  return config;
});
merchantApi.interceptors.response.use(response=>{
  if(!response.data.success) return Promise.reject(new Error(response.data.errorMsg || '操作失败'));
  return response.data.data;
},error=>{
  if(error.response && error.response.status===401){
    merchantSessionActive = false;
    sessionStorage.removeItem('merchant_token');location.replace('./login.html');
  }
  return Promise.reject(new Error((error.response && error.response.data.errorMsg) || '服务连接失败，请稍后重试'));
});

// 请求体始终只包含白名单字段；不将整个店铺对象（含销量、评分等）发回服务器。
function editableShop(shop){
  const result={};
  ['name','typeId','images','area','address','x','y','avgPrice','openHours'].forEach(key=>{
    result[key]=shop[key]==null ? (['area','openHours'].includes(key)?'':null) : shop[key];
  });
  return result;
}
new Vue({el:'#app',data:{merchant:{},shop:null,types:[],form:{},savedForm:{},loading:true,saving:false,loggingOut:false,pageError:'',
  rules:{name:[{required:true,message:'请填写店铺名称',trigger:'blur'}],typeId:[{required:true,message:'请选择分类',trigger:'change'}],address:[{required:true,message:'请填写地址',trigger:'blur'}],images:[{required:true,message:'请填写图片地址',trigger:'blur'}],avgPrice:[{required:true,message:'请填写人均价格',trigger:'blur'}],x:[{required:true,message:'请填写经度',trigger:'blur'}],y:[{required:true,message:'请填写纬度',trigger:'blur'}]}},
  computed:{
    dirty(){return JSON.stringify(this.form)!==JSON.stringify(this.savedForm);},
    imageUrls(){return (this.form.images || '').split(',').map(v=>v.trim()).filter(v=>/^(https?:\/\/|\/(?!\/))[^\s,]+$/.test(v));},
    cover(){return (this.shop.images || '').split(',')[0];},
    categoryName(){const category=this.types.find(v=>v.id===this.shop.typeId);return category?category.name:'店铺';}
  },
  created(){if(!sessionStorage.getItem('merchant_token')){location.replace('./login.html');return;}this.load();},
  mounted(){window.addEventListener('beforeunload',this.preventUnsaved);},
  beforeDestroy(){window.removeEventListener('beforeunload',this.preventUnsaved);},
  methods:{
    async load(){
      this.loading=true;this.pageError='';
      try{const [merchant,shop,types]=await Promise.all([merchantApi.get('/admin/merchant/me'),merchantApi.get('/admin/shop'),merchantApi.get('/shop-type/list')]);
        this.merchant=merchant;this.shop=shop;this.types=types;this.form=editableShop(shop);this.savedForm=JSON.parse(JSON.stringify(this.form));
      }catch(error){this.pageError=error.message;}finally{this.loading=false;}
    },
    restore(){this.form=JSON.parse(JSON.stringify(this.savedForm));this.$nextTick(()=>this.$refs.form.clearValidate());},
    save(){
      if(this.saving || !this.dirty) return;
      this.$refs.form.validate(async valid=>{
        if(!valid) return;
        this.saving=true;
        try{await merchantApi.put('/admin/shop',this.form);
          // 以数据库响应重新填充编辑表单，不能用异步 ES 的结果确认保存状态。
          await this.load();this.$message.success('店铺信息已保存，搜索展示可能稍后更新');
        }catch(error){this.$message.error(error.message);}finally{this.saving=false;}
      });
    },
    async logout(){
      if(this.loggingOut) return;
      if(this.dirty){try{await this.$confirm('当前修改尚未保存，确定退出吗？','退出登录',{type:'warning'});}catch(_){return;}}
      this.loggingOut=true;
      // 无论服务端撤销是否成功，都先清本地登录态并跳转，避免接口异常导致"退不出去"。
      try{await merchantApi.post('/admin/merchant/logout');}
      catch(error){/* 撤销失败不阻塞退出：token 稍后自然过期 */}
      // 用户已确认舍弃修改，不再让beforeunload弹出第二次确认或拦截登录页跳转。
      window.removeEventListener('beforeunload',this.preventUnsaved);
      sessionStorage.removeItem('merchant_token');
      location.replace('./login.html');
    },
    preventUnsaved(event){if(merchantSessionActive && this.dirty){event.preventDefault();event.returnValue='';}}
  }
});
