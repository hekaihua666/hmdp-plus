# 本地开发：仅运行 Docker 中间件，Java 从 IDEA 启动。
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
if (-not (Test-Path -LiteralPath 'src/main/resources/application.yaml')) {
    Copy-Item -LiteralPath 'src/main/resources/application.example.yaml' -Destination 'src/main/resources/application.yaml'
}

docker info *> $null
if ($LASTEXITCODE -ne 0) { throw '请先启动 Docker Desktop，然后重新运行本脚本。' }
if (-not (Test-Path -LiteralPath 'deploy/.env')) { throw '缺少 deploy/.env，请按 .env.example 配置数据库密码。' }
$passwordLine = Get-Content -LiteralPath 'deploy/.env' | Where-Object { $_ -match '^\s*DB_PASSWORD\s*=' } | Select-Object -First 1
if (-not $passwordLine) { throw 'deploy/.env 缺少 DB_PASSWORD。' }
$dbPassword = ($passwordLine -split '=', 2)[1].Trim()
if (($dbPassword.StartsWith('"') -and $dbPassword.EndsWith('"')) -or ($dbPassword.StartsWith("'") -and $dbPassword.EndsWith("'"))) { $dbPassword = $dbPassword.Substring(1, $dbPassword.Length - 2) }
# Properties 文件需转义反斜杠；密码不写进共享的 IDEA 配置。
$escapedPassword = $dbPassword.Replace('\', '\\').Replace(':', '\:').Replace('=', '\=').Replace(' ', '\ ')
New-Item -ItemType Directory -Path 'config' -Force | Out-Null
$localProperties = @"
spring.datasource.url=jdbc:mysql://127.0.0.1:3307/hmdp?useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true
spring.datasource.username=root
spring.datasource.password=$escapedPassword
spring.redis.host=127.0.0.1
spring.redis.port=6379
elasticsearch.host=127.0.0.1
elasticsearch.port=9200
rocketmq.name-server=127.0.0.1:9876
"@
[System.IO.File]::WriteAllText((Join-Path $PSScriptRoot 'config/application-local.properties'), $localProperties, (New-Object System.Text.UTF8Encoding($false)))

# 仅删除可重建的后端容器，释放 8081；镜像、图片和数据库卷保留。
docker compose --env-file deploy/.env -f deploy/compose.yaml stop backend
if ($LASTEXITCODE -ne 0) { throw '停止后端容器失败。' }
docker compose --env-file deploy/.env -f deploy/compose.yaml rm -f backend
if ($LASTEXITCODE -ne 0) { throw '移除后端容器失败。' }
foreach ($volume in @('hmdp-infra_mysql-data','hmdp-infra_es-data','hmdp-mq_broker-store','hmdp-mq_broker-logs','hmdp-mq_namesrv-logs')) {
    docker volume create $volume | Out-Null
    if ($LASTEXITCODE -ne 0) { throw '创建或检查数据卷失败。' }
}
docker compose --env-file deploy/.env -f deploy/compose.local.yaml up -d --no-build
if ($LASTEXITCODE -ne 0) { throw '启动本地中间件失败。' }
Write-Host '本地中间件已启动。IDEA 选择 HMDP - 本地开发 (IDEA)，点击 Run 或 Debug。'
Write-Host '网页：http://localhost:8080/ （需要先启动 IDEA 后端）'
