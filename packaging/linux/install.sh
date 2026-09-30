#!/bin/sh
# LAN-Drop Linux 安装脚本：写配置 + 生成 systemd user unit + 启动。
#
#   ./install.sh                        # 默认端口 8787/8788，装好即启动
#   ./install.sh --port 9000 --name 书房台式机
#   ./install.sh --no-start             # 只装不自启
#   ./install.sh --dry-run              # 只打印将要做什么（不写任何文件）
#
# 为什么用 systemd user unit 而不是系统服务：
#   - 不需要 sudo（用户级服务，装在家目录里），也不污染系统；
#   - 数据目录本来就在 XDG（~/.local/share/lan-drop），与「用户级」天然匹配；
#   - 需要开机即用（未登录）时再执行一次 `sudo loginctl enable-linger $USER`，
#     这是唯一需要提权的一步，脚本不会替你执行。
set -eu

PROGRAM_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
SERVER_JS="$PROGRAM_DIR/app/server/server.js"
SERVICE_NAME="lan-drop.service"
TEMPLATE="$PROGRAM_DIR/lan-drop.service"
LAUNCHER="$PROGRAM_DIR/bin/lan-drop"

CONFIG_DIR="${LAN_DROP_CONFIG_DIR:-${XDG_CONFIG_HOME:-$HOME/.config}/lan-drop}"
UNIT_DIR="${LAN_DROP_UNIT_DIR:-${XDG_CONFIG_HOME:-$HOME/.config}/systemd/user}"
ENV_FILE="$CONFIG_DIR/env"
UNIT_FILE="$UNIT_DIR/$SERVICE_NAME"

PORT=8787
DISCOVERY_PORT=8788
SERVER_NAME=""
DATA_ROOT=""
DO_START=1
DRY_RUN=0

usage() {
  sed -n '2,12p' "$0" | sed 's/^# \{0,1\}//'
  cat <<'EOF'
可选参数：
  --port N              HTTP/WebSocket 端口（默认 8787）
  --discovery-port N    局域网发现 UDP 端口（默认 8788）
  --name NAME           显示名（手机聊天页标题；默认取本机主机名）
  --data-root DIR       数据目录（默认 ~/.local/share/lan-drop）
  --no-start            只安装，不立即启动
  --dry-run             只打印计划，不写文件、不动 systemd
EOF
}

while [ $# -gt 0 ]; do
  case "$1" in
    --port) PORT="${2:?--port 需要参数}"; shift 2 ;;
    --discovery-port) DISCOVERY_PORT="${2:?--discovery-port 需要参数}"; shift 2 ;;
    --name) SERVER_NAME="${2:?--name 需要参数}"; shift 2 ;;
    --data-root) DATA_ROOT="${2:?--data-root 需要参数}"; shift 2 ;;
    --no-start) DO_START=0; shift ;;
    --dry-run) DRY_RUN=1; shift ;;
    -h|--help) usage; exit 0 ;;
    *) echo "未知参数：$1（用 --help 看用法）" >&2; exit 2 ;;
  esac
done

echo "=== LAN-Drop 安装（Linux） ==="
echo "  程序目录：$PROGRAM_DIR"

if [ ! -f "$SERVER_JS" ]; then
  echo "安装包不完整：找不到 $SERVER_JS（请完整解压 tar.gz）。" >&2
  exit 1
fi
[ -f "$TEMPLATE" ] || { echo "安装包不完整：找不到 $TEMPLATE" >&2; exit 1; }
[ -x "$LAUNCHER" ] || chmod +x "$LAUNCHER" 2>/dev/null || true

# 端口占用检查：先看有没有人在监听（ss 缺失时跳过，不阻断）
if command -v ss >/dev/null 2>&1 && ss -ltn 2>/dev/null | grep -q ":$PORT "; then
  echo "提示：TCP $PORT 已经在监听，可能是旧实例或别的程序。先停掉它，或用 --port 换端口。" >&2
fi

# 配置：只在不存在时写，避免覆盖用户改过的设置
ENV_CONTENT="# LAN-Drop 服务端配置（KEY=VALUE，Shell 语法，改动后重启服务生效）
# 本文件在配置目录里，升级覆盖程序目录不会丢。
LAN_DROP_PORT=$PORT
LAN_DROP_DISCOVERY_PORT=$DISCOVERY_PORT
# LAN_DROP_SERVER_NAME 决定手机聊天页标题显示的名字，默认取本机主机名，例如：
# LAN_DROP_SERVER_NAME=书房台式机"
if [ -n "$SERVER_NAME" ]; then
  ENV_CONTENT="$ENV_CONTENT
LAN_DROP_SERVER_NAME=$SERVER_NAME"
fi
if [ -n "$DATA_ROOT" ]; then
  ENV_CONTENT="$ENV_CONTENT
LAN_DROP_DATA_ROOT=$DATA_ROOT"
fi

UNIT_CONTENT=$(sed -e "s|__EXEC__|$LAUNCHER|" -e "s|__ENV_FILE__|$ENV_FILE|" "$TEMPLATE")

if [ "$DRY_RUN" -eq 1 ]; then
  echo ""
  echo "--- 将写入 $ENV_FILE ---"
  echo "$ENV_CONTENT"
  echo ""
  echo "--- 将写入 $UNIT_FILE ---"
  echo "$UNIT_CONTENT"
  echo ""
  echo "--- 将执行 ---"
  echo "  systemctl --user daemon-reload"
  [ "$DO_START" -eq 1 ] && echo "  systemctl --user enable --now $SERVICE_NAME"
  exit 0
fi

mkdir -p "$CONFIG_DIR" "$UNIT_DIR"
if [ -f "$ENV_FILE" ]; then
  echo "  配置：沿用已有 $ENV_FILE"
else
  printf '%s\n' "$ENV_CONTENT" > "$ENV_FILE"
  echo "  配置：已生成 $ENV_FILE"
fi

printf '%s\n' "$UNIT_CONTENT" > "$UNIT_FILE"
echo "  服务：已生成 $UNIT_FILE"

if ! command -v systemctl >/dev/null 2>&1 || ! systemctl --user show-environment >/dev/null 2>&1; then
  echo ""
  echo "这台机器上没有可用的 systemd 用户会话，unit 已写好但没法自动启用。手动启动方式：" >&2
  echo "  $LAUNCHER" >&2
  exit 1
fi

systemctl --user daemon-reload
if [ "$DO_START" -eq 1 ]; then
  systemctl --user enable --now "$SERVICE_NAME"
  sleep 1
  systemctl --user --no-pager --lines=0 status "$SERVICE_NAME" || true
else
  echo "  已按 --no-start 跳过启动（手动启动：systemctl --user start $SERVICE_NAME）"
fi

# 局域网地址：取默认路由出口的那个 IP（最接近「手机看到的地址」）
LAN_IP=$(ip -4 route get 1.1.1.1 2>/dev/null | awk '{for(i=1;i<=NF;i++) if($i=="src") print $(i+1)}' | head -1)
[ -n "$LAN_IP" ] || LAN_IP=$(hostname -I 2>/dev/null | awk '{print $1}')

PAIRING=$(journalctl --user -u "$SERVICE_NAME" -n 100 --no-pager 2>/dev/null |
          grep -oE '配对码 +[A-Z0-9]{6}' | tail -1 | awk '{print $2}')

echo ""
echo "=== 安装完成 ==="
[ -n "$LAN_IP" ] && echo "  手机访问   http://$LAN_IP:${PORT}"
echo "  配对码     ${PAIRING:-见日志：journalctl --user -u $SERVICE_NAME -n 50}"
echo "  手机 App →「配对」页点【扫描局域网】即可发现本机（无需手输地址）。"
echo ""
echo "  状态：systemctl --user status $SERVICE_NAME"
echo "  日志：journalctl --user -u $SERVICE_NAME -f"
echo "  配置：$ENV_FILE"
echo "  卸载：$PROGRAM_DIR/uninstall.sh"

LINGER=$(loginctl show-user "$USER" -p Linger --value 2>/dev/null || echo unknown)
if [ "$LINGER" != "yes" ]; then
  echo ""
  echo "提示：当前是登录后才启动。若希望开机（未登录也）常驻，执行一次："
  echo "  sudo loginctl enable-linger $USER"
fi
