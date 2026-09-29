#Requires -RunAsAdministrator
<#
.SYNOPSIS
    放行局域网设备访问本机（WSL 中运行的 LAN-Drop 服务端）。

.DESCRIPTION
    WSL2 处于镜像网络模式（networkingMode=mirrored）时，WSL 直接持有宿主机的局域网 IP，
    但 Hyper-V 防火墙的入站默认动作是 Block —— 实测表现为：
        Windows 访问 http://localhost:8787        → 通
        Windows 访问 http://<局域网IP>:8787       → 超时
    手机等局域网设备同样会被拦下。本脚本幂等地添加两类入站规则：
        1. Hyper-V 防火墙规则（针对 WSL 这个 VM Creator）
        2. 常规 Windows 防火墙规则（顺带覆盖直接在 Windows 上跑服务端的情况）
    两条规则都把来源限制在本地子网，不对外网开放。

.PARAMETER TcpPorts
    需要放行的 TCP 端口，默认 8787（HTTP + WebSocket）。

.PARAMETER UdpPorts
    需要放行的 UDP 端口，默认 8788（局域网发现广播）。

.PARAMETER Remove
    删除本脚本创建的全部规则，恢复原状。

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts\windows-allow-lan.ps1
.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts\windows-allow-lan.ps1 -Remove
#>
[CmdletBinding()]
param(
    [int[]]  $TcpPorts = @(8787),
    [int[]]  $UdpPorts = @(8788),
    [string] $RemoteScope = 'LocalSubnet',
    [switch] $Remove
)

$ErrorActionPreference = 'Stop'

# WSL 的 Hyper-V VM Creator ID（正常情况可自动探测，此处为兜底常量）
$FallbackWslVmCreatorId = '{40E0AC32-46A5-438A-A0B2-2B479E8F2E90}'

$HyperVRuleName = 'LAN-Drop-In'
$FwRuleName     = 'LAN-Drop-In'

function Test-Administrator {
    $identity  = [Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = New-Object Security.Principal.WindowsPrincipal($identity)
    return $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
}

function Get-WslVmCreatorId {
    try {
        $creator = Get-NetFirewallHyperVVMCreator -ErrorAction Stop |
                   Where-Object { $_.FriendlyName -eq 'WSL' } |
                   Select-Object -First 1
        if ($creator -and $creator.VMCreatorId) { return $creator.VMCreatorId }
    } catch {
        Write-Warning "无法枚举 Hyper-V VM Creator：$($_.Exception.Message)"
    }
    return $FallbackWslVmCreatorId
}

function Remove-LanDropRules {
    Write-Host '正在删除已有的 LAN-Drop 规则…' -ForegroundColor Yellow

    Get-NetFirewallHyperVRule -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -like "$HyperVRuleName*" -or $_.DisplayName -like 'LAN-Drop*' } |
        ForEach-Object {
            Write-Host "  删除 Hyper-V 规则：$($_.DisplayName)"
            Remove-NetFirewallHyperVRule -Name $_.Name -ErrorAction SilentlyContinue
        }

    Get-NetFirewallRule -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -like "$FwRuleName*" -or $_.DisplayName -like 'LAN-Drop*' } |
        ForEach-Object {
            Write-Host "  删除防火墙规则：$($_.DisplayName)"
            Remove-NetFirewallRule -Name $_.Name -ErrorAction SilentlyContinue
        }
}

function Get-LanIPv4 {
    try {
        # 取默认路由所在网卡的地址，最接近“手机看到的那个 IP”
        $route = Get-NetRoute -DestinationPrefix '0.0.0.0/0' -ErrorAction Stop |
                 Sort-Object RouteMetric | Select-Object -First 1
        if ($route) {
            $addr = Get-NetIPAddress -InterfaceIndex $route.InterfaceIndex -AddressFamily IPv4 -ErrorAction Stop |
                    Where-Object { $_.IPAddress -notlike '169.254.*' } |
                    Select-Object -First 1
            if ($addr) { return $addr.IPAddress }
        }
    } catch { }
    return $null
}

# ---------------------------------------------------------------- 主流程

if (-not (Test-Administrator)) {
    Write-Error '需要管理员权限。请右键 PowerShell →「以管理员身份运行」，再执行本脚本。'
    exit 1
}

Write-Host ''
Write-Host '=== LAN-Drop 局域网入站放行 ===' -ForegroundColor Cyan
Write-Host "  TCP 端口 : $($TcpPorts -join ', ')"
Write-Host "  UDP 端口 : $($UdpPorts -join ', ')"
Write-Host "  来源限制 : $RemoteScope"
Write-Host ''

Remove-LanDropRules

if ($Remove) {
    Write-Host '已按 -Remove 清理完毕。' -ForegroundColor Green
    exit 0
}

# 1) Hyper-V 防火墙：镜像网络模式下 WSL 入站的关键一环
$vmCreatorId = Get-WslVmCreatorId
Write-Host "WSL VM Creator ID：$vmCreatorId" -ForegroundColor DarkGray

try {
    New-NetFirewallHyperVRule `
        -Name            "$HyperVRuleName-TCP" `
        -DisplayName     'LAN-Drop (WSL, TCP)' `
        -Direction       Inbound `
        -VMCreatorId     $vmCreatorId `
        -Protocol        TCP `
        -LocalPorts      ([string[]]($TcpPorts | ForEach-Object { "$_" })) `
        -RemoteAddresses @($RemoteScope) `
        -Action          Allow `
        -Enabled         True | Out-Null
    Write-Host '  [OK] Hyper-V 规则（TCP）已添加' -ForegroundColor Green
} catch {
    Write-Warning "Hyper-V 防火墙规则（TCP）添加失败：$($_.Exception.Message)"
}

if ($UdpPorts.Count -gt 0) {
    try {
        New-NetFirewallHyperVRule `
            -Name            "$HyperVRuleName-UDP" `
            -DisplayName     'LAN-Drop (WSL, UDP)' `
            -Direction       Inbound `
            -VMCreatorId     $vmCreatorId `
            -Protocol        UDP `
            -LocalPorts      ([string[]]($UdpPorts | ForEach-Object { "$_" })) `
            -RemoteAddresses @($RemoteScope) `
            -Action          Allow `
            -Enabled         True | Out-Null
        Write-Host '  [OK] Hyper-V 规则（UDP）已添加' -ForegroundColor Green
    } catch {
        Write-Warning "Hyper-V 防火墙规则（UDP）添加失败：$($_.Exception.Message)"
    }
}

# 2) 常规 Windows 防火墙规则（覆盖直接在 Windows 上运行服务端的情况）
try {
    New-NetFirewallRule `
        -Name          "$FwRuleName-TCP" `
        -DisplayName   'LAN-Drop (TCP)' `
        -Direction     Inbound `
        -Action        Allow `
        -Protocol      TCP `
        -LocalPort     $TcpPorts `
        -RemoteAddress $RemoteScope `
        -Profile       Any `
        -Enabled       True | Out-Null
    Write-Host '  [OK] Windows 防火墙规则（TCP）已添加' -ForegroundColor Green
} catch {
    Write-Warning "Windows 防火墙规则（TCP）添加失败：$($_.Exception.Message)"
}

if ($UdpPorts.Count -gt 0) {
    try {
        New-NetFirewallRule `
            -Name          "$FwRuleName-UDP" `
            -DisplayName   'LAN-Drop (UDP)' `
            -Direction     Inbound `
            -Action        Allow `
            -Protocol      UDP `
            -LocalPort     $UdpPorts `
            -RemoteAddress $RemoteScope `
            -Profile       Any `
            -Enabled       True | Out-Null
        Write-Host '  [OK] Windows 防火墙规则（UDP）已添加' -ForegroundColor Green
    } catch {
        Write-Warning "Windows 防火墙规则（UDP）添加失败：$($_.Exception.Message)"
    }
}

Write-Host ''
Write-Host '=== 当前生效的 LAN-Drop 规则 ===' -ForegroundColor Cyan
Get-NetFirewallHyperVRule -ErrorAction SilentlyContinue |
    Where-Object { $_.DisplayName -like 'LAN-Drop*' } |
    Select-Object DisplayName, Direction, Action, LocalPorts |
    Format-Table -AutoSize | Out-String -Width 160 | Write-Host
Get-NetFirewallRule -ErrorAction SilentlyContinue |
    Where-Object { $_.DisplayName -like 'LAN-Drop*' } |
    Select-Object DisplayName, Direction, Action, Enabled |
    Format-Table -AutoSize | Out-String -Width 160 | Write-Host

$lanIp = Get-LanIPv4
Write-Host '=== 下一步 ===' -ForegroundColor Cyan
if ($lanIp) {
    Write-Host "  1) 在 WSL 中启动服务端后，用手机浏览器打开： http://${lanIp}:8787"
} else {
    Write-Host '  1) 在 WSL 中启动服务端后，用手机浏览器打开： http://<本机局域网IP>:8787'
}
Write-Host '  2) 若手机仍打不开，检查：手机是否与 PC 在同一 WiFi、'
Write-Host '     路由器是否开启了 AP 隔离（客户端隔离）、PC 的 WLAN 是否被识别为「公用网络」。'
Write-Host ''
Write-Host '完成。' -ForegroundColor Green
