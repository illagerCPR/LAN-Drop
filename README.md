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

前置：WSL2（Ubuntu）/ Linux，Node.js ≥ 22，JDK 21。

```bash
# 1) 用户级安装 Android SDK（无需 root，不使用 snap）
bash scripts/install-android-sdk.sh

# 2) 让手机能访问 WSL 中的服务端
#    在 Windows 上以【管理员】身份运行 PowerShell：
#    powershell -ExecutionPolicy Bypass -File scripts/windows-allow-lan.ps1

# 3) 安装前端/服务端依赖
pnpm install
```

依赖源已固化到国内镜像（见 `.npmrc` 与 `android/settings.gradle.kts`）：npm 走 npmmirror，Maven 走阿里云 + Google 直连，Gradle 发行版走腾讯云镜像。

## 路线图

- **P0** 环境准备与仓库骨架
- **P1** 服务端核心 + Web UI（PC ↔ 手机浏览器即可互发文字与文件）
- **P2** Android 原生客户端 MVP（配对、会话、文字、文件、Room 缓存）
- **P3** 后台常驻接收、通知、系统分享面板、断点续传、缩略图
- **P4** 稳定性、打包分发、文档收尾

详见 [docs/技术选型与开发计划.md](docs/技术选型与开发计划.md)。

## 开发约定

- 提交与标签均使用 GPG 签名；推送使用 GitHub 隐私邮箱。
- 源码中变量与函数名一律英文，不使用拼音。
- 每完成一项功能同步更新 README 与协议文档，文档与实现脱节视为未完成。
