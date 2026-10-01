# AGENTS.md

面向 AI 编码代理的仓库指南。只记录「不看会踩坑」的事，通用常识不写。

## 仓库速览

- pnpm monorepo：`apps/server`（Node 24 + Fastify + `node:sqlite`）、`apps/web`（Vite + React + TS）、`packages/protocol`（协议单一事实源）、`apps/desktop`（Tauri 2.x 桌面常驻壳，Rust 构建）；`android/` 是独立 Gradle 工程。
- 协议唯一事实源：`packages/protocol/src/index.ts`。Android 侧 Kotlin 镜像（`android/.../protocol/Protocol.kt`）必须与其逐字段对齐，字段名以此为准。

## 常用命令

```bash
pnpm install
pnpm -r typecheck           # 全仓类型检查，提交前必跑
pnpm -r test                # TS 侧单测（node --test 直接跑 .ts，无测试框架）：37 项
pnpm dev                    # 服务端 --watch，监听 0.0.0.0:8787
pnpm web:build              # 产出 apps/web/dist（dev 热更用 pnpm web:dev）
pnpm verify                 # 一键门禁：类型检查 + 单测 + 自起 8899 服务端跑全量冒烟 + 服务端日志 token 泄漏扫描（CI 同款）
node scripts/smoke-api.mjs  # 95 项端到端冒烟（HTTP + WS + 断点续传 + 0 字节 + 并发竞态 + 收尾摘要 + 撤销 + 磁盘满预检探测）；必须先起服务端，且必须本机跑（配对码仅回环可读）
source scripts/dev-env.sh   # JAVA_HOME / ANDROID_HOME / PATH；非交互 shell 必须显式 source（~/.bashrc 会 early-return）
cd android && ./gradlew :app:assembleDebug
pnpm desktop:resources      # 桌面壳资源：esbuild 自包含 server bundle（metafile 检查）+ web/dist + 按运行平台拉 sidecar node
pnpm fix:ps1-bom            # .ps1 缺 UTF-8 BOM 时补齐（--check 只检查）
```

- 验证顺序：服务端改动后**重启进程**再跑 smoke（静态根、数据目录在 boot 时确定）；`node --watch` 只热载 src。
- `apps/web/dist` 不存在时服务端退回 `apps/server/public` 冒烟页——Web 页面不对先确认有没有 build 过、服务端有没有重启。
- **单测是分层的，别只跑一边**：纯逻辑（尺寸算术、状态机、规则判定）放单测；跨进程行为放 `smoke-api.mjs`；
  真机/真浏览器行为只能实机验。TS 侧单测在 `packages/protocol/test`、`apps/web/test`，
  用 Node 内建 `node --test`（Node 24 直接跑 `.ts`，因此这两个包需要在 tsconfig 里写 `"types": ["node"]`，
  否则 `node:` 前缀的内建模块解析不到类型）。

## 环境与镜像（本机硬事实，勿改回官方源）

- npm → npmmirror（`.npmrc`）；Gradle 发行版 → 腾讯云（wrapper）；Maven → `google()` 直连 + 阿里云，顺序见 `android/settings.gradle.kts` 顶部注释。官方源实测仅 44~210 KB/s，镜像 1.2~3.2 MB/s。
- 本机 `/etc/hosts` 把 github.com 劫持到本地反代：curl 可用，但 **Java/Gradle 直连 GitHub 会 PKIX 失败**。任何构建依赖都不要走 GitHub 直链。
- Rust：rustup dist 走 **TUNA**（`https://mirrors.tuna.tsinghua.edu.cn/rustup`；rsproxy 的 rustup dist 镜像缺 channel 清单，实测 404）；crates.io 的 sparse 索引走 **rsproxy**（Windows 侧写死在 `C:\Users\illag\.cargo\config.toml`）。Windows 侧已有 stable-msvc 工具链 + VS 18 BuildTools（MSVC 14.51）+ WebView2 运行时，桌面壳构建在 Windows 侧做。
- WSL2 镜像网络模式；手机访问 WSL 服务端靠 Windows 防火墙放行规则（`scripts/windows-allow-lan.ps1`，已执行过，无需重跑）。「Windows 访问自己的 LAN IP 超时」是镜像模式**假阴性**，以服务端日志 `remoteAddress` 判断手机连通性。

## 服务端（apps/server）

- Node 24 原生类型剥离直接跑 TS：**无 tsx/ts-node、无编译步**。相对导入必须带 `.ts` 扩展名；tsconfig 开 `erasableSyntaxOnly`——禁 enum / namespace / 构造器参数属性。
- pnpm 11 拦构建脚本，`pnpm-workspace.yaml` 的 `allowBuilds: esbuild: true` 是必须的；再遇 `ERR_PNPM_IGNORED_BUILDS` 就在那里加白。
- **WS 路由必须注册在 `app.register()` 子作用域内**（见 `app.ts` 内注释）。写在根实例上不会被 @fastify/websocket 的 onRoute 钩子包装：handler 实际收到 `(request, reply)`，`socket` 参数是 Request 对象——鉴权字段全空、`socket.close()` 直接 TypeError，且 HTTP 层测试发现不了。
- 上传 PATCH 只注册了 `application/octet-stream` 的 contentTypeParser；客户端必须显式设该 Content-Type（`File.slice()` 得到的 Blob 会继承原文件 MIME，否则 415）。
- **上传 PATCH 在追加前必须 `truncateTo(tempPath, receivedBytes)`**（`routes/files.ts`）。`appendStreamToFile` 用 `O_APPEND`，中途断流时已落盘的残字节会留在文件里而 `receivedBytes` 不推进；不截断就从权威 offset 续传会把新数据接在残字节后面，静默写坏文件（`scripts/smoke-api.mjs` 有负向用例：注释掉这一行，「续传后收尾」立刻变 `422 sha256_mismatch`）。**不要删这一行。**
- 断点续传查询接口：`GET /api/v1/uploads/:id`（权威进度、`resumable`、`chunkSize`）、`GET /api/v1/uploads?state=open|completed|aborted`。均按设备隔离，响应里绝不能出现 `tempPath`。
- 鉴权：`Authorization: Bearer <token>`，或 `?token=`——**后者被 createAuthHook 收窄**：仅文件下载路由显式 `allowQueryToken: true`，WS 握手在 app.ts 单独保留（浏览器给 `<img>`/`<a>`/WebSocket 带不了请求头），其余接口只认请求头。访问日志的 `req.url` 由 app.ts 的 req 序列化器把 `token=` 值打码成 `[REDACTED]`——改日志配置时别丢这个序列化器。配对码接口仅回环地址可调。
- **分片写入有互斥**：同一会话同时只允许一个 PATCH 在写，第二个回 409 `chunk_write_in_progress`（带权威进度）。没有它，两个并发同 offset 分片会双双通过校验各自追加（实测 receivedBytes 冲到两倍）。
- **0 字节文件是合法上传**：会话创建即落空临时文件（`writeEmptyFile`）；complete 里除权威进度外还核对盘上尺寸（`size_mismatch_on_disk` 409）。分片接口对 `remaining<=0` 一律 409，别把它当 bug「修掉」。
- **complete 接受可选 body.sha256（客户端自证）**：不符 → 422 且会话中止，格式非法 → 400；与建会话时声明的 sha256 地位相同。客户端不声明时只算并存档摘要、不校验（Web 端刻意不声明——浏览器二次读盘是实打实的 UX 代价）。
- **撤销设备 `DELETE /pair/devices/:id` 仅回环可调**（先鉴权后回环检查）。撤销时必须手动回收该设备的上传会话——`uploads.device_id` **没有外键**，删设备行不级联，漏了会留孤儿会话；消息记录保留（`sender_name` 反范式存储）。`Hub.kickDevice` 以 4401 关连接，客户端据此进凭据失效态。
- **TLS 默认开启（v0.2.0）**：`src/tls.ts` 用 `selfsigned`（v5 API 是 async、有效期用
  `notBeforeDate/notAfterDate`，没有 days/algorithm）生成十年期自签证书，落数据根 `tls/`
  （私钥 0600）；过期/损坏整体重生成。双监听器：LAN 端口 https/wss + `127.0.0.1:8789`
  回环明文（控制台/配对码免证书警告）。两个 Fastify 实例共享同一 ctx——`Store.close()`
  已幂等，别再当单实例假设。**SPKI 指纹（sha256，base64url 无填充，恒 43 字符）必须三处同源**：
  `/info.tlsFingerprint`、配对响应、二维码 `#pair=CODE&fp=…`；改任何一处都要同步。
  桌面壳 `console_url()` 按 `LAN_DROP_TLS` 语义挑回环端口，与服务端 `parseBoolOr` 保持一致
  （未设=开，设了只有 "1"/"true" 是开）。
- **磁盘满两条防线**：建会话预检（`freeDiskBytes` 为 null 时 fail-open 跳过，绝不把查询失败
  当成空间为零）+ 分片 ENOSPC 回 507 且**保留会话**（残字节由下次追加前的 truncate 截掉）。
  verify-all 的 507 覆盖靠独立实例把 `LAN_DROP_RESERVE_BYTES` 调到 4 TiB，不是靠真满盘。
- 数据根：Linux `~/.local/share/lan-drop`，Windows `%LOCALAPPDATA%\LAN-Drop`；env 覆盖项（`LAN_DROP_PORT`、`LAN_DROP_TLS`、`LAN_DROP_LOOPBACK_PORT`、`LAN_DROP_RESERVE_BYTES` 等）见 `src/config.ts`。
- 展示名 `config.serverName` 默认取 `os.hostname()`，可用 `LAN_DROP_SERVER_NAME` 覆盖。**不要再改回写死的「LAN-Drop 服务端」**：这个名字显示在手机聊天页标题与 Web 控制台标题上，那个位置唯一的职责是回答「我在跟哪台机器说话」，而「服务端」既是实现术语、多台 PC 时又全都同名。改完记得重启服务端（名字在 boot 时确定）。

## 桌面常驻壳（Tauri，P4-6）

- 形态：`apps/desktop` 的 **Tauri 2.x 托盘壳，无窗口**。服务端不重写，以 sidecar 内嵌：
  release 用包内 `binaries/node`（v24.20.0，npmmirror 下载 + sha256 校验）跑 esbuild 自包含的
  `resources/server/server.js`（`createRequire` banner + metafile 自包含检查，与已删除的便携包
  流水线同一套，见 `apps/desktop/scripts/prepare-resources.mjs`）；debug 用 PATH 里的 node 直跑
  `LAN_DROP_DEV_ENTRY` 指向的 `apps/server/src/index.ts`。
- 资源布局必须与 `app.ts` 的 `resolveStaticRoot()` 候选顺序对齐：`resources/server/server.js` +
  `resources/web/dist`（server.js 上级的 web/dist 命中第二个候选）。改布局必须同时改那里的注释。
  **resources 落点两平台不同**：Windows（NSIS/CLI）全平铺 exe 同级；Linux（AppImage/deb 的
  AppRun 布局）sidecar node 在 exe 同级 `usr/bin/`，`server/server.js` 与 `web/dist` 却在
  exe 的 `../lib/<productName>/`（即 `usr/lib/LAN-Drop/`）——`main.rs` 按存在性双候选探测。
- **运行时路径三件事（都实测踩过）**：① `shell.sidecar()` 按 **exe 同级扁平名**解析
  （`sidecar("node")`）；externalBin 配置里的 `binaries/node` 只是打包器源路径，传它会找
  `exe_dir/binaries/node.exe`（os error 3）。② 别用 `resource_dir()`——裸跑（target/release
  直开）时解析出盘符根 `C:`，node 报 EISDIR；用 `current_exe()` 同级（裸跑与安装后布局一致）。
  ③ NSIS currentUser 安装目录 `%LOCALAPPDATA%\LAN-Drop` 与服务端默认数据根**撞目录**（卸载会
  误删数据），壳已注入 `LAN_DROP_DATA_ROOT=%LOCALAPPDATA%\LAN-Drop-Data`（用户显式设置时不覆盖）。
- **Windows 侧构建**：MSVC（VS 18 BuildTools）与 WebView2 运行时本机已有；crates 走 rsproxy
  sparse。**构建树必须放 Windows 本地盘**——仓库在 WSL 文件系统上，从 Windows 侧按 \\\\wsl.localhost
  路径构建，9P I/O 会让 cargo 慢到不可用。构建目录 `E:\ClaudeCode\lan-drop-desktop-build`
  （2026-10-01 自 `C:\Users\illag\` 迁来；`build.cmd` 用 `%~dp0` 相对定位，树内含独立 `cli/`
  （Windows 版 @tauri-apps/cli）与 `src-tauri/`（最新源码 + Windows node.exe + resources）），
  一键 NSIS 出安装包，产物拷回仓库 `dist/`。重建构建树：复制 src-tauri 源码（排除
  target/gen/binaries/resources）→ 资源用 `LAN_DROP_TARGET_PLATFORM=win32 pnpm desktop:resources`
  产出后拷入（或从旧树 robocopy）→ `robocopy` 旧树 `cli/`。
- **Linux 侧构建（AppImage）在 WSL 里直接做**（仓库就是本地 I/O，无需复制构建树）：
  `sudo apt install libwebkit2gtk-4.1-dev libxdo-dev libayatana-appindicator3-dev librsvg2-dev`
  （一次；build-essential/file/libssl-dev 本机已有）→ `pnpm desktop:resources`（按运行平台自动取
  Linux node）→ `pnpm --filter @lan-drop/desktop exec tauri build --bundles appimage`，产物
  `bundle/appimage/*.AppImage` 拷回 `dist/`。**WSLg 没有系统托盘**（StatusNotifierWatcher 缺失）：
  托盘目视项只能真 Linux 桌面验证；无托盘宿主时壳的处理见下一条「无托盘宿主降级」。
- **无托盘宿主降级（仅 Linux，2026-10-01 专项修复）**：会话总线上没有
  `org.kde.StatusNotifierWatcher`（WSLg / 极简会话 / GNOME 未装 AppIndicator 扩展）时，
  **建托盘前就跳过它**（`status_notifier_watcher_present()`，GIO 查 `NameHasOwner`；
  `gio`/`glib` 0.18 本就在 tray-icon/libappindicator 依赖树里，不是新下载）。原因是实测：
  无宿主时 libayatana-appindicator 退化成 GtkStatusIcon fallback，托盘 widget 建不出来，
  GTK 内部对空指针调 `gtk_widget_get_scale_factor` 打出
  `Gtk-CRITICAL: assertion 'GTK_IS_WIDGET (widget)' failed`——**图标照样不显示**，
  却让用户（和 Agent）以为服务端挂了；端口其实一直在听、`/api/v1/info` 从 Windows 侧
  `localhost` 也返回 200。跳过托盘后改为**启动即打开控制台**（无托盘时浏览器是唯一入口）：
  WSL 里走 `cmd.exe /c start "" <url>` 交给 Windows 侧默认浏览器（镜像模式下 Windows 浏览器
  访问 `127.0.0.1:<回环端口>` 直达 WSL 监听器，实测 200），其余 Linux 走 xdg-open；
  终端另打印一行控制台地址横幅，无托盘模式提示 `pkill -x LAN-Drop` 退出。
  **必须等回环端口真的可连接再打开浏览器**：sidecar 是刚 spawn 的，node 要 ~0.6 秒才 bind，
  抢先打开只会得到「无法访问此页面」——服务端日志里连一条请求都没有（连接被拒，根本没到达），
  这正是用户报的「控制台打不开」；`open_console_when_ready()` 轮询到就绪（最多 30 秒）再开，
  等待毫秒数打进 stderr。`is_wsl()` 与 `spawn_windows_browser()` 的失败都要打日志——
  这条路上任何静默失败都表现成「什么都没发生」，事后极难定位。
  **验收纪律：不要为了测这条路径反复弹用户的浏览器窗口**（实测会把用户惹毛）。用 PATH 前置
  「cmd.exe 替身」（`/tmp/stubbin/cmd.exe`，一个记录 `$*` 与时刻的可执行脚本）跑 AppImage，
  就能验证「传了什么参数、在端口就绪之后多久调用」，一个标签页都不开；真浏览器是否成功打开，
  用服务端日志判断（连续出现 `GET /` + 静态资源 + `/api/v1/info` 即为浏览器加载了控制台）。
  另：`install_linux_log_filter()` 只按域+固定文案滤掉那行 CRITICAL 与 libayatana 的
  deprecation WARNING，其余 GTK 消息原样转交 GLib 默认处理器——**不要扩大过滤范围**；
  托盘图标缺失也不再让 setup 失败。Windows 行为不变（托盘是唯一入口，失败即 setup 失败）。

- 壳的行为约定：`single-instance` 插件必须最先注册（第二次启动=打开控制台，绝不出现第二个
  sidecar）；sidecar 意外退出时壳 `exit(1)`（`killed_by_us` 标记防止主动退出被 Terminated 事件
  误报成异常退出）；sidecar 的 stdout/stderr 落应用日志目录 `server-sidecar.log`（Windows：
  `%LOCALAPPDATA%\io.github.illagercpr.landrop.desktop\logs\`，Linux：
  `~/.local/share/io.github.illagercpr.landrop.desktop/logs/`）。杀 sidecar → 壳 exit(1) 的崩溃
  联动在两平台都有日志证据（Linux 上 node 收 SIGTERM 会优雅退出 code 0，壳仍按「非壳所杀」处理）。
- 图标：`apps/desktop/scripts/make-icon.mjs` 用 SDF + 亚采样手写 PNG（纯 node:zlib，不引图像库），
  再 `pnpm dlx @tauri-apps/cli icon` 生成全套；改图标先改脚本再重生成。
- 防火墙仍走 `scripts/windows-allow-lan.ps1`（管理员执行一次：TCP 8787 + UDP 8788）；Tauri
  安装包不代做防火墙规则。
- **便携包（P4-1）已随桌面壳放弃**：`scripts/package.mjs`、`scripts/verify-package.mjs`、
  `packaging/` 已删除（git 历史可考）；esbuild bundle 的要点（createRequire banner、metafile
  自包含检查、node.exe 镜像下载与校验）全部延续在 `prepare-resources.mjs`。

### 系统分享面板与多选批量（P4-2）

- **分享 Intent 会成为本任务的「基础 Intent」**（Android 的既有行为）。由此引出两条纪律：
  1. MainActivity 必须是 `launchMode="singleTop"`，否则分享时会在栈上再叠一个实例，
     两套输入框抢着消费同一次分享；
  2. **不要**用 `savedInstanceState == null` 判断「是不是新的分享投递」来防重复——
     任务带着保存状态重建时该判断为假，用户分享过来会毫无反应（比偶尔重复更糟，实测踩过）。
     重复投递已用无界面中转 Activity 修掉（`share/ShareTrampolineActivity`）：SEND/SEND_MULTIPLE 的
     intent-filter 在中转身上，中转解析后投进程级 ShareInbox，再以**不带分享数据**的启动 Intent
     拉起 MainActivity 并立即 finish——分享内容绝不放进转发 Intent，否则冷启动时它又会成为任务
     基础 Intent；MainActivity 自身的 Intent 没有任何人解析（`publishShare`/`onNewIntent` 已移除），
     任务重建时重放什么都不会发生。
- **URI 读授权归「接收它的 Activity」所有，随其销毁吊销**：中转拿到授权就 finish，必须把 URI 装进
  转发 Intent 的 `ClipData` 带 `FLAG_GRANT_READ_URI_PERMISSION` 转移给 MainActivity（授权寿命仍是
  本进程存续期）。漏了这步的现象是全体文件分享都「无法确定文件大小」。
  验证「基础 Intent 已干净」读 `dumpsys activity recents` 里任务的 `intent={...}` 行即可，不必复现
  杀进程重开；重复投递回归用「分享 → HOME → `am kill` → 最近任务点卡片重开」跑真实路径。
- 分享落点在 `ui/AppRoot` 而不是聊天页：分享可能发生在**尚未配对**时，那时聊天页根本没被组合。
  未配对时文件不入队（那批 URI 反正留不住），只 Toast 提示先配对。
- `share/ShareInbox` 是进程级收件箱，**消费必须取出即清空**（`getAndUpdate { null }`），
  否则一次重组就把同一批文件再发一遍。解析逻辑里只有 `normalizeShare(RawShare)` 与框架解耦，
  新情况优先加在那里并用 `ShareIntentTest` 覆盖（现有 18 项）。
- **`ACTION_SEND` 的 URI 是临时授权**：不能 `takePersistableUriPermission`（`takePersistableRead`
  已 `runCatching` 兜住）。后果是分享进来的文件**只在本进程存续期内可读**，进程被杀后重新上传会
  打不开源文件并落成永久失败——这是刻意的取舍，别去「修」成假装能续传。
  应用内多选走的 SAF 才是持久授权。
- 解析必须同时读 `EXTRA_STREAM`、`ClipData` 与 `Intent.data`：真实发送方多把 URI 放进 `ClipData`
  （只有 `EXTRA_STREAM` 的分享**没有读授权**，实测表现为「无法确定文件大小」）；
  同一批 URI 两边都有时要**去重**。`EXTRA_STREAM` 还可能是 `String`/`String[]`，
  按 `Uri` 硬读会 `ClassCastException` **把应用崩掉**，所以有宽容兜底。
- **去重必须按「媒体条目身份」，不能比 URI 字符串**（`share/ShareInbox.kt` 的 `mediaIdentityOf`）。
  相册分享单张照片时两边装的是**同一张照片的两种形态**：`…/images/media/1000102703`（`EXTRA_STREAM`）
  与 `…/file/1000102703`（`ClipData`）。字符串不相等 → 字符串去重必漏 → 同一张照片传两遍
  （用户实测报过「分享了一张图片，上传了两份」）。依据：MediaProvider 里 `images`/`video`/`audio`
  都是 `files` 表之上的视图（`CREATE VIEW images AS SELECT … FROM files WHERE media_type=1`），
  同一卷内行 id 唯一标识一个文件。**只认已核实是 `files` 表或其视图的形态**
  （`file`/`downloads`/`images|video|audio/media`）——`…/images/thumbnails/<id>` 是独立表，
  只按「最后一段数字」合并会把缩略图和别的文件并成一个；带 `?`/`#` 的一律不合并
  （`?width=` 会改变实际内容）。**拿不准就不合并**：重复上传可忍，合并错会让用户静默丢文件。
- 真机驱动分享有两条硬事实：`am start` 只有 `--esa`（String[]），**没有 Uri 数组选项**，
  所以造不出「两种形态各带授权」的输入；`--esa` 不生成 `ClipData`，因此
  `--grant-prefix-uri-permission` 对 `content://media/external/` 的**前缀授权实测不生效**
  （应用读不到，落成「无法确定文件大小」）。能自足使用的只有 `-d <uri> --grant-read-uri-permission`
  （单 URI、冷启动都有效）。别把「上一次真实分享遗留的临时授权」当成自己的授权生效了——
  进程一被杀授权就没了，`am force-stop` 后重跑最能暴露这一点。
- **上传一律串行**（`TransferRepository.uploadQueue`）：单发与批量共用一把锁。并发多选会把局域网带宽
  切成几份并堆同样多的前台服务通知。下载不走这把锁。
- 真机验收用 `am start -a android.intent.action.SEND …` 驱动即可（文字用 `--es`；单文件用
  `-d <uri> --grant-read-uri-permission`；多文件用 `--esa android.intent.extra.STREAM a,b`，
  但那批 URI **没有授权**——`--esa` 是纯字符串数组，系统不会为它建 `ClipData`，所以要么用
  应用自己的 `file://` 内部文件，要么靠 `-d` 单独给一个 URI 授权）。
  两个坑：整条命令要加单引号，否则本机 shell 会把 `*/*` 当 glob 展开；
  验证数量要用**无 limit 的聚合查询**（`limit 5` 会让计数卡在 5，把「成功」看成「被吞」）。
- **应用内多选可以按文件名驱动，不要盲点**（本会话修正旧结论）：vivo 的
  `documentsui/.picker.PickActivity` **会**把文件名写成普通 `TextView` 的 `text`（旧前提
  「不暴露文件名」出自相册式媒体选择器）。手势：**长按进入选择模式（标题变「已选择 N 项」）
  → 点选其余项 → 点工具栏「选择」确认**；**单击 = 直接返回该文件**（单选路径，选择器立即关闭），
  溢出菜单里只有「排序/全选」、没有确认键。隐私纪律：列表里同样可见用户的私有文件名
  （准考证、私人文档等），**只按自己测试文件名做精确匹配点击**，绝不按坐标盲点、
  绝不碰非本测试创建的条目；测试用完把种下的文件删掉。
- **vivo 的 Toast 既不进无障碍 dump 也不进 logcat**，机器验证不可行——只能请用户目视确认，
  且验收记录必须写明证据来源（「用户确认」≠「机器验证」）。
- **输入法弹起会把底部输入栏顶高约 1000px**：点过输入框之后再点「发送」等底部按钮，
  必须重新 dump 取新坐标（发送 y≈2533 → IME 弹起后 y≈1496），否则点空、消息留在草稿框。

### 时间线图片缩略图（P4-3）

- **服务端没有、也不该有缩略图接口**：桌面壳里的服务端是 esbuild 打出的自包含单文件，`sharp` 这类原生
  图像库进不去。所以「服务端有」的那条路是**拉原图 + 本地降采样**，缩略图逻辑因此全在客户端
  （`media/ThumbnailLoader.kt`）。三条随之而来的约束：只在该行被组合时取（LazyColumn 天生如此，
  滑走即取消）、结果按 `fileId@档位` 落盘缓存、并发上限 2（别和用户正在传的文件抢带宽）。
- **解码用 `ImageDecoder`，不是 `BitmapFactory`**：手机竖拍照片的方向写在 EXIF 里，
  `BitmapFactory` 不理会它，解出来是**躺倒的**（实测 2000×1500 + `Orientation=6` 会显示成横图）；
  `ImageDecoder`（API 28+，minSdk 33 已覆盖）按 EXIF 摆正。解码后立刻缩到目标长边再进缓存——
  缓存里存的必须是**显示用的那一份**，否则一张 4000×3000 就是 48 MB 堆。
  另：`decoder.allocator` 必须设 `ALLOCATOR_SOFTWARE`，硬件位图不能作为缩放源。
- **取图顺序按代价排，失败顺延**（`media/ThumbnailSource.kt` 的 `thumbnailSourcesOf`，
  纯函数、可 JVM 单测）：① 已下载的本机副本 → ② 本机发出时的源文件 → ③ 服务端原图。
  ② 之所以要靠后且必须能顺延：`ACTION_SEND` 的授权活不过进程，SAF 的长期授权上传完就
  `release` 了——两种情况都实测失效过。③ 有 24 MB 上限，超过就只显示文件卡片。
- **正在下载这条消息时不取服务端原图**：自动接收一开，消息刚到就开始下载，时间线同时在组合，
  不挡一下就是把同一份字节传两遍（规则在纯函数里，单测钉住）。
- **预览尺寸自己算，不要交给 `ContentScale.Fit`**：Fit 只缩放画面，布局仍占满约束，
  竖图会被塞进一个横着多出空白的大框里。`fitInside`（`media/ThumbnailGeometry.kt`）直接给出
  布局尺寸，不放大超过原图（小图不糊）。
- 降采样判据是**长边**不小于目标：按「两条边都不小于目标」来判，16:9 的图会被当成方图，
  2560×1440 一点不降地整张解进内存（14 MB），而 1280×720 已经够清楚。
- 真机验收可以完全不动屏幕：① 服务端取图会在 `cache/thumbnails/<fileId>-<档位>.webp` 留下
  **缩放后的**成品，尺寸即证据（EXIF 那张是 768×1024，没 EXIF 的同尺寸图是 1024×768）；
  ② 无障碍节点（`content-desc` = 文件名）的 bounds 就是**实际渲染尺寸**，可与 `fitInside`
  的算术逐像素对账；③ 想证明「下载后改读本机副本」，把服务端那份原图**移走**再清空
  `cache/thumbnails/` 重启应用——缩略图还能重建，就只可能来自本机副本（实测重建结果与
  先前逐字节相同）。全程不需要截图或整屏 dump。

### 传输暂停/继续与链接消息（P4-4）

- **上传状态机在 `apps/web/src/upload.ts`（`Uploader`），不要把它写回组件里**：它之所以能被
  `node --test` 直接单测，是因为网络能力经 `UploadTransport` 注入、与 React 无关。界面只拿
  `UploadSnapshot[]` 渲染。四条不变量（都有单测）：
  1. **暂停在分片之间生效**，不掐断在飞的请求——中途断流会留下半片残字节，把状态机拖进
     「服务端认为收到了、客户端以为没发」的模糊地带；界面用 `pausing` 显示「暂停中…」。
  2. **每次开工（继续/重试）都先 `GET /uploads/:id` 校准锚点**，绝不信本地 `offset`：
     分片写进去了而响应在回程丢了时，本地必然落后，从本地续就是一次注定 409 的重发。
  3. **会话不在（404）或已 `aborted` → 重建会话从零再来**；连续 3 片服务端进度不前进即判失败
     （否则会话在别处被中止时就是一个安静的死循环）。
  4. **队列只挑 `queued` 的任务**（暂停的不占队列，后面的文件照发）。代价是 `#runJob` 返回前
     必须让任务离开 `running`/`pausing`，收尾处有 `#settle` 兜底。
- **「什么算链接」只有一条规则：协议包的 `isLinkText`（只认 http/https）**。发送端据此决定 `kind`，
  两端渲染端据此决定是否做成可点链接；Android 侧是 `protocol/LinkText.kt` 镜像，
  两边各有一张 **15 条边界表**的单测（`packages/protocol/test/link.test.ts`、`protocol/LinkTextTest`），
  改一边必须改另一边。**只认 http/https 是安全边界**：`kind` 由发送方自填、服务端不做语义校验，
  旧代码直接 `href={message.text}`，`javascript:` 能当链接发出去。
- Android 发送纯 URL 一直落成 `kind=text`（`LanDropApi.sendText` 的默认参数），已经修掉；
  「夹在句子里的 URL」仍是纯文本，**不要**顺手加 linkify——那会让两端对同一句话给出不同渲染。
- Compose 里 `LinkInteractionListener` 是 `androidx.compose.ui.text` 的**顶层**接口（不在
  `LinkAnnotation` 内），且自定义监听器会**取代**默认 `UriHandler`，所以「没有应用能打开」
  要自己 `runCatching` + Toast。
- 真机验「链接可点」不需要截图：`input tap` 链接节点的中心，再看
  `dumpsys activity activities | grep topResumedActivity` 是否变成浏览器；点纯文本做负向对照。

### 消息保留策略（P4-5）

- **删除是严格 seq 前缀（`seq <= uptoSeq`），不要改成按 `created_at` 逐行删**：客户端只听得懂
  「删到 seq X 为止」。天数阈值与条数阈值各自换算成 seq 边界取较大者；`AUTOINCREMENT` 的
  空洞按实际 seq 计算（`apps/server/test/store-purge.test.ts` 钉住），按「条数偏移」算会误删。
- **手动「清空会话」与保留策略的文件语义不同，是设计不是不一致**：保留策略删被清消息引用的
  文件行与磁盘字节（不然策略只删 DB 行、磁盘照样涨）；手动清空刻意保留文件（用户主动操作，
  宁可保守）。多条消息引用同一文件时以留存者为准。
- **客户端联动有两条路径，缺一不可**：在线收 `messages.purged` 广播实时删；离线错过的靠
  `GET /messages` 响应的 `purgedUpto` 水位在 syncNow/拉取时补删（幂等）。**服务端重启必然
  断开所有客户端**，所以启动那一轮清理的广播注定没人收到——水位补齐是主路径，不是兜底。
  Kotlin 侧 `purgedUpto` 默认 `0`，兼容未升级的服务端。
- **水位持久化在 `meta` 表且只进不退**：手动清空会把消息表清空，随表现算的水位会瞬间归零、
  历史信息丢失。改清理逻辑时保持 `max(旧值, 本次uptoSeq)` 的推进方向。
- 验证保留策略别等一小时定时器：`LAN_DROP_CLEANUP_INTERVAL_MS` 可调（默认 3600000），
  15 秒一轮足够跑完「在线广播 + 离线水位」两条路径的真机验证。

### 摄像头扫码配对（P3-4）

- **单测造二维码要造「相机拍得到」的尺寸**：`QRCodeWriter` 传 `width=0` 会生成 1px/模块的
  最小图（33×33），**任何二值化器都解不出来**（`HybridBinarizer`、全局直方图、
  `PURE_BARCODE` hint 全部实测失败）；按相机实际分辨率放大（300×300）后全部通过。
- **`MultiFormatReader.decode()` 内部会 `setHints(null)` 重置 hints**：要先 `setHints(...)`
  再 `decodeWithState`，否则「只解 QR_CODE」的优化静默失效。
- zxing 3.5.4 的 `PlanarYUVLuminanceSource` 构造器是 **8 参**（末位 `reverseHorizontal`）；
  QR 解码对 90° 旋转天然不变，Y 平面无需旋转，镜像传 `false`。CameraX 的 Y 平面要按
  `rowStride`/`pixelStride` 折叠成紧致亮度图（`scan/QrDecoder.kt`）。
- **`ListenableFuture` 桥协程不必引 Guava**：手写 `suspendCancellableCoroutine` + 直接执行器
  `addListener` 即可；相机绑定外层必须 `finally { unbindAll() }`（扫到/返回/异常三条路都松开）。
- 相机权限只在扫码页申请（manifest 里的 `CAMERA` 权限为它而加，别挪到启动流程）。
- 给真机扫码测试造二维码的复用技巧：用 zxing 把配对链接生成 **SVG** 写到
  `/mnt/c/Users/Public/`，再 `cmd.exe /c start <文件>` 用 Windows 打开——PC 全屏显示、
  手机直接对准扫即可。

## Web（apps/web）

- tsconfig 开 `exactOptionalPropertyTypes`：给可选字段显式传 `undefined` 会编译错，用条件展开（`...(x ? { k: v } : {})`）。
- SPA 用 hash 路由（配对码走 `#pair=CODE`），服务端 404 兜底不做 SPA history 回退——不要引入 BrowserRouter 式路由。

## Android

- 工具链已实测锁定：AGP 9.4.1 / Gradle 9.7.1 / Kotlin 编译器插件 2.2.10 / KSP 2.3.12 / compileSdk 37 / minSdk 33。升级前先读 `docs/技术选型与开发计划.md` §2.5 三条硬约束（生态要求 AGP ≥ 9.1 且 compileSdk ≥ 37，AGP 8 路线不可行）。
- AGP 9 内置 Kotlin：**不要** apply `org.jetbrains.kotlin.android`；**仍要** apply `org.jetbrains.kotlin.plugin.compose` 与 `org.jetbrains.kotlin.plugin.serialization`（版本必须等于内置 KGP）。
- Room 的 `@Index` / `@Query` 写**数据库列名**（如 `created_at`），不是 Kotlin 属性名。
- SDK 包名小版本化：装平台是 `platforms;android-37.2` 这类，不是 `android-37`。
- **配对码是大写字母 + 数字**（服务端 `PAIRING_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"`，刻意排除 0/O、1/I/L）。输入框不能用数字键盘，输入后要 `uppercase()`。
- 传输记录必须能在 App 启动时收尾：进程被系统杀掉时 `queued/running` 会永久卡在「进行中」。逻辑在 `TransferRepository.reconcileInterruptedTransfers()`——新增传输方向时要在同一处补收尾（服务端会话与临时分片、MediaStore 的 `IS_PENDING` 隐藏行都要清）。**有续传能力后这里不再一律判失败**：现场还在的落到「已暂停」，现场没了的才清理并判失败；刻意不做自动续传（启动时 WiFi 常未就绪）。
- **断点续传的两端各有一条不变量，分量不同，别混为一谈**：服务端的 `ftruncate` 是**必需**的（`O_APPEND` 追加，不截断必写坏）；客户端的 `Os.ftruncate`+`Os.lseek` 是**兜底**（`"rw"` 打开不是 `O_APPEND`，定位覆盖本就正确）。用 `Os.ftruncate`/`Os.lseek` 而非 `FileChannel`——channel 所有权归 `FileOutputStream`，容易把 fd 关两次。
- **上传摘要「边传边算」且只吃服务端确认的字节**（`TransferRepository.runUpload`）：`digest.update` 必须放在 `uploadChunk` 成功之后——被 409 拒掉的重发字节不能进摘要；409 realign 时摘要覆盖区间一并对齐（服务端超前从本地补读 `seedDigest`，服务端回退 `digest.reset()` 从头重算）；续传会话开工先把 `0..offset` 重读一遍补进摘要（选项 B 的代价）。收尾 `completeUpload` 必带整文件摘要，服务端不符即 422。0 字节文件摘要是空串 sha256，判「大小未知」的依据是 `resolvePickedFile` 的 -1 哨兵，不是 `<= 0`。
- 「暂停」与「取消」在协程里都是 `CancellationException`，只能靠 `TransferRepository.intents` 里记的意图区分：暂停保住两端现场，取消才清场。改取消路径时务必两条都走一遍。
- SAF 源文件必须 `takePersistableUriPermission(uri, FLAG_GRANT_READ_URI_PERMISSION)`，否则进程重启后续传打不开源文件；传完/取消时 `release`。**只有读权限的 URI 不能用 `"rw"` 探测长度**，会抛 `SecurityException` 被误判成「文件丢失」，固定用 `"r"`。
- 失败判据：4xx（除 408/429）与 `SecurityException` = 永久失败，其余（IO、5xx、超时）= 可恢复，落到「已暂停」保住进度。下载结束时必须核对字节数——服务端截断连接时 `read()` 返回 -1 而不抛错。
- debug 构建的**实际包名带 `.debug` 后缀**（`applicationIdSuffix`），`am start` / `run-as` / `pm list packages` 都要用它，用 `applicationId` 会报「Activity class does not exist」并误判成没装上。
- **TLS 指纹固定是信任边界，不是可选装饰**（`net/FingerprintTrustManager.kt`）：SPKI sha256
  （base64url 无填充，43 字符）来自配对二维码 `#fp=`（相机信道），配对时必须与服务端
  `/info` 自报值核对，不符 = 中间人，直接中止；手输地址没有二维码指纹，走 TOFU（宽松
  TLS 只允许「配对引导」用）。`FingerprintPins` 比较前先归一化（`+`/`/`/`=` ↔ `-_`）。
  pinned 客户端按指纹缓存在 AppContainer，`hostnameVerifier` 放行是模型的一部分（指纹即
  身份），别「顺手加回」主机名校验——裸 IP + 自签证书下它永远不通过。
- **多服务端**：`ConnectionStore` 是多行存储（`server.<id>.*` + `activeServerId`，0.1.x
  单服务端格式首读自动迁移）；`connection`/`connections` 两个 StateFlow。消息与传输按
  `server_id` 隔离（Room v3，`(server_id, seq)` 联合唯一）；**恢复传输连任务自己的服务端**
  （`connectionFor(transfer.serverId)`），不是当前选中的。`LanDropSocket` 的 listener 按
  连接实例把关——旧 socket 的迟到回调一律忽略，否则旧服务端消息会写进新服务端的缓存行。
  换服务端**不再清缓存**（游标各自单调，旧文档里的清缓存逻辑已删）。
- **release 构建已签名 + R8**：签名材料 `android/keystore/`（gitignored：jks + 
  keystore.properties，丢失则无法再为更新签名——务必让用户备份）。`keystore.properties`
  缺失时 assembleRelease 退回未签名产物，CI 不受影响。`build.gradle.kts` 顶部
  `import java.util.Properties` 不能省（脚本里裸写 `java.util.Properties` 会 Unresolved）。
- `./gradlew :app:testDebugUnitTest` 是 120 项 JVM 单测（无需设备与服务端）：`ProtocolJsonTest` 18 项协议一致性（改协议时同步更新里面的真实响应样本，含收尾摘要请求体的字段名与 null 省略行为），`net/WsEnvelopeParserTest` 6 项 WS 事件信封解析（`message.new` 真实样本、`message.deleted`/`messages.purged` 必须解析为对应事件，缺 payload、未知 type 与坏 JSON 必须拒绝——协议向前兼容靠这条），`notify/TransferNoticeTest` 17 项通知逻辑，`net/ServerDiscoveryTest` 6 项发现应答解析（非本服务 / 未来版本 / 坏报文必须拒绝——UDP 报文来自局域网任意设备，解析必须严格），`share/ShareIntentTest` 18 项分享内容归一化（**按媒体条目身份去重**——真实 URI 对、不同卷、带查询串、缩略图表、SAF 文档 URI，另加 `ClipData` 兜底、`data` 兜底、空白文字、非分享 action、`String[]` 兼容），`media/ThumbnailSourceTest` 18 项缩略图取图顺序，`media/ThumbnailGeometryTest` 12 项预览尺寸与降采样算术，`protocol/LinkTextTest` 3 项链接判定（其中一项用 15 条边界表与事实源逐条对齐），`scan/QrDecoderTest` 5 项扫码解码（stride/pixelStride 打包还原、非法输入返 null），`data/prefs/PairingPayloadTest` 10 项配对链接解析边界（含 `#fp=` TLS 指纹 4 项）。
- 文件下载落盘路径是 `Download/LAN-Drop`（MediaStore `RELATIVE_PATH`，注意大小写与连字符）。

### UDP 自动发现与连接状态（P3-3）

- 发现协议两端成对实现：服务端 `apps/server/src/discovery.ts`、客户端 `net/ServerDiscovery.kt`。
  探测报文是文本 `LANDROP-DISCOVER-v1`（UDP 8788，**整包精确匹配**）；应答 JSON
  `{ service, v, id, name, port, tls }`（`tls` 是 v0.2.0 新增，老服务端没有该字段，
  客户端按 false 处理）。改任何一端的字段/魔法串/端口必须同步另一端，
  Android 侧常量在 `ServerDiscovery.DISCOVERY_PORT`。
- **问答式而非定时广播**：应答是单播，Android 不申请 `MulticastLock` 也收得到；
  客户端要同时发 `255.255.255.255` 与各网卡定向广播（部分 AP/ROM 组合会吞其中一种）。
  WSL2 镜像模式实测能收到局域网 UDP 广播，无需额外端口转发。
- **凭据失效是独立状态 `SocketState.CREDENTIALS_INVALID`**：服务端以 WS 4401 / HTTP 401
  拒绝时，客户端停止重连、横幅直接给出「解除配对重新配对」的出路。**不要把它并回
  RECONNECTING**——重试一万次也是 401，继续显示「正在重连…」是实测踩过的误导
  （服务端设备行丢失后横幅挂了半天）。找回扫描在凭据失效时跳过（服务端活着，扫了也空转）。
- 断线找回按 `serverId` 匹配、更新 `ConnectionStore.updateBaseUrl`（凭据不动），
  `connection` StateFlow 新值会自动触发重连。扫描发现「同一台服务端但已启用 TLS」而本地
  凭据没有指纹时，进 `SocketState.TLS_UNTRUSTED` 停止重连（指纹只能来自重新扫码）；
  切换服务端**不清缓存**（Room 按服务端隔离，旧文档的清缓存逻辑已删）。
- **自动接收默认关，且开启时刻即基准点（`autoReceiveSince`）**：只自动下载晚于基准点的
  入站文件消息，防止开启后首次全量同步把服务端历史文件全拉下来。去重靠两层：
  进程内 `autoReceivedIds`（挡 WS 推送与增量同步的并发窗口）+ 传输表 `message_id` 查询
  （兜底进程重启）。离线补拉的消息同样要过 `maybeAutoReceive`，否则开关形同虚设。
- **清理服务端测试数据时点名删除，绝不整表清设备行**：手机设备行删了，手机就会永远 401
  （见上一条）。另外 `/tmp/send-file.mjs` 这类 PC 侧脚本每次运行都自注册一台新设备
  （`PC/linux`），用完要删，否则设备表累积僵尸行、排查「设备行为什么变了」会被带偏。

### 前台服务与通知（P3-2）

- `notify/TransferService` 的生命周期**跟着「有没有传输在跑」**：`TransferRepository` 在开工/恢复时经 `TransferServiceLauncher` 拉起，服务自己订阅 Room，全部离开 `queued/running` 后 1.5 s `stopSelf()`。新增传输方向时照样要拉服务，否则锁屏后进程会被冻结。
- **服务只订阅 Room，不接收推送的进度**：服务与界面共用同一个 `AppContainer`，进度只有一个真相。别引入「服务眼中的传输状态」这第二份副本。
- `startForeground` 由 `ServiceCompat.startForeground(..., FOREGROUND_SERVICE_TYPE_DATA_SYNC)` 调用，manifest 里的 `foregroundServiceType="dataSync"` 不能删（Android 14+ 缺了会抛 `InvalidForegroundServiceTypeException`）。必须在 `onStartCommand` 里立刻调（5 秒限制），此刻还没读到进度，先挂 `Notifications.preparing` 占位。
- 通知必须 `setForegroundServiceBehavior(FOREGROUND_SERVICE_IMMEDIATE)`：Android 12+ 默认会把新前台服务的通知推迟约 10 秒才显示。
- **Android 15 起 `dataSync` 前台服务有 6 小时/24 小时配额**，用尽时系统调 `onTimeout(startId, fgsType)`。`TransferService` 已在该回调里 `pauseAll()` + 发提醒 + 停止自己；**不要把这个回调删掉**，否则用户视角是「传到一半通知没了，也没有任何解释」。
- 后台启动前台服务受 Android 12+ 限制（`ForegroundServiceStartNotAllowedException`，属 `IllegalStateException`）。拉起处一律 try/catch：通知挂不上只是少个提示，不能让用户的发送失败。
- 三条通知渠道（`transfer_progress` 低 / `transfer_result` 中 / `message` 中）**一旦建立重要性就只有用户能改**，不要合并。
- **「用户主动暂停」与「网络中断暂停」靠 `error` 是否为空区分**：主动暂停走 `setStateIfExists(..., PAUSED, null)`，中断路径会写原因。`TransferNotice.result()` 依赖这个约定决定提不提醒。若将来给主动暂停也填 `error`，通知会开始乱响。
- 通知按钮的 `PendingIntent` 相等性**不含 extra**，只按 requestCode + Intent 过滤等价部分比较；传输 ID 必须掺进 requestCode（见 `Notifications.serviceAction`），否则两条传输的按钮会互相覆盖。
- `AppVisibility.foreground` 由 `MainActivity` 的 onStart/onStop 维护（单 Activity 应用刻意不引 `ProcessLifecycleOwner`），只用于「该不该发新消息通知」。
- 进行中的进度通知会持续刷新 → SystemUI 进不了 idle → **`uiautomator dump` 报 `could not get idle state` 并失败**，必须重试（实测要 20 次）。且 dump 失败时**不会写文件**：脚本若失败后照旧 `cat` 远端路径，读到的是上一次的旧 dump，会得出完全错误的界面结论（`/tmp/uidump.sh` 的做法是先删远端文件、失败即退出）。
- **`am kill` 杀不掉带前台服务的进程**（实测）：上传进行中 `TransferService` 是前台服务，进程不属于「后台进程」，`am kill` 无效、上传照跑（曾让一次「中途杀进程制造续传现场」的测试直接传完）。要杀就 `am force-stop <package>`——代价是 ACTION_SEND 的临时 URI 授权一并消失（源文件不可读 → 按设计判失败），所以「制造续传现场」必须走 SAF 持久授权的传输。
- 前台服务 `android:exported="false"`，所以 `adb shell am startservice` 起不动它（`Requires permission not exported from uid`）——通知按钮只能靠真实点击验证；且「暂停」「取消」两键同排相邻（实测 x=178 与 x=311），**绝不能盲点或按估算坐标点**，点错就是删掉几百 MB 半成品。

## 约定

- 提交与 tag 一律 GPG 签名（指纹 `D2E7DBACB6E233780954B2DEF09BEB5215872019`，无口令，可静默签）；推送必须用 GitHub 隐私邮箱 `63698328+illagerCPR@users.noreply.github.com`。
- 源码标识符全英文，禁拼音。
- 每完成一项功能同步更新 `README.md` 与 `docs/技术选型与开发计划.md`，文档与实现脱节视为未完成。
- `scripts/windows-allow-lan.ps1` 必须保持 **UTF-8 with BOM**：PS 5.1 对无 BOM 文件按 ANSI/GBK 解码，中文字符串会破坏语法；也不要手工另存为 ANSI（依赖系统区域设置）。用 `pnpm fix:ps1-bom` 补齐（`--check` 只检查）。
- 需要 sudo 提权时找用户执行；**不准使用 snap 安装**。
