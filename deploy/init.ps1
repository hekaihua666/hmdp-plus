# HMDP 一键初始化脚本（幂等，可反复执行）。
#
# 负责「结构初始化」三件事：建表、建 RocketMQ Topic、建 ES 索引。
# 前提：先运行 start-all.bat 把 MySQL / Broker / ES 容器都拉起来。
# 数据初始化（ES 全量导入、演示数据）见 deploy/README.md 的「可选步骤」。
#
# 运行方式（Windows PowerShell 5.1 / PowerShell 7 均可）：
#   powershell -File deploy\init.ps1
#   pwsh -File deploy/init.ps1

# 不要用 Stop：MySQL 会把「密码写在命令行」的无害警告打到 stderr，
# 在 5.1 下配合 Stop 会被当成致命错误。这里用 Continue，改靠 $LASTEXITCODE 判断成败。
$ErrorActionPreference = 'Continue'
# 关键：把中文 SQL 按 UTF-8 传给 docker 容器，否则 GBK 乱码会报 ERROR 1064。
$OutputEncoding = [System.Text.Encoding]::UTF8

$ProjectRoot = Split-Path -Parent $PSScriptRoot
$MigrationDir = Join-Path $ProjectRoot 'database\migrations'

# 统一执行「管道进容器的 MySQL」命令：成功返回 stdout，失败抛异常。
function Invoke-MySqlPipe([string]$sqlText) {
    $sqlText | docker exec -i hmdp-mysql sh -c 'exec mysql -uroot -p"$MYSQL_ROOT_PASSWORD" --default-character-set=utf8mb4 hmdp' 2>&1 | Out-String
    if ($LASTEXITCODE -ne 0) {
        throw "MySQL 命令失败，退出码 $LASTEXITCODE"
    }
}

Write-Host ''
Write-Host '== [0/3] 前置检查：容器是否在运行 ==' -ForegroundColor Cyan
$required = @('hmdp-mysql', 'hmdp-broker', 'hmdp-es')
foreach ($c in $required) {
    $state = docker inspect -f '{{.State.Running}}' $c 2>$null
    if ($state -ne 'true') {
        Write-Host "  [错误] 容器 $c 未运行，请先运行 start-all.bat 启动全套服务。" -ForegroundColor Red
        exit 1
    }
}
Write-Host '  全部容器已就绪'

Write-Host ''
Write-Host '== [1/3] 建表（幂等） ==' -ForegroundColor Cyan
# 1a. 建 tb_shop_sync_event 表（CREATE TABLE IF NOT EXISTS，天然幂等）
$outboxSql = Get-Content -Raw -Encoding UTF8 (Join-Path $MigrationDir '20261030-shop-sync-outbox.sql')
Invoke-MySqlPipe $outboxSql | Out-Null
Write-Host '  tb_shop_sync_event 表已就绪'

# 1b. 幂等补 revision 列：兼容「表已存在但缺列」的中间态（老迁移只建了表、没跑 revision 迁移）
$colOut = Invoke-MySqlPipe "SHOW COLUMNS FROM tb_shop_sync_event LIKE 'revision';"
if ([string]::IsNullOrWhiteSpace($colOut) -or $colOut -notmatch 'revision') {
    $revisionSql = Get-Content -Raw -Encoding UTF8 (Join-Path $MigrationDir '20261030-shop-sync-revision.sql')
    Invoke-MySqlPipe $revisionSql | Out-Null
    Write-Host '  revision 列已补齐'
} else {
    Write-Host '  revision 列已存在，跳过'
}

Write-Host ''
Write-Host '== [2/3] 建 RocketMQ Topic（幂等） ==' -ForegroundColor Cyan
# 4 个业务 Topic；broker.conf 里 autoCreateTopicEnable=false，新 Topic 必须手动建。
# %DLQ% 开头的死信队列必须存在，否则消费重试耗尽后投递失败。
$topics = @(
    @{ Name = 'hmdp-seckill-order';             R = 4; W = 4 },
    @{ Name = '%DLQ%hmdp-seckill-order-consumer'; R = 1; W = 1 },
    @{ Name = 'hmdp-shop-sync';                 R = 4; W = 4 },
    @{ Name = '%DLQ%hmdp-shop-sync-consumer';   R = 1; W = 1 }
)
foreach ($t in $topics) {
    docker exec hmdp-broker sh mqadmin updateTopic -n namesrv:9876 -b broker:10911 -t $t.Name -r $t.R -w $t.W 2>&1 | Out-Null
    if ($LASTEXITCODE -ne 0) { Write-Host "  [错误] 建 Topic $($t.Name) 失败" -ForegroundColor Red; exit 1 }
    Write-Host "  $($t.Name) 已就绪"
}

Write-Host ''
Write-Host '== [3/3] 建 ES 索引（幂等） ==' -ForegroundColor Cyan
$esUrl = 'http://localhost:9200'
$index = 'hmdp_shop'
# Mapping 与 src/main/java/com/hmdp/constants/EsConstants.java 的 SHOP_INDEX_JSON 保持一致。
$mapping = @'
{
  "mappings": {
    "properties": {
      "id": { "type": "keyword" },
      "name": { "type": "text", "analyzer": "ik_max_word" },
      "namePinyin": { "type": "keyword" },
      "nameInitials": { "type": "keyword" },
      "typeId": { "type": "long" },
      "area": { "type": "text", "analyzer": "ik_max_word" },
      "address": { "type": "text", "analyzer": "ik_max_word" },
      "avgPrice": { "type": "long" },
      "score": { "type": "integer" },
      "sold": { "type": "integer" },
      "comments": { "type": "integer" },
      "images": { "type": "keyword", "index": false },
      "location": { "type": "geo_point" },
      "suggestion": { "type": "completion", "analyzer": "keyword" }
    }
  }
}
'@
$exists = $false
try {
    Invoke-WebRequest -Uri "$esUrl/$index" -Method Head -UseBasicParsing -TimeoutSec 10 | Out-Null
    $exists = $true
} catch {
    $status = $null
    if ($_.Exception.Response) { $status = [int]$_.Exception.Response.StatusCode }
    if ($status -ne 404) {
        Write-Host "  [错误] 检查 ES 索引失败：$($_.Exception.Message)" -ForegroundColor Red
        exit 1
    }
    $exists = $false
}
if ($exists) {
    Write-Host "  索引 $index 已存在，跳过"
} else {
    Invoke-RestMethod -Uri "$esUrl/$index" -Method Put -Body $mapping -ContentType 'application/json' -TimeoutSec 20 | Out-Null
    Write-Host "  索引 $index 已创建"
}

Write-Host ''
Write-Host '初始化完成：表 / Topic / ES 索引全部就绪。' -ForegroundColor Green
Write-Host '若需灌数据（ES 全量导入、演示数据），见 deploy/README.md「可选步骤」。'
