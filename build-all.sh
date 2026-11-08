#!/bin/bash
# 麦米 My Money 完整构建脚本 - 构建到 dist 目录
#
# 版本号不再硬编码：直接从 app/build.gradle.kts 里读 versionName，避免
# "改了版本却忘了改脚本"导致 dist/ 里文件名与 APK 实际版本不一致。

set -e

cd "$(dirname "$0")"

VERSION_NAME=$(grep -oP 'versionName\s*=\s*"\K[^"]+' app/build.gradle.kts | head -1)
VERSION_CODE=$(grep -oP 'versionCode\s*=\s*\K[0-9]+' app/build.gradle.kts | head -1)

if [ -z "$VERSION_NAME" ] || [ -z "$VERSION_CODE" ]; then
    echo "❌ 无法从 app/build.gradle.kts 读取版本号"
    exit 1
fi

echo "=========================================="
echo "My Money $VERSION_NAME ($VERSION_CODE) - 完整构建"
echo "=========================================="

# 环境检查
if [ ! -f "gradlew" ]; then
    echo "❌ 错误：请在项目根目录运行此脚本"
    exit 1
fi

# 加载环境变量
if [ -f "$HOME/opt/mymoney-env.sh" ]; then
    echo "📦 加载环境变量..."
    # shellcheck disable=SC1091
    source "$HOME/opt/mymoney-env.sh"
fi

# ~/.gradle 在只读文件系统上时，退到可写的临时 GRADLE_USER_HOME
if ! touch "$HOME/.gradle/.write_test" 2>/dev/null; then
    TMP_GRADLE="$(mktemp -d)/gradle-home"
    echo "⚠️  ~/.gradle 不可写，改用 $TMP_GRADLE"
    cp -a "$HOME/.gradle/caches" "$HOME/.gradle/wrapper" "$HOME/.gradle/daemon" \
        "$HOME/.gradle/gradle.properties" "$TMP_GRADLE/" 2>/dev/null || true
    export GRADLE_USER_HOME="$TMP_GRADLE"
else
    rm -f "$HOME/.gradle/.write_test"
fi

# UI 组件闸门：禁止页面直接回退到 Material3 弹窗/选择控件
echo ""
echo "🔎 检查 UI 组件统一性..."
./scripts/check-ui-components.sh

# 创建 dist 目录
mkdir -p dist

# 清理旧构建
echo ""
echo "🧹 清理旧构建..."
./gradlew clean

# 运行测试
echo ""
echo "🧪 运行单元测试..."
./gradlew :app:testDebugUnitTest

# 构建主 App (Debug / Release)
echo ""
echo "🔨 构建主 App (Debug，包含内置 LSP 增强)..."
./gradlew :app:assembleDebug

echo ""
echo "🔨 构建主 App (Release，包含内置 LSP 增强)..."
./gradlew :app:assembleRelease

APP_DEBUG="app/build/outputs/apk/debug/app-debug.apk"
APP_RELEASE="app/build/outputs/apk/release/app-release.apk"
# 每次收集创建独立目录，APK包内版本/签名/摘要核对通过才更新最新指针。
NOTES_ARGS=()
if [ -f "dist/notes/v${VERSION_NAME}/RELEASE_NOTES-v${VERSION_NAME}.md" ]; then
    NOTES_ARGS=(--notes "dist/notes/v${VERSION_NAME}/RELEASE_NOTES-v${VERSION_NAME}.md")
fi
python3 tools/release/collect-app-build.py \
    --release "$APP_RELEASE" --debug "$APP_DEBUG" \
    --expected-version "$VERSION_NAME" --expected-code "$VERSION_CODE" "${NOTES_ARGS[@]}"
echo "最新产物位置：dist/latest-app.json"

echo ""
echo "=========================================="
echo "✅ 构建完成"
echo "=========================================="
echo ""
echo "LSP 模块已内置，无需第二个 APK："
echo "  - 标准模式：直接安装使用"
echo "  - LSP 模式：在 LSPosed 中启用麦米 My Money，作用域只勾「微信」和「支付宝」"
echo "    （系统框架 android 不属于推荐作用域，不要再勾选）"
