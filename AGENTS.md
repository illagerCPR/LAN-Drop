# AGENTS.md

面向 AI 编码代理的仓库指南。只记录「不看会踩坑」的事，通用常识不写。

## 仓库速览

- pnpm monorepo：`apps/server`（Node 24 + Fastify + `node:sqlite`）、`apps/web`（Vite + React + TS）、`packages/protocol`（协议单一事实源）；`android/` 是独立 Gradle 工程。
- 协议唯一事实源：`packages/protocol/src/index.ts`。Android 侧 Kotlin 镜像（`android/.../protocol/Protocol.kt`）必须与其逐字段对齐，字段名以此为准。

## 常用命令

```bash
pnpm install
pnpm -r typecheck           # 全仓类型检查；没有测试框架，这就是 TS 侧的验证手段，提交前必跑
pnpm dev                    # 服务端 --watch，监听 0.0.0.0:8787
pnpm web:build              # 产出 apps/web/dist（dev 热更用 pnpm web:dev）
node scripts/smoke-api.mjs  # 62 项端到端冒烟（HTTP + WS + 断点续传）；必须先起服务端，且必须本机跑（配对码仅回环可读）
source scripts/dev-env.sh   # JAVA_HOME / ANDROID_HOME / PATH；非交互 shell 必须显式 source（~/.bashrc 会 early-return）
cd android && ./gradlew :app:assembleDebug
```

- 验证顺序：服务端改动后**重启进程**再跑 smoke（静态根、数据目录在 boot 时确定）；`node --watch` 只热载 src。
- `apps/web/dist` 不存在时服务端退回 `apps/server/public` 冒烟页——Web 页面不对先确认有没有 build 过、服务端有没有重启。

## 环境与镜像（本机硬事实，勿改回官方源）

- npm → npmmirror（`.npmrc`）；Gradle 发行版 → 腾讯云（wrapper）；Maven → `google()` 直连 + 阿里云，顺序见 `android/settings.gradle.kts` 顶部注释。官方源实测仅 44~210 KB/s，镜像 1.2~3.2 MB/s。
- 本机 `/etc/hosts` 把 github.com 劫持到本地反代：curl 可用，但 **Java/Gradle 直连 GitHub 会 PKIX 失败**。任何构建依赖都不要走 GitHub 直链。
- WSL2 镜像网络模式；手机访问 WSL 服务端靠 Windows 防火墙放行规则（`scripts/windows-allow-lan.ps1`，已执行过，无需重跑）。「Windows 访问自己的 LAN IP 超时」是镜像模式**假阴性**，以服务端日志 `remoteAddress` 判断手机连通性。

## 服务端（apps/server）

- Node 24 原生类型剥离直接跑 TS：**无 tsx/ts-node、无编译步**。相对导入必须带 `.ts` 扩展名；tsconfig 开 `erasableSyntaxOnly`——禁 enum / namespace / 构造器参数属性。
- pnpm 11 拦构建脚本，`pnpm-workspace.yaml` 的 `allowBuilds: esbuild: true` 是必须的；再遇 `ERR_PNPM_IGNORED_BUILDS` 就在那里加白。
- **WS 路由必须注册在 `app.register()` 子作用域内**（见 `app.ts` 内注释）。写在根实例上不会被 @fastify/websocket 的 onRoute 钩子包装：handler 实际收到 `(request, reply)`，`socket` 参数是 Request 对象——鉴权字段全空、`socket.close()` 直接 TypeError，且 HTTP 层测试发现不了。
- 上传 PATCH 只注册了 `application/octet-stream` 的 contentTypeParser；客户端必须显式设该 Content-Type（`File.slice()` 得到的 Blob 会继承原文件 MIME，否则 415）。
- **上传 PATCH 在追加前必须 `truncateTo(tempPath, receivedBytes)`**（`routes/files.ts`）。`appendStreamToFile` 用 `O_APPEND`，中途断流时已落盘的残字节会留在文件里而 `receivedBytes` 不推进；不截断就从权威 offset 续传会把新数据接在残字节后面，静默写坏文件（`scripts/smoke-api.mjs` 有负向用例：注释掉这一行，「续传后收尾」立刻变 `422 sha256_mismatch`）。**不要删这一行。**
- 断点续传查询接口：`GET /api/v1/uploads/:id`（权威进度、`resumable`、`chunkSize`）、`GET /api/v1/uploads?state=open|completed|aborted`。均按设备隔离，响应里绝不能出现 `tempPath`。
- 鉴权：`Authorization: Bearer <token>` 或 `?token=`（后者专给 `<img>`/`<a>` 下载用，它们带不了请求头）。配对码接口仅回环地址可调。
- 数据根：Linux `~/.local/share/lan-drop`，Windows `%LOCALAPPDATA%\LAN-Drop`；env 覆盖项（`LAN_DROP_PORT` 等）见 `src/config.ts`。

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
- 「暂停」与「取消」在协程里都是 `CancellationException`，只能靠 `TransferRepository.intents` 里记的意图区分：暂停保住两端现场，取消才清场。改取消路径时务必两条都走一遍。
- SAF 源文件必须 `takePersistableUriPermission(uri, FLAG_GRANT_READ_URI_PERMISSION)`，否则进程重启后续传打不开源文件；传完/取消时 `release`。**只有读权限的 URI 不能用 `"rw"` 探测长度**，会抛 `SecurityException` 被误判成「文件丢失」，固定用 `"r"`。
- 失败判据：4xx（除 408/429）与 `SecurityException` = 永久失败，其余（IO、5xx、超时）= 可恢复，落到「已暂停」保住进度。下载结束时必须核对字节数——服务端截断连接时 `read()` 返回 -1 而不抛错。
- debug 构建的**实际包名带 `.debug` 后缀**（`applicationIdSuffix`），`am start` / `run-as` / `pm list packages` 都要用它，用 `applicationId` 会报「Activity class does not exist」并误判成没装上。
- `./gradlew :app:testDebugUnitTest` 是 15 项协议一致性单测（JVM，无需设备与服务端）。改协议时同步更新 `app/src/test/.../ProtocolJsonTest.kt` 里的真实响应样本。
- 文件下载落盘路径是 `Download/LAN-Drop`（MediaStore `RELATIVE_PATH`，注意大小写与连字符）。

## 约定

- 提交与 tag 一律 GPG 签名（指纹 `D2E7DBACB6E233780954B2DEF09BEB5215872019`，无口令，可静默签）；推送必须用 GitHub 隐私邮箱 `63698328+illagerCPR@users.noreply.github.com`。
- 源码标识符全英文，禁拼音。
- 每完成一项功能同步更新 `README.md` 与 `docs/技术选型与开发计划.md`，文档与实现脱节视为未完成。
- `scripts/windows-allow-lan.ps1` 必须保持 **UTF-8 with BOM**：PS 5.1 对无 BOM 文件按 ANSI/GBK 解码，中文字符串会破坏语法；也不要手工另存为 ANSI（依赖系统区域设置）。
- 需要 sudo 提权时找用户执行；**不准使用 snap 安装**。
