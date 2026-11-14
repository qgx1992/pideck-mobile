<#
.SYNOPSIS
    把局域网 APK 下载页（8080）注册成开机自启的计划任务，无终端窗口。

.DESCRIPTION
    用 pythonw.exe（无控制台）跑 scripts/serve-apk.py，日志落在
    %LOCALAPPDATA%\PiDeck-DownloadServer\server.log。

    为什么是计划任务而不是「启动」文件夹里的快捷方式：任务计划里能明确指定
    「登录时 / 延迟 20 秒 / 电池供电也运行 / 不限执行时长 / 隐藏窗口」，
    出错时还能在任务历史里看到，不会像快捷方式那样静默失败。

.EXAMPLE
    powershell -NoProfile -ExecutionPolicy Bypass -File scripts/install-download-server-task.ps1
    powershell -NoProfile -File scripts/install-download-server-task.ps1 -Uninstall
#>
[CmdletBinding()]
param(
    [string]$TaskName = "PiDeck Download Server",
    [int]$Port = 8080,
    [switch]$Uninstall,
    [switch]$NoStart
)

$ErrorActionPreference = "Stop"

if ($Uninstall) {
    if (Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue) {
        Stop-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
        Unregister-ScheduledTask -TaskName $TaskName -Confirm:$false
        "已删除计划任务：$TaskName"
    } else {
        "没有这个计划任务：$TaskName"
    }
    # 顺手清掉可能还在跑的监听进程
    foreach ($conn in (Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue)) {
        Stop-Process -Id $conn.OwningProcess -Force -ErrorAction SilentlyContinue
    }
    return
}

$repo = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$script = Join-Path $repo "scripts\serve-apk.py"
if (-not (Test-Path $script)) { throw "找不到启动脚本：$script" }

$pythonw = Join-Path $env:LOCALAPPDATA "Programs\Python\Python312\pythonw.exe"
if (-not (Test-Path $pythonw)) {
    # 退回到 python.exe 所在的目录里找同款 pythonw
    $py = (Get-Command python -ErrorAction SilentlyContinue).Source
    if ($py) { $pythonw = Join-Path (Split-Path $py) "pythonw.exe" }
}
if (-not (Test-Path $pythonw)) { throw "找不到 pythonw.exe（无窗口运行需要它）" }

$action = New-ScheduledTaskAction -Execute $pythonw `
    -Argument ('"{0}" --port {1}' -f $script, $Port) `
    -WorkingDirectory (Split-Path $pythonw)

# 登录后延迟 20 秒：等网络栈就绪，避免刚开机时绑不上/绑到错误的接口
$trigger = New-ScheduledTaskTrigger -AtLogOn -User ("{0}\{1}" -f $env:USERDOMAIN, $env:USERNAME)
$trigger.Delay = "PT20S"

# 每 10 分钟看一次：进程被谁带走（工具超时杀进程组、手滑 Stop-Process）之后，
# 下一个整点由这条触发器把服务拉回来。MultipleInstances=IgnoreNew 意味着
# 「已经在跑」时这条只是空转，不会起第二份。
$watchdog = New-ScheduledTaskTrigger -Once -At (Get-Date).AddMinutes(2) `
    -RepetitionInterval (New-TimeSpan -Minutes 10) `
    -RepetitionDuration (New-TimeSpan -Days 3650)

$settings = New-ScheduledTaskSettingsSet `
    -AllowStartIfOnBatteries `
    -DontStopIfGoingOnBatteries `
    -StartWhenAvailable `
    -RestartCount 3 -RestartInterval (New-TimeSpan -Minutes 1) `
    -ExecutionTimeLimit ([TimeSpan]::Zero) `
    -MultipleInstances IgnoreNew

$principal = New-ScheduledTaskPrincipal -UserId ("{0}\{1}" -f $env:USERDOMAIN, $env:USERNAME) -LogonType Interactive -RunLevel Limited

Register-ScheduledTask -TaskName $TaskName -Action $action -Trigger @($trigger, $watchdog) `
    -Settings $settings -Principal $principal `
    -Description "PiDeck 手机版局域网下载页（apk/ 快照，端口 $Port），无窗口常驻" -Force | Out-Null

"  自愈：每 10 分钟检查一次（已在跑时不会起第二份），失败重试 3 次 × 1 分钟"

"已注册计划任务：$TaskName"
"  命令：$pythonw `"$script`" --port $Port"
"  日志：$env:LOCALAPPDATA\PiDeck-DownloadServer\server.log"

if (-not $NoStart) {
    # 端口若已被旧的手工进程占着，先让任务接管
    foreach ($conn in (Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue)) {
        Stop-Process -Id $conn.OwningProcess -Force -ErrorAction SilentlyContinue
        Start-Sleep -Milliseconds 500
    }
    Start-ScheduledTask -TaskName $TaskName
    Start-Sleep -Seconds 3
    try {
        $code = (Invoke-WebRequest -Uri "http://127.0.0.1:$Port/" -UseBasicParsing -TimeoutSec 5).StatusCode
        "  自检：http://127.0.0.1:$Port/ -> $code"
    } catch {
        "  自检失败：$($_.Exception.Message)（看日志 $env:LOCALAPPDATA\PiDeck-DownloadServer\server.log）"
    }
}
