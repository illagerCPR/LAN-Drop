# TODO

> 生成于 2026-09-30，对应提交 `1d59cd9`。已完成的功能与逐项验收记录见
> [README「当前进度」](../README.md) 与 [docs/技术选型与开发计划.md](技术选型与开发计划.md)
> 各阶段的「验收记录与踩坑」小节。

## 功能收尾（2026-09-30 全部完成）

- [x] **摄像头扫码配对**（P3 遗留）：已实现。配对页新增「扫码配对」入口
  （`ui/pair/QrScanScreen.kt`），CameraX 分析流 → `scan/QrDecoder.kt`（zxing）解码，
  复用既有的 `extractPairingCode` 解析。真机 E2E（vivo V2301A）：对准 PC 屏上的二维码 →
  自动填入地址与配对码并提示「已从二维码填入地址与配对码」→ 点配对进入聊天页，
  服务端设备行同步出现。
- [x] **P4-2：未配对时分享文件**。Toast「请先完成配对，再分享文件」真机弹出
  （**用户目视确认**——该 ROM 的 Toast 不进无障碍树也不进 logcat，机器验证不可行），
  文件未入队、服务端无上传。
- [x] **P4-2：应用内「文件」多选的自动化验证**。前提修正：本机 DocumentsUI 选择器
  **会**向无障碍树暴露文件名（旧结论出自相册式媒体选择器）。手势：**长按进入选择模式 →
  点选其余项 → 点工具栏「选择」确认**（单击=直接返回该文件，是单选路径）。
  已按文件名驱动完成 E2E（全程零盲点）：两个探针文件（1243 B / 5687 B）逐字节到达服务端，
  sha256 与源一致。
- [x] **`MessageRepository.clearLocal()` 是死代码**：已接上——聊天页「更多 → 清除本地消息缓存」，
  确认对话框说明「服务端记录不受影响」。语义是**缓存修复**：清空 Room 后立即全量重拉
  （latestSeq 归零触发）。真机 E2E 两次：时间线清空 + 提示，随后消息从服务端重新拉回；
  第二次在服务端数据清空后执行，结果停在「还没有消息」，两端一致。

## 便携包（P4-1，已放弃）

- [x] ~~便携包（Windows zip / Linux tar.gz + systemd unit）~~：2026-09-30 交付双平台验收；
  同日决策记录 #8 落地（桌面常驻壳）后**放弃**——Tauri 自带安装包与 WebView2 引导，zip 便携包的
  node.exe 组装、install.ps1 与双平台验收脚本不再维护（`scripts/package.mjs`、
  `scripts/verify-package.mjs`、`packaging/` 已删除，git 历史可考；esbuild 自包含 bundle 的
  要点延续在桌面壳的 `prepare-resources.mjs` 里）。

## 桌面常驻壳（2026-09-30 完成）

- [x] **准备 Rust 工具链，PC 服务端程序使用 Tauri**（决策记录 #8 落地；实现与验收详见
  [计划文档 P4-6](技术选型与开发计划.md)）：
  1. Rust 工具链 ✅——Windows 侧 stable-msvc 1.97.1 + VS 18 BuildTools + WebView2（本机已有）；
     WSL 侧补装 rustup + stable 1.98.1（rustup dist 走 TUNA：rsproxy 的 rustup dist 镜像缺
     channel 清单，实测 404）。crates.io sparse 索引走 rsproxy（Windows 侧
     `C:\Users\illag\.cargo\config.toml`）。
  2. PC 常驻程序形态 ✅——`apps/desktop`：Tauri 2.12 托盘壳（无窗口；托盘菜单 = 打开控制台 /
     开机自启 / 退出），Node 服务端以 sidecar 内嵌（node.exe v24.20.0 + esbuild 自包含
     server.js），Rust 重写服务端不在本条目范围。
  3. 与便携包的关系 ✅——**放弃便携包**，分发走 Tauri NSIS 安装包。

  真机验收（Windows 开发机）：`tauri build` 出 `LAN-Drop_0.1.0_x64-setup.exe`（25.4 MB）；
  程序目录（`%LOCALAPPDATA%\LAN-Drop`）与数据根（`%LOCALAPPDATA%\LAN-Drop-Data`，壳注入
  `LAN_DROP_DATA_ROOT`）分离，卸载不碰数据；sidecar 拉起 node 监听 8787，healthz / 控制台 /
  pino 日志落 `server-sidecar.log` 全部验证；杀 sidecar → 壳随之退出（exit 1）有日志证据；
  单实例（第二次启动无第二个 sidecar）。托盘图标/菜单/退出/自启的目视确认待用户。
- [x] **Linux AppImage 桌面壳构建**（2026-10-01）：WSL 侧 `tauri build --bundles appimage` 出
  `LAN-Drop_0.1.0_amd64.AppImage`（123.55 MiB）；前置 4 个系统包（libwebkit2gtk-4.1-dev /
  libxdo-dev / libayatana-appindicator3-dev / librsvg2-dev，sudo 一次）；Linux node sidecar 由
  `prepare-resources.mjs` 按运行平台自动下载校验。HTTP/进程层已机器验证（healthz / info /
  控制台 / 日志 / 数据根 / 杀 sidecar → 壳退出）；托盘目视项需真 Linux 桌面（WSLg 无系统托盘，
  壳已做托盘初始化失败降级）。
- [ ] macOS 桌面壳构建：待有 macOS 机器/需求时再补。

## 安全加固轮（2026-10-01 完成）

> 起因是全面改进审计定级的三个 P0（0 字节上传 500、token 进访问日志、分片并发双写）
> 与三个 P1（无设备撤销、无 CI、威胁模型未成文）。实现细节见 README「安全与威胁模型」
> 「设备管理」两节与 AGENTS.md 服务端/Android 小节的新增条目。

- [x] **0 字节文件上传**：服务端会话创建即落空临时文件 + complete 核对盘上尺寸；
  Android 侧区分「大小未知」（`-1` 哨兵）与「真空文件」（0），不再把空文件报成
  「无法确定文件大小」；Web 端天然兼容。
- [x] **token 不再进日志**：Fastify req 序列化器把访问日志里的 `?token=` 打码；
  `pnpm verify` 的冒烟收尾会扫服务端日志，发现未脱敏 token 即失败。
- [x] **`?token=` 作用域收窄**：仅文件下载与 WS 握手（浏览器硬约束）可携带；
  其余接口只认 Authorization 头。Android WS 与下载全部改走请求头。
- [x] **分片写入互斥**：同一会话并发 PATCH 回 409 `chunk_write_in_progress`。
- [x] **客户端收尾摘要自证**：complete 接受 body.sha256（不符 422）；Android 边传
  边算、续传会话重读前缀补摘要（选项 B）；Web 刻意不声明（不对称已记录在案）。
- [x] **设备撤销**：DELETE /pair/devices/:id（仅回环）+ 回收上传会话 + 踢下线 4401；
  Web 控制台新增「设备管理」面板。
- [x] **CI**：GitHub Actions 三 job（ts 全链路门禁 / android JVM 单测硬门禁，109 项 CI 全过 /
  hygiene：ps1 BOM + 文档链接检查）。
- [x] **`pnpm verify` 一键门禁**：typecheck + 单测 + 自起 8899 服务端全量冒烟
  （93 项）+ 日志 token 泄漏扫描。
- [x] **真机验收**（2026-10-01，详见计划文档「安全加固轮」验收边界）：0 字节上传、
  1 MB 摘要自证、250 MB 强杀续传（三方 sha256 一致）、撤销踢下线与凭据失效横幅、
  WS 无查询凭据——五项全过；`am kill` 杀不掉带前台服务的进程（要用 `am force-stop`）。

## 可选增强（规划内，未排期）

- [ ] **边界用例收尾**：磁盘满（0 字节、超长/纯中文文件名、并发上传已做进冒烟）。
- [ ] **自签 TLS + 指纹固定**（决策记录 #3：列为 P4 可选项）。
- [ ] **后台「常驻接收」开关**（用户可开关的常驻模式）：当前刻意不做——无传输时进程会被
  系统冻结，做的话要连同冻结后的重连语义一起设计（理由见计划文档 P3-2 坑 1）。
- [ ] **Android 多服务端**：`Connection?` 单数存储，切换服务端要清缓存重配对。
- [ ] **Web 无障碍**：aria/role/alt 覆盖接近于零。
- [ ] **LICENSE 与 npm `license` 字段**、CHANGELOG、Android release 签名与 minify
  （当前 release APK 实为 debug 配置 42.9 MB）。

## 刻意不做（记录在案，勿当遗漏）

- Web 端**下载方向**的暂停/恢复：浏览器原生下载由浏览器接管，无法编程暂停
  （上传侧已在 P4-4 完成，见 `apps/web/src/upload.ts` 的 `Uploader`）。
- 分享进来的文件**不做持久授权**：`ACTION_SEND` 的 URI 授权活不过进程，进程被杀后
  重新上传会落成「源文件不可读」的永久失败——刻意取舍（应用内多选走 SAF 才有持久授权）。
- Windows 包不做 Windows 服务：自启走计划任务「登录时」（见 README 便携打包小节）。
