#Requires -Version 5.1
<#
  编码要求：UTF-8 with BOM（见 common.ps1 顶部说明）。

.SYNOPSIS
    无窗口启动 LAN-Drop 服务端，并等待它退出（由「登录时」计划任务调用，也可手动运行）。

.DESCRIPTION
    为什么需要这一层：
      1. 计划任务直接启动 node.exe 会留下一个控制台黑窗，而 Task Scheduler 没有「隐藏窗口」选项，
         只能由 PowerShell 的 -WindowStyle Hidden 代为启动；
      2. 日志要落到数据目录（程序目录可能只读、升级会被覆盖）；
      3. 脚本等待 node 退出后再结束，任务状态才反映真实运行情况，-RestartCount 也才有意义。
#>
[CmdletBinding()]
param(
    [string] $DataRoot = ''
)

. (Join-Path $PSScriptRoot 'common.ps1')

$ProgramDir = Split-Path -Parent $PSCommandPath
$NodeExe    = Join-Path $ProgramDir 'node\node.exe'
$ServerJs   = Join-Path $ProgramDir 'app\server\server.js'

if (-not $DataRoot) { $DataRoot = Join-Path $env:LOCALAPPDATA 'LAN-Drop' }
$EnvFile   = Join-Path $DataRoot 'lan-drop.env'
$LogDir    = Join-Path $DataRoot 'logs'
$LogOut    = Join-Path $LogDir 'server.log'
$LogErr    = Join-Path $LogDir 'server.err.log'

if (-not (Test-Path -LiteralPath $NodeExe) -or -not (Test-Path -LiteralPath $ServerJs)) {
    Write-Error "安装包不完整：$NodeExe / $ServerJs"
    exit 1
}

$existing = Get-LanDropServerProcess -ServerJs $ServerJs
if ($existing.Count -gt 0) {
    Write-Host "LAN-Drop 已在运行（PID $($existing[0].ProcessId)），本次不重复启动。" -ForegroundColor Yellow
    exit 0
}

New-Item -ItemType Directory -Path $LogDir -Force | Out-Null

# 每次启动只保留上一轮日志：server.log(.1) 与 server.err.log(.1)，避免长期运行堆满磁盘
foreach ($path in @($LogOut, $LogErr)) {
    if (Test-Path -LiteralPath $path) {
        Move-Item -LiteralPath $path -Destination "$path.1" -Force
    }
}

# 环境文件在数据目录里，升级覆盖程序目录不会丢配置
Import-LanDropEnvFile -Path $EnvFile | Out-Null

Set-Location -LiteralPath $ProgramDir
$process = Start-Process -FilePath $NodeExe -ArgumentList @($ServerJs) `
    -WindowStyle Hidden -PassThru `
    -RedirectStandardOutput $LogOut -RedirectStandardError $LogErr

Wait-Process -Id $process.Id
exit $process.ExitCode
