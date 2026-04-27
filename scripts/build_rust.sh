#!/bin/bash
# build_rust.sh - 编译 Rust 库 (auto-artifactarium)
# 这个脚本编译 auto-artifactarium 为 Android 平台

set -e

echo "========================================="
echo "  编译 Rust 库 (auto-artifactarium)"
echo "========================================="

# 颜色定义
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# 检查 Rust
if ! command -v cargo &> /dev/null; then
    echo -e "${RED}错误: cargo 未安装${NC}"
    echo "请先安装 Rust: https://www.rust-lang.org/tools/install"
    exit 1
fi

# 检查 auto-artifactarium 目录
if [ ! -d "auto-artifactarium" ]; then
    echo -e "${YELLOW}⚠ auto-artifactarium 目录不存在${NC}"
    echo "正在克隆 auto-artifactarium..."
    git submodule add https://github.com/konkers/auto-artifactarium.git
    git submodule update --init --recursive
fi

cd auto-artifactarium

# 需要编译的 Android 架构
TARGETS=(
    "aarch64-linux-android"
    "armv7-linux-androideabi"
    "i686-linux-android"
    "x86_64-linux-android"
)

# 为每个架构编译
for TARGET in "${TARGETS[@]}"; do
    echo -e "${YELLOW}正在为 $TARGET 编译...${NC}"
    
    # 检查目标是否已添加
    if ! rustup target list --installed | grep -q "$TARGET"; then
        echo "添加目标 $TARGET..."
        rustup target add $TARGET
    fi
    
    # 编译
    if cargo build --release --target $TARGET; then
        echo -e "${GREEN}✓ $TARGET 编译成功${NC}"
        
        # 确定 ABI 名称
        case $TARGET in
            "aarch64-linux-android")
                ABI="arm64-v8a"
                ;;
            "armv7-linux-androideabi")
                ABI="armeabi-v7a"
                ;;
            "i686-linux-android")
                ABI="x86"
                ;;
            "x86_64-linux-android")
                ABI="x86_64"
                ;;
        esac
        
        # 创建 jniLibs 目录并复制库
        mkdir -p "../app/src/main/jniLibs/$ABI"
        cp "target/$TARGET/release/libauto_artifactarium.so" "../app/src/main/jniLibs/$ABI/"
        echo -e "${GREEN}✓ 库已复制到 jniLibs/$ABI/${NC}"
        
    else
        echo -e "${RED}✗ $TARGET 编译失败${NC}"
        exit 1
    fi
done

cd ..

echo -e "${GREEN}=========================================${NC}"
echo -e "${GREEN}Rust 库编译完成！${NC}"
echo -e "${GREEN}库位置: app/src/main/jniLibs/${NC}"
echo -e "${GREEN}=========================================${NC}"
