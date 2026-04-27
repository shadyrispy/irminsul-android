#!/bin/bash
# build_cpp.sh - 编译 C++ JNI 代码
# 这个脚本编译 capture_engine.cpp

set -e

echo "========================================="
echo "  编译 C++ JNI 代码"
echo "========================================="

# 颜色定义
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# 检查 CMake
if ! command -v cmake &> /dev/null; then
    echo -e "${RED}错误: cmake 未安装${NC}"
    echo "请先安装 cmake"
    exit 1
fi

# 检查 Android NDK
if [ -z "$ANDROID_HOME" ] && [ -z "$ANDROID_SDK_ROOT" ]; then
    echo -e "${RED}错误: 未设置 ANDROID_HOME 或 ANDROID_SDK_ROOT 环境变量${NC}"
    echo "请设置 Android SDK 路径"
    exit 1
fi

ANDROID_SDK=${ANDROID_HOME:-$ANDROID_SDK_ROOT}

# 查找 NDK 路径
NDK_PATH=""
if [ -d "$ANDROID_SDK/ndk" ]; then
    NDK_PATH=$(ls -d $ANDROID_SDK/ndk/* | tail -1)
elif [ -d "$ANDROID_SDK/ndk-bundle" ]; then
    NDK_PATH="$ANDROID_SDK/ndk-bundle"
fi

if [ -z "$NDK_PATH" ]; then
    echo -e "${RED}错误: 未找到 Android NDK${NC}"
    echo "请安装 NDK 或设置 ANDROID_NDK 环境变量"
    exit 1
fi

echo -e "${GREEN}✓ 找到 NDK: $NDK_PATH${NC}"

# 需要编译的架构
ABIS=("arm64-v8a" "armeabi-v7a" "x86" "x86_64")

# 为每个架构编译
for ABI in "${ABIS[@]}"; do
    echo -e "${YELLOW}正在为 $ABI 编译...${NC}"
    
    # 创建构建目录
    BUILD_DIR="app/src/main/cpp/build/$ABI"
    mkdir -p "$BUILD_DIR"
    cd "$BUILD_DIR"
    
    # 运行 CMake
    cmake \
        -DANDROID_ABI=$ABI \
        -DANDROID_NDK=$NDK_PATH \
        -DCMAKE_BUILD_TYPE=Release \
        -DCMAKE_TOOLCHAIN_FILE=$NDK_PATH/build/cmake/android.toolchain.cmake \
        ../..
    
    # 编译
    cmake --build . --config Release -j$(nproc)
    
    # 复制编译好的库到 jniLibs
    mkdir -p "../../../../jniLibs/$ABI"
    if [ -f "libcapture_engine.so" ]; then
        cp "libcapture_engine.so" "../../../../jniLibs/$ABI/"
        echo -e "${GREEN}✓ $ABI C++ 库编译完成${NC}"
    else
        echo -e "${RED}✗ $ABI 编译失败：未找到 libcapture_engine.so${NC}"
        exit 1
    fi
    
    cd ../../../../..
done

echo -e "${GREEN}=========================================${NC}"
echo -e "${GREEN}C++ JNI 代码编译完成！${NC}"
echo -e "${GREEN}库位置: app/src/main/jniLibs/${NC}"
echo -e "${GREEN}=========================================${NC}"
