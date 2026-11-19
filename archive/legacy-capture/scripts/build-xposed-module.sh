#!/bin/bash
# MyMoney 构建脚本（LSP 增强已内置进主 App，不再有独立的 xposed-module）
#
# 历史上这里是"构建独立的 LSP 增强模块 APK"，0.6.2 起模块代码已内置进 app 模块，
# settings.gradle.kts 里只有 :app 和 :billkit —— 所以 `:xposed-module:assembleRelease`
# 这个任务从来不存在，跑必失败。这里改成构建设置里真实存在的目标。
#
# 日常出包请直接用 ./build-all.sh（它负责版本号、dist 命名、单测与签名）。

set -e

echo "=========================================="
echo "MyMoney 构建（LSP 增强已内置）"
echo "=========================================="

if [ ! -f "gradlew" ]; then
    echo "❌ 错误：请在项目根目录运行此脚本"
    exit 1
fi

if [ -f "$HOME/opt/mymoney-env.sh" ]; then
    echo "📦 加载环境变量..."
    # shellcheck disable=SC1091
    source "$HOME/opt/mymoney-env.sh"
fi

echo ""
echo "🧹 清理旧构建..."
./gradlew clean

echo ""
echo "🧪 单元测试..."
./gradlew :app:testDebugUnitTest

echo ""
echo "🔨 构建主 App (Debug + Release)..."
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease

echo ""
echo "=========================================="
echo "📦 构建产物："
echo "=========================================="

APP_APK="app/build/outputs/apk/debug/app-debug.apk"
APP_APK_RELEASE="app/build/outputs/apk/release/app-release.apk"

for apk in "$APP_APK" "$APP_APK_RELEASE"; do
    if [ -f "$apk" ]; then
        echo "✅ $apk ($(du -h "$apk" | cut -f1))"
    else
        echo "❌ 缺少产物：$apk"
        exit 1
    fi
done

echo ""
echo "=========================================="
echo "🚀 安装 / 启用："
echo "=========================================="
echo "1. 安装唯一的 APK（LSP 功能已内置，不需要第二个模块 APK）："
echo "   adb install -r $APP_APK"
echo ""
echo "2. 如需 LSP 增强：在 LSPosed 管理器里启用「MyMoney」，"
echo "   作用域只勾「系统框架 (android)」—— 勾 MyMoney 自己不会有任何效果。"
echo ""
echo "3. 验证：设置 → 权限 → LSP 增强模块；"
echo "   真正生效的判据是 LSPosed 日志里出现「阻止系统解绑无障碍服务」。"
echo ""
echo "=========================================="
echo "✅ 构建完成"
echo "=========================================="
