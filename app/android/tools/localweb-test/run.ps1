# 把 LocalWebServer 拉到 JVM 上跑（Android 依赖用 src/android 下的桩替掉）
#
#   run.ps1                          跑 14 项断言
#   run.ps1 -Live <upstream> -Token <t>   起一个对着真桌面端的实例，打印 LIVE_ORIGIN 后等 120s
#
param(
    [string]$Live = "",
    [string]$Token = ""
)

$ErrorActionPreference = "Stop"
$toolDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$src = Join-Path $toolDir "src"
$out = Join-Path $toolDir "out"
$server = Join-Path $toolDir "..\..\app\src\main\java\dev\pideck\mobile\LocalWebServer.java"

if (Test-Path $out) { Remove-Item -Recurse -Force $out }
New-Item -ItemType Directory -Path $out | Out-Null

# 始终用仓库里的那一份源码，避免测试树里留一份会漂移的副本
Copy-Item $server (Join-Path $src "dev\pideck\mobile\LocalWebServer.java") -Force

$files = @(
    (Join-Path $src "android\util\Log.java"),
    (Join-Path $src "android\content\Context.java"),
    (Join-Path $src "dev\pideck\mobile\LocalWebServer.java"),
    (Join-Path $src "dev\pideck\mobile\LocalWebServerTest.java"),
    (Join-Path $src "dev\pideck\mobile\LiveRun.java")
)

& javac -encoding UTF-8 -d $out @files
if ($LASTEXITCODE -ne 0) { throw "javac 失败" }

# 注意：-D 开头的参数必须整个加引号，否则 PowerShell 会把它当成自己的参数（踩过：ClassNotFoundException: /encoding=UTF-8）
if ($Live) {
    & java "-Dstdout.encoding=UTF-8" "-cp" $out "dev.pideck.mobile.LiveRun" $Live $Token
} else {
    & java "-Dstdout.encoding=UTF-8" "-Dfile.encoding=UTF-8" "-cp" $out "dev.pideck.mobile.LocalWebServerTest"
    if ($LASTEXITCODE -ne 0) { throw "自测未全绿" }
}
