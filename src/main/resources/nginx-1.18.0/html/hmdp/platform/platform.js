/** 平台列表操作只提交status，不发送整行商户对象或店铺归属信息。 */
const platformApp=new Vue({el:'#app',data:{admin:{},rows:[],total:0,current:1,pageSize:20,keyword:'',status:null,loading:false,error:'',updatingId:null,loggingOut:false,requestVersion:0},
  async created(){
    if(!sessionStorage.getItem('platform_token')){location.replace('./login.html');return;}
    try{this.admin=await platformApi.get('/platform/admin/me');await this.load();}
    catch(error){this.error=error.message;}
  },
  methods:{
    async load(){
      const version=++this.requestVersion;this.loading=true;this.error='';
      try{const page=await platformApi.get('/platform/merchant',{params:{keyword:this.keyword || null,status:this.status,current:this.current,pageSize:this.pageSize}});
        // 快速改筛选条件时，只接收最后一批响应，防止旧结果覆盖新列表。
        if(version!==this.requestVersion) return;
        this.rows=page.records;this.total=page.total;
        if(!this.rows.length && this.current>1 && this.total>0){this.current=Math.max(1,Math.ceil(this.total/this.pageSize));await this.load();}
      }catch(error){if(version===this.requestVersion){this.error=error.message;this.rows=[];this.total=0;}}
      finally{if(version===this.requestVersion) this.loading=false;}
    },
    search(){this.current=1;this.load();},
    changePage(current){this.current=current;this.load();},
    // 只清理展示标记，数据库保留原始名称，供已有学习数据脚本识别。
    shopName(name){return (name || '').replace(/（演示）/g,'').replace(/（模拟地址）/g,'').replace(/演示生活街/g,'生活街');},
    formatTime(value){return value ? value.replace('T',' ').slice(0,16) : '—';},
    async changeStatus(row){
      if(this.updatingId!=null) return;
      const status=row.status===1 ? 0 : 1, action=status===0 ? '封禁' : '解封';
      const message=status===0 ? '封禁后，该商户将无法登录或管理店铺。' : '解封后，该商户可以重新登录并管理店铺。';
      try{await this.$confirm(message,action+'商户「'+this.shopName(row.shopName || row.name)+'」',{confirmButtonText:'确认'+action,cancelButtonText:'取消',type:status===0 ? 'warning' : 'info'});}
      catch(cancel){return;}
      this.updatingId=row.id;
      try{await platformApi.put('/platform/merchant/'+row.id+'/status',{status});this.$message.success('商户已'+action);await this.load();}
      catch(error){this.$message.error(error.message);}finally{this.updatingId=null;}
    },
    async logout(){
      if(this.loggingOut) return;this.loggingOut=true;
      try{await platformApi.post('/platform/admin/logout');}
      catch(error){/* 服务端撤销失败也完成本地退出，固定TTL限制服务器会话寿命。 */}
      finally{sessionStorage.removeItem('platform_token');location.replace('./login.html');}
    }
  }
});
