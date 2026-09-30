#Requires -Version 5.1
<#
  编码要求：UTF-8 with BOM（见 common.ps1 顶部说明）。

.SYNOPSIS
    查看 LAN-Drop 运行状态：进程、端口、自启任务、健康检查与最近日志（由 status.cmd 调用）。
#>
[CmdletBinding()]
param(
    [string] $DataRoot = '',
    [string] $TaskName = 'LAN-Drop',
    [int]    $Port = 0
)

. (Join-Path $PSScriptRoot 'common.ps1')

$ProgramDir = Split-Path -Parent $PSCommandPath
$ServerJs   = Join-Path $ProgramDir 'app\server\server.js'
if (-not $DataRoot) { $DataRoot = Join-Path $env:LOCALAPPDATA 'LAN-Drop' }

$envFile = Join-Path $DataRoot 'lan-drop.env'
$settings = Read-LanDropEnvFile -Path $envFile
if ($Port -le 0) {
    $Port = if ($settings.Contains('LAN_DROP_PORT')) { [int]$settings['LAN_DROP_PORT'] } else { 8787 }
}

Write-Host ''
Write-Host '=== LAN-Drop 状态 ===' -ForegroundColor Cyan

$processes = Get-LanDropServerProcess -ServerJs $ServerJs
if ($processes.Count -gt 0) {
    foreach ($process in $processes) {
        Write-Host "  进程      运行中（PID $($process.ProcessId)）" -ForegroundColor Green
    }
} else {
    Write-Host '  进程      未运行' -ForegroundColor Yellow
}

$listener = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
Write-Host "  端口      TCP $Port $(if ($listener) { '正在监听' } else { '未监听' })"

try {
    $info = Invoke-RestMethod -Uri "http://127.0.0.1:$Port/api/v1/info" -TimeoutSec 3
    Write-Host "  健康检查  正常（名称 $($info.serverName)，协议 v$($info.protocolVersion)）" -ForegroundColor Green
} catch {
    Write-Host '  健康检查  无响应' -ForegroundColor Yellow
}

$task = Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
if ($task) {
    $taskInfo = Get-ScheduledTaskInfo -TaskName $TaskName -ErrorAction SilentlyContinue
    Write-Host "  自启任务  $TaskName（状态 $($task.State)，上次结果 $($taskInfo.LastTaskResult)）"
} else {
    Write-Host '  自启任务  未注册'
}

$lanIp = Get-LanDropLanIPv4
if ($lanIp) { Write-Host "  手机访问  http://${lanIp}:$Port" -ForegroundColor Green }
Write-Host "  配置      $envFile"
Write-Host "  日志      $(Join-Path $DataRoot 'logs\server.log')"

$logOut = Join-Path $DataRoot 'logs\server.log'
if (Test-Path -LiteralPath $logOut) {
    Write-Host ''
    Write-Host '--- 日志末尾 5 行 ---' -ForegroundColor DarkGray
    Get-Content -LiteralPath $logOut -Tail 5 -Encoding UTF8 | ForEach-Object { Write-Host "  $_" -ForegroundColor DarkGray }
}
Write-Host ''
