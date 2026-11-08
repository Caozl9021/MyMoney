#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if grep -RIn --include='*.kt' \
  -e 'androidx.compose.material3.AlertDialog' \
  -e 'androidx.compose.material3.Checkbox' \
  -e 'androidx.compose.material3.DropdownMenu' \
  app/src/main/java/io/github/mymoney/ui \
  | grep -v 'ui/design/AppDialog.kt'; then
  echo '发现未统一的 Material3 弹窗/选择控件，请使用 ui/design 下的 App 组件。' >&2
  exit 1
fi
echo 'UI component check passed.'
