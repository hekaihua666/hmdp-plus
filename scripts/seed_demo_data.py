"""本机学习数据：新增200店、20作者、50原创笔记及各店优惠信息。

读取现有本机配置但不输出任何凭据。首次导入在一个 MySQL 事务内完成，
ID清单写入 git 忽略的 target/demo-data，重复执行只校验清单和补齐 Redis。
ES必须随后显式运行 DemoIndexSyncTest，复用正式拼音与文档转换逻辑。
依赖：pip install pymysql pyyaml 'redis>=5'；仅供当前学习数据库使用。
"""
import argparse
import html
import json
import math
import os
import re
from datetime import datetime, timedelta
from pathlib import Path
from urllib.parse import urlparse
import pymysql
import redis
import yaml

ROOT = Path(__file__).resolve().parents[1]
STATE = ROOT / 'target/demo-data'
IMAGES = ROOT / 'src/main/resources/nginx-1.18.0/html/hmdp/imgs/demo'
MANIFEST = STATE / 'manifest.json'

# 按当前10类分别编写业态、价位与营业时间；全部名称为原创演示场景。
SCENES = {
  1: ('美食', 'DINING', '#dd7759', ['小巷杭帮菜','南风铜锅','谷雨茶餐厅','山禾日式食堂','青柚轻食','炊烟江南小馆','暮色烤肉','早安面馆','江畔鱼宴','豆花小院'], [32,48,68,88,118,158,218,268], '10:30-22:00'),
  2: ('KTV', 'MUSIC', '#9072c2', ['声浪KTV','星河音乐空间','麦芽欢唱','蓝调练歌房','音悦汇','夜航K歌馆','旋律派对','云朵音乐客厅','回声歌坊','森音小包厢'], [38,58,88,128,168,228], '12:00-02:00'),
  3: ('丽人·美发', 'STYLE', '#cb749b', ['木槿发型工作室','丝语造型','白茶理发馆','橙光发廊','轻风美发','青木头皮护理','初见发艺','拾光造型','弧线烫染','一剪之间'], [38,68,98,158,228,328], '10:00-21:00'),
  4: ('健身运动', 'MOVE', '#568bb0', ['起跑线健身','蓝弧游泳馆','轻跃普拉提','山岚攀岩','飞羽羽毛球馆','越野篮球空间','晨曦瑜伽','节拍团课','方寸乒乓球','氧气训练室'], [35,48,78,128,168,258], '07:00-22:00'),
  5: ('按摩·足疗', 'RELAX', '#69937d', ['竹里足道','舒缓小筑','松间足浴','清泉休闲','沐禾按摩','晚风足疗','竹影休息馆','云水足道','知足时光','栖木舒缓馆'], [49,78,98,138,188,258], '11:00-23:30'),
  6: ('美容SPA', 'SPA', '#839b78', ['溪畔SPA','莲间水疗','青檀芳香空间','露水护理馆','栖云SPA','清禾美容工作室','森屿水疗','浮光SPA','月白护理空间','山隐芳香馆'], [98,138,168,198,268,398], '10:00-21:30'),
  7: ('亲子游乐', 'PLAY', '#d9a653', ['小鹿探索乐园','纸飞机手作馆','彩虹积木空间','星球亲子工坊','树屋故事馆','麦田儿童乐园','小鲸科学馆','橡果陶艺','云朵蹦跳馆','小熊绘画屋'], [28,48,68,98,158,228], '09:30-20:00'),
  8: ('酒吧', 'NIGHT', '#6374a1', ['微醺小岛','月光音乐酒馆','青橘清吧','留白酒廊','星野精酿','晚潮小酒馆','转角无酒精吧','夜色爵士馆','河岸酒坊','琥珀会客厅'], [48,68,98,138,188,258], '18:00-02:00'),
  9: ('轰趴馆', 'PARTY', '#987eb5', ['纸飞机聚会馆','周末派对屋','橙子桌游空间','树屋朋友客厅','星光聚会馆','小岛轰趴','云间厨房派对','方糖桌游馆','晴日聚会屋','花园活动空间'], [48,68,98,138,188,258], '10:00-23:00'),
  10: ('美睫·美甲', 'NAILS', '#ca8b9c', ['指尖花园','月牙美甲','白桃甲艺','猫眼美睫','青柠美甲','拾色甲艺','晨露美睫','琥珀指艺','云朵美甲','柔光美睫'], [38,68,98,138,198,258], '10:00-21:00')
}
AUTHORS = ['阿宁散步','小满周末','柚子手记','北北探店','云朵爱咖啡','小林慢生活','晴子随记','阿澈在杭州','木木爱运动','米粒亲子记','麦子听歌','南瓜不熬夜','小雨看设计','青禾日记','小鹿观察员','桃桃吃一口','阿森骑行','书页与风','沐沐体验记','晚晚聊空间']

# 每个分类五个不同主题，避免把同一段内容只换店名重复50次。
NOTES = {
  1: [('雨天想吃一碗热面','工作日午休只有四十分钟，我选了靠窗的位置，先点小份面再加一碟蔬菜。汤面适合趁热吃，晚一点容易偏软。赶时间时建议先确认出餐时间。'),('两个人的铜锅晚饭怎么点','这次按两人份搭配蔬菜和肉片，没有直接选大套餐。蘸料先少放，再按口味调整。小桌放满后有些拥挤，聚餐前可以问一下是否有宽一点的位置。'),('茶餐厅里的半小时午休','我的关注点是座位与出餐节奏，不是拍照。靠门位置比较热闹，想安静聊天可以往里坐。奶茶建议自行选择甜度，主食分量可以先问服务人员。'),('周末烤肉的点单备忘','先点少量再加单比较好控制预算；不同肉片烤制时间不同，夹生的部分不要直接入口。吃完可以沿运河散步，店内味道较浓，外套放在收纳袋里更方便。'),('一个人吃饭也能认真一点','选小份套餐比临时拼几道菜省心。饭点前到店通常更从容，想吃清淡一点记得在下单时说明。我的预算含主食和一杯饮料，不含额外加菜。')],
  2: [('四个人唱歌，小包间够不够','这次关心的是包间大小和麦克风切换。先确认计费从入场还是开机开始，饮料与小食是否另收费用也要问清楚。唱歌间隙给嗓子留一点休息时间。'),('白天练歌比深夜聚会更适合我','周日下午的节奏比较轻松，我们把慢歌放在前面。预约时要确认到店时间与最晚结束时间，音量不必开得太高。短时段体验适合先熟悉点歌系统。'),('生日聚会前要确认的三件事','蛋糕能否自带、布置是否收费、人数上限，这三件事先问好。朋友迟到时可以安排轻松的背景音乐，不必一直唱。结账前核对额外服务项目。'),('歌单准备好，聚会少一点冷场','每人先挑两首熟悉的歌曲，轮流唱比霸麦更有趣。合唱选大家都知道的副歌，包间里留出走动空间。预约成功后保存时间说明，避免临时改场。'),('下班后的两小时音乐时间','没有安排熬夜，唱完刚好赶上回程。建议看清时段套餐与超时单价，同行人少时无需追求大包间。若设备有杂音，可以直接请工作人员协助更换。')],
  3: [('剪短发前，把参考图讲清楚','我准备了正面和侧面参考图，也说清楚每天愿意花多少时间打理。沟通长度比只说好看更有用。报价与是否含洗吹要在动剪刀前确认。'),('刘海修剪的小记录','这次只修刘海和发尾，没有做烫染。坐下后先观察光线，湿发与干发长度会有区别。赶时间可以预约简单项目，想保留长度要明确说出底线。'),('染发前先讨论维护成本','我重点问了颜色褪去后的变化、补染周期和后续护理，不凭一张照片就下决定。有过敏史应先告知并按产品要求做相应测试，项目价格以书面说明为准。'),('日常通勤发型不必太复杂','希望早上梳好就能出门，因此把需求放在易打理上。发量与发质不同，参考图只能辅助沟通。完成后可以请发型师演示日常吹风的基本步骤。'),('第一次进发型工作室怎么沟通','先讲预算，再讲希望保留的部分，最后确认项目清单。套餐外的护理应先问价格，不需要的项目直接拒绝。体验结束后记下实际耗时，方便下次预约。')],
  4: [('第一次团课，先跟上节奏','到店前确认课程强度与新手适配情况，提前十分钟熟悉场地。动作不舒服时及时停下并问教练，不跟别人比重量。结束后留一点时间补水和休息。'),('攀岩初体验，安全流程比打卡重要','先听场馆的安全讲解，再挑适合新手的路线。鞋子与保护装备按工作人员要求使用，不能凭视频自行模仿复杂动作。轮流尝试时注意落地区域。'),('羽毛球约场的小技巧','先确认场地是按小时还是整场计费，球和球拍是否自备。热身后再开始对打，双打搭档沟通站位能减少碰撞。带一套替换衣服，回程舒服一些。'),('早晨练瑜伽的安静时段','我更看重通风和课程节奏，没有追求高难度体式。初次到课可以说明自己的身体限制，由老师判断是否适合参与。课后不急着走，给自己几分钟放松。'),('游泳体验前的装备清单','泳帽、泳镜与更衣用品提前准备，入场前确认开放泳道和安全规则。不会游泳应选择有指导的课程，不独自进入深水区。场馆卫生和救生配置是重点。')],
  5: [('下班后的足浴体验备忘','我先确认水温和项目时长，感觉不适时直接提出调整。这里记录的是休闲体验，不是治疗建议。预约前看清项目是否包含加钟，结账时再核对一次。'),('安静环境比项目多更重要','想休息时，我会先问包间是否独立、背景音乐能否调低。个人用品与毛巾的更换流程可以当面确认。无需因为推荐就叠加多个项目，选择适合自己的时长。'),('周末约足疗，先看费用说明','同样写着六十分钟，是否包含等候与准备时间可能不同。预约时把人数和到店时间讲清楚，不适合的力度及时反馈。消费凭证留好，方便核对明细。'),('只想短暂休息的一次体验','没有追求很长的套餐，主要留意座椅舒适度与室内温度。若身体不舒服或有特殊情况，应先确认能否参与相关项目。休闲服务不能代替正规医疗。'),('双人体验怎么安排更从容','一起预约时确认是否能同时开始，以及房间安排。先喝少量温水，时间结束后缓一会儿再走。提前确定预算，额外项目需要双方同意再选择。')],
  6: [('把一小时留给安静的SPA空间','这次关注的是灯光、温度和流程沟通。开始前先说明是否对香氛或护肤品敏感，不喜欢的味道可以明确拒绝。体验是放松场景，没有所谓必然的疗效。'),('首次做护理，先读项目清单','预约时问清洁面、护理与休息的时间各占多少，避免把等待算进体验。护肤品信息可以先了解，容易敏感时不随意尝试新产品。预算与项目范围提前确认。'),('芳香空间的选择记录','比起浓烈气味，我更喜欢通风与柔和光线。项目开始前先沟通是否使用香氛，有过敏史应谨慎选择。这里是原创演示记录，不是实际商家的效果评价。'),('双人水疗预约的注意事项','先确认独立空间、可预约时段与改期规则。两个人的偏好不同，不必选完全相同的服务；任何不舒服的环节都应停止。服务介绍和最终消费清单要一致。'),('周末慢下来，但不冲动办卡','单次体验更适合了解环境与服务流程，先不做大额预付。询问清楚取消预约的条件，用品是否一次性或清洁更换。护理体验因人而异，不用宣传词替代判断。')],
  7: [('亲子手作，把时间留给孩子','选了流程简单的手作主题，没有要求成品一定漂亮。提前问适用年龄与是否需要家长陪同，小零件注意看管。衣服可以选耐脏一点的，现场节奏以孩子为主。'),('积木空间适合慢慢探索','我更关心不同年龄区是否分开、材料如何清洁。进入前说明活动规则，离开时带孩子一起收纳。若当天人多，可以先做安静的小任务再参加集体活动。'),('儿童乐园半日安排','先看开放时间和身高年龄限制，不把行程排得太满。补水、休息和卫生间位置都提前熟悉，家长不要只顾拍照。食品能否带入以现场规定为准。'),('陶艺体验不必急着做完','从揉泥开始比直接追求复杂造型更有参与感。烧制、上釉与取件时间要提前问，成品不能当天带走时保存凭证。桌面操作时陪孩子注意工具使用。'),('绘画课旁听的一点观察','我们选短时体验课，看看孩子是否喜欢环境。关注老师怎样引导表达，而不是比较谁画得像。材料是否另收费、陪同位置和结束时间都应先确认。')],
  8: [('不喝酒也可以坐坐的清吧','我选无酒精饮品，先问清是否存在最低消费。聊天更适合较安静的位置，音乐响起后可以换个时段再来。晚间出门安排好回程，饮酒后不要驾驶。'),('听爵士的晚上，提前选好座位','演出时段与普通营业时间可能不同，入场前确认座位与费用。我们把手机音量调低，尽量不影响别人听歌。点饮品按个人需求来，不为氛围勉强饮酒。'),('精酿初体验先从小杯了解','选择小杯更方便了解风味，饮品度数与价格提前看清。未成年人不应饮酒，成年人也要控制量。晚间消费之后选择公共交通或正规代驾返程。'),('朋友聊天的夜间小角落','相比拍照，我更喜欢不用提高声音的座位。问清服务费与预约保留时间，迟到时主动联系店员。这里描述的是演示场景，没有实际到店消费记录。'),('把夜生活缩短成一个轻松时段','没有安排转场，坐一会儿就回家。无酒精菜单和热饮选择也值得提前问问。同行人彼此照应，个人物品放在视线范围内，不接受来历不明的饮品。')],
  9: [('六个朋友的周末桌游局','我们先挑规则简单的合作游戏，熟悉后再换更复杂的。预约时确认房间人数上限、桌游清单与结束时间，饮料小食是否收费也要问清楚。聚会以大家都能参与为主。'),('生日轰趴先做一份分工表','有人负责带蛋糕，有人准备音乐，还有人安排回程。布置、自带食品和清洁费用都提前确认，不临时把费用交给一个人承担。活动结束后一起整理空间。'),('有厨房的聚会空间怎么选','先确认是否允许自己做饭、厨具清洁与食材存放要求。不会使用的设备先问工作人员，不擅自接电或操作。餐后清洁范围和押金退还规则应写清楚。'),('不赶场的一次朋友聚会','预留聊天时间比把所有娱乐项目都试一遍更轻松。桌游、观影与用餐区能否同时使用要先确认，夜间控制音量，避免影响周边。临时增加人数先征求场馆同意。'),('雨天聚会的室内备选','关注交通与活动区大小，先问清改期规则。设备发生故障时联系工作人员，不自行拆修。预算按场地、餐饮和额外服务分别计算，离开前核对个人物品。')],
  10: [('第一次美甲，先从简单配色开始','我带了日常衣服的颜色作参考，没有叠加很多装饰。项目包含卸甲还是需要另付费要先确认，工具清洁也值得留意。喜欢的款式可以讨论是否适合自己的指甲长度。'),('短甲也能有清爽的细节','这次重点是修形与单色，工作时更方便。操作过程中不舒服要及时反馈，不为了拍照勉强选择很长的款式。预约前问清预计时间，给自己留一点缓冲。'),('美睫体验前的沟通清单','先说明是否容易敏感，以及平时是否戴眼镜。材料、款式和操作步骤需要由工作人员讲清楚，有不适及时停止。这里记录的是演示场景，不承诺实际效果或持久时间。'),('换季想换一个低调的甲色','在自然光下比较色卡，更容易判断实际观感。询问补修和卸除的收费范围，避免只看低价套餐的标题。把消费项目写清楚，不临时追加自己不需要的装饰。'),('两个人一起预约的美甲时间','提前说明人数与项目，不一定能够同时结束。复杂图案应先确认是否能做及费用，参考图只用于沟通。保持舒适的坐姿，操作完成后按工作人员说明进行日常护理。')]
}

def resolve(value):
    """兼容 application.yaml 中的环境变量占位符，不把值写入清单。"""
    def sub(match):
        name, _, fallback = match[1].partition(':')
        return os.environ.get(name, fallback)
    return re.sub(r'\$\{([^}]+)\}', sub, str(value))

def connections():
    cfg = yaml.safe_load((ROOT/'src/main/resources/application.yaml').read_text(encoding='utf-8'))['spring']
    db = cfg['datasource']; url = urlparse(resolve(db['url']).removeprefix('jdbc:'))
    sql = pymysql.connect(host=url.hostname, port=url.port or 3306, user=resolve(db['username']),
        password=resolve(db['password']), database=url.path.strip('/'), charset='utf8mb4',
        cursorclass=pymysql.cursors.DictCursor, autocommit=False)
    rc = cfg['redis']
    cache = redis.Redis(host=resolve(rc['host']), port=int(resolve(rc['port'])), protocol=2,
        password=resolve(rc['password']) if rc.get('password') else None,
        db=int(resolve(rc.get('database', 0))), decode_responses=True)
    return sql, cache

def cover(filename, title, category, variant=0, caption='原创演示插画'):
    """用本地 SVG 制作分业态插画；不盗用真实商家照片，也不依赖远程图床。"""
    name, english, color, *_ = SCENES[category]
    shapes = {
      1:'<ellipse cx="360" cy="228" rx="115" ry="42"/><path d="M245 228 Q260 345 360 345 Q460 345 475 228"/><path d="M310 140 Q290 115 320 85 M355 140 Q335 115 365 85 M400 140 Q380 115 410 85"/>',
      2:'<rect x="330" y="120" width="70" height="142" rx="35"/><path d="M300 214 V244 Q300 302 365 302 Q430 302 430 244 V214 M365 302 V345 M325 345 H405"/>',
      3:'<circle cx="300" cy="260" r="32"/><circle cx="420" cy="260" r="32"/><path d="M321 237 L420 110 M399 237 L300 110 M340 212 L365 180"/>',
      4:'<path d="M290 228 H430"/><rect x="252" y="164" width="38" height="128" rx="8"/><rect x="430" y="164" width="38" height="128" rx="8"/><path d="M235 190 V267 M487 190 V267"/>',
      5:'<path d="M360 330 Q240 225 300 120 Q425 146 360 330 M360 330 Q490 257 432 166 Q355 193 360 330 M300 150 L360 330"/>',
      6:'<path d="M360 315 Q280 245 360 130 Q440 245 360 315 M360 315 Q220 290 245 190 Q340 200 360 315 M360 315 Q500 290 475 190 Q380 200 360 315 M280 345 H440"/>',
      7:'<rect x="263" y="250" width="96" height="96" rx="10"/><rect x="368" y="250" width="96" height="96" rx="10"/><rect x="316" y="146" width="96" height="96" rx="10"/><circle cx="310" cy="298" r="8"/><circle cx="416" cy="298" r="8"/>',
      8:'<path d="M270 143 H450 L360 250 Z M360 250 V338 M320 338 H400 M385 168 L427 105"/>',
      9:'<ellipse cx="305" cy="178" rx="47" ry="65"/><ellipse cx="416" cy="164" rx="47" ry="65"/><path d="M305 243 Q340 270 306 326 M416 229 Q383 270 420 326 M270 124 L258 100 M450 116 L468 95"/>',
      10:'<rect x="318" y="142" width="86" height="76" rx="9"/><rect x="292" y="218" width="138" height="117" rx="22"/><path d="M339 157 V200 M360 157 V200 M382 157 V200 M329 267 Q360 242 392 267"/>'}
    clean = title.replace('（演示）','')
    lines = [clean[i:i+16] for i in range(0, len(clean), 16)][:2]
    text = ''.join(f'<text x="42" y="{416+i*38}" font-size="{28 if i else 32}">{html.escape(line)}</text>' for i,line in enumerate(lines))
    svg = f'''<svg xmlns="http://www.w3.org/2000/svg" width="720" height="540" viewBox="0 0 720 540">
<rect width="720" height="540" fill="{color}"/><circle cx="{620-variant%5*24}" cy="105" r="180" fill="#fff" opacity=".09"/><circle cx="90" cy="300" r="160" fill="#fff" opacity=".05"/>
<g fill="none" stroke="#fff" stroke-width="9" stroke-linecap="round" stroke-linejoin="round" transform="translate({variant%3*7-7},0)">{shapes[category]}</g>
<g fill="#fff" font-family="Microsoft YaHei,Arial,sans-serif"><text x="42" y="54" font-size="17" letter-spacing="4">HANGZHOU · {english}</text>{text}<text x="42" y="502" font-size="15" opacity=".75">{html.escape(caption)}</text><text x="670" y="54" font-size="18" text-anchor="end">{variant+1:02}</text></g></svg>'''
    (IMAGES/filename).write_text(svg, encoding='utf-8')
    return '/imgs/demo/'+filename

def verify_manifest(cursor, manifest):
    """只有全部ID仍存在且保留演示标识，才把第二次执行视作幂等成功。"""
    for table, ids, field in [('tb_shop',manifest['shops'],'name'),('tb_user',manifest['users'],'nick_name'),('tb_blog',manifest['blogs'],'title'),('tb_voucher',manifest['vouchers'],'rules')]:
        cursor.execute(f'SELECT id,{field} AS marker FROM {table} WHERE id IN ({",".join(["%s"]*len(ids))})',ids)
        rows=cursor.fetchall()
        if len(rows)!=len(ids) or any('演示' not in row['marker'] for row in rows):
            raise RuntimeError('演示清单与数据库不一致，停止操作，不能覆盖已有业务数据：'+table)

def synchronize_cache(cursor, cache, manifest):
    """只补充本批新秒杀库存；附近查询使用ES，不再生成Redis GEO。"""
    # 不再geoadd，并不意味着删除既有shop:geo:*键；历史学习数据原样保留。
    # 新商户的位置通过后续显式ES同步进入geo_point索引，不能只导库后就宣称搜索已同步。
    for voucher_id in manifest['seckill']:
        # 重跑不重置已消耗的Redis库存；若已有购买记录但缓存丢失，交由人工核对。
        cursor.execute('SELECT COUNT(*) AS n FROM tb_voucher_order WHERE voucher_id=%s',(voucher_id,))
        if cursor.fetchone()['n'] == 0:
            cursor.execute('SELECT stock FROM tb_seckill_voucher WHERE voucher_id=%s',(voucher_id,))
            cache.set('seckill:stock:'+str(voucher_id), cursor.fetchone()['stock'], nx=True)

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apply',action='store_true',help='显式导入本机演示数据；默认只输出计划')
    args=parser.parse_args()
    if not args.apply:
        print('计划：新增200家店、20个演示作者、50篇原创笔记、200张普通展示券与10张体验秒杀券。使用 --apply 执行。')
        return
    STATE.mkdir(parents=True,exist_ok=True);IMAGES.mkdir(parents=True,exist_ok=True)
    sql, cache=connections()
    try:
        cache.ping()
        with sql.cursor() as cur:
            if MANIFEST.exists():
                manifest=json.loads(MANIFEST.read_text(encoding='utf-8'))
                verify_manifest(cur,manifest);synchronize_cache(cur,cache,manifest)
                print('已校验现有演示批次，未重复插入。');return
            # 显式检查现有分类及标识碰撞；不修改类目、不猜ID、不覆盖原作者。
            cur.execute('SELECT id,name FROM tb_shop_type ORDER BY id')
            types=cur.fetchall()
            if [t['id'] for t in types]!=list(SCENES) or any(t['name'] != SCENES[t['id']][0] for t in types):
                raise RuntimeError('当前分类ID或名称与种子场景不匹配，不能按首页显示顺序推断ID')
            cur.execute("SELECT COUNT(*) AS n FROM tb_user WHERE phone LIKE 'demo%'")
            if cur.fetchone()['n']: raise RuntimeError('演示账号标识已存在但没有清单，请先核对旧批次')
            cur.execute("SELECT COUNT(*) AS n FROM tb_shop WHERE name LIKE '%（演示）'")
            if cur.fetchone()['n']: raise RuntimeError('演示店铺已存在但没有清单，请先核对旧批次')
            manifest={'batch':'hmdp-demo-20261011-v1','createdAt':datetime.now().isoformat(),'shops':[],'users':[],'blogs':[],'vouchers':[],'seckill':[],'byType':{},'status':'prepared'}
            # 仅展示作者使用 demo 开头的占位号码，无法误发短信到现实用户，也不开放免鉴权登录。
            for i,name in enumerate(AUTHORS):
                avatar=f'avatar-{i+1}.svg';color=list(SCENES.values())[i%10][2]
                (IMAGES/avatar).write_text(f'<svg xmlns="http://www.w3.org/2000/svg" width="100" height="100"><rect width="100" height="100" rx="50" fill="{color}"/><text x="50" y="64" text-anchor="middle" fill="white" font-family="Microsoft YaHei" font-size="38">{name[0]}</text></svg>',encoding='utf-8')
                cur.execute('INSERT INTO tb_user(phone,nick_name,icon) VALUES(%s,%s,%s)',(f'demo{i+1:07}','演示·'+name,'/imgs/demo/'+avatar))
                uid=cur.lastrowid;manifest['users'].append(uid)
                cur.execute('INSERT INTO tb_user_info(user_id,city,introduce) VALUES(%s,%s,%s)',(uid,'杭州','原创演示账号，用于体验笔记、作者主页与社交数据关联。'))
            areas=['运河上街','远洋乐堤港','大关商圈','拱墅万达','水晶城','湖滨商圈','武林商圈','城西银泰','滨江商圈','西溪商圈']
            for category,scene in SCENES.items():
                name,english,color,brands,prices,hours=scene;shops=[]
                for j in range(20):
                    area=areas[j%10];brand=brands[j%10];store=f'{brand}（{area}{"东街" if j<10 else "西街"}店）（演示）'
                    # 14家在5km内，6家在5-10km内；均为模拟坐标，刻意覆盖各距离筛选。
                    radius=.25+j*.3 if j<14 else 5.5+(j-14)*.6
                    angle=(j*137+category*29)*math.pi/180
                    x=120.149993+radius*math.cos(angle)/(111.32*math.cos(math.radians(30.334229)))
                    y=30.334229+radius*math.sin(angle)/111.32
                    price=prices[(j+category)%len(prices)]
                    image=cover(f'shop-{category}-{j+1}.svg',brand,category,j,'杭州生活场景 · 原创演示插画')
                    second=cover(f'shop-{category}-{j+1}-space.svg',f'{brand} · 空间',category,j+20,'示意配图 · 非真实门店照片')
                    cur.execute('INSERT INTO tb_shop(name,type_id,images,area,address,x,y,avg_price,sold,comments,score,open_hours) VALUES(%s,%s,%s,%s,%s,%s,%s,%s,0,0,%s,%s)',
                        (store,category,image+','+second,area,f'杭州市 {area} 演示生活街{100+j*7+category}号（模拟地址）',round(x,6),round(y,6),price,[28,35,39,42,44,46,48,49][j%8],hours))
                    sid=cur.lastrowid;manifest['shops'].append(sid);shops.append({'id':sid,'name':store,'price':price,'area':area,'image':image})
                    amount=max(3000,min(20000,price*100));pay=amount*8//10
                    rules='演示券，仅用于项目功能体验，不可用于真实消费或核销。需提前确认适用项目；普通券暂未开放购买。'
                    cur.execute('INSERT INTO tb_voucher(shop_id,title,sub_title,rules,pay_value,actual_value,type,status) VALUES(%s,%s,%s,%s,%s,%s,0,1)',
                        (sid,f'{name}体验代金券（演示）',f'{amount//100}元展示券 · 使用前确认项目',rules,pay,amount))
                    manifest['vouchers'].append(cur.lastrowid)
                manifest['byType'][str(category)]=[s['id'] for s in shops]
                # 每类一张真正接入原Redis秒杀业务的体验券，初始库存30，无虚构购买记录。
                first=shops[0]
                cur.execute('INSERT INTO tb_voucher(shop_id,title,sub_title,rules,pay_value,actual_value,type,status) VALUES(%s,%s,%s,%s,990,2000,1,1)',
                    (first['id'],f'{name}限时体验券（演示）','每人限一张 · 仅体验抢购流程','演示秒杀券，仅验证抢购和订单流程，不可真实支付或核销。'))
                vid=cur.lastrowid;manifest['vouchers'].append(vid);manifest['seckill'].append(vid)
                cur.execute('INSERT INTO tb_seckill_voucher(voucher_id,stock,begin_time,end_time) VALUES(%s,30,%s,%s)',(vid,datetime.now()-timedelta(hours=1),datetime.now()+timedelta(days=30)))
                for j,(title,body) in enumerate(NOTES[category]):
                    shop=shops[j];author=manifest['users'][(category*3+j)%20]
                    cover_image=cover(f'note-{category}-{j+1}.svg',title,category,j,'原创生活笔记 · 演示内容')
                    content=f'<p><strong>【原创演示笔记】{html.escape(title)}</strong></p><p>场景：{html.escape(shop["name"])}，{shop["area"]}。参考预算约{shop["price"]}元，实际收费需以店内说明为准。</p><p>{body}</p><p>本文为学习项目编写的模拟内容，未在上述门店发生实际消费；配图为原创插画，不代表真实门店。</p>'
                    # 发布时间跨分类交错，首页同分时先看到不同主题，而不是整页同一业态。
                    cur.execute('INSERT INTO tb_blog(shop_id,user_id,title,images,content,liked,comments,create_time) VALUES(%s,%s,%s,%s,%s,0,0,%s)',
                        (shop['id'],author,title+'（演示）',cover_image+','+shop['image'],content,datetime.now()-timedelta(hours=j*10+category-1)))
                    manifest['blogs'].append(cur.lastrowid)
            # 先持久化本批ID，异常时事务回滚；清单留在target中，不包含登录凭据。
            MANIFEST.write_text(json.dumps(manifest,ensure_ascii=False,indent=2),encoding='utf-8')
            sql.commit()
            manifest['status']='committed'
            MANIFEST.write_text(json.dumps(manifest,ensure_ascii=False,indent=2),encoding='utf-8')
            synchronize_cache(cur,cache,manifest)
            print('完成：新增200家演示店铺、20个作者、50篇不同笔记、210张演示券（含10张秒杀券）。')
            print('下一步：显式运行 DemoIndexSyncTest 同步ES，并启动后端验收。')
    except Exception:
        sql.rollback()
        # 事务尚未提交的清单不能被下次执行误认成成功；已提交清单必须保留供恢复。
        if MANIFEST.exists() and json.loads(MANIFEST.read_text(encoding='utf-8')).get('status')=='prepared':
            with sql.cursor() as cur:
                cur.execute('SELECT COUNT(*) AS n FROM tb_shop WHERE id IN ('+','.join(['%s']*len(manifest['shops']))+')',manifest['shops'])
                if cur.fetchone()['n']==0: MANIFEST.unlink()
        raise
    finally:
        sql.close();cache.close()

if __name__ == '__main__': main()
