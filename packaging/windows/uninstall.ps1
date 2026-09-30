#Requires -Version 5.1
<#
  编码要求：UTF-8 with BOM（见 common.ps1 顶部说明）。

.SYNOPSIS
    卸载 LAN-Drop 便携版：停进程、删自启任务、删防火墙规则；数据默认保留。

.DESCRIPTION
    需要管理员权限（删计划任务与防火墙规则）。程序目录本脚本**不删**：
    你正在用这个目录里的脚本，删除它请手动来（脚本结束时会提示路径）。
    数据目录（数据库、收到的文件、日志）默认保留，加 -PurgeData 才删——那是不可恢复的。

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File uninstall.ps1
.EXAMPLE
    powershell -ExecutionPolicy Bypass -File uninstall.ps1 -PurgeData
#>
[CmdletBinding()]
param(
    [string] $DataRoot = '',
    [string] $TaskName = 'LAN-Drop',
    [switch] $PurgeData,
    [switch] $KeepFirewall,
    [switch] $Force
)

. (Join-Path $PSScriptRoot 'common.ps1')

$ProgramDir = Split-Path -Parent $PSCommandPath
$ServerJs   = Join-Path $ProgramDir 'app\server\server.js'
if (-not $DataRoot) { $DataRoot = Join-Path $env:LOCALAPPDATA 'LAN-Drop' }

Write-Host ''
Write-Host '=== LAN-Drop 卸载 ===' -ForegroundColor Cyan

if (-not (Test-LanDropAdministrator)) {
    Write-Host '需要管理员权限：删除登录自启任务与防火墙规则都要提权。' -ForegroundColor Red
    Write-Host "请以管理员身份执行： powershell -ExecutionPolicy Bypass -File `"$PSCommandPath`"" -ForegroundColor Yellow
    exit 1
}

# 1) 停进程
$count = Stop-LanDropServer -ServerJs $ServerJs
if ($count -gt 0) { Write-LanDropOk "已停止 $count 个服务端进程" } else { Write-LanDropNote '服务端本来就没在运行' }

# 2) 删自启任务
try {
    Unregister-ScheduledTask -TaskName $TaskName -Confirm:$false -ErrorAction Stop
    Write-LanDropOk "已删除计划任务 $TaskName"
} catch {
    Write-LanDropNote "计划任务 $TaskName 不存在或无需删除"
}

# 3) 删防火墙规则
if ($KeepFirewall) {
    Write-LanDropNote '按 -KeepFirewall 保留防火墙规则'
} else {
    $rules = @(Get-NetFirewallRule -ErrorAction SilentlyContinue |
               Where-Object { $_.Name -like 'LAN-Drop*' -or $_.DisplayName -like 'LAN-Drop*' })
    foreach ($rule in $rules) { Remove-NetFirewallRule -Name $rule.Name -ErrorAction SilentlyContinue }
    Write-LanDropOk "已删除 $($rules.Count) 条防火墙规则"
}

# 4) 数据目录
Write-Host ''
if ($PurgeData) {
    if (-not (Test-Path -LiteralPath $DataRoot)) {
        Write-LanDropNote "数据目录不存在：$DataRoot"
    } else {
        Write-Host "将删除数据目录（数据库、文件仓库、日志，不可恢复）：" -ForegroundColor Yellow
        Write-Host "  $DataRoot" -ForegroundColor Yellow
        $answer = if ($Force) { 'y' } else { Read-Host '确认删除？输入 y 回车' }
        if ($answer -eq 'y' -or $answer -eq 'Y') {
            Remove-Item -LiteralPath $DataRoot -Recurse -Force
            Write-LanDropOk '数据目录已删除'
        } else {
            Write-LanDropNote '已取消删除，数据保留'
        }
    }
} else {
    Write-LanDropNote "数据目录保留：$DataRoot（要一起删就加 -PurgeData）"
}

Write-Host ''
Write-Host '=== 卸载完成 ===' -ForegroundColor Cyan
Write-LanDropNote "程序目录还在，确认不需要后手动删除：$ProgramDir"
Write-Host ''
