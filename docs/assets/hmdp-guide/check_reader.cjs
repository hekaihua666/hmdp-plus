// 检查本地 HTML 阅读版，保存桌面和手机预览，避免只完成 Markdown 静态检查。
const {chromium}=require('/path/to/local-file);
const path=require('path');
const {pathToFileURL}=require('url');
(async()=>{
 const browser=await chromium.launch({executablePath:'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',headless:true});
 const page=await browser.newPage({viewport:{width:1440,height:1050}});
 await page.goto(pathToFileURL(path.resolve(__dirname,'../../HMDP项目实战讲义.html')).href,{waitUntil:'load'});
 await page.evaluate(async()=>Promise.all(Array.from(document.images).map(im=>im.decode())));
 const report=await page.evaluate(()=>({images:document.images.length,broken:[...document.images].filter(im=>!im.naturalWidth).map(im=>im.src),chapters:document.querySelectorAll('main h2').length,overflow:document.documentElement.scrollWidth>innerWidth,bodyWidth:innerWidth,links:document.querySelectorAll('a').length}));
 await page.screenshot({path:path.join(__dirname,'qa-reader-desktop.png')});
 await page.getByRole('heading',{name:'8.4、每次重读 MySQL，为什么仍可能写旧',exact:true}).scrollIntoViewIfNeeded();
 await page.screenshot({path:path.join(__dirname,'qa-reader-chapter.png')});
 await page.setViewportSize({width:430,height:932});
 await page.evaluate(()=>scrollTo(0,0));
 await page.screenshot({path:path.join(__dirname,'qa-reader-mobile.png')});
 const mobileOverflow=await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth);
 console.log(JSON.stringify({...report,mobileOverflow}));
 if(report.broken.length||report.overflow||mobileOverflow) throw new Error('阅读版布局检查失败');
 await browser.close();
})().catch(e=>{console.error(e);process.exitCode=1});
