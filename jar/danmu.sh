#!/bin/bash

# 获取脚本所在目录
SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"

echo "开始生成 leodm.jar..."

# 1. 删除旧文件
echo "清理旧文件..."
rm -f "$SCRIPT_DIR/leodm.jar"
rm -rf "$SCRIPT_DIR/Smali_classes"

# 2. 检查 APK 文件是否存在
APK_PATH="$SCRIPT_DIR/../app/build/outputs/apk/release/app-release-unsigned.apk"
if [ ! -f "$APK_PATH" ]; then
    echo "错误: 找不到 APK 文件: $APK_PATH"
    echo "请确保已运行 assembleRelease 构建 APK"
    exit 1
fi

# 3. 检查 apktool 是否存在
APKTOOL_PATH="$SCRIPT_DIR/3rd/apktool_2.11.0.jar"
if [ ! -f "$APKTOOL_PATH" ]; then
    echo "错误: 找不到 apktool: $APKTOOL_PATH"
    exit 1
fi

# 4. 反编译 APK - 使用与Windows相同的参数
echo "反编译 APK..."
java -jar "$APKTOOL_PATH" d -f --only-main-classes "$APK_PATH" -o "$SCRIPT_DIR/Smali_classes"

# 检查是否反编译成功
if [ ! -d "$SCRIPT_DIR/Smali_classes" ]; then
    echo "反编译失败，检查 APK 文件或 apktool 版本"
    exit 1
fi

# 5. 清理 spider.jar 目录
echo "清理 spider.jar 目录..."
rm -rf "$SCRIPT_DIR/spider.jar/smali/com/github/catvod/spider"
rm -rf "$SCRIPT_DIR/spider.jar/smali/com/github/catvod/js"
rm -rf "$SCRIPT_DIR/spider.jar/smali/com/github/catvod/net"
rm -rf "$SCRIPT_DIR/spider.jar/smali/org/slf4j"

# 6. 创建目录结构
echo "创建目录结构..."
mkdir -p "$SCRIPT_DIR/spider.jar/smali/com/github/catvod/"
mkdir -p "$SCRIPT_DIR/spider.jar/smali/org/slf4j/"

# 7. 移动必要的 smali 文件
echo "移动 smali 文件..."
# 使用find命令检查目录是否存在，更健壮的方式
if [ -d "$SCRIPT_DIR/Smali_classes/smali/com/github/catvod/spider" ]; then
    mv "$SCRIPT_DIR/Smali_classes/smali/com/github/catvod/spider" "$SCRIPT_DIR/spider.jar/smali/com/github/catvod/"
else
    echo "警告: 找不到 spider 目录"
    ls -la "$SCRIPT_DIR/Smali_classes/smali/com/github/catvod/" 2>/dev/null || echo "catvod 目录不存在"
fi

if [ -d "$SCRIPT_DIR/Smali_classes/smali/com/github/catvod/js" ]; then
    mv "$SCRIPT_DIR/Smali_classes/smali/com/github/catvod/js" "$SCRIPT_DIR/spider.jar/smali/com/github/catvod/"
else
    echo "警告: 找不到 js 目录"
fi

if [ -d "$SCRIPT_DIR/Smali_classes/smali/com/github/catvod/net" ]; then
    mv "$SCRIPT_DIR/Smali_classes/smali/com/github/catvod/net" "$SCRIPT_DIR/spider.jar/smali/com/github/catvod/"
else
    echo "警告: 找不到 net 目录"
fi

if [ -d "$SCRIPT_DIR/Smali_classes/smali/org/slf4j" ]; then
    mv "$SCRIPT_DIR/Smali_classes/smali/org/slf4j" "$SCRIPT_DIR/spider.jar/smali/org/slf4j/"
else
    echo "警告: 找不到 slf4j 目录"
fi

# 8. 删除 assets 目录 - 与Windows版本完全一致
echo "清理 assets 目录..."
if [ -d "$SCRIPT_DIR/spider.jar/assets" ]; then
    rm -rf "$SCRIPT_DIR/spider.jar/assets"
fi

# 9. 重新构建为 dex.jar
echo "重新构建 dex.jar..."
java -jar "$APKTOOL_PATH" b "$SCRIPT_DIR/spider.jar" -c

# 10. 重命名为 leodm.jar (这是唯一的不同点，将danmu.jar改为leodm.jar)
echo "生成 leodm.jar..."
if [ -f "$SCRIPT_DIR/spider.jar/dist/dex.jar" ]; then
    mv "$SCRIPT_DIR/spider.jar/dist/dex.jar" "$SCRIPT_DIR/leodm.jar"
else
    echo "错误: 找不到生成的 dex.jar"
    exit 1
fi

# 11. 生成 MD5 校验文件 (Mac 版本) 
echo "生成 MD5 校验..."
md5 "$SCRIPT_DIR/leodm.jar" | awk '{print $4}' > "$SCRIPT_DIR/leodm.jar.md5"
echo "MD5: $(cat "$SCRIPT_DIR/leodm.jar.md5")"

# 12. 清理临时文件 - 与Windows版本完全一致
echo "清理临时文件..."
rm -rf "$SCRIPT_DIR/spider.jar/build"
rm -rf "$SCRIPT_DIR/spider.jar/smali"
rm -rf "$SCRIPT_DIR/spider.jar/dist"
rm -rf "$SCRIPT_DIR/Smali_classes"

echo "✅ leodm.jar 生成成功！"
echo "文件位置: $SCRIPT_DIR/leodm.jar"
echo "MD5 文件: $SCRIPT_DIR/leodm.jar.md5"