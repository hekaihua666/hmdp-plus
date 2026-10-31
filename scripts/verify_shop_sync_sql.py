"""在 MySQL 连接级临时表上执行真实 Mapper SQL，不改业务记录。需要本机 hmdp-mysql 容器。"""
from pathlib import Path
import re
import subprocess

root = Path(__file__).resolve().parents[1]
source = (root / 'src/main/java/com/hmdp/mapper/ShopSyncEventMapper.java').read_text(encoding='utf-8')


def sql(method, **params):
    """读取 Java 注解中的 SQL，绑定本脚本固定的测试参数。"""
    for match in re.finditer(r'@(Insert|Update|Select)\(((?:"[^"]*"|\s|\+)+)\)\s*[\w<>]+\s+(\w+)\(', source, re.S):
        if match[3] == method:
            statement = ''.join(re.findall(r'"([^"]*)"', match[2]))
            for key, value in params.items():
                statement = statement.replace('#{' + key + '}', str(value))
            return statement + ';'
    raise ValueError(method)


# 临时表仅当前连接可见，覆盖同名业务表；连接结束自动删除。
create = (root / 'database/migrations/20261030-shop-sync-outbox.sql').read_text(encoding='utf-8')
create = create.replace('CREATE TABLE IF NOT EXISTS', 'CREATE TEMPORARY TABLE')
upgrade = (root / 'database/migrations/20261030-shop-sync-revision.sql').read_text(encoding='utf-8')
commands = [create, upgrade, 'CREATE TEMPORARY TABLE tb_shop(id BIGINT PRIMARY KEY,name VARCHAR(50));',
            "INSERT INTO tb_shop VALUES(1,'fixture');", 'START TRANSACTION;', sql('upsert', shopId=1),
            'ROLLBACK;', "SELECT 'rollback',COUNT(*)=0 FROM tb_shop_sync_event;", sql('upsert', shopId=1),
            'SET @old=(SELECT revision FROM tb_shop_sync_event WHERE shop_id=1);',
            sql('claimSend', id=1, revision='@old'), "SELECT 'claim',ROW_COUNT()=1;",
            sql('upsert', shopId=1), sql('sent', id=1, revision='@old'),
            "SELECT 'stale_ack',ROW_COUNT()=0;", sql('sendFailed', id=1, revision='@old', reason="'old error'"),
            "SELECT 'stale_failure',ROW_COUNT()=0;",
            "SELECT 'new_pending',state='PENDING' AND revision=@old+1 AND last_error IS NULL FROM tb_shop_sync_event;",
            sql('sent', id=1, revision='@old+1'), "SELECT 'new_ack',ROW_COUNT()=1;",
            sql('consumeFailed', id=1, revision='@old+1', reason="'ES unavailable'"),
            "SELECT 'sent_dlq_recorded',state='SENT' AND last_error='ES unavailable' FROM tb_shop_sync_event;",
            sql('consumeFailed', id=1, revision='@old', reason="'stale DLQ'"),
            "SELECT 'stale_dlq',ROW_COUNT()=0;",
            "SELECT 'paired_revision',sync_revision=@old+1 FROM (" + sql('shopForSync', id=1).rstrip(';') + ") paired;"]
result = subprocess.run(['docker', 'exec', '-i', 'hmdp-mysql', 'sh', '-c',
                         'exec mysql -N -B -uroot -p"$MYSQL_ROOT_PASSWORD" hmdp'],
                        input='\n'.join(commands), text=True, encoding='utf-8', capture_output=True, check=True)
rows = [row.split('\t') for row in result.stdout.strip().splitlines()]
assert len(rows) == 9 and all(value == '1' for _, value in rows), result.stdout
for label, _ in rows:
    print(label + ': PASS')
