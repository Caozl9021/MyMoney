#!/bin/bash
# LSP 模块激活状态检查
#
# 只做能真实读到的检查。历史版本第 4 步用 `adb shell "unzip -l ..."` 读设备上的 APK，
# 但绝大多数 ROM 没有 unzip，命令静默失败 —— 现在改成 adb pull 到本地再读。

set -uo pipefail

echo "=========================================="
echo "LSP 模块激活状态检查"
echo "=========================================="
echo ""

if ! command -v adb >/dev/null 2>&1; then
    echo "❌ 未找到 adb，请先配置 Android platform-tools"
    exit 1
fi

if [ "$(adb get-state 2>/dev/null)" != "device" ]; then
    echo "❌ 没有已连接的设备（adb devices 为空或未授权）"
    exit 1
fi

echo "1. Xposed / LSPosed 系统属性："
adb shell getprop 2>/dev/null | grep -E "(xposed|lsposed|autobook)" || echo "   未找到相关属性"
echo ""

echo "2. 模块是否被 LSPosed 加载（看有没有我们的 TAG）："
if adb logcat -d 2>/dev/null | grep "AutoBook-Xposed" | tail -20; then
    :
else
    echo "   未找到 AutoBook-Xposed 日志（模块可能未启用，或日志已被轮转）"
fi
echo ""

echo "3. 关键判据 —— 有没有真的拦住系统操作："
HITS=$(adb logcat -d 2>/dev/null | grep -cE "阻止系统解绑无障碍服务|阻止移除无障碍服务|阻止系统杀进程")
if [ "$HITS" -gt 0 ]; then
    echo "   ✅ 命中 $HITS 次，Hook 确实生效"
    adb logcat -d 2>/dev/null | grep -E "阻止系统解绑无障碍服务|阻止移除无障碍服务|阻止系统杀进程" | tail -5
else
    echo "   ⚠️  没有命中记录。这不代表模块没生效，只说明勾选作用域以来系统还没尝试过这些操作。"
fi
echo ""

echo "4. APK 里的 xposed_init（拉回本地读，不依赖设备端 unzip）："
APK_PATH=$(adb shell pm path com.example.mymoney 2>/dev/null | sed -n 's/^package://p' | tr -d '\r' | head -1)
if [ -n "$APK_PATH" ]; then
    TMP_DIR="$(mktemp -d)"
    TMP_APK="$TMP_DIR/mymoney.apk"
    if adb pull "$APK_PATH" "$TMP_APK" >/dev/null 2>&1; then
        if command -v unzip >/dev/null 2>&1; then
            ENTRY=$(unzip -p "$TMP_APK" assets/xposed_init 2>/dev/null | tr -d '\r\n')
            if [ -n "$ENTRY" ]; then
                echo "   ✅ assets/xposed_init = $ENTRY"
            else
                echo "   ❌ APK 里没有 assets/xposed_init —— 不是带 LSP 的构建"
            fi
        else
            echo "   ⚠️  本机没有 unzip，跳过（可手动确认 assets/xposed_init 存在）"
        fi
    else
        echo "   ⚠️  adb pull 失败：$APK_PATH"
    fi
    rm -rf "$TMP_DIR"
else
    echo "   ⚠️  设备上没有安装 com.example.mymoney"
fi
echo ""

echo "=========================================="
echo "判读要点：第 3 步出现「阻止系统解绑无障碍服务」才算 Hook 真生效；"
echo "设置页的「已确认」需要模块成功写入系统属性，普通权限下常常写不进去。"
echo "=========================================="
