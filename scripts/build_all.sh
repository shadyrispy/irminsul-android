#!/bin/bash
# build_all.sh - 一键编译所有依赖
# 这个脚本会自动编译 Rust 库和 Android 项目

set -e  # 遇到错误立即退出

echo "========================================="
echo " irminsul-android 一键编译脚本"
echo "========================================="

# 颜色定义
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# 检查函数
check_command() {
    if ! command -v $1 &> /dev/null; then
        echo -e "${RED}错误: $1 未安装${NC}"
        echo "请先安装 $1 后再运行此脚本"
        exit 1
    fi
}

# 1. 检查必要工具
echo -e "${YELLOW}[1/6] 检查必要工具...${NC}"
check_command "git"
check_command "cargo"
check_command "rustc"
check_command "cmake"

# 检查 Android SDK 和 NDK
if [ -z "$ANDROID_HOME" ] && [ -z "$ANDROID_SDK_ROOT" ]; then
    echo -e "${RED}错误: 未设置 ANDROID_HOME 或 ANDROID_SDK_ROOT 环境变量${NC}"
    echo "请设置 Android SDK 路径，例如："
    echo "  export ANDROID_HOME=~/Android/Sdk"
    exit 1
fi

ANDROID_SDK=${ANDROID_HOME:-$ANDROID_SDK_ROOT}
echo -e "${GREEN}✓ Android SDK 已配置: $ANDROID_SDK${NC}"

# 2. 更新 submodule
echo -e "${YELLOW}[2/6] 更新 git submodule...${NC}"
if [ -f ".gitmodules" ]; then
    git submodule update --init --recursive
    echo -e "${GREEN}✓ Submodule 更新完成${NC}"
else
    echo -e "${YELLOW}⚠ 未找到 .gitmodules，跳过 submodule 更新${NC}"
fi

# 3. 添加 Android 目标（如果未添加）
echo -e "${YELLOW}[3/6] 检查 Rust Android 目标...${NC}"
TARGETS=("aarch64-linux-android" "armv7-linux-androideabi" "i686-linux-android" "x86_64-linux-android")

for target in "${TARGETS[@]}"; do
    if rustup target list --installed | grep -q "$target"; then
        echo -e "${GREEN}✓ $target 已安装${NC}"
    else
        echo "正在添加 $target..."
        rustup target add $target
    fi
done

# 4. 编译 Rust 库 (auto-artifactarium)
echo -e "${YELLOW}[4/6] 编译 Rust 库...${NC}"

if [ -d "auto-artifactarium" ]; then {
    cd auto-artifactarium
    
    # 为所有 Android 架构编译
    for target in "${TARGETS[@]}"; do
        echo "正在为 $target 编译..."
        cargo build --release --target $target
        
        # 创建对应的 jniLibs 目录
        case $target in
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
        
        mkdir -p "../app/src/main/jniLibs/$ABI"
        cp "target/$target/release/libauto_artifactarium.so" "../app/src/main/jniLibs/$ABI/"
        echo -e "${GREEN}✓ $ABI 库编译完成${NC}"
    done
    
    cd ..
} || {
    echo -e "${RED}错误: Rust 库编译失败${NC}"
    exit 1
}
else
    echo -e "${YELLOW}⚠ auto-artifactarium 目录不存在，跳过 Rust 编译${NC}"
fi

# 5. 编译 C++ JNI 代码
echo -e "${YELLOW}[5/6] 编译 C++ JNI 代码...${NC}"

if [ -d "app/src/main/cpp" ]; then {
    cd app
    
    # 为所有架构编译
    for ABI in "arm64-v8a" "armeabi-v7a" "x86" "x86_64"; do
        echo "正在为 $ABI 编译 C++ 代码..."
        
        mkdir -p "src/main/cpp/build/$ABI"
        cd "src/main/cpp/build/$ABI"
        
        cmake -DANDROID_ABI=$ABI \
              -DANDROID_NDK=$ANDROID_SDK/ndk/$(ls $ANDROID_SDK/ndk/ | tail -1) \
              -DCMAKE_BUILD_TYPE=Release \
              -DCMAKE_TOOLCHAIN_FILE=$ANDROID_SDK/ndk/$(ls $ANDROID_SDK/ndk/ | tail -1)/build/cmake/android.toolchain.cmake \
              ../../..
        
        cmake --build . --config Release
        
        # 复制编译好的库到 jniLibs
        mkdir -p "../../../../jniLibs/$ABI"
        cp "libcapture_engine.so" "../../../../jniLibs/$ABI/"
        echo -e "${GREEN}✓ $ABI C++ 库编译完成${NC}"
        
        cd ../../../../..
    done
    
    cd ..
} || {
    echo -e "${RED}错误: C++ JNI 代码编译失败${NC}"
    exit 1
}
else
    echo -e "${RED}错误: app/src/main/cpp 目录不存在${NC}"
    exit 1
fi

# 6. 构建 Android APK
echo -e "${YELLOW}[6/6] 构建 Android APK...${NC}"

./gradlew assembleDebug || {
    echo -e "${RED}错误: Android APK 构建失败${NC}"
    exit 1
}

echo -e "${GREEN}=========================================${NC}"
echo -e "${GREEN}编译完成！${NC}"
echo -e "${GREEN}APK 位置: app/build/outputs/apk/debug/app-debug.apk${NC}"
echo -e "${GREEN}=========================================${NC}"

# 提示安装
echo ""
echo "是否立即安装到连接的设备? (y/n)"
read -r response
if [ "$response" = "y" ] || [ "$response" = "Y" ]; then
    ./gradlew installDebug
    echo -e "${GREEN}✓ 应用已安装到设备${NC}"
fi
