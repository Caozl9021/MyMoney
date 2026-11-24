#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if grep -RInE --include='*.kt' \
  -e 'androidx\.compose\.material3\.(AlertDialog|Checkbox|DropdownMenu|DatePickerDialog|DateRangePicker|Slider|TextButton|IconButton|Button)([^A-Za-z0-9_]|$)' \
  -e '(^|[^A-Za-z0-9_])(AlertDialog|DropdownMenu|DatePickerDialog|DateRangePicker|TextButton|IconButton)\s*\(' \
  app/src/main/java/io/github/mymoney/ui \
  | grep -v 'ui/design/AppDialog.kt'; then
  echo '发现未统一的 Material3 弹窗/日期/按钮/滑杆控件，请使用 ui/design 下的 App 组件。' >&2
  exit 1
fi
echo 'UI component check passed.'
