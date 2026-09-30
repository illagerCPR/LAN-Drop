#Requires -Version 5.1
<#
  编码要求：UTF-8 with BOM（见 common.ps1 顶部说明）。

.SYNOPSIS
    停止正在运行的 LAN-Drop 服务端（由 stop.cmd 调用，也可直接运行）。

.DESCRIPTION
    只结束「命令行里带着本包 server.js 路径」的 node 进程，不碰机器上其他 node 程序。
#>
[CmdletBinding()]
param()

. (Join-Path $PSScriptRoot 'common.ps1')

$ServerJs = Join-Path (Split-Path -Parent $PSCommandPath) 'app\server\server.js'
$processes = Get-LanDropServerProcess -ServerJs $ServerJs

if ($processes.Count -eq 0) {
    Write-Host 'LAN-Drop 没有在运行。' -ForegroundColor Yellow
    exit 0
}

foreach ($process in $processes) {
    Write-Host "停止 PID $($process.ProcessId) …"
    try { Stop-Process -Id $process.ProcessId -Force -ErrorAction Stop } catch {
        Write-Host "  停止失败：$($_.Exception.Message)" -ForegroundColor Red
    }
}
Start-Sleep -Milliseconds 500

$left = Get-LanDropServerProcess -ServerJs $ServerJs
if ($left.Count -eq 0) {
    Write-Host '已停止。' -ForegroundColor Green
    exit 0
}
Write-Host "仍有 $($left.Count) 个进程未退出。" -ForegroundColor Yellow
exit 1
