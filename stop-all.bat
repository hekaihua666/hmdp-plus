@echo off
title HMDP 停止依赖
cd /d "%~dp0"

echo 正在停止全部中间件 ...
docker compose -f deploy/compose.yaml stop

echo.
echo 已停止，数据保留在命名卷和 deploy/infra/redis 目录里
echo 如需彻底删除数据卷: docker compose -f deploy/compose.yaml down -v
echo.
pause
