#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]
//! LAN-Drop 桌面常驻壳。
//!
//! 形态：**只有托盘，没有窗口**。服务端是现有 Node 实现，以 sidecar 方式拉起
//! （release = 打包内 `binaries/node` + `resources/server/server.js`；debug = PATH 里的
//! node 直跑 `LAN_DROP_DEV_ENTRY` 指向的 `apps/server/src/index.ts`）。控制台（Web UI）
//! 在系统浏览器打开；数据目录沿用服务端默认（Windows `%LOCALAPPDATA%\LAN-Drop`），
//! 与开发态、便携包时代一致，配对数据天然延续。

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

/// sidecar 的生命周期与「是不是壳主动杀的」标记：主动退出时先杀进程，
/// sidecar 的 Terminated 事件随之而来，不能再触发一次 exit(1)（会掩盖正常退出码）。
struct ServerHandle {
    child: Mutex<Option<CommandChild>>,
    killed_by_us: AtomicBool,
}

fn console_url() -> String {
    let port = std::env::var("LAN_DROP_PORT")
        .ok()
        .and_then(|value| value.trim().parse::<u16>().ok())
        .unwrap_or(DEFAULT_PORT);
    format!("http://127.0.0.1:{port}/")
}

fn open_console(app: &AppHandle) {
    if let Err(error) = app.opener().open_url(console_url(), None::<&str>) {
        eprintln!("打开控制台失败: {error}");
    }
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

            let tray_result = TrayIconBuilder::with_id("main-tray")
                .icon(app.default_window_icon().expect("应用图标缺失").clone())
                .tooltip("LAN-Drop")
                .menu(&menu)
                .show_menu_on_left_click(true)
                .on_menu_event(|app, event| match event.id.as_ref() {
                    "open" => open_console(app),
                    "autostart" => toggle_autostart(app),
                    "quit" => app.exit(0),
                    _ => {}
                })
                .build(app);
            // 无 StatusNotifierWatcher 的环境（WSLg、部分 Wayland 会话）里 libappindicator
            // 初始化不了托盘。Linux 下降级为「无托盘但服务端照常常驻」——服务端可用性
            // 优先于托盘入口，退出交由 sidecar 意外退出路径或 pkill -x LAN-Drop。
            // Windows 不降级：托盘是唯一交互入口，失败即 setup 失败（与历史行为一致）。
            if let Err(error) = tray_result {
                if cfg!(windows) {
                    return Err(error.into());
                }
                eprintln!("托盘不可用（{error}），LAN-Drop 以无托盘模式继续；退出：pkill -x LAN-Drop");
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
