"""把本批演示店铺及关联笔记的示意图替换为本地分类摄影素材。

默认只显示计划；--download 只下载图片；--apply --backup-dir <目录> 才改数据。
依赖与 seed_demo_data.py 相同。仅处理原导入清单，保护课程数据和自定义图片。
素材出处与许可见 demo_photo_catalog.json；不是这些虚构店铺的真实实景。
"""
import argparse
import hashlib
import json
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path
from urllib.request import Request, urlopen

from seed_demo_data import ROOT, MANIFEST, connections

CATALOG = Path(__file__).with_name('demo_photo_catalog.json')
WEB = ROOT / 'src/main/resources/nginx-1.18.0/html/hmdp'
PHOTO_DIR = WEB / 'imgs/demo/photos'

def read_json(path):
    return json.loads(path.read_text(encoding='utf-8'))

def fetch_photo(photo):
    """保存网页来源对应的压缩JPEG；不依赖第三方网站在页面运行时可用。"""
    destination = WEB / photo['localPath'].lstrip('/')
    if destination.exists():
        data = destination.read_bytes()
    else:
        photo_id = photo['photoId']
        url = f'https://images.pexels.com/photos/{photo_id}/pexels-photo-{photo_id}.jpeg?auto=compress&cs=tinysrgb&w=960'
        request = Request(url, headers={'User-Agent': 'Mozilla/5.0', 'Referer': photo['url']})
        with urlopen(request, timeout=30) as response:
            data = response.read()
            if not response.headers.get('Content-Type', '').startswith('image/'):
                raise RuntimeError(f'素材{photo_id}没有返回图片')
        if not data.startswith(b'\xff\xd8') or len(data) < 2000:
            raise RuntimeError(f'素材{photo_id}不是完整的JPEG响应')
        destination.write_bytes(data)
    return {'photoId': photo['photoId'], 'bytes': len(data), 'sha256': hashlib.sha256(data).hexdigest()}

def select_images(shop, ordinal, pool):
    """常见细分业态按内容匹配；每店选择三张不同素材，不复制同一套配图。"""
    name = shop['name']
    keywords = {
        '游泳': 'pool', '攀岩': 'climbing', '羽毛球': 'badminton',
        '篮球': 'basketball', '乒乓球': 'table tennis', '瑜伽': 'yoga',
        '普拉提': 'yoga', '健身': 'gym', '训练': 'gym', '团课': 'gym',
        '美睫': 'eyelash', '陶艺': 'pottery', '绘画': 'painting',
        '手作': 'workshop', '工坊': 'workshop', '儿童乐园': 'playground',
        '探索乐园': 'playground', '蹦跳': 'playground', '桌游': 'board game',
    }
    term = next((term for word, term in keywords.items() if word in name), '')
    matches = [p for p in pool if term and term in (p['title']+' '+p['description']).lower()]
    # 美甲店避免把美睫操作当成主要业务；餐饮店封面优先环境照。
    if shop['type_id'] == 10 and not term:
        matches = [p for p in pool if 'eyelash' not in p['title'].lower()]
    if shop['type_id'] == 1:
        matches = [p for p in pool if any(t in p['title'].lower() for t in ['interior', 'inside'])]
    selected_pool = matches or pool
    first = selected_pool[ordinal % len(selected_pool)]
    others = [p for p in selected_pool if p['photoId'] != first['photoId']]
    others += [p for p in pool if p['photoId'] != first['photoId'] and p not in others]
    # 环境与体验照片组合，允许分类素材复用，但每家店的组合保持不同。
    second = others[(ordinal // len(selected_pool)) % len(others)]
    third = next(p for p in others[(ordinal+3) % len(others):] + others if p != second)
    return ','.join(p['localPath'] for p in [first, second, third])

def apply_photos(by_type, backup_dir):
    """MySQL先提交，再定向同步ES和失效Redis；失败可重跑补齐，不声称跨库事务。"""
    manifest = read_json(MANIFEST)
    ids = manifest['shops']
    if len(ids) != 200 or len(set(ids)) != 200:
        raise RuntimeError('原200家演示店铺清单不完整，停止处理')
    backup_dir.mkdir(parents=True, exist_ok=True)
    sql, cache = connections()
    try:
        cache.ping()
        with sql.cursor() as cursor:
            cursor.execute('SELECT id,name,type_id,images FROM tb_shop WHERE id IN ('+','.join(['%s']*len(ids))+') ORDER BY id', ids)
            shops = cursor.fetchall()
            cursor.execute('SELECT id,shop_id,title,images,content FROM tb_blog WHERE id IN ('+','.join(['%s']*len(manifest['blogs']))+') ORDER BY id', manifest['blogs'])
            blogs = cursor.fetchall()
            if len(shops) != 200 or any('（演示）' not in s['name'] for s in shops):
                raise RuntimeError('店铺清单或演示标识不一致，停止处理')
            # 保留第一次修改前的图片字段，避免重复执行把回退快照覆盖成新值。
            snapshot = backup_dir / 'images-before.json'
            if not snapshot.exists():
                snapshot.write_text(json.dumps({'shops': shops, 'blogs': blogs}, ensure_ascii=False, indent=2), encoding='utf-8')
            image_map = {}
            for category, shop_ids in manifest['byType'].items():
                pool = by_type[category]
                for ordinal, shop_id in enumerate(shop_ids):
                    shop = next(s for s in shops if s['id'] == shop_id)
                    if str(shop['type_id']) != category:
                        raise RuntimeError('分类与原导入清单不一致')
                    paths = shop['images'].split(',')
                    # 用户上传或手动修改过的照片原样保留；不覆盖其他来源的资产。
                    if not all(p.startswith(('/imgs/demo/shop-', '/imgs/demo/scene-', '/imgs/demo/photos/')) for p in paths):
                        continue
                    image_map[shop_id] = select_images(shop, ordinal, pool)
                    if shop['images'] != image_map[shop_id]:
                        cursor.execute('UPDATE tb_shop SET images=%s WHERE id=%s AND images=%s AND name=%s', (image_map[shop_id], shop_id, shop['images'], shop['name']))
                        if cursor.rowcount != 1:
                            raise RuntimeError('店铺图片被并发修改，撤回本次数据库修改')
            changed_blogs = 0
            for blog in blogs:
                if blog['shop_id'] not in image_map or '（演示）' not in blog['title']:
                    continue
                if not all(p.startswith(('/imgs/demo/note-', '/imgs/demo/shop-', '/imgs/demo/photos/')) for p in blog['images'].split(',')):
                    continue
                # 关联笔记复用本店同一组摄影素材，保持首页、关联笔记与详情内容一致。
                content = blog['content'].replace('配图为原创插画，不代表真实门店。', '配图来自Pexels摄影素材，仅作场景演示，非本店实景，也不代表图中人物的推荐。')
                if blog['images'] != image_map[blog['shop_id']] or content != blog['content']:
                    cursor.execute('UPDATE tb_blog SET images=%s,content=%s WHERE id=%s AND images=%s AND content=%s', (image_map[blog['shop_id']], content, blog['id'], blog['images'], blog['content']))
                    if cursor.rowcount != 1:
                        raise RuntimeError('笔记图片被并发修改，撤回本次数据库修改')
                changed_blogs += 1
            sql.commit()
        # ShopDoc.images直接来自Shop.images；只更新这一字段，保留其余索引信息。
        lines = []
        for shop_id, images in image_map.items():
            lines += [json.dumps({'update': {'_index': 'hmdp_shop', '_id': str(shop_id)}}), json.dumps({'doc': {'images': images}}, ensure_ascii=False)]
        if lines:
            request = Request('http://127.0.0.1:9200/_bulk?refresh=wait_for', data=('\n'.join(lines)+'\n').encode('utf-8'), headers={'Content-Type': 'application/x-ndjson'}, method='POST')
            with urlopen(request, timeout=30) as response:
                result = json.load(response)
            if result.get('errors'):
                raise RuntimeError('MySQL已提交但部分ES图片同步失败，请重跑本脚本补齐')
            cache.delete(*['cache:shop:'+str(shop_id) for shop_id in image_map])
        report = {'shops': len(image_map), 'blogs': changed_blogs, 'skippedCustomShops': 200-len(image_map), 'images': image_map}
        (backup_dir/'applied.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
        print(f"已更新{len(image_map)}家演示店、{changed_blogs}篇关联笔记，并同步ES及清理对应店铺缓存。")
    finally:
        sql.rollback()
        sql.close()
        cache.close()

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--download', action='store_true')
    parser.add_argument('--apply', action='store_true')
    parser.add_argument('--backup-dir', type=Path)
    args = parser.parse_args()
    by_type = read_json(CATALOG)['byType']
    photos = {p['photoId']: p for pool in by_type.values() for p in pool}
    print(f'计划：使用{len(photos)}张分类摄影素材更新200家演示店铺和关联笔记。')
    if args.apply and not args.backup_dir:
        parser.error('--apply必须指定--backup-dir保存旧图片字段')
    if args.download:
        PHOTO_DIR.mkdir(parents=True, exist_ok=True)
        downloaded, failed = [], []
        with ThreadPoolExecutor(max_workers=6) as workers:
            pending = {workers.submit(fetch_photo, photo): photo['photoId'] for photo in photos.values()}
            for future in as_completed(pending):
                try:
                    downloaded.append(future.result())
                except Exception as error:
                    failed.append(pending[future])
                    print(f'下载失败：素材{pending[future]}，{type(error).__name__}', flush=True)
        if failed:
            raise RuntimeError(f'以下素材未下载，尚未修改数据库：{failed}')
        (PHOTO_DIR/'download-manifest.json').write_text(json.dumps(downloaded, indent=2), encoding='utf-8')
        print(f'已保存{len(downloaded)}张JPEG，共{sum(p["bytes"] for p in downloaded)//1024}KB。')
    if args.apply:
        if not all((WEB/p['localPath'].lstrip('/')).exists() for p in photos.values()):
            raise RuntimeError('素材未完整下载，先执行--download')
        apply_photos(by_type, args.backup_dir)

if __name__ == '__main__':
    main()
