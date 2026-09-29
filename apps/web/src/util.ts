/** 把字节数格式化成人类可读的大小。 */
export function formatBytes(bytes: number): string {
  if (!Number.isFinite(bytes) || bytes < 0) return "未知大小";
  if (bytes < 1024) return `${bytes} B`;
  const units = ["KB", "MB", "GB", "TB"];
  let value = bytes;
  let unit = "B";
  for (const next of units) {
    if (value < 1024) break;
    value /= 1024;
    unit = next;
  }
  return `${value >= 100 ? value.toFixed(0) : value.toFixed(1)} ${unit}`;
}

/** 从 UserAgent 猜一个可读的设备名，配对时上报。 */
export function describeBrowser(): string {
  const ua = navigator.userAgent;
  const os = /Android/i.test(ua)
    ? "Android"
    : /iPhone|iPad|iPod/i.test(ua)
      ? "iOS"
      : /Windows/i.test(ua)
        ? "Windows"
        : /Macintosh|Mac OS X/i.test(ua)
          ? "macOS"
          : /Linux/i.test(ua)
            ? "Linux"
            : "未知系统";
  const browser = /Edg\//.test(ua)
    ? "Edge"
    : /Chrome\//.test(ua)
      ? "Chrome"
      : /Firefox\//.test(ua)
        ? "Firefox"
        : /Safari\//.test(ua)
          ? "Safari"
          : "浏览器";
  return `${os} · ${browser}`;
}

/** 解析 `#pair=CODE` 形式的配对码（二维码扫码进入时携带）。 */
export function readPairCodeFromHash(): string | null {
  if (!location.hash.startsWith("#")) return null;
  const value = new URLSearchParams(location.hash.slice(1)).get("pair");
  const trimmed = value?.trim() ?? "";
  return trimmed.length > 0 ? trimmed : null;
}

/** 配对完成后清掉 hash，避免刷新时把已用过的码再提交一遍。 */
export function clearHash(): void {
  if (location.hash !== "") {
    history.replaceState(null, "", location.pathname + location.search);
  }
}

/** 文件图标：按 MIME/扩展名挑一个 emoji。 */
export function fileIcon(name: string, mime?: string): string {
  const type = (mime ?? "").toLowerCase();
  const ext = name.includes(".") ? (name.split(".").pop() ?? "").toLowerCase() : "";
  if (type.startsWith("image/") || ["png", "jpg", "jpeg", "gif", "webp", "bmp"].includes(ext)) return "🖼️";
  if (type.startsWith("video/") || ["mp4", "mkv", "avi", "mov", "webm"].includes(ext)) return "🎬";
  if (type.startsWith("audio/") || ["mp3", "flac", "wav", "ogg", "m4a"].includes(ext)) return "🎵";
  if (type.startsWith("text/") || ["txt", "md", "log", "csv", "json"].includes(ext)) return "📄";
  if (["zip", "7z", "rar", "tar", "gz", "xz"].includes(ext)) return "🗜️";
  if (["pdf"].includes(ext)) return "📕";
  if (["doc", "docx"].includes(ext)) return "📘";
  if (["xls", "xlsx", "ppt", "pptx"].includes(ext)) return "📊";
  if (["apk", "exe", "msi", "deb", "rpm", "appimage"].includes(ext)) return "📦";
  return "📁";
}

/** 消息时间戳的展示文案。 */
export function formatTime(unixMs: number): string {
  return new Date(unixMs).toLocaleTimeString("zh-CN", { hour: "2-digit", minute: "2-digit" });
}

/** 日期分组的展示文案（今天/昨天/具体日期）。 */
export function formatDayLabel(unixMs: number): string {
  const date = new Date(unixMs);
  const today = new Date();
  const yesterday = new Date(today.getTime() - 24 * 60 * 60 * 1000);
  const sameDay = (a: Date, b: Date) =>
    a.getFullYear() === b.getFullYear() && a.getMonth() === b.getMonth() && a.getDate() === b.getDate();

  if (sameDay(date, today)) return "今天";
  if (sameDay(date, yesterday)) return "昨天";
  return date.toLocaleDateString("zh-CN", { year: "numeric", month: "long", day: "numeric" });
}
