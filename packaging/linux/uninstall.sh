#!/bin/sh
# LAN-Drop Linux 卸载脚本：停服务、禁用自启、删 unit；数据默认保留。
#
#   ./uninstall.sh              # 保留数据（数据库、收到的文件、日志）
#   ./uninstall.sh --purge      # 连数据一起删（不可恢复，会二次确认）
#
# 程序目录本脚本不删：你正在用它；确认不需要后手动 rm -rf。
set -eu

PROGRAM_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
SERVICE_NAME="lan-drop.service"
CONFIG_DIR="${LAN_DROP_CONFIG_DIR:-${XDG_CONFIG_HOME:-$HOME/.config}/lan-drop}"
UNIT_DIR="${LAN_DROP_UNIT_DIR:-${XDG_CONFIG_HOME:-$HOME/.config}/systemd/user}"
UNIT_FILE="$UNIT_DIR/$SERVICE_NAME"

PURGE=0
KEEP_CONFIG=0
while [ $# -gt 0 ]; do
  case "$1" in
    --purge) PURGE=1; shift ;;
    --keep-config) KEEP_CONFIG=1; shift ;;
    -h|--help) sed -n '2,9p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "未知参数：$1（用 --help 看用法）" >&2; exit 2 ;;
  esac
done

echo "=== LAN-Drop 卸载（Linux） ==="

if command -v systemctl >/dev/null 2>&1 && systemctl --user show-environment >/dev/null 2>&1; then
  systemctl --user disable --now "$SERVICE_NAME" 2>/dev/null || true
  echo "  已停止并禁用 $SERVICE_NAME"
else
  echo "  没有可用的 systemd 用户会话，跳过 systemctl（必要时手动 kill 服务端进程）"
fi

if [ -f "$UNIT_FILE" ]; then
  rm -f "$UNIT_FILE"
  command -v systemctl >/dev/null 2>&1 && systemctl --user daemon-reload 2>/dev/null || true
  echo "  已删除 $UNIT_FILE"
else
  echo "  $UNIT_FILE 不存在，无需删除"
fi

if [ "$KEEP_CONFIG" -eq 1 ]; then
  echo "  按 --keep-config 保留配置目录 $CONFIG_DIR"
elif [ -d "$CONFIG_DIR" ] && [ "$PURGE" -eq 0 ]; then
  echo "  配置保留：$CONFIG_DIR/env（要删就加 --purge，或手动 rm -rf $CONFIG_DIR）"
fi

# 数据目录：默认 ~/.local/share/lan-drop，或用配置里的 LAN_DROP_DATA_ROOT
DATA_ROOT="${XDG_DATA_HOME:-$HOME/.local/share}/lan-drop"
ENV_FILE="$CONFIG_DIR/env"
if [ -f "$ENV_FILE" ]; then
  # shellcheck disable=SC1090
  CUSTOM_ROOT=$(set -a; . "$ENV_FILE" 2>/dev/null; printf '%s' "${LAN_DROP_DATA_ROOT:-}")
  [ -n "$CUSTOM_ROOT" ] && DATA_ROOT="$CUSTOM_ROOT"
fi

if [ "$PURGE" -eq 1 ]; then
  echo ""
  echo "将删除数据目录（数据库、收到的文件、日志，不可恢复）："
  echo "  $DATA_ROOT"
  printf '确认删除？输入 y 回车：'
  read -r answer
  if [ "$answer" = "y" ] || [ "$answer" = "Y" ]; then
    rm -rf "$DATA_ROOT" "$CONFIG_DIR"
    echo "  数据与配置已删除"
  else
    echo "  已取消删除"
  fi
else
  echo "  数据保留：$DATA_ROOT（要一起删就加 --purge）"
fi

echo ""
echo "=== 卸载完成 ==="
echo "  程序目录还在，确认不需要后手动删除：rm -rf $PROGRAM_DIR"
