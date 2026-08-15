# 生成 debug 签名密钥库（标准 Android debug key，无保密性）。
# 本仓库不提交 *.keystore；构建前运行一次即可。
$ErrorActionPreference = "Stop"
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$out = Join-Path (Split-Path -Parent $here) "app\debug.keystore"

if (Test-Path $out) {
  Write-Output "已存在: $out（跳过）"
  exit 0
}

keytool -genkeypair -v `
  -keystore $out `
  -alias androiddebugkey `
  -storepass android `
  -keypass android `
  -keyalg RSA -keysize 2048 -validity 10000 `
  -dname "CN=Android Debug,O=Android,C=US"

Write-Output "OK: $out"
