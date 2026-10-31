// 从 Markdown 生成离线 HTML 阅读版；图片保持相对路径，不依赖在线脚本或字体。
const fs=require('fs');
const path=require('path');
const {marked}=require('/path/to/local-file);
const docs=path.resolve(__dirname,'../..');
const filename='HMDP项目实战讲义';
const source=fs.readFileSync(path.join(docs,filename+'.md'),'utf8');
let html=marked.parse(source,{gfm:true});
let number=0;
const navigation=[];
html=html.replace(/<h([123])>([\s\S]*?)<\/h\1>/g,(_,level,content)=>{
  const id='section-'+(++number);
  if(level==='2') navigation.push(`<a href="#${id}">${content}</a>`);
  return `<h${level} id="${id}">${content}</h${level}>`;
});
const output=`<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>${filename}</title>
<style>
*{box-sizing:border-box}html{scroll-behavior:smooth}body{margin:0;background:#eef2f7;color:#24364b;font:17px/1.95 'Microsoft YaHei','PingFang SC',sans-serif}
aside{position:fixed;left:0;top:0;bottom:0;width:248px;padding:32px 20px;background:#152d4b;color:#d8e4f4;overflow:auto}aside strong{display:block;color:white;font-size:23px;margin-bottom:16px}aside a{display:block;color:#d8e4f4;text-decoration:none;font-size:13px;line-height:1.65;padding:8px 0;border-bottom:1px solid #ffffff12}aside a:hover{color:#7edbc3}
main{max-width:1140px;margin:32px 32px 60px 280px;padding:54px 62px;background:white;box-shadow:0 8px 40px #24364b0b;border-radius:16px}h1{font-size:43px;line-height:1.5;color:#152d4b;margin-top:0;border-bottom:5px solid #63b9a5;padding-bottom:22px}h2{font-size:29px;color:#152d4b;border-left:5px solid #63b9a5;padding-left:17px;margin-top:65px;scroll-margin-top:20px}h4{font-size:19px;margin-top:30px;color:#2d5b77;scroll-margin-top:20px}h3{font-size:22px;margin-top:35px;color:#234e75;scroll-margin-top:20px}
p{margin:18px 0}a{color:#2675a4;text-underline-offset:4px}strong{color:#163c5c}blockquote{margin:22px 0;padding:12px 22px;border-left:4px solid #79a7cf;background:#f1f6fb;border-radius:0 9px 9px 0;color:#53667d}blockquote p{margin:6px 0}img{display:block;max-width:100%;height:auto;margin:28px auto;border:1px solid #e4eaf1;border-radius:8px}p:has(>em:only-child){font-size:14px;color:#6d7f93;text-align:center}pre{background:#142c48;color:#edf4fc;padding:22px;border-radius:10px;overflow-x:auto;font-size:14px;line-height:1.75}pre code{font:inherit;background:none;color:inherit;padding:0}code{font-family:Consolas,'Microsoft YaHei',monospace;background:#eef3f8;color:#244e77;padding:2px 5px;border-radius:4px;font-size:.88em;font-style:normal}table{border-collapse:collapse;width:100%;margin:26px 0;font-size:14px;line-height:1.8}th{background:#eaf1f8;color:#173a5d}th,td{text-align:left;border:1px solid #dce5ee;padding:12px;overflow-wrap:anywhere}tr:nth-child(even){background:#f8fafc}hr{border:0;border-top:1px solid #e3eaf2;margin:48px 0}li{margin:8px 0}.badge{display:inline-block;border-radius:30px;background:#e6f5ef;color:#245b49;padding:5px 14px;font-size:13px;margin-bottom:24px}
@media(min-width:1550px){main{margin-left:auto;margin-right:auto;transform:translateX(100px)}}@media(max-width:1000px){aside{display:none}main{margin:14px;padding:30px 24px}h1{font-size:33px}body{font-size:16px}}@media print{aside{display:none}main{margin:0;padding:0;box-shadow:none;transform:none;max-width:none}pre{white-space:pre-wrap}h2,h3{break-after:avoid}img,table{break-inside:avoid}}
</style></head><body><aside><strong>HMDP 实战讲义</strong><p>沿业务流程读懂项目</p>${navigation.join('')}</aside><main><span class="badge">源码核对 · 图文讲解 · 2026-10-30</span>${html}</main></body></html>`;
fs.writeFileSync(path.join(docs,filename+'.html'),output);
console.log('HTML reader generated');
