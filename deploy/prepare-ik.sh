#!/usr/bin/env bash
# Keep the official IK package version aligned with Elasticsearch.
set -euo pipefail
cd "$(dirname "$0")"
dest=infra/elasticsearch/plugins/ik
if [ -f "$dest/plugin-descriptor.properties" ]; then echo 'IK already prepared.'; exit 0; fi
mkdir -p "$dest" ../target/downloads
archive=../target/downloads/elasticsearch-analysis-ik-7.17.24.zip
curl -fL --retry 2 https://release.infinilabs.com/analysis-ik/stable/elasticsearch-analysis-ik-7.17.24.zip -o "$archive"
unzip -q "$archive" -d "$dest"
echo 'IK 7.17.24 prepared.'
