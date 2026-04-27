# irminsul-android

原神数据导出工具的 Android 实现，通过抓包方式获取游戏数据，无需 root 权限。

## 项目简介

irminsul-android 是基于 [irminsul](https://github.com/konkers/irminsul) 项目的 Android 移植版本，使用 [auto-artifactarium](https://github.com/konkers/auto-artifactarium) 进行数据包解析，通过 VPNService 实现无 root 抓包。

## 核心功能

- **无 root 抓包**：使用 Android VPNService 捕获游戏流量
- **自动数据解析**：集成 auto-artifactarium 解析原神数据包
- **数据导出**：支持导出为 GOOD 格式，可直接导入 [Genshin Optimizer](https://frzyc.github.io/genshin-optimizer/)
- **角色数据**：展示角色、武器、圣遗物、素材等游戏数据

## 项目结构

```
irminsul-android/
├── app/                          # Android 应用模块
│   └── src/main/
│       ├── java/.../irminsul/   # Kotlin 代码
│       ├── cpp/                   # C++ JNI 代码
│       └── rust/                  # Rust 代码 (auto-artifactarium)
├── auto-artifactarium/           # submodule -> https://github.com/konkers/auto-artifactarium
├── scripts/                      # 构建和辅助脚本
└── build.gradle                 # 项目配置
```

## 快速开始

### 1. 克隆项目

```bash
# 克隆主项目
git clone --recursive https://github.com/konkers/irminsul-android.git
cd irminsul-android

# 如果忘记加 --recursive，可以后续更新 submodule
git submodule update --init --recursive
```

### 2. 一键编译

```bash
# 使用提供的构建脚本编译所有依赖
cd scripts
chmod +x build_all.sh
./build_all.sh
```

### 3. 在 Android Studio 中打开并运行

## 构建说明

### 环境要求

- Android Studio Hedgehog | 2023.1.1+
- Android NDK 25.2.9519653+
- Rust 1.75.0+ (带 Android 目标支持)
- CMake 3.22.1+

### 一键编译脚本

项目提供了 `build_all.sh` 脚本，自动完成以下步骤：

1. 检查并安装 Rust 环境和 Android 目标
2. 更新 auto-artifactarium submodule
3. 编译 Rust 库 (auto-artifactarium)
4. 编译 C++ JNI 代码
5. 构建 Android APK

### 手动构建步骤

如果需要手动构建，请按以下步骤：

#### 1. 配置 Rust 环境

```bash
# 安装 Rust
curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh

# 添加 Android 目标
rustup target add aarch64-linux-android
rustup target add armv7-linux-androideabi
rustup target add i686-linux-android
rustup target add x86_64-linux-android

# 安装 cargo-ndk (可选，简化构建)
cargo install cargo-ndk
```

#### 2. 编译 Rust 库 (auto-artifactarium)

```bash
# 进入 submodule 目录
cd auto-artifactarium

# 编译 release 版本
cargo build --release --target aarch64-linux-android

# 复制生成的库到 Android 项目
cp target/aarch64-linux-android/release/libauto_artifactarium.so \
   ../app/src/main/jniLibs/arm64-v8a/

# 重复以上步骤为其他架构编译
```

#### 3. 编译 C++ JNI 代码

```bash
cd ../app

# 使用 CMake 编译
cmake -DANDROID_ABI=arm64-v8a \
      -DANDROID_NDK=${ANDROID_HOME}/ndk/25.2.9519653 \
      -DCMAKE_BUILD_TYPE=Release \
      -B build
cmake --build build
```

#### 4. 构建 Android APK

```bash
# 在 Android Studio 中打开项目，或使用命令行
./gradlew assembleRelease
```

## 使用说明

1. **启动应用**：授予必要的 VPN 权限
2. **开始捕获**：点击"开始捕获"按钮
3. **启动游戏**：打开原神并开始游戏
4. **查看数据**：切换回应用查看捕获的游戏数据
5. **导出数据**：选择需要导出的数据类型，导出为 GOOD 格式

## 技术架构

```
┌─────────────────────────────────────────┐
│            irminsul-android             │
│                                        │
│  ┌──────────────────────────────────┐  │
│  │         UI (Jetpack Compose)    │  │
│  └──────────────┬───────────────────┘  │
│                 │                       │
│  ┌──────────────┴───────────────────┐  │
│  │      Kotlin (ViewModel/Service)  │  │
│  └──────────────┬───────────────────┘  │
│                 │                       │
│  ┌──────────────┴───────────────────┐  │
│  │      JNI (Java Native Interface) │  │
│  └──────────────┬───────────────────┘  │
│                 │                       │
│  ┌──────────────┴───────────────────┐  │
│  │   C++ (capture_engine)            │  │
│  │   + Rust (auto-artifactarium)    │  │
│  └──────────────────────────────────┘  │
└─────────────────────────────────────────┘
```

## auto-artifactarium Submodule

本项目使用 git submodule 方式集成 auto-artifactarium，方便后续版本更新。

### 更新 auto-artifactarium

```bash
# 更新 submodule 到最新版本
git submodule update --remote auto-artifactarium

# 提交更新
git add auto-artifactarium
git commit -m "Update auto-artifactarium to latest version"
```

### 修改 auto-artifactarium

如果需要修改 auto-artifactarium 源码：

```bash
# 进入 submodule 目录
cd auto-artifactarium

# 创建新分支进行修改
git checkout -b feature/new-protocol
# ... 进行修改 ...
git commit -am "Add support for new protocol"

# 返回主项目目录
cd ..
git add auto-artifactarium
git commit -m "Update auto-artifactarium with custom changes"
```

## 许可证

- **本项目**：MIT License
- **auto-artifactarium**：MIT License
- **PCAPdroid**（如果使用）：GPL-3.0

## 致谢

- [irminsul](https://github.com/konkers/irminsul) - 原始项目
- [auto-artifactarium](https://github.com/konkers/auto-artifactarium) - 数据解析
- [Genshin Optimizer](https://frzyc.github.io/genshin-optimizer/) - 数据格式标准

## 常见问题

### Q: 为什么需要 VPN 权限？
A: 应用使用 VpnService 拦截网络流量以捕获原神的数据包。

### Q: 数据是否安全？
A: 所有数据捕获和解析都在设备本地完成，不会发送到外部服务器。

### Q: 支持哪些数据导出？
A: 目前支持角色、武器、圣遗物、素材的 GOOD 格式导出。

### Q: 如何更新 auto-artifactarium？
A: 使用 `git submodule update --remote auto-artifactarium` 命令更新。
