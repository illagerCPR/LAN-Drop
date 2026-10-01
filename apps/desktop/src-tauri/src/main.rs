#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]
//! LAN-Drop 桌面常驻壳。
//!
//! 形态：**只有托盘，没有窗口**。服务端是现有 Node 实现，以 sidecar 方式拉起
//! （release = 打包内 `binaries/node` + `resources/server/server.js`；debug = PATH 里的
//! node 直跑 `LAN_DROP_DEV_ENTRY` 指向的 `apps/server/src/index.ts`）。控制台（Web UI）
//! 在系统浏览器打开；数据目录沿用服务端默认（Windows `%LOCALAPPDATA%\LAN-Drop`），
//! 与开发态、便携包时代一致，配对数据天然延续。
//!
//! Linux 的降级路径（WSLg、极简会话、GNOME 未装 AppIndicator 扩展）：**服务端可用性优先于托盘**。
//! 会话总线上没有 StatusNotifierWatcher 时根本不建托盘（那条路只会让 libayatana-appindicator
//! 退化成失败的 GtkStatusIcon fallback 并打出 Gtk-CRITICAL，图标却照样不显示），
//! 改为启动即打开控制台——无托盘时浏览器是用户唯一能看见的入口。Windows 不降级。

use std::io::Write;
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Mutex;

use tauri::{
    menu::{CheckMenuItem, Menu, MenuItem},
    tray::TrayIconBuilder,
    AppHandle, Manager, RunEvent,
};
use tauri_plugin_autostart::{MacosLauncher, ManagerExt as _};
use tauri_plugin_opener::OpenerExt;
use tauri_plugin_shell::process::{CommandChild, CommandEvent};
use tauri_plugin_shell::ShellExt;

/// 服务端默认端口（与服务端 config.ts 一致；可用 LAN_DROP_PORT 覆盖）。
const DEFAULT_PORT: u16 = 8787;

/// TLS 开启时回环明文监听器的默认端口（与服务端 config.ts 一致）。
const DEFAULT_LOOPBACK_PORT: u16 = 8789;

/// sidecar 的生命周期与「是不是壳主动杀的」标记：主动退出时先杀进程，
/// sidecar 的 Terminated 事件随之而来，不能再触发一次 exit(1)（会掩盖正常退出码）。
struct ServerHandle {
    child: Mutex<Option<CommandChild>>,
    killed_by_us: AtomicBool,
}

/// 控制台（回环明文监听器）的端口。
fn console_port() -> u16 {
    // TLS 语义必须与服务端 config.ts 的 parseBoolOr 完全一致：
    // 未设置 = 开启（服务端默认），设置后只有 "1"/"true" 算开启。
    // TLS 开启时 LAN 端口是自签证书，浏览器会弹警告；控制台走回环明文端口。
    let tls_enabled = match std::env::var("LAN_DROP_TLS") {
        Ok(value) => {
            let value = value.trim();
            value == "1" || value.eq_ignore_ascii_case("true")
        }
        Err(_) => true,
    };
    if tls_enabled {
        std::env::var("LAN_DROP_LOOPBACK_PORT")
            .ok()
            .and_then(|value| value.trim().parse::<u16>().ok())
            .unwrap_or(DEFAULT_LOOPBACK_PORT)
    } else {
        std::env::var("LAN_DROP_PORT")
            .ok()
            .and_then(|value| value.trim().parse::<u16>().ok())
            .unwrap_or(DEFAULT_PORT)
    }
}

fn console_url() -> String {
    format!("http://127.0.0.1:{}/", console_port())
}

/// 等回环监听器真的就绪，再打开控制台（另起线程，不阻塞 setup）。
///
/// sidecar 是刚 spawn 出来的，node 要一两秒才 bind 端口；此刻抢先打开浏览器只会得到
/// 「无法访问此页面」——实测这个时间差下服务端日志里连一条请求都没有（连接被拒，压根没到达），
/// 用户看到的就是「控制台打不开」。轮询到端口可连接为止（最多 30 秒）再交给浏览器。
fn open_console_when_ready(app: AppHandle) {
    std::thread::spawn(move || {
        let port = console_port();
        let address = std::net::SocketAddr::from(([127, 0, 0, 1], port));
        let started = std::time::Instant::now();
        let deadline = started + std::time::Duration::from_secs(30);
        while std::time::Instant::now() < deadline {
            if std::net::TcpStream::connect_timeout(&address, std::time::Duration::from_millis(300))
                .is_ok()
            {
                eprintln!(
                    "控制台已就绪（等待 {} ms），打开 {}",
                    started.elapsed().as_millis(),
                    console_url()
                );
                open_console(&app);
                return;
            }
            std::thread::sleep(std::time::Duration::from_millis(200));
        }
        eprintln!("等待控制台端口 {port} 超时，仍尝试打开 {}", console_url());
        open_console(&app);
    });
}

fn open_console(app: &AppHandle) {
    let url = console_url();
    // WSLg 里没有 Linux 浏览器：xdg-open 只会「成功返回、什么都没有发生」，控制台要交给
    // Windows 侧的默认浏览器打开（`cmd.exe /c start` 走 WSL 交互；WSL2 镜像模式下 Windows
    // 浏览器访问 127.0.0.1:<回环端口> 能直达 WSL 的回环监听器，已实测 200）。
    #[cfg(target_os = "linux")]
    if is_wsl() && spawn_windows_browser(&url) {
        return;
    }
    if let Err(error) = app.opener().open_url(url.clone(), None::<&str>) {
        eprintln!("打开控制台失败（{error}），请手动在浏览器访问 {url}");
    }
}

/// 是否运行在 WSL 里（WSLg 的「打开浏览器」必须借 Windows 侧）。
#[cfg(target_os = "linux")]
fn is_wsl() -> bool {
    std::env::var_os("WSL_DISTRO_NAME").is_some()
        || std::env::var_os("WSL_INTEROP").is_some()
        || std::fs::read_to_string("/proc/version")
            .map(|text| text.to_ascii_lowercase().contains("microsoft"))
            .unwrap_or(false)
}

/// 用 Windows 侧默认浏览器打开 URL；`cmd.exe` 不存在（非 WSL）或启动失败时返回 false，
/// 由调用方退回 xdg-open。/c start 的第一个参数是窗口标题，必须给空串占位。
/// 失败原因一定要打出来：这条路上任何静默失败都会表现成「什么都没发生」，极难排查。
#[cfg(target_os = "linux")]
fn spawn_windows_browser(url: &str) -> bool {
    match std::process::Command::new("cmd.exe")
        .args(["/c", "start", "", url])
        .stdout(std::process::Stdio::null())
        .stderr(std::process::Stdio::null())
        .spawn()
    {
        Ok(_) => true,
        Err(error) => {
            eprintln!("调用 Windows 侧 cmd.exe 失败（{error}）");
            false
        }
    }
}

/// 会话总线上有没有 StatusNotifierWatcher 宿主（KDE 面板、GNOME 的 AppIndicator 扩展等）。
///
/// 没有宿主时（WSLg、无桌面环境的极简会话、GNOME 未装扩展）**不要**去建托盘：
/// libayatana-appindicator 会退化成 GtkStatusIcon fallback，图标照样不显示，却在 GTK 内部
/// 打出 `gtk_widget_get_scale_factor: assertion 'GTK_IS_WIDGET (widget)' failed`
/// （fallback 的托盘 widget 根本没建出来），用户看到的就是这行吓人的 CRITICAL 加上
/// 「什么都没有发生」。这里把这条路径整条跳过，改为自动打开控制台——无托盘时浏览器是唯一入口。
#[cfg(target_os = "linux")]
fn status_notifier_watcher_present() -> bool {
    use gio::glib::variant::ToVariant;

    let Ok(bus) = gio::bus_get_sync(gio::BusType::Session, gio::Cancellable::NONE) else {
        return false;
    };
    let parameters = ("org.kde.StatusNotifierWatcher",).to_variant();
    let reply = bus.call_sync(
        Some("org.freedesktop.DBus"),
        "/org/freedesktop/DBus",
        "org.freedesktop.DBus",
        "NameHasOwner",
        Some(&parameters),
        None,
        gio::DBusCallFlags::NONE,
        2_000,
        gio::Cancellable::NONE,
    );
    reply
        .ok()
        .and_then(|value| value.get::<(bool,)>())
        .map(|(has_owner,)| has_owner)
        .unwrap_or(false)
}

/// 滤掉两条与本项目无关的上游固定噪音，其余 GTK 消息原样转交 GLib 默认处理器：
///  1. `libayatana-appindicator-WARNING: ... is deprecated`（库构造时无条件打印）；
///  2. `Gtk-CRITICAL: gtk_widget_get_scale_factor: assertion 'GTK_IS_WIDGET (widget)' failed`
///     —— 见 status_notifier_watcher_present() 的注释；有托盘宿主时它也可能出现，无功能后果。
/// 过滤只按域 + 完整消息片段匹配，**不要**扩大范围：真实的 GTK 错误必须照旧可见。
#[cfg(target_os = "linux")]
fn install_linux_log_filter() {
    use glib::LogLevels;

    glib::log_set_handler(
        Some("Gtk"),
        LogLevels::LEVEL_CRITICAL,
        false,
        false,
        |domain, level, message| {
            if message.contains("gtk_widget_get_scale_factor") {
                return;
            }
            glib::log_default_handler(domain, level, Some(message));
        },
    );
    glib::log_set_handler(
        Some("libayatana-appindicator"),
        LogLevels::LEVEL_WARNING | LogLevels::LEVEL_INFO | LogLevels::LEVEL_DEBUG,
        false,
        false,
        |_, _, _| {},
    );
}

fn kill_server(app: &AppHandle) {
    let Some(state) = app.try_state::<ServerHandle>() else {
        return;
    };
    let Ok(mut guard) = state.child.lock() else {
        return;
    };
    if let Some(child) = guard.take() {
        state.killed_by_us.store(true, Ordering::SeqCst);
        let _ = child.kill();
    }
}

/// sidecar 的 stdout/stderr 落到应用日志目录（Windows：`%LOCALAPPDATA%\<identifier>\logs\`）。
struct ServerLog {
    file: Option<std::fs::File>,
}

/// Unix 时间戳 → `YYYY-MM-DD HH:MM:SS`（UTC）。纯 std 实现（Howard Hinnant 的 civil_from_days），
/// 不为一条日志前缀引入 chrono/time 依赖。
fn format_timestamp(secs: u64) -> String {
    let days = (secs / 86_400) as i64;
    let rem = secs % 86_400;
    // civil_from_days：把 Unix 天数转成 (年, 月, 日)
    let z = days + 719_468;
    let era = z.div_euclid(146_097);
    let doe = z.rem_euclid(146_097);
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    let year = yoe + era * 400;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let day = doy - (153 * mp + 2) / 5 + 1;
    let month = if mp < 10 { mp + 3 } else { mp - 9 };
    let year = if month <= 2 { year + 1 } else { year };
    format!(
        "{year:04}-{month:02}-{day:02} {:02}:{:02}:{:02}",
        rem / 3600,
        (rem % 3600) / 60,
        rem % 60
    )
}

impl ServerLog {
    fn open(app: &AppHandle) -> Self {
        let dir = app
            .path()
            .app_log_dir()
            .map_err(|error| error.to_string())
            .and_then(|dir: PathBuf| {
                std::fs::create_dir_all(&dir).map_err(|error| error.to_string())?;
                Ok(dir)
            });
        let file = match dir {
            Ok(dir) => std::fs::OpenOptions::new()
                .create(true)
                .append(true)
                .open(dir.join("server-sidecar.log"))
                .ok(),
            Err(error) => {
                eprintln!("打开日志目录失败: {error}");
                None
            }
        };
        Self { file }
    }

    fn line(&mut self, text: &str) {
        let secs = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map(|duration| duration.as_secs())
            .unwrap_or(0);
        if let Some(file) = self.file.as_mut() {
            let _ = writeln!(file, "{} {text}", format_timestamp(secs));
        }
    }
}

/// NSIS currentUser 默认把程序装进 `%LOCALAPPDATA%\LAN-Drop`，与服务端默认数据根
/// **撞在同一个目录**——卸载器会连用户数据（SQLite、收到的文件）一起删掉。
/// 桌面壳因此把数据根挪到旁边的 `LAN-Drop-Data`；用户显式设置过 `LAN_DROP_DATA_ROOT`
/// 时不覆盖。仅 Windows 注入（Linux/macOS 的默认数据根不在安装目录里，无此冲突）。
fn ensure_data_root() {
    if !cfg!(windows) || std::env::var_os("LAN_DROP_DATA_ROOT").is_some() {
        return;
    }
    if let Some(local) = std::env::var_os("LOCALAPPDATA") {
        let mut root = PathBuf::from(local);
        root.push("LAN-Drop-Data");
        std::env::set_var("LAN_DROP_DATA_ROOT", root);
    }
}

fn spawn_server(app: &AppHandle) -> Result<(), Box<dyn std::error::Error>> {
    let mut log = ServerLog::open(app);
    log.line(&format!("LAN-Drop 桌面壳启动，控制台 {}", console_url()));

    let command = if cfg!(debug_assertions) {
        let entry = std::env::var("LAN_DROP_DEV_ENTRY")
            .expect("调试运行需要 LAN_DROP_DEV_ENTRY 指向 apps/server/src/index.ts");
        app.shell().command("node").args([entry])
    } else {
        // 不用 resource_dir()：裸跑（target/release 直接双击）时它解析出的路径不可靠，
        // 实测把参数变成了盘符根 `C:`，node 报 EISDIR。exe 同级布局在裸跑与安装后一致
        // （CLI 与 NSIS 都把 sidecar 与 resources 平铺到主程序旁），current_exe 永远确定。
        let exe_dir = std::env::current_exe()?
            .parent()
            .ok_or("主程序没有父目录")?
            .to_path_buf();
        // sidecar 与主程序同平台同目录：Windows 是 node.exe，Linux（AppImage 内）是 node。
        let node = exe_dir.join(if cfg!(windows) { "node.exe" } else { "node" });
        // resources 落点两平台不同：Windows（NSIS/CLI）全平铺在 exe 同级；Linux（AppImage/deb
        // 的 AppRun 布局）在 exe 的 ../lib/<productName>/ —— usr/bin/LAN-Drop 旁是 sidecar node，
        // server/server.js 与 web/dist 却在 usr/lib/LAN-Drop/ 下。按存在性探测两条候选，
        // 谁存在用谁（server.js 上级的 web/dist 与 resolveStaticRoot() 候选顺序天然对齐）。
        let lib_resources = exe_dir
            .parent()
            .map(|prefix| prefix.join("lib").join("LAN-Drop"))
            .unwrap_or_else(|| exe_dir.clone());
        let script_candidates = [
            exe_dir.join("server").join("server.js"),
            lib_resources.join("server").join("server.js"),
        ];
        let script = script_candidates
            .iter()
            .find(|candidate| candidate.exists())
            .ok_or_else(|| {
                format!(
                    "sidecar 资源缺失：node={} exists={}，server.js 两条候选均不存在：{}",
                    node.display(),
                    node.exists(),
                    script_candidates
                        .iter()
                        .map(|path| path.display().to_string())
                        .collect::<Vec<_>>()
                        .join("、")
                )
            })?
            .to_path_buf();
        log.line(&format!("sidecar：{} {}", node.display(), script.display()));
        // sidecar() 解析的是 exe 同级的扁平名字：externalBin 配置里的 "binaries/node" 只是
        // 打包器的源路径（src-tauri/binaries/node-<triple>.exe），CLI/安装器都会把它平铺成
        // exe 旁的 node.exe —— 这里传 "binaries/node" 会找 exe_dir/binaries/node.exe，
        // 报 os error 3（路径不存在）。
        app.shell().sidecar("node")?.args([script])
    };

    let (mut rx, child) = command.spawn()?;
    app.manage(ServerHandle {
        child: Mutex::new(Some(child)),
        killed_by_us: AtomicBool::new(false),
    });

    let handle = app.clone();
    tauri::async_runtime::spawn(async move {
        while let Some(event) = rx.recv().await {
            match event {
                CommandEvent::Stdout(line) | CommandEvent::Stderr(line) => {
                    log.line(&String::from_utf8_lossy(&line));
                }
                CommandEvent::Terminated(status) => {
                    log.line(&format!("服务端进程退出: {status:?}"));
                    let killed_by_us = handle
                        .try_state::<ServerHandle>()
                        .map(|state| state.killed_by_us.load(Ordering::SeqCst))
                        .unwrap_or(true);
                    if !killed_by_us {
                        log.line("服务端意外退出，壳随之退出（exit 1）");
                        handle.exit(1);
                    }
                    break;
                }
                _ => {}
            }
        }
    });
    Ok(())
}

static AUTOSTART_ITEM: std::sync::OnceLock<CheckMenuItem<tauri::Wry>> = std::sync::OnceLock::new();

fn toggle_autostart(app: &AppHandle) {
    let autolaunch = app.autolaunch();
    let enabled = autolaunch.is_enabled().unwrap_or(false);
    let result = if enabled {
        autolaunch.disable()
    } else {
        autolaunch.enable()
    };
    match result {
        Ok(()) => {
            if let Some(item) = AUTOSTART_ITEM.get() {
                let _ = item.set_checked(!enabled);
            }
        }
        Err(error) => eprintln!("切换开机自启失败: {error}"),
    }
}

fn main() {
    #[cfg(target_os = "linux")]
    install_linux_log_filter();

    tauri::Builder::default()
        // single-instance 必须最先注册：第二次启动只把控制台拉起来，绝不出现第二个 sidecar。
        .plugin(tauri_plugin_single_instance::init(|app, _argv, _cwd| {
            open_console(app);
        }))
        .plugin(tauri_plugin_shell::init())
        .plugin(tauri_plugin_autostart::init(
            MacosLauncher::LaunchAgent,
            None,
        ))
        .plugin(tauri_plugin_opener::init())
        .setup(|app| {
            ensure_data_root();
            spawn_server(app.handle())?;

            // 服务端已经在跑了，接下来只处理「入口」：有托盘宿主就建托盘，没有就走降级路径
            // （跳过 GTK 托盘——那条路只会打断言失败且图标照样不显示——改为自动打开控制台）。
            #[cfg(target_os = "linux")]
            let tray_host = status_notifier_watcher_present();
            #[cfg(not(target_os = "linux"))]
            let tray_host = true;

            let mut tray_ready = false;
            if tray_host {
                let open = MenuItem::with_id(app, "open", "打开控制台", true, None::<&str>)?;
                let autostart_enabled = app.autolaunch().is_enabled().unwrap_or(false);
                let autostart = CheckMenuItem::with_id(
                    app,
                    "autostart",
                    "开机自启",
                    true,
                    autostart_enabled,
                    None::<&str>,
                )?;
                let _ = AUTOSTART_ITEM.set(autostart.clone());
                let quit = MenuItem::with_id(app, "quit", "退出 LAN-Drop", true, None::<&str>)?;
                let menu = Menu::with_items(app, &[&open, &autostart, &quit])?;

                let mut builder = TrayIconBuilder::with_id("main-tray")
                    .tooltip("LAN-Drop")
                    .menu(&menu)
                    .show_menu_on_left_click(true)
                    .on_menu_event(|app, event| match event.id.as_ref() {
                        "open" => open_console(app),
                        "autostart" => toggle_autostart(app),
                        "quit" => app.exit(0),
                        _ => {}
                    });
                // 图标缺失不该让整个壳失败：没有图标的托盘项仍然是可用的入口。
                if let Some(icon) = app.default_window_icon() {
                    builder = builder.icon(icon.clone());
                }
                match builder.build(app) {
                    Ok(_) => tray_ready = true,
                    Err(error) => {
                        // Windows 不降级：托盘是唯一交互入口，失败即 setup 失败（与历史行为一致）。
                        if cfg!(windows) {
                            return Err(error.into());
                        }
                        eprintln!("托盘不可用（{error}）。");
                    }
                }
            }

            eprintln!("LAN-Drop 服务端已在后台常驻，控制台 {}", console_url());
            if !tray_ready {
                // 无托盘宿主（WSLg / 极简会话 / GNOME 未装扩展）或托盘创建失败：
                // 浏览器是用户唯一能看见的入口，启动即打开——但要等端口真的就绪（见
                // open_console_when_ready：抢在 sidecar bind 之前打开只会得到错误页）。
                eprintln!("（无托盘模式）退出：pkill -x LAN-Drop");
                open_console_when_ready(app.handle().clone());
            }
            Ok(())
        })
        .build(tauri::generate_context!())
        .expect("构建 Tauri 应用失败")
        .run(|app, event| match event {
            RunEvent::ExitRequested { .. } => kill_server(app),
            RunEvent::Exit => kill_server(app),
            _ => {}
        });
}
