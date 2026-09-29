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
        │   ├─ UDP   :8788   局域网发现广播                                    │
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
        │  前台服务常驻接收        │                  └────────────────────────┘
        │  系统分享面板接入        │
        └────────────────────────┘
```

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
- **P2** Android 原生客户端 MVP（配对、会话、文字、文件、Room 缓存）
- **P3** 后台常驻接收、通知、系统分享面板、断点续传、缩略图
- **P4** 稳定性、打包分发、文档收尾

详见 [docs/技术选型与开发计划.md](docs/技术选型与开发计划.md)。

## 当前进度（P1 已可用）

浏览器打开 `http://<PC局域网IP>:8787`，扫码或一键配对后即可互发文字与文件：

| 配对页（PC 展示二维码） | 聊天页（文字 + 文件卡片） |
| --- | --- |
| ![配对页](docs/screenshots/p1-pairing.png) | ![聊天页](docs/screenshots/p1-chat.png) |

已实现：Token 配对（二维码 / 手输 / 本机一键）、聊天时间线（文字 / 链接 / 文件卡片 + 图片预览）、
拖拽 / 粘贴 / 选单上传（4 MiB 分片 + 断点续传 + sha256 校验）、Range 下载（含中文文件名 RFC 5987）、
WS 实时广播与指数退避重连（离线缺口由 `since=seq` 补拉）。
`node scripts/smoke-api.mjs` 43 项端到端冒烟全绿（HTTP + WebSocket）。

## 开发约定

- 提交与标签均使用 GPG 签名；推送使用 GitHub 隐私邮箱。
- 源码中变量与函数名一律英文，不使用拼音。
- 每完成一项功能同步更新 README 与协议文档，文档与实现脱节视为未完成。
