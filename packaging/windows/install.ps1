#Requires -Version 5.1
<#
  编码要求：UTF-8 with BOM（见 common.ps1 顶部说明）。

.SYNOPSIS
    LAN-Drop 便携版安装：放行防火墙、写配置、注册「登录时」自启任务并启动服务端。

.DESCRIPTION
    本脚本**不做 Windows 服务**（不引入 nssm/WinSW）：自启用任务计划程序，进程就是普通用户进程。
    为什么需要管理员：创建防火墙入站规则与注册计划任务都需要提权。

    程序目录与数据目录是分开的：
        程序目录 = 本脚本所在目录（升级时整个覆盖）
        数据目录 = %LOCALAPPDATA%\LAN-Drop（数据库、文件、日志、配置都在这里，升级不动）

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File install.ps1
.EXAMPLE
    powershell -ExecutionPolicy Bypass -File install.ps1 -Port 9000 -ServerName 书房台式机 -NoAutostart
.EXAMPLE
    powershell -ExecutionPolicy Bypass -File uninstall.ps1
#>
[CmdletBinding()]
param(
    [int]    $Port = 8787,
    [int]    $DiscoveryPort = 8788,
    [string] $ServerName = '',
    [string] $DataRoot = '',
    [string] $TaskName = 'LAN-Drop',
    [switch] $NoFirewall,
    [switch] $NoAutostart,
    [switch] $NoStart,
    [switch] $Force
)

. (Join-Path $PSScriptRoot 'common.ps1')

$ProgramDir = Split-Path -Parent $PSCommandPath
$NodeExe    = Join-Path $ProgramDir 'node\node.exe'
$ServerJs   = Join-Path $ProgramDir 'app\server\server.js'
$WebIndex   = Join-Path $ProgramDir 'app\web\dist\index.html'

if (-not $DataRoot) { $DataRoot = Join-Path $env:LOCALAPPDATA 'LAN-Drop' }
$EnvFile  = Join-Path $DataRoot 'lan-drop.env'
$LogDir   = Join-Path $DataRoot 'logs'
$ServerLog = Join-Path $LogDir 'server.log'

Write-Host ''
Write-Host '=== LAN-Drop 便携版安装 ===' -ForegroundColor Cyan
Write-LanDropNote "程序目录：$ProgramDir"
Write-LanDropNote "数据目录：$DataRoot"
Write-LanDropNote "服务端口：TCP $Port ／ 发现端口：UDP $DiscoveryPort（Wi-Fi 网络配置文件需为「专用网络」）"
Write-Host ''

# ---------------------------------------------------------------- 0. 前置检查
if (-not (Test-LanDropAdministrator)) {
    Write-Host '需要管理员权限：创建防火墙规则与注册登录自启任务都要提权。' -ForegroundColor Red
    Write-Host '请右键本文件 →「使用 PowerShell 运行」时选择管理员，或：' -ForegroundColor Yellow
    Write-Host "  「以管理员身份打开 PowerShell」后执行： powershell -ExecutionPolicy Bypass -File `"$PSCommandPath`"" -ForegroundColor Yellow
    exit 1
}

foreach ($required in @($NodeExe, $ServerJs, $WebIndex)) {
    if (-not (Test-Path -LiteralPath $required)) {
        Write-Host "安装包不完整，缺少：$required" -ForegroundColor Red
        Write-Host '请重新完整解压压缩包（不要只复制部分文件）。' -ForegroundColor Yellow
        exit 1
    }
}

$nodeMajor = Get-LanDropNodeMajor -NodeExe $NodeExe
if ($nodeMajor -lt 24) {
    Write-Host "包内 node.exe 版本过低（v$nodeMajor）：服务端用到 node:sqlite，需要 Node 24 或更高。" -ForegroundColor Red
    exit 1
}
Write-LanDropOk "包内 node.exe v$nodeMajor"

$occupied = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
if ($occupied -and -not $Force) {
    $owner = (Get-Process -Id $occupied.OwningProcess -ErrorAction SilentlyContinue).ProcessName
    Write-Host "端口 $Port 已被占用（进程：$owner，PID $($occupied.OwningProcess)）。" -ForegroundColor Red
    Write-Host '请先停掉它，或换端口重装： install.ps1 -Port 9000' -ForegroundColor Yellow
    exit 1
}

New-Item -ItemType Directory -Path $LogDir -Force | Out-Null

# ---------------------------------------------------------------- 1. 配置
Write-LanDropStep '写入配置（已存在则保留，不覆盖你的改动）'
$values = [ordered]@{
    'LAN_DROP_PORT'           = $Port
    'LAN_DROP_DISCOVERY_PORT' = $DiscoveryPort
}
if ($ServerName) { $values['LAN_DROP_SERVER_NAME'] = $ServerName }
if ($DataRoot -ne (Join-Path $env:LOCALAPPDATA 'LAN-Drop')) { $values['LAN_DROP_DATA_ROOT'] = $DataRoot }

$written = Write-LanDropEnvFile -Path $EnvFile -Values $values -Comments @(
    'LAN_DROP_SERVER_NAME 决定手机聊天页标题显示的名字，默认取本机主机名',
    'LAN_DROP_DATA_ROOT / LAN_DROP_FILES_ROOT 可把数据挪到别的盘'
)
if ($written) { Write-LanDropOk "已生成 $EnvFile" } else { Write-LanDropOk "沿用已有 $EnvFile" }

# ---------------------------------------------------------------- 2. 防火墙
if ($NoFirewall) {
    Write-LanDropStep '按 -NoFirewall 跳过防火墙放行'
} else {
    Write-LanDropStep "放行防火墙入站（TCP $Port / UDP $DiscoveryPort，来源限本地子网）"
    try {
        Get-NetFirewallRule -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -like 'LAN-Drop*' -or $_.DisplayName -like 'LAN-Drop*' } |
            ForEach-Object { Remove-NetFirewallRule -Name $_.Name -ErrorAction SilentlyContinue }

        New-NetFirewallRule -Name 'LAN-Drop-TCP' -DisplayName 'LAN-Drop (TCP)' -Direction Inbound `
            -Action Allow -Protocol TCP -LocalPort $Port -RemoteAddress LocalSubnet -Profile Any -Enabled True | Out-Null
        New-NetFirewallRule -Name 'LAN-Drop-UDP' -DisplayName 'LAN-Drop (UDP)' -Direction Inbound `
            -Action Allow -Protocol UDP -LocalPort $DiscoveryPort -RemoteAddress LocalSubnet -Profile Any -Enabled True | Out-Null
        Write-LanDropOk '防火墙规则 LAN-Drop (TCP) / LAN-Drop (UDP) 已建立'
    } catch {
        Write-Host "    防火墙规则创建失败：$($_.Exception.Message)" -ForegroundColor Yellow
        Write-Host '    手机将连不上：请手动放行上述端口，或改用第三方防火墙放行 node.exe。' -ForegroundColor Yellow
    }
}

# ---------------------------------------------------------------- 3. 登录自启
if ($NoAutostart) {
    Write-LanDropStep '按 -NoAutostart 跳过自启任务注册'
} else {
    Write-LanDropStep "注册「登录时」自启任务：$TaskName"
    try {
        Unregister-ScheduledTask -TaskName $TaskName -Confirm:$false -ErrorAction SilentlyContinue

        # 用 PowerShell 包一层的原因：Task Scheduler 直接起 node.exe 会弹出控制台黑窗，
        # 而它没有「隐藏窗口」这个选项；run-hidden.ps1 负责无窗口启动并把日志落到数据目录。
        # 数据目录必须显式传给 run-hidden.ps1：自定义 -DataRoot 时它自己猜不到。
        $action = New-ScheduledTaskAction -Execute 'powershell.exe' `
            -Argument "-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File `"$ProgramDir\run-hidden.ps1`" -DataRoot `"$DataRoot`""
        $userId  = "$env:USERDOMAIN\$env:USERNAME"
        $trigger = New-ScheduledTaskTrigger -AtLogOn -User $userId
        $settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries `
            -StartWhenAvailable -MultipleInstances IgnoreNew `
            -RestartCount 3 -RestartInterval (New-TimeSpan -Minutes 1) -ExecutionTimeLimit (New-TimeSpan -Seconds 0)
        $principal = New-ScheduledTaskPrincipal -UserId $userId -LogonType Interactive -RunLevel Limited

        Register-ScheduledTask -TaskName $TaskName -Action $action -Trigger $trigger `
            -Settings $settings -Principal $principal -Force | Out-Null
        Write-LanDropOk '已注册（登录后自动启动，无需常驻窗口）'
    } catch {
        Write-Host "    任务注册失败：$($_.Exception.Message)" -ForegroundColor Yellow
        Write-Host '    可改用「启动」文件夹方式手动自启，或忽略（每次手动跑 start.cmd）。' -ForegroundColor Yellow
    }
}

# ---------------------------------------------------------------- 4. 启动
if ($NoStart) {
    Write-LanDropStep '按 -NoStart 跳过启动'
} else {
    Write-LanDropStep '启动服务端'
    $running = Get-LanDropServerProcess -ServerJs $ServerJs
    if ($running.Count -gt 0) {
        Write-LanDropOk "已有实例在跑（PID $($running[0].ProcessId)），跳过启动"
    } elseif (-not $NoAutostart -and (Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue)) {
        Start-ScheduledTask -TaskName $TaskName
        Write-LanDropOk '已通过计划任务启动'
    } else {
        Start-Process -FilePath 'powershell.exe' -WindowStyle Hidden -ArgumentList @(
            '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', (Join-Path $ProgramDir 'run-hidden.ps1'),
            '-DataRoot', $DataRoot
        ) | Out-Null
        Write-LanDropOk '已后台启动'
    }

    # 等服务端起来（首次启动要建库，给足 15 秒）
    $healthy = $false
    for ($i = 0; $i -lt 30; $i++) {
        Start-Sleep -Milliseconds 500
        try {
            $info = Invoke-RestMethod -Uri "http://127.0.0.1:$Port/api/v1/info" -TimeoutSec 2
            $healthy = $true
            break
        } catch { }
    }

    if ($healthy) {
        Write-LanDropOk "服务端已就绪：$($info.serverName)（协议 v$($info.protocolVersion)）"
    } else {
        Write-Host '    服务端 15 秒内没有响应，请看日志排查：' -ForegroundColor Yellow
        Write-Host "      $ServerLog" -ForegroundColor Yellow
        exit 1
    }
}

# ---------------------------------------------------------------- 5. 交付信息
$lanIp = Get-LanDropLanIPv4
$pairingCode = $null
if (Test-Path -LiteralPath $ServerLog) {
    $match = [regex]::Match((Get-Content -LiteralPath $ServerLog -Raw -Encoding UTF8), '配对码\s+([A-Z0-9]{6})')
    if ($match.Success) { $pairingCode = $match.Groups[1].Value }
}

Write-Host ''
Write-Host '=== 安装完成 ===' -ForegroundColor Cyan
if ($lanIp) { Write-Host "  手机访问   http://${lanIp}:$Port" -ForegroundColor Green }
Write-Host "  配对码     $(if ($pairingCode) { $pairingCode } else { '见日志（下方）' })" -ForegroundColor Green
Write-LanDropNote '手机 App →「配对」页点「扫描局域网」即可发现本机；也可手输上面的地址后输入配对码。'
Write-Host ''
Write-LanDropNote "配置：$EnvFile（改完用 stop.cmd + start.cmd 或重启任务生效）"
Write-LanDropNote "日志：$ServerLog（无窗口运行，排错先看这里）"
Write-LanDropNote "停止：stop.cmd    状态：status.cmd    卸载：uninstall.ps1（右键管理员运行）"
if (-not $NoAutostart) { Write-LanDropNote "自启：计划任务「$TaskName」（登录时启动；不需要就 uninstall 后手动 start.cmd）" }
Write-Host ''
