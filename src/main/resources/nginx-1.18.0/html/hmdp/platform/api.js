/** 平台独立请求实例与浏览器存储键，既不读取也不清理消费者/商户token。 */
const platformApi=axios.create({baseURL:'/api',timeout:10000});
platformApi.interceptors.request.use(config=>{
  config.headers.authorization=sessionStorage.getItem('platform_token') || '';
  return config;
});
platformApi.interceptors.response.use(response=>{
  if(!response.data.success) return Promise.reject(new Error(response.data.errorMsg || '操作失败'));
  return response.data.data;
},error=>{
  if(error.response && error.response.status===401){
    sessionStorage.removeItem('platform_token');location.replace('./login.html');
  }
  return Promise.reject(new Error((error.response && error.response.data.errorMsg) || '服务连接失败，请稍后重试'));
});
