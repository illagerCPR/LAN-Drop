<#
  编码要求：本文件必须以「UTF-8 with BOM」保存，不要去掉 BOM。
  Windows PowerShell 5.1 对无 BOM 的 .ps1 会按系统 ANSI 代码页解码，中文字符串会变乱码，
  其中某些字节恰好是引号/反引号，会报出与真实原因无关的语法错误。
  自查： powershell -c "[void][System.Management.Automation.Language.Parser]::ParseFile('路径',[ref]$null,[ref]$e); $e.Count"

  LAN-Drop 便携包里几个脚本共用的工具函数（install / uninstall / run-hidden / stop / status 都点源本文件）。
#>

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Test-LanDropAdministrator {
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = New-Object Security.Principal.WindowsPrincipal($identity)
    return $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
}

function Get-LanDropLanIPv4 {
    <#
      取默认路由所在网卡上的 IPv4 地址（最接近「手机看到的那个 IP」）。
      镜像模式/WSL 那一套在这里不适用：这是原生 Windows 进程自己的地址。
    #>
    try {
        $route = Get-NetRoute -DestinationPrefix '0.0.0.0/0' -ErrorAction Stop |
                 Sort-Object RouteMetric | Select-Object -First 1
        if ($route) {
            $address = Get-NetIPAddress -InterfaceIndex $route.InterfaceIndex -AddressFamily IPv4 -ErrorAction Stop |
                       Where-Object { $_.IPAddress -notlike '169.254.*' } |
                       Select-Object -First 1
            if ($address) { return $address.IPAddress }
        }
    } catch { }
    return $null
}

function Read-LanDropEnvFile {
    <# 读 KEY=VALUE 形式的环境文件，返回有序 hashtable；文件不存在返回空表。 #>
    param([Parameter(Mandatory = $true)][string] $Path)

    $values = [ordered]@{}
    if (-not (Test-Path -LiteralPath $Path)) { return $values }

    foreach ($line in (Get-Content -LiteralPath $Path -Encoding UTF8)) {
        $trimmed = $line.Trim()
        if ($trimmed.Length -eq 0 -or $trimmed.StartsWith('#')) { continue }
        $index = $trimmed.IndexOf('=')
        if ($index -lt 1) { continue }
        $values[$trimmed.Substring(0, $index).Trim()] = $trimmed.Substring($index + 1).Trim()
    }
    return $values
}

function Import-LanDropEnvFile {
    <# 把环境文件里的键值写进当前进程环境，供 Start-Process 继承。 #>
    param([Parameter(Mandatory = $true)][string] $Path)

    $values = Read-LanDropEnvFile -Path $Path
    foreach ($key in $values.Keys) {
        Set-Item -Path "Env:$key" -Value $values[$key]
    }
    return $values
}

function Write-LanDropEnvFile {
    <# 只在文件不存在时写入，避免覆盖用户改过的配置（升级/重装都不该重置端口与名字）。 #>
    param(
        [Parameter(Mandatory = $true)][string] $Path,
        # 用 IDictionary 而不是 [ordered]：[ordered] 只能写在哈希字面量上，
        # 拿它当参数类型会让**整个脚本解析失败**（"ordered 属性只能…"），实测踩过。
        [Parameter(Mandatory = $true)][System.Collections.IDictionary] $Values,
        [string[]] $Comments = @()
    )

    if (Test-Path -LiteralPath $Path) { return $false }

    $lines = @()
    $lines += '# LAN-Drop 服务端配置（KEY=VALUE，改动后需重启服务端生效）'
    $lines += '# 本文件在数据目录里，升级时覆盖程序目录不会丢。'
    foreach ($comment in $Comments) { $lines += "# $comment" }
    foreach ($key in $Values.Keys) { $lines += "$key=$($Values[$key])" }

    $directory = Split-Path -Parent $Path
    if ($directory -and -not (Test-Path -LiteralPath $directory)) {
        New-Item -ItemType Directory -Path $directory -Force | Out-Null
    }
    # UTF-8 无 BOM：服务端与 cmd 的 for /f 都按原样读，BOM 会污染第一个键名
    [IO.File]::WriteAllLines($Path, $lines, (New-Object Text.UTF8Encoding($false)))
    return $true
}

function Get-LanDropServerProcess {
    <# 按命令行精确匹配本包的服务端进程（便携包可能被解压到任意路径，只认自己的 server.js）。 #>
    param([Parameter(Mandatory = $true)][string] $ServerJs)

    return @(Get-CimInstance Win32_Process -Filter "Name = 'node.exe'" -ErrorAction SilentlyContinue |
             Where-Object { $_.CommandLine -and $_.CommandLine -like "*$ServerJs*" })
}

function Stop-LanDropServer {
    param([Parameter(Mandatory = $true)][string] $ServerJs)

    $processes = Get-LanDropServerProcess -ServerJs $ServerJs
    if ($processes.Count -eq 0) { return 0 }

    foreach ($process in $processes) {
        try { Stop-Process -Id $process.ProcessId -Force -ErrorAction Stop } catch { }
    }
    Start-Sleep -Milliseconds 500
    return $processes.Count
}

function Get-LanDropNodeMajor {
    param([Parameter(Mandatory = $true)][string] $NodeExe)

    $raw = (& $NodeExe -v) 2>$null
    if (-not $raw) { return 0 }
    $text = "$raw".Trim().TrimStart('v')
    $major = 0
    if ([int]::TryParse(($text -split '\.')[0], [ref] $major)) { return $major }
    return 0
}

function Write-LanDropStep { param([string] $Text) Write-Host "==> $Text" -ForegroundColor Cyan }
function Write-LanDropOk   { param([string] $Text) Write-Host "    [OK] $Text" -ForegroundColor Green }
function Write-LanDropNote { param([string] $Text) Write-Host "    $Text" -ForegroundColor DarkGray }
