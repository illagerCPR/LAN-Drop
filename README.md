# LAN-Drop

局域网文件 / 文字传输工具，用来替代已下线的 **Edge Drop**（Edge 的跨设备 Drop 功能）。PC 作为服务端，Android 作为客户端，不依赖任何云服务与账号。

## 为什么做这个

Edge Drop 下线后，在自家局域网里「电脑 ↔ 手机」随手丢一段文字、几张图、一个几百 MB 的安装包，反而又变麻烦了：微信要压缩、网盘要登录要上传、数据线要插拔。LAN-Drop 只做一件事——**同一个 WiFi 下，两台设备之间直传，数据不出局域网。**

## 架构

```
        ┌──────────────────────── PC（Linux / Windows）────────────────────────┐
        │                                                                     │
        │   Node.js 服务端                                                    │
        │   ├─ HTTP  :8787   REST + 文件上传/下载（Range 断点续传）            │
        │   ├─ WS    :8787   文字消息、事件、在线状态、传输进度                │
        │   ├─ SQLite        设备 / 消息 / 传输记录（node:sqlite 内置）        │
        │   └─ 文件仓库      ~/.local/share/lan-drop/files/…                  │
        │                                                                     │
        │   Web UI（浏览器 / 手机浏览器兜底）                                  │
        └─────────────────────────────────┬───────────────────────────────────┘
                                          │  局域网，无云、无账号
                    ┌─────────────────────┴─────────────────────┐
                    │                                           │
        ┌───────────▼────────────┐                  ┌───────────▼────────────┐
        │  Android 客户端         │                  │  手机浏览器（PWA 兜底） │
        │  Kotlin + Compose       │                  │  零安装                 │
        │  Room 缓存会话与传输记录 │                  │  须保持页面打开          │
        │  手输地址 + 配对码配对   │                  └────────────────────────┘
        └────────────────────────┘
```

> 规划中（P3）：UDP 广播自动发现服务端、摄像头扫码配对、前台服务常驻接收、系统分享面板。
> 这些尚未实现——文档只描述已经能跑的东西。

**分工原则**：控制面走 WebSocket，数据面走 HTTP。文件字节永不进 WS 通道，避免与聊天消息互相队头阻塞，同时白拿 HTTP 的 `Range` 断点续传与浏览器直下能力。

## 目录结构

```
LAN-Drop/
├─ apps/
│  ├─ server/            Node + TypeScript 服务端（HTTP / WS / SQLite / 文件流）
│  └─ web/               Vite + React + TypeScript（PC 主界面 + 手机 PWA 兜底）
├─ packages/
│  └─ protocol/          协议单一事实源（TS 类型 + JSON Schema）
├─ android/              Kotlin + Jetpack Compose 客户端（独立 Gradle 构建）
├─ docs/                 技术选型、协议、路线图
└─ scripts/              环境准备、Windows 防火墙、构建脚本
```

## 技术栈

| 层 | 选型 |
| --- | --- |
| 服务端 | Node.js 24 + TypeScript + Fastify + `ws` + `node:sqlite` |
| 前端 | Vite + React + TypeScript |
| Android | Kotlin + Jetpack Compose + Material 3 + Room + OkHttp + kotlinx.serialization |
| 传输 | 局域网 HTTP(S) + WebSocket，二维码/UDP 广播发现，Token 配对 |
| 数据根 | Linux `~/.local/share/lan-drop`；Windows `%LOCALAPPDATA%\LAN-Drop` |

## 开发环境

前置：WSL2（Ubuntu）/ Linux，Node.js ≥ 24，JDK 21。

```bash
# 1) 用户级安装 Android SDK（无需 root，不使用 snap）
bash scripts/install-android-sdk.sh

# 2) 让手机能访问 WSL 中的服务端
#    在 Windows 上以【管理员】身份运行 PowerShell（会弹 UAC，只需一次）：
#    powershell -ExecutionPolicy Bypass -File scripts\windows-allow-lan.ps1
#    未执行此步时，只有 localhost 能访问，手机会超时（WSL 镜像模式下
#    Hyper-V 防火墙默认拦截局域网入站，已实测确认）。

# 3) 让当前 shell 拿到 JDK / Android SDK / Gradle 路径
source scripts/dev-env.sh

# 4) 安装依赖并启动服务端
pnpm install
pnpm dev            # 启动后控制台会打印「手机访问 http://<IP>:8787」

# 5) 构建 Web UI（服务端检测到 apps/web/dist 后自动托管，浏览器直接访问 :8787）
pnpm web:build      # 开发热更可用 pnpm web:dev（Vite 代理 /api 到 8787）

# 6) 构建 Android 调试包（产物：android/app/build/outputs/apk/debug/app-debug.apk）
cd android && ./gradlew :app:assembleDebug

# 7) 跑协议一致性测试（JVM 单测，无需设备/服务端）
cd android && ./gradlew :app:testDebugUnitTest

# 8) 无线调试部署（手机：开发者选项 → 无线调试）
adb pair <手机IP>:<配对端口> <6 位配对码>
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
```

依赖源已固化到国内镜像（见 `.npmrc`、`android/settings.gradle.kts`、
`android/gradle/wrapper/gradle-wrapper.properties`）：npm 走 npmmirror，
Maven 走 Google 直连 + 阿里云，Gradle 发行版走腾讯云镜像。

Android 工具链版本已实测锁定（AGP 9.4.1 / Gradle 9.7.1 / Kotlin 编译器插件 2.2.10 /
KSP 2.3.12 / compileSdk 37 / minSdk 33），**改动前请先读
[docs/技术选型与开发计划.md](docs/技术选型与开发计划.md) §2.5**，那里记录了三条
会直接导致构建失败的硬约束。

## 路线图

- **P0** 环境准备与仓库骨架 ✅
- **P1** 服务端核心 + Web UI ✅（手机 ↔ PC 已实测互发文字与文件；大文件项以 ~180 MB 视频代替验收）
- **P2** Android 原生客户端 MVP ✅（配对、时间线、文字、文件收发、Room 离线缓存）
- **P3** 断点续传 ✅（暂停/继续/崩溃恢复）· 后台常驻接收、通知、系统分享面板、缩略图、UDP/扫码配对
- **P4** 稳定性、打包分发、文档收尾

详见 [docs/技术选型与开发计划.md](docs/技术选型与开发计划.md)。

## 当前进度：P1 + P2 均已在真机验收通过

### PC 端（Web UI）

浏览器打开 `http://<PC局域网IP>:8787`，扫码或一键配对后即可互发文字与文件：

| 配对页（PC 展示二维码） | 聊天页（文字 + 文件卡片） |
| --- | --- |
| ![配对页](docs/screenshots/p1-pairing.png) | ![聊天页](docs/screenshots/p1-chat.png) |

Token 配对（二维码 / 手输 / 本机一键）、聊天时间线（文字 / 链接 / 文件卡片 + 图片预览）、
拖拽 / 粘贴 / 选单上传（4 MiB 分片 + 断点续传 + sha256 校验）、Range 下载（含中文文件名 RFC 5987）、
WS 实时广播与指数退避重连（离线缺口由 `since=seq` 补拉）。
`node scripts/smoke-api.mjs` 62 项端到端冒烟全绿（HTTP + WebSocket + 断点续传）。

### Android 客户端（原生）

手输「服务器地址 + 配对码」配对（也可粘贴 PC 上的配对链接自动填入），配对后凭据落盘、
重启免重配。时间线、在线状态、传输记录三个界面：文字消息乐观发送（失败可重试/丢弃）、
文件经 SAF 选择后 4 MiB 分片上传、下载落盘系统「下载/LAN-Drop」（MediaStore，无需存储权限）、
传输记录可取消并显示失败原因。

**真机验收（2026-09-30，vivo V2301A / Android 14）：**

| 项 | 结果 |
| --- | --- |
| 配对 → 首次全量同步 | ✅ 服务端既有 11 条消息全部拉到本地 |
| 手机 → PC 文字 | ✅ 服务端收到，发送者名正确 |
| PC → 手机文字 | ✅ 中文 / emoji / 换行均正确，经 WS 实时到达 |
| PC → 手机文件 20 MB | ✅ 落盘 `Download/LAN-Drop/`，sha256 与服务端逐字节一致 |
| 手机 → PC 文件 8 MB | ✅ 2 个分片，服务端 sha256 与源文件一致 |
| 断网重启后历史仍在 | ✅ 停服务端 + 强杀 App 重启，时间线仍完整；飞行模式下同样成立 |
| 断线自动重连 | ✅ 服务端恢复后 3 秒内回到「在线」 |
| 离线缺口补偿 | ✅ App 离线期间 PC 发的消息，重启后自动补齐 |

`cd android && ./gradlew :app:testDebugUnitTest` 另有 15 项协议一致性单测
（拿服务端真实响应样本验证 Kotlin 侧模型，防两端协议漂移）。

> 实测环境备注：当时手机 ←→ WSL 的 WiFi 吞吐只有 ~0.5 MB/s（`adb pull` 8 MB 用 15 秒），
> 应用层下载跑到 ~375 KB/s，约为链路的 75%，属正常开销。**瓶颈在无线 AP（设备老化），
> 不在实现**——同一套代码在正常链路上应能跑满百兆/千兆。定位这类问题要分层测：
> 先用 `adb pull`/`adb push` 测链路，再用应用内传输测实现，两者对比才有意义。
> 也正因为这条链路，断点续传不是锦上添花：1 GB 要传 35 分钟，任何一次中断都不能从头再来。

## 断点续传

链路慢，所以「传了一半断掉」是常态而不是异常。P3 的断点续传覆盖三种中断来源：
手动暂停、网络抖动、进程被杀。

**两端各有一条不变量**：

| 方向 | 权威进度在哪 | 写入前必须做的事 |
| --- | --- | --- |
| 上传（手机 → PC） | 服务端 `GET /api/v1/uploads/:id` 的 `receivedBytes` | 服务端 `ftruncate` 掉自己那侧的残字节（**必需**） |
| 下载（PC → 手机） | 本地 MediaStore 文件的真实长度 | 客户端定位到 offset 写入，并 `ftruncate` 对齐（兜底不变量） |

两条的分量不同，值得说清楚：

- **上传侧是必需的。** 服务端用 `O_APPEND` 追加写，不截断就会把新数据接在残字节后面，
  文件从此永久错位。已用负向冒烟用例实测复现：把 `truncateTo` 注释掉，
  「续传后收尾」立刻从通过变成 `sha256_mismatch`。
- **下载侧是兜底的。** `"rw"` 打开的 fd 不是 `O_APPEND`，写指针定位到 offset 后是逐字节
  覆盖，服务端 `Range` 返回的也正是从该 offset 开始的字节，所以对齐本身就已正确。
  截断的作用是把「开始写之前文件长度恒等于 offset」变成显式保证，防住
  「服务端返回的字节比文件原有尾巴还短」这类将来可能出现的情形。

「残字节」来自中途断流：请求体只落了一半盘，而两端记账的进度都停在这一片之前。

- 服务端新增 `GET /api/v1/uploads/:id`（查权威进度）与 `GET /api/v1/uploads?state=`（列会话），
  均按设备隔离，且不泄露临时路径。
- 客户端「暂停」= 取消协程但**不动两端现场**：服务端会话留在 `open`、临时分片留在磁盘、
  本地半成品留在原地；「继续」时上传按服务端、下载按本地文件重新对齐。
- 进程被杀后重启：记录落到「已暂停」而不是「失败」，点一下即可接着传。
  刻意**不做自动续传**——App 启动时 WiFi 常常还没就绪，自动重试会把可恢复的任务烧成失败。
- 网络抖动导致的失败同样保住进度：只有 4xx（除 408/429）与权限失效才判永久失败。
- 下载结束时显式核对字节数：服务端截断连接时 `read()` 返回 -1 而不是抛错，
  不核对就会把半截文件当成功落盘。

## 开发约定

- 提交与标签均使用 GPG 签名；推送使用 GitHub 隐私邮箱。
- 源码中变量与函数名一律英文，不使用拼音。
- 每完成一项功能同步更新 README 与协议文档，文档与实现脱节视为未完成。
