# 本地开发：Docker 中间件 + IDEA 后端

本地开发与全容器部署共用数据卷，使用同一个 `hmdp` Compose 项目，按需切换。不要同时运行两个后端争用 8081。

## 已有本机数据时

1. 启动 Docker Desktop。
2. 双击项目根目录 `start-local.bat`。脚本停止并移除可重建的后端容器，保留镜像和数据，调整 Nginx 和 Broker 的本地地址。
3. IDEA 打开根目录 `pom.xml`，使用 JDK 17，等待 Maven 导入。
4. 在运行配置中选择 **HMDP - 本地开发 (IDEA)**，点击 Run 或 Debug。
5. 打开 `http://localhost:8080/`。

启动脚本从 `deploy/.env` 读取数据库密码，写入被 Git 忽略的 `config/application-local.properties`。共享 `.run/HMDP-local.run.xml` 只包含启动参数，不包含密码。不要把私人配置提交到仓库。

如果 IDEA 没有立即显示新运行项，重新打开项目，或通过 Run → Edit Configurations 创建 Application：主类 `com.hmdp.HmDianPingApplication`，模块 `hmdp`，工作目录为项目根目录，程序参数为：

```text
--spring.profiles.active=local --spring.config.additional-location=optional:file:./config/
```

## 新机器第一次启动

1. 安装 JDK 17、Maven、Docker Desktop（Windows / WSL2）。
2. 复制 `deploy/.env.example` 为 `deploy/.env`，设置自己的数据库密码。
3. 执行 `powershell -ExecutionPolicy Bypass -File deploy/prepare-ik.ps1`。
4. 执行 `docker compose --env-file deploy/.env -f deploy/compose.local.yaml build elasticsearch`。
5. 执行 `start-local.bat`，等待 MySQL/ES/MQ 就绪。
6. 首次结构、Topic 与索引初始化见 [部署说明](DEPLOYMENT.md)，之后启动 IDEA 后端。

新空 MySQL 卷会导入最新表结构和脱敏演示数据。已有数据卷不会重新导入，不需要重置或删除数据。

## 切回全 Docker 运行

先在 IDEA 停止 Java 后端，然后从项目根目录执行：

```powershell
docker compose --env-file deploy/.env -f deploy/compose.yaml up -d --build
```

这会恢复容器内部的 Nginx/Broker 配置，并重新创建 backend。切回本地开发，再运行 `start-local.bat` 即可。

## 文件职责

| 文件 | 作用 |
| --- | --- |
| `deploy/compose.local.yaml` | 只启动中间件和 Nginx |
| `deploy/local/nginx.conf` | Nginx 转发给宿主机 IDEA 的 8081 |
| `deploy/local/broker.conf` | 向本机 Java 客户端广播 127.0.0.1:10911 |
| `.run/HMDP-local.run.xml` | 可共享的 IDEA 运行项 |
| `config/application-local.properties` | 自动生成的私人连接配置 |

截图及启动验证使用与上述运行项相同的应用参数完成；正常开发由 IDEA 管理 Java 进程。
