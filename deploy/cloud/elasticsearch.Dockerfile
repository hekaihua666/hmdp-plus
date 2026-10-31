# 云服务器版 ES 镜像：只换基础镜像来源。
#
# 本机用 docker.elastic.co/elasticsearch:7.17.24，这个域名在国内云服务器上拉取得很慢
# （经常几百 KB/s，1GB 镜像要等半小时）。Docker Hub 的官方 elasticsearch:7.17.24
# 是同一个镜像，可以走国内 registry 镜像加速，快得多。IK 插件仍用本地文件。
#
# 构建上下文是 deploy/（见 compose.cloud.yaml），所以这里是相对 deploy/ 的路径。
FROM elasticsearch:7.17.24

# ik 插件（含 config 下的自定义词典）
COPY infra/elasticsearch/plugins/ik /usr/share/elasticsearch/plugins/ik
