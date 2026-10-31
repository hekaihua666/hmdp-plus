// 只浏览公开页面和后台登录页，不登录、不提交表单、不修改业务数据。
const {chromium} = require('/path/to/local-file);
const path = require('path');

(async () => {
  const browser = await chromium.launch({executablePath:'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe', headless:true});
  const page = await browser.newPage({viewport:{width:1280,height:900},deviceScaleFactor:1});
  const pages=[['ui-home','index.html'],['ui-search','shop-list.html'],['ui-shop','shop-detail.html?id=17'],
    ['ui-blog','blog-detail.html?id=5'],['ui-merchant-login','merchant/login.html'],['ui-platform-login','platform/login.html']];
  for(const [name,url] of pages){
    await page.goto('http://localhost:8080/'+url,{waitUntil:'networkidle'});
    // 确保可见图片已解码，再截图；不将未加载数据的空白页面当作效果图。
    await page.evaluate(async()=>Promise.all(Array.from(document.images).map(img=>img.decode().catch(()=>{}))));
    await page.screenshot({path:path.join(__dirname,name+'.png'),fullPage:false});
    console.log(name, (await page.locator('body').innerText()).slice(0,90).replace(/\n/g,' '));
  }
  await page.goto('http://localhost:8080/shop-list.html',{waitUntil:'networkidle'});
  const price = page.locator('.filter-item').filter({hasText:/价格|人均/}).first();
  await price.click();
  await page.locator('.filter-panel').waitFor();
  await page.screenshot({path:path.join(__dirname,'ui-filters.png')});
  console.log('ui-filters captured');
  await browser.close();
})().catch(error=>{console.error(error);process.exitCode=1;});
