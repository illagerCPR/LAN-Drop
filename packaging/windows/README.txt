LAN-Drop 便携版（Windows）
==========================

替代已下线的 Edge Drop：同一个 Wi-Fi 下，在电脑与手机之间传文件、传文字。
电脑当服务端，手机装 Android App 当客户端；浏览器打开也能用。

本包是「便携目录包」：不解压安装、不写注册表、不装 Windows 服务；
程序目录可以整个删掉，数据在 %LOCALAPPDATA%\LAN-Drop 里，升级只覆盖程序目录。


三步部署
--------

1) 解压到任意目录（例如 D:\LAN-Drop），**不要塞进 C:\Program Files**（写日志会受权限限制，
   虽然数据在用户目录、通常仍然可用，但没必要给自己找麻烦）。

2) 右键 install.ps1 →「使用 PowerShell 运行」，并**以管理员身份运行**。
   （创建防火墙规则和注册登录自启都要提权；脚本开头会自检，不是管理员会直接提示。）
   安装脚本会：放行 TCP 8787 与 UDP 8788（仅本地子网来源）→ 写配置 →
   注册「登录时」自启任务 → 启动服务端 → 打印手机访问地址与配对码。

3) 手机装好 App，打开「配对」页点【扫描局域网】，选中本机 → 输入配对码 → 开始收发。
   手机浏览器也可以：直接打开安装脚本打印的 http://<本机IP>:8787，输入配对码。


文件说明
--------

  app\server\server.js   服务端（单文件，自带全部依赖，不需要装 Node）
  app\web\dist\          浏览器界面
  node\node.exe          内置 Node 运行时（版本见 VERSION）
  run-hidden.ps1         计划任务调用它：无窗口启动 + 日志落盘
  start.cmd              前台运行（调试用，能直接看到配对码与实时日志）
  stop.cmd               停止服务端
  status.cmd             查看状态：进程 / 端口 / 自启任务 / 健康检查 / 最近日志
  install.ps1            安装（防火墙 + 配置 + 自启 + 启动）
  uninstall.ps1          卸载（停进程 + 删自启任务 + 删防火墙规则；数据默认保留）
  VERSION                版本与构建信息


常用命令
--------

  powershell -ExecutionPolicy Bypass -File install.ps1 -Port 9000 -ServerName 书房台式机
      换端口、换显示名（显示名会出现在手机聊天页标题上）
  powershell -ExecutionPolicy Bypass -File install.ps1 -NoAutostart
      不注册自启，只装好防火墙与配置
  powershell -ExecutionPolicy Bypass -File uninstall.ps1 -PurgeData
      连数据（数据库、收到的文件、日志）一起删除


配置与数据
----------

  配置  %LOCALAPPDATA%\LAN-Drop\lan-drop.env     KEY=VALUE，改完重启服务端生效
  数据  %LOCALAPPDATA%\LAN-Drop\lan-drop.sqlite  消息、设备、传输记录
  文件  %LOCALAPPDATA%\LAN-Drop\files\           收到的文件（按年月分目录）
  日志  %LOCALAPPDATA%\LAN-Drop\logs\server.log  无窗口运行时的输出，排错先看这里

  可用配置项：
    LAN_DROP_PORT            HTTP/WebSocket 端口，默认 8787
    LAN_DROP_DISCOVERY_PORT  局域网发现 UDP 端口，默认 8788
    LAN_DROP_SERVER_NAME     显示名，默认取本机主机名
    LAN_DROP_DATA_ROOT       数据目录，默认 %LOCALAPPDATA%\LAN-Drop
    LAN_DROP_FILES_ROOT      文件仓库，默认 <数据目录>\files


升级
----

用新版本覆盖程序目录即可（配置、数据库、文件、日志都在数据目录里，不受影响）：

  stop.cmd
  :: 解压新版本覆盖本目录（保留你自己的 lan-drop.env；它在数据目录里，本来就不会被动）
  powershell -ExecutionPolicy Bypass -File install.ps1


排错
----

* 手机扫不到 / 连不上
    - 确认手机与电脑在同一 Wi-Fi，且路由器没有开「AP 隔离 / 客户端隔离」。
    - 确认 Windows 把当前网络识别为「专用网络」：
      设置 → 网络和 Internet → 属性 → 网络配置文件 → 专用。
      若被识别为「公用网络」，防火墙规则（来源限本地子网）不会生效。
    - 看防火墙规则是否在： status.cmd。
    - 本机自测：浏览器打开 http://127.0.0.1:8787 应该能看到界面。

* 端口被占用
    install.ps1 会报出占用进程。改端口重装：install.ps1 -Port 9000。
    注意：换端口后手机自动发现仍能找到本机（发现应答里带着实际端口）。

* 服务端起不来
    status.cmd 看进程与端口，再看 %LOCALAPPDATA%\LAN-Drop\logs\server.log 与 server.err.log。
    前台跑一遍最直观：start.cmd。

* 不想开机自启
    uninstall.ps1（不带 -PurgeData）会一并删掉自启任务与防火墙规则；
    只想留防火墙与配置就手动删任务：任务计划程序 → 任务计划程序库 → LAN-Drop → 删除。

* 卸载
    powershell -ExecutionPolicy Bypass -File uninstall.ps1            (保留数据)
    powershell -ExecutionPolicy Bypass -File uninstall.ps1 -PurgeData (连数据一起删)
    然后手动删除程序目录。


安全说明
--------

服务端只接受私有网段来源，且默认要求配对（配对码一次性、有有效期）。
传输是 HTTP 明文，仅适用于你信任的局域网；不要把 8787 端口映射到公网。
