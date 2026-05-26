#!/bin/bash
set -e

# 编译 GoProxyAndroid 源码为 Android ARM/ARM64 可执行文件
# 输出到 jar/assets/ 目录，供 leodm.jar 打包时用
#
# 用法:
#   ./build-go.sh [go-source-dir]
#
# 环境变量:
#   NDK_ROOT  — Android NDK 路径 (默认检测 /root/android-ndk-r26b)
#   GO_CMD    — Go 编译器路径 (默认 /usr/local/go/bin/go)

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
PROJECT_DIR="$( cd "$SCRIPT_DIR/.." && pwd )"
SOURCE_DIR="${1:-$SCRIPT_DIR/../../go-proxy-android-v2}"
OUTPUT_DIR="$SCRIPT_DIR/assets"
if [ -z "$NDK_ROOT" ] && [ -n "$ANDROID_NDK_HOME" ]; then
    NDK_ROOT="$ANDROID_NDK_HOME"
fi
NDK_ROOT="${NDK_ROOT:-/root/android-ndk-r26b}"
GO_CMD="${GO_CMD:-/usr/local/go/bin/go}"

echo "========================================="
echo "Go 代理交叉编译"
echo "源文件: $SOURCE_DIR"
echo "输出目录: $OUTPUT_DIR"
echo "NDK: $NDK_ROOT"
echo "Go: $($GO_CMD version)"
echo "========================================="

if [ ! -d "$SOURCE_DIR" ]; then
    echo "错误: Go 源码目录不存在: $SOURCE_DIR"
    echo "用法: $0 [go-source-dir]"
    exit 1
fi

if [ ! -f "$SOURCE_DIR/main.go" ]; then
    echo "错误: $SOURCE_DIR 中未找到 main.go"
    exit 1
fi

TOOLCHAIN_DIR="$NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64/bin"
if [ ! -d "$TOOLCHAIN_DIR" ]; then
    echo "错误: NDK 工具链目录不存在: $TOOLCHAIN_DIR"
    exit 1
fi

export PATH="$TOOLCHAIN_DIR:$PATH"
mkdir -p "$OUTPUT_DIR"

# arm64-v8a
echo ""
echo ">>> 编译 arm64-v8a..."
(cd "$SOURCE_DIR" && CGO_ENABLED=1 GOOS=android GOARCH=arm64 \
    CC=aarch64-linux-android21-clang \
    "$GO_CMD" build -ldflags="-w -s" -trimpath \
    -o "$OUTPUT_DIR/goProxy-arm64" .)
echo "完成: $(file "$OUTPUT_DIR/goProxy-arm64" | awk -F: '{print $2}')"

# armeabi-v7a
echo ""
echo ">>> 编译 armeabi-v7a..."
(cd "$SOURCE_DIR" && CGO_ENABLED=1 GOOS=android GOARCH=arm GOARM=7 \
    CC=armv7a-linux-androideabi21-clang \
    "$GO_CMD" build -ldflags="-w -s" -trimpath \
    -o "$OUTPUT_DIR/goProxy-arm" .)
echo "完成: $(file "$OUTPUT_DIR/goProxy-arm" | awk -F: '{print $2}')"

# strip
if [ -f "$TOOLCHAIN_DIR/llvm-strip" ]; then
    echo ""
    echo ">>> Stripping 符号..."
    "$TOOLCHAIN_DIR/llvm-strip" "$OUTPUT_DIR/goProxy-arm64"
    "$TOOLCHAIN_DIR/llvm-strip" "$OUTPUT_DIR/goProxy-arm"
    echo "Strip 完成"
fi

echo ""
echo "========================================="
echo "输出文件:"
ls -lh "$OUTPUT_DIR/goProxy-arm" "$OUTPUT_DIR/goProxy-arm64"
echo "========================================="
