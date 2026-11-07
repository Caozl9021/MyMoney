#!/usr/bin/env bash
# 一键生成「麦米云备份」连接码（MMJOIN2，业务授权5分钟、一次有效）。
#
# 用法：
#   ./connect-code.sh                    # 用配置出码；手机默认使用自身型号作为设备名
#   ./connect-code.sh "Xiaomi 14"        # 指定设备名
#   ./connect-code.sh --server http://<家庭服务地址> "Xiaomi 14"
#   ./connect-code.sh --tskey tskey-auth-xxxxx "Xiaomi Mi 10"   # 临时换 key（一般不用）
#
# 首次使用前，在 connect-code.env 里填好 TSKKEY 与 SERVER_URL（见同目录 connect-code.env.example），
# 或直接用下面的默认值 / 环境变量覆盖。
#
# 说明：
#   - tskey 请用 reusable（可复用）的授权 key，一把通吃所有设备，不必每次新生成。
#   - 本脚本在运行中的 mymoney-home-sync 容器里调用官方 CLI，无需手工拼 base64。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CONTAINER="${MYMONEY_CONTAINER:-mymoney-home-sync}"

# 读本地配置（如有）
if [[ -f "$ROOT/connect-code.env" ]]; then
  # shellcheck disable=SC1091
  source "$ROOT/connect-code.env"
fi

TSKEY="${TSKKEY:-${MYMONEY_TSKEY:-}}"
SERVER_URL="${SERVER_URL:-${MYMONEY_SERVER_URL:-http://<家庭服务地址>}}"
CTRL_URL="${CTRL_URL:-https://controlplane.tailscale.com}"
DEVICE_NAME=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --tskey) TSKEY="$2"; shift 2 ;;
    --server) SERVER_URL="$2"; shift 2 ;;
    --ctrl) CTRL_URL="$2"; shift 2 ;;
    -h|--help) sed -n '2,20p' "$0"; exit 0 ;;
    *) DEVICE_NAME="$1"; shift ;;
  esac
done

if [[ -z "$TSKEY" ]]; then
  echo "缺少 tskey。请在 connect-code.env 里设置 TSKKEY=tskey-auth-... 或用 --tskey 传入。" >&2
  echo "（请使用 reusable 授权 key，一把可反复用于所有设备。）" >&2
  exit 2
fi

if ! docker ps --format '{{.Names}}' | grep -qx "$CONTAINER"; then
  echo "容器 $CONTAINER 未运行，先启动家庭服务容器。" >&2
  exit 3
fi

CODE="$(docker exec "$CONTAINER" python -m app.cli connect-code \
  --auth-key "$TSKEY" \
  --server-url "$SERVER_URL" \
  --ctrl-url "$CTRL_URL" \
  --device-name "$DEVICE_NAME")"

echo "$CODE"
echo "" >&2
echo "设备名：${DEVICE_NAME:-由手机自动填写}" >&2
echo "业务授权5分钟有效，仅限一个安装；重装可选择接替原设备。网络授权独立生效。" >&2
echo "长度：${#CODE} 字符。复制上面整串（含 MMJOIN2. 前缀）粘到 App 的「连接码」输入框即可。" >&2
