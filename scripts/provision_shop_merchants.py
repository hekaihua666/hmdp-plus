"""为当前学习库里未绑定的门店补商户账号，不改已有账号或门店资料。

默认只检查；明确传 --apply 才写库。复用本机配置，全部变更在一个事务内完成。
账号为 shop_<门店ID>，独立随机密码采用 BCrypt；凭据只写本机清单，不打印。
重复执行仅处理仍未绑定的店铺，不能重置密码或接管同名的既有账号。
"""
import argparse
import csv
import json
import secrets
from datetime import datetime
from pathlib import Path

import bcrypt
from seed_demo_data import ROOT, connections


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apply', action='store_true', help='明确执行账号创建与归属绑定')
    parser.add_argument('--output-dir', type=Path, default=ROOT / 'target/merchant-accounts',
                        help='本机快照与账号清单目录，不应加入Git')
    args = parser.parse_args()
    sql, cache = connections()
    account_file = None
    commit_started = False
    try:
        with sql.cursor() as cursor:
            # 锁定店铺后再创建账号，避免并发初始化把同一店绑定到不同账号。
            cursor.execute('SELECT * FROM tb_shop ORDER BY id' + (' FOR UPDATE' if args.apply else ''))
            shops = cursor.fetchall()
            cursor.execute('SELECT * FROM tb_merchant ORDER BY id')
            merchants = cursor.fetchall()
            merchant_ids = {row['id'] for row in merchants}
            owned = [row['owner_id'] for row in shops if row['owner_id'] is not None]
            if len(owned) != len(set(owned)) or any(owner not in merchant_ids for owner in owned):
                raise RuntimeError('已有归属存在重复或指向不存在的账号，停止写入，请先核实')
            pending = [row for row in shops if row['owner_id'] is None]
            usernames = {row['username'] for row in merchants}
            if any('shop_' + str(row['id']) in usernames for row in pending):
                raise RuntimeError('待创建账号与既有账号重名，停止写入，不自动接管账号')
            print(json.dumps({'shops': len(shops), 'merchants': len(merchants),
                              'unbound': len(pending), 'apply': args.apply}, ensure_ascii=False))
            if not args.apply or not pending:
                return

            args.output_dir.mkdir(parents=True, exist_ok=True)
            batch = datetime.now().strftime('%Y%m%d-%H%M%S-%f')
            # 写库前保留完整本机恢复快照。含密码哈希，只存本机，不输出到日志。
            snapshot = args.output_dir / ('before-' + batch + '.json')
            snapshot.write_text(json.dumps({'shops': shops, 'merchants': merchants},
                                          ensure_ascii=False, default=str, indent=2), encoding='utf-8')
            accounts = []
            for shop in pending:
                username = 'shop_' + str(shop['id'])
                password = secrets.token_urlsafe(12)
                name = shop['name'].replace('（演示）', '')[:64]
                password_hash = bcrypt.hashpw(password.encode('utf-8'), bcrypt.gensalt(rounds=10)).decode('ascii')
                cursor.execute('INSERT INTO tb_merchant (username,password_hash,name,status,session_version) '
                               'VALUES (%s,%s,%s,1,0)', (username, password_hash, name))
                merchant_id = cursor.lastrowid
                cursor.execute('UPDATE tb_shop SET owner_id=%s WHERE id=%s AND owner_id IS NULL',
                               (merchant_id, shop['id']))
                if cursor.rowcount != 1:
                    raise RuntimeError('店铺归属已变化，整批回滚，不覆盖现有绑定')
                accounts.append({'门店ID': shop['id'], '门店名称': name, '商户ID': merchant_id,
                                 '登录账号': username, '初始密码': password})

            # 先保存清单再提交，避免账号创建成功却遗失随机密码。提交失败会撤销本批清单。
            account_file = args.output_dir / ('merchant-accounts-' + batch + '.csv')
            with account_file.open('x', encoding='utf-8-sig', newline='') as stream:
                writer = csv.DictWriter(stream, fieldnames=accounts[0].keys())
                writer.writeheader()
                writer.writerows(accounts)
            commit_started = True
            sql.commit()
            print(json.dumps({'created': len(accounts), 'credentials_file': str(account_file)}, ensure_ascii=False))
    except Exception:
        sql.rollback()
        # 提交前失败可确认未落库；提交时断连则结果未知，保留清单供人工核对，避免遗失密码。
        if not commit_started and account_file is not None and account_file.exists():
            account_file.unlink()
        raise
    finally:
        sql.rollback()
        sql.close()
        cache.close()


if __name__ == '__main__':
    main()
