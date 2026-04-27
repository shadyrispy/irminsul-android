cmake_minimum_required(VERSION 3.22.1)
project("irminsul-android")

# 设置 C++ 标准
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Android 配置
set(ANDROID_ABI ${CMAKE_ANDROID_ARCH_ABI})

# 查找 Android NDK 库
find_library(ANDROID_LOG_LIB log)
find_library(ANDROID_ANDROID_LIB android)
find_library(ANDROID_DL_LIB dl)

# Rust 目标映射
if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(RUST_TARGET "aarch64-linux-android")
elseif(ANDROID_ABI STREQUAL "armeabi-v7a")
    set(RUST_TARGET "armv7-linux-androideabi")
elseif(ANDROID_ABI STREQUAL "x86")
    set(RUST_TARGET "i686-linux-android")
elseif(ANDROID_ABI STREQUAL "x86_64")
    set(RUST_TARGET "x86_64-linux-android")
endif()

message(STATUS "Configuring for ABI: ${ANDROID_ABI}, Rust target: ${RUST_TARGET}")

# 查找 Rust 库 (auto-artifactarium)
set(RUST_LIB_PATH "")
set(RUST_LIB_FOUND FALSE)

# 可能的库路径
set(RUST_LIB_PATHS
    "${CMAKE_SOURCE_DIR}/../../auto-artifactarium/target/${RUST_TARGET}/release"
    "${CMAKE_SOURCE_DIR}/../auto-artifactarium/target/${RUST_TARGET}/release"
    "${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}"
    "${CMAKE_SOURCE_DIR}/../../jniLibs/${ANDROID_ABI}"
)

foreach(LIB_PATH ${RUST_LIB_PATHS})
    if(EXISTS "${LIB_PATH}/libauto_artifactarium.so")
        set(RUST_LIB_PATH "${LIB_PATH}/libauto_artifactarium.so")
        set(RUST_LIB_FOUND TRUE)
        message(STATUS "Found Rust library: ${RUST_LIB_PATH}")
        break()
    endif()
endforeach()

# 创建捕获引擎库
add_library(capture_engine SHARED
    capture_engine.cpp
)

# 包含目录
target_include_directories(capture_engine PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${ANDROID_NDK}/sysroot/usr/include
)

# 链接库
target_link_libraries(capture_engine
    ${ANDROID_LOG_LIB}
    ${ANDROID_ANDROID_LIB}
    ${ANDROID_DL_LIB}
)

# 如果找到 Rust 库，则复制
if(RUST_LIB_FOUND)
    add_custom_command(TARGET capture_engine POST_BUILD
        COMMAND ${CMAKE_COMMAND} -E copy
            ${RUST_LIB_PATH}
            $<TARGET_FILE_DIR:capture_engine>
        COMMENT "Copying Rust library to output directory"
    )
    message(STATUS "Will copy Rust library after build")
endif()

# 设置编译选项
target_compile_options(capture_engine PRIVATE
    -Wall
    -Wextra
    $<$<CONFIG:Release>:-O3>
    $<$<CONFIG:Debug>:-g>
)

message(STATUS "CMake configuration completed for ${ANDROID_ABI}")
