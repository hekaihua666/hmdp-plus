/**
 * info.html与other-info.html共用的轻社交主页。
 * isOwn只决定展示哪种页面和选择哪个接口，不构成授权；服务端仍验证会话与“本人”身份。
 * 页面仅维护作者列表/笔记页码，不维护lastId、minTime、offset或每人一个的Feed收件箱。
 */
const app = new Vue({
  el:'#app',
  data:{
    // 自己的主页不从URL接受用户ID；公开作者主页才用URL中的id选择阅读对象。
    util, isOwn:!location.pathname.endsWith('/other-info.html'), user:{}, info:{},
    tab:util.getUrlParam('tab')==='following' ? 'following' : 'notes',
    // 两个标签各存一套列表、下一页页码和结束标记，切换标签不会混用分页位置。
    blogs:[], authors:[], notePage:1, authorPage:1, notesFinished:false, authorsFinished:false,
    // loading约束列表加载，followingBusy约束关系写入；前端防连点不能代替后端并发控制。
    loading:false, error:'', followed:false, followingBusy:false
  },
  created(){
    // 游客访问“我的”先跳登录；读取公开作者主页则不主动调用受保护的/user/me。
    if(this.isOwn && !token){location.href='/login.html';return;}
    const path=this.isOwn ? '/user/me' : '/user/'+util.getUrlParam('id');
    axios.get(path).then(({data})=>{
      this.user=data;
      // 简介不是主列表的必要条件：没有填写或读取失败时，模板显示默认介绍。
      axios.get('/user/info/'+data.id).then(({data})=>this.info=data || {}).catch(()=>{});
      // 只有带消费者会话时才查“我是否关注”；游客仍可看笔记，不因这个辅助接口401被跳走。
      if(!this.isOwn){this.tab='notes';if(token)this.queryFollow();}
      this.loadCurrent();
    }).catch(()=>{this.error='用户资料加载失败，请刷新重试';});
  },
  methods:{
    /**
     * 选当前标签的接口：关注作者 -> /follow/of/me，本人笔记 -> /blog/of/me，作者笔记 -> /blog/of/user。
     * /of/me不需要客户端指定userId；即使传了其他ID，服务端也按登录上下文取本人。
     * 页码只在成功后+1，失败保留原页码；少于固定10条时结束。刚好10条可能再请求一个空页。
     */
    loadCurrent(){
      if(this.loading)return;
      const authors=this.isOwn && this.tab==='following';
      if(authors ? this.authorsFinished : this.notesFinished)return;
      this.loading=true;this.error='';
      const request=authors ? axios.get('/follow/of/me',{params:{current:this.authorPage}})
        : axios.get(this.isOwn ? '/blog/of/me' : '/blog/of/user',{params:{current:this.notePage,id:this.user.id}});
      request.then(({data})=>{
        const rows=data || [];
        if(authors){this.authors=this.authors.concat(rows);this.authorPage++;this.authorsFinished=rows.length<10;}
        else{this.blogs=this.blogs.concat(rows);this.notePage++;this.notesFinished=rows.length<10;}
      // finally无论成功失败都释放加载状态；否则一次网络失败就会永远挡住“重试”。
      }).catch(()=>this.error='加载失败，请重试').finally(()=>this.loading=false);
    },
    switchTab(tab){
      // 资料未就绪或旧标签请求未完成时不切换，避免把旧响应装进另一标签的状态。
      if(!this.user.id || this.loading || tab===this.tab)return;
      this.tab=tab;this.error='';
      if(tab==='following' && !this.authors.length && !this.authorsFinished)this.loadCurrent();
      if(tab==='notes' && !this.blogs.length && !this.notesFinished)this.loadCurrent();
    },
    /** 从服务端读取关系状态，刷新后不依赖上次页面里暂存的按钮文案。 */
    queryFollow(){axios.get('/follow/or/not/'+this.user.id).then(({data})=>this.followed=data).catch(()=>{});},
    follow(){
      if(!token){location.href='/login.html';return;}
      if(this.followingBusy)return;
      this.followingBusy=true;
      // URL最后一段是目标状态true/false，成功后才更新本地followed，失败时保留原按钮状态。
      axios.put('/follow/'+this.user.id+'/'+!this.followed)
        .then(()=>{this.followed=!this.followed;})
        .catch(e=>this.$message.error(e)).finally(()=>this.followingBusy=false);
    },
    /**
     * 取关成功后重新从第一页加载，而不是仅splice一项再沿用旧offset。
     * 例如第一页删除1人后，原第11人会前移到第10位，直接请求offset=10可能跳过该作者。
     * 这仍是页码分页，不宣称在其他客户端同时改动关系时也有快照级的无漏读保证。
     */
    unfollow(author){
      if(this.followingBusy)return;
      this.followingBusy=true;
      axios.put('/follow/'+author.id+'/false').then(()=>{
        this.authors=[];this.authorPage=1;this.authorsFinished=false;this.loadCurrent();
      }).catch(e=>this.$message.error(e)).finally(()=>this.followingBusy=false);
    },
    logout(){
      // 服务端先尝试删除当前Redis会话；网络失败也清浏览器状态，避免页面卡在“退出失败”。
      // 清除待查询订单ID只移除本地快捷入口，不取消数据库中已受理的MQ抢购申请。
      axios.post('/user/logout').catch(()=>{}) .finally(()=>{
        sessionStorage.removeItem('token');sessionStorage.removeItem('userInfo');
        sessionStorage.removeItem('hmdp_seckill_pending');location.href='/index.html';
      });
    },
    /** 列表只展示第一张图，保留原“多图逗号分隔”的数据协议。 */
    cover(images){return (images || '').split(',')[0] || '/imgs/icons/default-icon.png';},
    /** 资料尚未加载时重试整个页面；列表失败只重试当前页，不错误推进页码。 */
    retry(){if(!this.user.id){location.reload();return;}this.loadCurrent();},
    toBlog(id){location.href='/blog-detail.html?id='+id;},
    toAuthor(id){location.href='/other-info.html?id='+id;},
    publish(){location.href='/blog-edit.html';},
    goBack(){if(history.length>1)history.back();else location.href='/index.html';}
  }
});
