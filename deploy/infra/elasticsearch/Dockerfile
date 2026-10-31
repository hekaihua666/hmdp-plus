# 基于官方 ES 7.17.24，仅追加 IK 中文分词器插件。
# 版本必须与 elasticsearch-rest-high-level-client / ik 插件严格一致（7.17.24）。
FROM docker.elastic.co/elasticsearch/elasticsearch:7.17.24

# ik 插件（含 config 下的自定义词典），来源：本机 D:\elasticsearch\elasticsearch-7.17.24\plugins\ik
COPY plugins/ik /usr/share/elasticsearch/plugins/ik
