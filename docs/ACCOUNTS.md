# 初始化自己的管理账号

项目不提供所有人共用的后台默认密码。新环境可通过已存在的显式测试工具建立自己的平台管理员和两个示例商户。工具不会重置已有管理员密码，也不抢占已有门店归属。

以下是本机 JDK/Maven 的 PowerShell 示例；先确保 MySQL 可连接，根目录存在从模板复制的 `src/main/resources/application.yaml`。

```powershell
$env:DB_PASSWORD = '替换为你自己的数据库密码'
$env:PLATFORM_ADMIN_PASSWORD = '设置自己的平台密码'
mvn '-Dtest=PlatformSetupTest' '-Dhmdp.platform.init=true' test

$env:MERCHANT_A_PASSWORD = '设置商户甲密码'
$env:MERCHANT_B_PASSWORD = '设置商户乙密码'
$env:MERCHANT_A_SHOP_ID = '17'
$env:MERCHANT_B_SHOP_ID = '1'
mvn '-Dtest=MerchantSetupTest' '-Dhmdp.merchant.init=true' test
```

新建的平台用户名为 `admin`；两个示例商户为 `merchant_a`、`merchant_b`。密码由上述环境变量决定，BCrypt 哈希保存到数据库。

云端也可在源码构建环境设置 DB_URL 指向目标 MySQL，或使用已有 Maven 维护容器的同网络方式执行上述测试。不要把真实密码写进源码、README 或提交记录。

已有历史数据的库，先阅读工具源码并确认归属；不要为了让截图一致而重置现有账号。公开发行版截图来自本机演示环境，截图中的账号数量不代表空库已有全部商户账号。
