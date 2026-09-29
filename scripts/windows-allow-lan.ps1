#Requires -RunAsAdministrator
<#
  编码要求：本文件必须以「UTF-8 with BOM」保存，不要去掉 BOM。
  Windows PowerShell 5.1 对无 BOM 的 .ps1 会按系统 ANSI 代码页（中文系统为 GBK）解码，
  文件里的中文字符串会被解成乱码，其中某些字节恰好是引号或反引号，会直接破坏语法，
  报出「参数列表中缺少参数」「意外的标记 )」之类与真实原因无关的错误。
  PowerShell 7（pwsh）默认按 UTF-8 读取，不受影响——所以「我这儿能跑」并不代表脚本没问题。
  可用以下命令自查： powershell -c "[void][System.Management.Automation.Language.Parser]::ParseFile('脚本路径',[ref]$null,[ref]$e); $e.Count"

.SYNOPSIS
    放行局域网设备访问本机（WSL 中运行的 LAN-Drop 服务端）。

.DESCRIPTION
    WSL2 处于镜像网络模式（networkingMode=mirrored）时，WSL 直接持有宿主机的局域网 IP，
    手机等局域网设备需要经 Windows 防火墙才能访问 WSL 中运行的服务端。
    本脚本幂等地添加两类入站规则，来源均限制在本地子网，不对外网开放：
        1. 常规 Windows 防火墙规则 —— 【实测确认这是让手机访问成功的那一条】
        2. Hyper-V 防火墙规则（针对 WSL 这个 VM Creator）—— 兜底，失败不影响使用

    一个重要提醒（本项目踩过的坑）：
    「在宿主机上访问 http://<本机局域网IP>:8787 超时」不能作为「手机连不上」的判据。
    镜像模式下宿主机连接自己持有的那个 IP 会走本机回环捷径，根本到不了 WSL 的监听套接字，
    必然假阴性。判断手机是否真的可达，请看服务端日志里的 remoteAddress，
    或直接用手机打开页面。

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

function Get-LanAddress {
    <#
      取默认路由所在网卡上的 IPv4 地址对象（最接近「手机看到的那个 IP」）。
    #>
    try {
        $route = Get-NetRoute -DestinationPrefix '0.0.0.0/0' -ErrorAction Stop |
                 Sort-Object RouteMetric | Select-Object -First 1
        if ($route) {
            return Get-NetIPAddress -InterfaceIndex $route.InterfaceIndex -AddressFamily IPv4 -ErrorAction Stop |
                   Where-Object { $_.IPAddress -notlike '169.254.*' } |
                   Select-Object -First 1
        }
    } catch { }
    return $null
}

function Get-LanIPv4 {
    $addr = Get-LanAddress
    if ($addr) { return $addr.IPAddress }
    return $null
}

function Get-LanSubnetCidr {
    <#
      由本机局域网地址与前缀长度算出网段 CIDR（如 192.168.1.0/24）。

      注意：Hyper-V 防火墙的 -RemoteAddresses 不接受 'LocalSubnet' 这类关键字，
      必须给显式 CIDR，否则 New-NetFirewallHyperVRule 会直接失败（本项目实际踩到）。
      掩码按字节构造，避免 PowerShell 的移位/类型转换陷阱：
      0xFFFFFFFF 在 PowerShell 中是 Int32 的 -1，用它做 -band 相当于没做掩码。
    #>
    $addr = Get-LanAddress
    if (-not $addr) { return $null }

    $ipBytes = [System.Net.IPAddress]::Parse($addr.IPAddress).GetAddressBytes()
    $prefix  = [int]$addr.PrefixLength
    $netBytes = New-Object byte[] 4

    for ($i = 0; $i -lt 4; $i++) {
        $bits = $prefix - ($i * 8)
        if ($bits -le 0) { $m = 0 }
        elseif ($bits -ge 8) { $m = 255 }
        else { $m = (0xFF -shl (8 - $bits)) -band 0xFF }
        $netBytes[$i] = [byte]($ipBytes[$i] -band $m)
    }

    $net = ($netBytes | ForEach-Object { "$_" }) -join '.'
    return "$net/$prefix"
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

# 1) Hyper-V 防火墙规则
#    微软文档指出镜像模式下 WSL 入站会经过 Hyper-V 防火墙，故一并添加。
#    但本项目实测结论是：仅常规 Windows 防火墙规则就足以让手机访问成功
#    （服务端日志已见 remoteAddress=192.168.1.101 的 200 响应）。
#    因此这里失败不视为致命错误，只提示。
$vmCreatorId = Get-WslVmCreatorId
$lanCidr     = Get-LanSubnetCidr
Write-Host "WSL VM Creator ID : $vmCreatorId" -ForegroundColor DarkGray
Write-Host "局域网网段        : $(if ($lanCidr) { $lanCidr } else { '未能探测，Hyper-V 规则将不限制来源' })" -ForegroundColor DarkGray

$hyperVTargets = @(
    @{ Name = "$HyperVRuleName-TCP"; Display = 'LAN-Drop (WSL, TCP)'; Protocol = 'TCP'; Ports = $TcpPorts }
)
if ($UdpPorts.Count -gt 0) {
    $hyperVTargets += @{ Name = "$HyperVRuleName-UDP"; Display = 'LAN-Drop (WSL, UDP)'; Protocol = 'UDP'; Ports = $UdpPorts }
}

foreach ($target in $hyperVTargets) {
    $ruleParams = @{
        Name        = $target.Name
        DisplayName = $target.Display
        Direction   = 'Inbound'
        VMCreatorId = $vmCreatorId
        Protocol    = $target.Protocol
        LocalPorts  = [string[]]($target.Ports | ForEach-Object { "$_" })
        Action      = 'Allow'
        Enabled     = $True
    }
    # 必须用显式 CIDR：-RemoteAddresses 不接受 'LocalSubnet' 关键字
    if ($lanCidr) { $ruleParams['RemoteAddresses'] = @($lanCidr) }

    try {
        New-NetFirewallHyperVRule @ruleParams | Out-Null
        Write-Host "  [OK] Hyper-V 规则（$($target.Protocol)）已添加" -ForegroundColor Green
    } catch {
        Write-Warning "Hyper-V 规则（$($target.Protocol)）添加失败，已跳过：$($_.Exception.Message)"
        Write-Host '       （不影响使用：本项目实测中常规防火墙规则已足够）' -ForegroundColor DarkGray
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
