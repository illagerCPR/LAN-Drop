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
node scripts/smoke-api.mjs  # 67 项端到端冒烟（HTTP + WS + 断点续传 + UDP 发现）；必须先起服务端，且必须本机跑（配对码仅回环可读）
source scripts/dev-env.sh   # JAVA_HOME / ANDROID_HOME / PATH；非交互 shell 必须显式 source（~/.bashrc 会 early-return）
cd android && ./gradlew :app:assembleDebug
pnpm package:all            # 出便携包：dist/lan-drop-<版本>-{win-x64.zip,linux-x64.tar.gz}（默认先重建 web）
pnpm verify:package         # 验收 Linux 包（解压真包 → 包内产物启动 → 67 项 smoke）
node scripts/verify-package.mjs --target win   # 验收 Windows 包（真实 Windows 进程跑 smoke，经 WSL 互操作）
pnpm fix:ps1-bom            # .ps1 缺 UTF-8 BOM 时补齐（--check 只检查）；出包时缺 BOM 会直接失败
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
- 展示名 `config.serverName` 默认取 `os.hostname()`，可用 `LAN_DROP_SERVER_NAME` 覆盖。**不要再改回写死的「LAN-Drop 服务端」**：这个名字显示在手机聊天页标题与 Web 控制台标题上，那个位置唯一的职责是回答「我在跟哪台机器说话」，而「服务端」既是实现术语、多台 PC 时又全都同名。改完记得重启服务端（名字在 boot 时确定）。

## 便携打包（P4-1）

- 出包只走 `scripts/package.mjs`（`pnpm package:win|linux|all`）。仓库里服务端**永远**是
  `node src/index.ts` 无编译直跑；esbuild 只在出包时把它打成自包含单文件。产物布局固定为
  `app/server/server.js` + `app/web/dist`（+ `app/public` 兜底），与 `app.ts` 的
  `resolveStaticRoot()` 候选顺序对齐——**改布局必须同时改那里的注释与候选顺序**。
- **bundle 的 ESM 产物必须带 `createRequire` banner**（见 `package.mjs` 的 `banner`）。
  fastify/avvio/ws 内部 `require("node:events")`，ESM 里没有 `require` 时 esbuild 的
  `__require` 兜底直接抛 `Dynamic require of "..." is not supported`，表现为**启动即崩**。
  删掉这个 banner 或把它挪出 banner（`__require` 初始化更早）都会复现。
- **自包含检查用 esbuild metafile，不要改回正则扫产物文本**：ajv 把
  `require("ajv/dist/runtime/uri").default` 当字符串字面量写进生成代码，文本扫描必然误报。
  判定内建模块要同时认裸名（CJS 侧 `assert`）与 `node:` 前缀（ESM 侧 `node:assert`）。
- Windows 包**不做 Windows 服务**（不引 nssm/WinSW）：自启 = 计划任务「登录时」调
  `run-hidden.ps1`（Task Scheduler 没有隐藏窗口选项，直接起 node.exe 会留黑窗）。
  配置写**数据目录**里的 `lan-drop.env`（不是程序目录），否则升级覆盖时用户配置会丢。
  停止服务按「命令行含本包 `server.js` 路径」匹配进程——便携包可解压到任意路径。
- Windows 侧文件约束：`.ps1` 必须 **UTF-8 with BOM**（出包时缺 BOM 直接拒绝出包；
  用 `pnpm fix:ps1-bom` 补），`.cmd` 只写 **ASCII**（cmd.exe 在中文系统是 936 代码页，
  中文会乱码），中文说明放 `README.txt`。`[ordered]` 不能当参数类型（整个脚本解析失败），
  用 `[System.Collections.IDictionary]`。
- `node.exe` 从 npmmirror 拉、校验 `SHASUMS256.txt`、缓存在 `build/cache/`；版本固定在
  `package.mjs` 的 `BUNDLED_NODE_VERSION`，升它要顺带更新 `VERSION` 说明。
- 验收走 `scripts/verify-package.mjs`：解压**真包**→包内产物启动→67 项 smoke，并额外检查
  前端产物被托管（不是兜底冒烟页）、Linux 可执行位/`sh -n`/unit 路径、Windows 包 `.ps1`
  过真实 PowerShell 解析器、win 包 `node.exe` 在真实 Windows 上启动。**防火墙规则与
  登录自启任务的真实注册不在自动化范围内**（会改动本机系统），改动 install 脚本后要人工确认。
- WSL 互操作细节：`powershell.exe` 从 Node 调用时中文输出是 GBK，脚本里先设
  `[Console]::OutputEncoding = [Text.Encoding]::UTF8`；`Start-Process`/`*>` 重定向**不会**
  创建目录，日志目录要先建好，否则现象是「服务端一行日志都没有」。

### 系统分享面板与多选批量（P4-2）

- **分享 Intent 会成为本任务的「基础 Intent」**（Android 的既有行为）。由此引出两条纪律：
  1. MainActivity 必须是 `launchMode="singleTop"`，否则分享时会在栈上再叠一个实例，
     两套输入框抢着消费同一次分享；
  2. **不要**用 `savedInstanceState == null` 判断「是不是新的分享投递」来防重复——
     任务带着保存状态重建时该判断为假，用户分享过来会毫无反应（比偶尔重复更糟，实测踩过）。
     重复投递的正解是无界面中转 Activity（见 README 的已知问题）。
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

### 时间线图片缩略图（P4-3）

- **服务端没有、也不该有缩略图接口**：便携包是 esbuild 打出的自包含单文件，`sharp` 这类原生
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
- `./gradlew :app:testDebugUnitTest` 是 86 项 JVM 单测（无需设备与服务端）：`ProtocolJsonTest` 15 项协议一致性（改协议时同步更新里面的真实响应样本），`notify/TransferNoticeTest` 17 项通知逻辑，`net/ServerDiscoveryTest` 6 项发现应答解析（非本服务 / 未来版本 / 坏报文必须拒绝——UDP 报文来自局域网任意设备，解析必须严格），`share/ShareIntentTest` 18 项分享内容归一化（**按媒体条目身份去重**——真实 URI 对、不同卷、带查询串、缩略图表、SAF 文档 URI，另加 `ClipData` 兜底、`data` 兜底、空白文字、非分享 action、`String[]` 兼容），`media/ThumbnailSourceTest` 18 项缩略图取图顺序，`media/ThumbnailGeometryTest` 12 项预览尺寸与降采样算术。
- 文件下载落盘路径是 `Download/LAN-Drop`（MediaStore `RELATIVE_PATH`，注意大小写与连字符）。

### UDP 自动发现与连接状态（P3-3）

- 发现协议两端成对实现：服务端 `apps/server/src/discovery.ts`、客户端 `net/ServerDiscovery.kt`。
  探测报文是文本 `LANDROP-DISCOVER-v1`（UDP 8788，**整包精确匹配**）；应答 JSON
  `{ service, v, id, name, port }`。改任何一端的字段/魔法串/端口必须同步另一端，
  Android 侧常量在 `ServerDiscovery.DISCOVERY_PORT`。
- **问答式而非定时广播**：应答是单播，Android 不申请 `MulticastLock` 也收得到；
  客户端要同时发 `255.255.255.255` 与各网卡定向广播（部分 AP/ROM 组合会吞其中一种）。
  WSL2 镜像模式实测能收到局域网 UDP 广播，无需额外端口转发。
- **凭据失效是独立状态 `SocketState.CREDENTIALS_INVALID`**：服务端以 WS 4401 / HTTP 401
  拒绝时，客户端停止重连、横幅直接给出「解除配对重新配对」的出路。**不要把它并回
  RECONNECTING**——重试一万次也是 401，继续显示「正在重连…」是实测踩过的误导
  （服务端设备行丢失后横幅挂了半天）。找回扫描在凭据失效时跳过（服务端活着，扫了也空转）。
- 断线找回按 `serverId` 匹配、更新 `ConnectionStore.updateBaseUrl`（凭据不动），
  `connection` StateFlow 新值会自动触发重连。别在这里做「换 serverId 就重新配对」以外的事——
  换了 serverId 的服务端必须走清缓存重配对（见 `PairingRepository.pair` 里的既有逻辑）。
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
- 前台服务 `android:exported="false"`，所以 `adb shell am startservice` 起不动它（`Requires permission not exported from uid`）——通知按钮只能靠真实点击验证；且「暂停」「取消」两键同排相邻（实测 x=178 与 x=311），**绝不能盲点或按估算坐标点**，点错就是删掉几百 MB 半成品。

## 约定

- 提交与 tag 一律 GPG 签名（指纹 `D2E7DBACB6E233780954B2DEF09BEB5215872019`，无口令，可静默签）；推送必须用 GitHub 隐私邮箱 `63698328+illagerCPR@users.noreply.github.com`。
- 源码标识符全英文，禁拼音。
- 每完成一项功能同步更新 `README.md` 与 `docs/技术选型与开发计划.md`，文档与实现脱节视为未完成。
- `scripts/windows-allow-lan.ps1` 必须保持 **UTF-8 with BOM**：PS 5.1 对无 BOM 文件按 ANSI/GBK 解码，中文字符串会破坏语法；也不要手工另存为 ANSI（依赖系统区域设置）。`packaging/windows/*.ps1` 同样是硬要求：出包脚本遇到缺 BOM 直接拒绝出包，用 `pnpm fix:ps1-bom` 补齐（`--check` 只检查）。
- 需要 sudo 提权时找用户执行；**不准使用 snap 安装**。
