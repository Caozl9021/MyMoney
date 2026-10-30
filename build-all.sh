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
BRANCH_TAG="$(git branch --show-current)"
BRANCH_TAG="${BRANCH_TAG//\//-}"
BRANCH_TAG="${BRANCH_TAG:-detached}"
DIST_DEBUG="dist/MyMoney-${VERSION_NAME}-${BRANCH_TAG}-debug.apk"
DIST_RELEASE="dist/MyMoney-${VERSION_NAME}-${BRANCH_TAG}-release.apk"

# 复制 + 核对版本（aapt2 读到的不一致就报错，不静默通过）
echo ""
echo "📦 复制产物到 dist/ 并核对版本..."

AAPT2="${ANDROID_HOME:-$HOME/Android/Sdk}/build-tools/35.0.0/aapt2"
check_version() {
    local apk="$1"
    if [ ! -x "$AAPT2" ]; then
        echo "⚠️  未找到 aapt2，跳过版本核对：$apk"
        return 0
    fi
    local badging
    badging=$("$AAPT2" dump badging "$apk" 2>/dev/null | head -1)
    if ! echo "$badging" | grep -q "versionName='$VERSION_NAME'" ||
        ! echo "$badging" | grep -q "versionCode='$VERSION_CODE'"; then
        echo "❌ $apk 的实际版本与 gradle 不一致："
        echo "   $badging"
        exit 1
    fi
    echo "   ✓ $(basename "$apk") = $VERSION_NAME ($VERSION_CODE)"
}

for pair in "$APP_DEBUG:$DIST_DEBUG" "$APP_RELEASE:$DIST_RELEASE"; do
    src="${pair%%:*}"
    dst="${pair##*:}"
    if [ ! -f "$src" ]; then
        echo "❌ 缺少产物：$src"
        exit 1
    fi
    cp "$src" "$dst"
    check_version "$dst"
done

echo ""
echo "=========================================="
echo "📊 构建产物："
echo "=========================================="
ls -lh dist/ | grep "MyMoney"

echo ""
echo "=========================================="
echo "✅ 构建完成"
echo "=========================================="
echo ""
echo "LSP 模块已内置，无需第二个 APK："
echo "  - 标准模式：直接安装使用"
echo "  - LSP 模式：在 LSPosed 中启用麦米 My Money，作用域只勾「微信」和「支付宝」"
echo "    （系统框架 android 不属于推荐作用域，不要再勾选）"
