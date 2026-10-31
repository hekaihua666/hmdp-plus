# Download the pinned IK package from the upstream release server.
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$dest = Join-Path $PSScriptRoot 'infra/elasticsearch/plugins/ik'
if (Test-Path (Join-Path $dest 'plugin-descriptor.properties')) { Write-Host 'IK already prepared.'; exit 0 }
New-Item -ItemType Directory -Path $dest -Force | Out-Null
$cache = Join-Path $PSScriptRoot '../target/downloads'
New-Item -ItemType Directory -Path $cache -Force | Out-Null
$archive = Join-Path $cache 'elasticsearch-analysis-ik-7.17.24.zip'
Invoke-WebRequest 'https://release.infinilabs.com/analysis-ik/stable/elasticsearch-analysis-ik-7.17.24.zip' -OutFile $archive
Expand-Archive -LiteralPath $archive -DestinationPath $dest -Force
Write-Host 'IK 7.17.24 prepared.'
