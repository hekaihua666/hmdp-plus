@echo off
setlocal enabledelayedexpansion
title HMDP 一键启动
cd /d "%~dp0"

set "DOCKER_DESKTOP=D:\DockerDesktop\Docker Desktop.exe"

echo ============================================
echo   HMDP 依赖一键启动
echo   MySQL 3307 / Redis 6379 / ES 9200
echo   Nginx 8080 / RocketMQ 9876,10911
echo ============================================
echo.

docker info >nul 2>&1
if not errorlevel 1 goto docker_ready

echo [1/3] 启动 Docker Desktop ...
if exist "%DOCKER_DESKTOP%" goto start_docker
echo       [错误] 找不到 Docker Desktop
echo       预期路径: %DOCKER_DESKTOP%
pause
exit /b 1

:start_docker
start "" "%DOCKER_DESKTOP%"
echo       等待 Docker 就绪，最多 3 分钟 ...
set /a tries=0

:wait_docker
ping -n 6 127.0.0.1 >nul 2>&1
docker info >nul 2>&1
if not errorlevel 1 goto docker_ready
set /a tries+=1
if !tries! lss 36 goto wait_docker
echo       [错误] Docker 启动超时，请手动启动 Docker Desktop 后重试
pause
exit /b 1

:docker_ready
echo [1/3] Docker 已就绪

echo [2/3] 确保数据卷存在（幂等，不影响已有数据）...
docker volume create hmdp-infra_mysql-data >nul
docker volume create hmdp-infra_es-data >nul
docker volume create hmdp-mq_broker-store >nul
docker volume create hmdp-mq_broker-logs >nul
docker volume create hmdp-mq_namesrv-logs >nul

echo [3/3] 启动全部中间件（单一 compose 项目）...
docker compose -f deploy/compose.yaml up -d
if errorlevel 1 goto fail

echo.
echo ============================================
echo   全部启动完成
echo ============================================
echo.
docker compose -f deploy/compose.yaml ps
echo.
echo  下一步: 首次部署请运行初始化脚本（建表/建Topic/建ES索引）
echo        pwsh -File deploy\init.ps1
echo  前端页面: http://localhost:8080
echo.
pause
exit /b 0

:fail
echo.
echo [错误] 启动失败，请查看上面的输出
pause
exit /b 1
