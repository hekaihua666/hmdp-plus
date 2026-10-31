"""重建讲义流程图。图源保留为 Mermaid 与 draw.io，产物写在本目录。"""
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET

base = Path(__file__).resolve().parent
renderer = Path('/path/to/local-file)
diagrams = {
'02-user-journey': '''flowchart LR
 A["发现门店"] --> B["关键词与条件筛选"]
 B --> C["查看门店与优惠券"]
 C --> D["登录后申请抢购"]
 D --> E["查询申请结果"]
 C --> F["阅读探店笔记"]
 F --> G["点赞或关注作者"]
''',
'03-login': '''sequenceDiagram
 participant B as 浏览器
 participant A as 登录接口
 participant R as Redis
 participant M as MySQL
 B->>A: 提交手机号与验证码
 A->>R: GET login:code:手机号
 A->>M: 查询或创建用户
 A->>R: HSET login:token:随机令牌
 A-->>B: 返回令牌
 B->>A: 请求携带 authorization
 A->>R: 读取会话并刷新有效期
 A->>A: 写入 UserHolder，执行权限检查
 A-->>B: 返回业务结果，清理 ThreadLocal
''',
'04-cache': '''flowchart TB
 A["查询门店详情"] --> B{"Redis 内容？"}
 B -->|正常 JSON| C["反序列化返回"]
 B -->|空字符串| D["返回不存在"]
 B -->|键不存在| E["查询 MySQL"]
 E --> F{"门店存在？"}
 F -->|存在| G["缓存 JSON，30 分钟"]
 F -->|不存在| H["缓存空字符串，2 分钟"]
 G --> I["返回门店"]
 H --> D
''',
'05-search': '''flowchart TB
 A["关键词、分类、价格、评分、坐标"] --> B["ShopSearchDTO 参数检查"]
 B --> C["must：中文或拼音相关性"]
 B --> D["filter：分类、范围、距离"]
 C --> E["同一个 bool 查询"]
 D --> E
 E --> F["排序与 ID 次排序"]
 F --> G["分页、高亮、区间聚合"]
 G --> H["ES 返回列表、总数和统计"]
''',
'06-seckill': '''sequenceDiagram
 participant U as 用户
 participant A as HTTP 入口
 participant D as MySQL 申请表
 participant T as 后台任务
 participant R as Redis Lua
 participant Q as RocketMQ
 participant C as 落单消费者
 U->>A: 申请抢购
 A->>R: 只读预检
 A->>D: 提交 INIT 申请
 A-->>U: 返回申请编号，尚未落单
 T->>D: 独立提交 UNKNOWN 预占意图
 T->>R: 幂等预占
 T->>D: 记录 READY 与 HELD
 T->>Q: 发送固定身份消息
 Q->>C: 投递下单通知
 C->>D: 同事务扣库存、写订单、标成功
 U->>A: 查询申请状态
 A-->>U: SUCCEEDED 才显示抢购成功
''',
'07-request-state': '''stateDiagram-v2
 [*] --> INIT
 INIT --> READY: 预占确认成功
 INIT --> CANCELLING: 拒绝或处理超时
 READY --> SUCCEEDED: 事务落单成功
 READY --> CANCELLING: 超时、业务拒绝或死信裁决
 CANCELLING --> FAILED: 预占释放确认完成
 SUCCEEDED --> [*]
 FAILED --> [*]
''',
'08-uncertain': '''flowchart TB
 A["事务提交 UNKNOWN"] --> B["调用 Redis 预占"]
 B --> C{"取得明确结果？"}
 C -->|成功| D["READY 与 HELD"]
 C -->|网络超时| E["保留 INIT 与 UNKNOWN"]
 E --> F["同一 orderId 恢复核查"]
 F --> G{"凭据是否充分？"}
 G -->|可确认| H["继续推进或补偿"]
 G -->|凭据缺失| I["保留状态，等待核查"]
''',
'09-outbox': '''sequenceDiagram
 participant B as 商户后台
 participant M as MySQL
 participant R as Redis
 participant T as 发送任务
 participant Q as RocketMQ
 participant E as Elasticsearch
 participant C as 同步消费者
 B->>M: 同事务更新门店与 Outbox revision
 M-->>B: 提交成功
 B->>R: 提交后删除详情缓存
 T->>M: 按 revision 领取待发送记录
 T->>Q: 同步发送 shopId 与发送版本
 T->>M: 按 revision 确认为 SENT
 Q->>C: 投递门店同步通知
 C->>M: 单条联表查询读取数据与数据版本
 C->>E: external_gte 覆盖写
 E-->>C: 成功或旧版本 409
''',
'10-version': '''sequenceDiagram
 participant A as 消费者 A
 participant D as MySQL
 participant B as 消费者 B
 participant E as Elasticsearch
 A->>D: 读取旧数据与版本 V
 D->>D: 商户更新，版本变为 V+1
 B->>D: 读取新数据与版本 V+1
 B->>E: 写入 V+1，成功
 A->>E: 迟到写入 V
 E-->>A: 409，保留新数据
''',
'11-follow': '''sequenceDiagram
 participant B as 浏览器
 participant S as FollowService
 participant M as MySQL
 B->>S: 确保关注指定作者
 S->>M: 事务首次读：锁当前用户行
 S->>M: 查询作者是否存在
 S->>M: 查询当前关注关系
 S->>M: 不存在才插入
 M-->>S: 事务提交，释放行锁
 S-->>B: 返回目标关注状态
'''
}

# 本轮增补的四张图，仍使用同一套渲染参数。
diagrams.update({'12-check-reserve': 'flowchart TB\n A["HTTP 请求：券与登录校验"] --> B["check：只读检查时间、库存和用户归属"]\n B -->|返回 0| C["生成 orderId，提交 INIT 申请"]\n C --> D["后台 T1：提交 UNKNOWN 意图"]\n D --> E["后台 T2：重查申请状态"]\n E --> F["reserve：按 orderId 幂等预占"]\n F -->|确认成功| G["记录 READY 与 HELD"]\n', '13-two-transactions': 'sequenceDiagram\n participant W as 后台 prepare\n participant D as MySQL\n participant R as Redis\n W->>D: BEGIN T1，锁申请并写 UNKNOWN\n D-->>W: COMMIT T1，释放行锁\n W->>D: BEGIN T2，重新锁行和检查状态\n W->>R: reserve，同一 orderId\n alt 收到预占成功\n R-->>W: 返回 0\n W->>D: 保存 READY 与 HELD\n D-->>W: COMMIT T2\n else 预占响应未知或后续异常\n W->>D: ROLLBACK T2\n D-->>W: 保留 T1 已提交的 UNKNOWN\n end\n', '14-watchdog': 'sequenceDiagram\n participant T as 初始化线程\n participant W as 客户端续期任务\n participant R as Redis\n T->>R: lock，持有者计数与租期\n R-->>T: 获取成功\n T->>W: 无显式租期，安排续期\n W->>R: 校验持有者，延长 TTL\n R-->>W: 续期成功，安排下轮\n T->>T: 完成库存初始化\n T->>R: unlock，校验持有者并释放\n T->>W: 取消对应续期登记\n', '15-upload': 'sequenceDiagram\n participant B as 浏览器\n participant N as Nginx\n participant J as Java 上传接口\n participant F as 共享静态目录\n B->>N: POST 上传 FormData\n N->>J: 转发到 /upload/blog\n J->>F: UUID 命名与两级目录落盘\n F-->>J: 文件保存完成\n J-->>N: 返回 /blogs/目录/文件名\n N-->>B: 返回上传结果\n B->>B: 路径前补 /imgs，保存笔记地址\n B->>N: GET /imgs/blogs/目录/文件名\n N->>F: 读取同一个文件\n F-->>N: 图片内容\n N-->>B: 静态图片响应\n'})

# 为所有流程图统一配色；时序与状态图保持渲染器主题，避免非法混用 classDef。
for name, source in diagrams.items():
    path = base / (name + '.mmd')
    path.write_text(source, encoding='utf-8')
    for extension in ('svg', 'png'):
        command = ['node', str(renderer), '--input', str(path), '--output', str(base/(name+'.'+extension)),
                   '--bg', '#ffffff', '--font', 'Microsoft YaHei', '--padding', '32',
                   '--fg','#17345c','--muted','#39516c','--line','#607d9f','--accent','#2a8a76',
                   '--surface','#edf4fb','--border','#7094ba']
        if extension == 'png':
            command += ['--format', 'png', '--width', '1800']
        subprocess.run(command, check=True, capture_output=True)
    print(name + ': rendered')

# 架构总览用原生 draw.io 图形精确布局，方便后续拖拽编辑。
model = ET.Element('mxGraphModel', page='1', pageWidth='1400', pageHeight='1000', background='#ffffff')
root = ET.SubElement(model, 'root')
ET.SubElement(root, 'mxCell', id='0'); ET.SubElement(root, 'mxCell', id='1', parent='0')
def box(key, label, x, y, w, h, color='#eaf2ff', font=21):
    cell=ET.SubElement(root,'mxCell',id=key,value=label,vertex='1',parent='1',
        style=f'rounded=1;arcSize=14;html=1;whiteSpace=wrap;fontFamily=Microsoft YaHei;fontSize={font};fillColor={color};strokeColor=#8ca4c5;fontColor=#17345c;spacing=12;')
    ET.SubElement(cell,'mxGeometry',x=str(x),y=str(y),width=str(w),height=str(h),attrib={'as':'geometry'})
def arrow(key, source, target, label=''):
    cell=ET.SubElement(root,'mxCell',id=key,value=label,edge='1',source=source,target=target,parent='1',
        style='edgeStyle=orthogonalEdgeStyle;rounded=1;html=1;endArrow=block;strokeColor=#64748b;strokeWidth=2;fontFamily=Microsoft YaHei;fontSize=16;labelBackgroundColor=#ffffff;')
    ET.SubElement(cell,'mxGeometry',relative='1',attrib={'as':'geometry'})
box('title','HMDP · 从门店发现到可靠落单',60,25,1280,70,'#f3f6fb',32)
box('c','消费者端<br>找店 / 看笔记 / 抢购',60,145,360,100)
box('b','商户端<br>编辑本人门店 / 发券',520,145,360,100)
box('p','平台端<br>商户账号查询 / 启停',980,145,360,100)
box('n','Nginx :8080　静态页面 + /api 反向代理',260,315,880,65,'#f3f6fb')
box('app','Spring Boot :8081 · 单体后端<br>独立身份拦截 → Controller → Service → Mapper',260,450,880,110,'#e7f5ed',23)
box('mysql','MySQL :3307<br>业务事实 / 申请状态 / Outbox',40,680,300,115)
box('redis','Redis :6379<br>会话 / 缓存 / 预占 / 点赞',380,680,300,115)
box('es','Elasticsearch :9200<br>门店检索 / 补全 / 地理筛选',720,680,300,115)
box('mq','RocketMQ<br>订单通知 / 门店同步通知',1060,680,300,115)
for i,key in enumerate(('c','b','p')):arrow('u'+str(i),key,'n')
arrow('http','n','app','HTTP')
for i,key in enumerate(('mysql','redis','es','mq')):arrow('d'+str(i),'app',key)
box('note','当前部署：后端在 Windows，基础服务由 Compose 管理；图中模块不等于独立微服务。',60,890,1280,62,'#fff4d6',19)
ET.ElementTree(model).write(base/'01-architecture.drawio',encoding='utf-8',xml_declaration=True)
ET.parse(base/'01-architecture.drawio')
print('01-architecture: XML validated')
