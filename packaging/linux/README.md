LAN-Drop（Linux 便携包）
========================

替代已下线的 Edge Drop：同一局域网内，在电脑与手机之间传文件、传文字。
电脑当服务端，手机装 Android App 当客户端；用浏览器打开也能收发。

本包不需要 root、不装到系统目录：解压到任意位置，跑 install.sh 装成
**systemd 用户服务**（登录后自启），数据在 XDG 数据目录里，升级只覆盖程序目录。


快速开始
--------

    tar -xzf lan-drop-<版本>-linux-x64.tar.gz
    cd lan-drop-<版本>-linux-x64
    ./install.sh                 # 写配置 + 装 systemd user 服务 + 立即启动

安装脚本最后会打印手机访问地址与配对码。手机装好 App 后，在「配对」页点
【扫描局域网】即可发现本机，无需手输地址。

    ./install.sh --port 9000 --name 书房台式机      # 换端口 / 换显示名
    ./install.sh --no-start                         # 只安装不启动
    ./install.sh --dry-run                          # 先看看会写什么、做什么
    ./uninstall.sh                                  # 卸载（保留数据）
    ./uninstall.sh --purge                          # 卸载并删除数据


依赖
----

* Node.js ≥ 24（服务端用 `node:sqlite`，低版本没有这个内建模块）
* systemd 用户会话（绝大多数桌面发行版都有）
* 若把便携版 node 放在 `node/node`，启动器会优先用它，不依赖系统 node

  ./install.sh 会检查并给出明确提示；`bin/lan-drop` 也可直接手动运行（前台）。


文件说明
--------

  bin/lan-drop        启动器（systemd 的 ExecStart 指向它；手动调试也用它）
  app/server/server.js  服务端（单文件，自带全部依赖）
  app/web/dist/         浏览器界面
  lan-drop.service      systemd user unit 模板（install.sh 会替换路径后安装）
  install.sh            安装：写配置、装 unit、启动
  uninstall.sh          卸载：停服务、删 unit；数据默认保留
  VERSION               版本与构建信息


配置与数据
----------

  配置  ~/.config/lan-drop/env          KEY=VALUE（Shell 赋值语法），改完重启服务生效
  服务  ~/.config/systemd/user/lan-drop.service
  数据  ~/.local/share/lan-drop/        数据库 lan-drop.sqlite、文件仓库 files/、身份
  日志  journalctl --user -u lan-drop -f

  可用配置项：
    LAN_DROP_PORT            HTTP/WebSocket 端口，默认 8787
    LAN_DROP_DISCOVERY_PORT  局域网发现 UDP 端口，默认 8788
    LAN_DROP_SERVER_NAME     显示名（手机聊天页标题），默认取本机主机名
    LAN_DROP_DATA_ROOT       数据目录，默认 ~/.local/share/lan-drop
    LAN_DROP_FILES_ROOT      文件仓库，默认 <数据目录>/files


常用命令
--------

    systemctl --user status lan-drop        # 状态
    systemctl --user restart lan-drop       # 重启（改完配置用它）
    systemctl --user stop lan-drop          # 停止
    journalctl --user -u lan-drop -f        # 实时日志（配对码也在这里）
    ./bin/lan-drop                          # 前台运行（调试最直观）


升级
----

    ./uninstall.sh --keep-config        # 停服务、删 unit（保留配置与数据）
    tar -xzf lan-drop-<新版本>-linux-x64.tar.gz
    cd lan-drop-<新版本>-linux-x64 && ./install.sh

配置在 `~/.config`、数据在 `~/.local/share`，都不在程序目录里，覆盖升级不会丢。


排错
----

* 手机扫不到 / 连不上
    - 确认手机与电脑在同一 Wi-Fi，路由器没开「AP 隔离 / 客户端隔离」。
    - 确认服务端在跑： `systemctl --user status lan-drop`。
    - 本机自测：浏览器打开 http://127.0.0.1:8787 应能看到界面。
    - 防火墙（ufw/firewalld）需放行 TCP 8787 与 UDP 8788：
      `sudo ufw allow 8787/tcp`、`sudo ufw allow 8788/udp`。

* 开机（未登录）就要能用
    默认是「登录后启动」。要常驻，执行一次（需要 sudo，脚本不会替你执行）：
      `sudo loginctl enable-linger $USER`

* 端口被占用
    换端口重装：`./install.sh --port 9000`（手机自动发现仍能找到，应答里带着实际端口）。

* 在 SSH / 容器里没有 systemd 用户会话
    install.sh 会写出 unit 但无法启用，直接前台跑即可： `./bin/lan-drop`。


安全说明
--------

服务端只接受私有网段来源，默认要求配对（配对码一次性、有有效期）。
传输是 HTTP 明文，仅适用于你信任的局域网；不要把 8787 端口映射到公网。
