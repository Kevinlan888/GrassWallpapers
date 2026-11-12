# GrassWallpapers（四季草地）

[![Build APK](https://github.com/Kevinlan888/GrassWallpapers/actions/workflows/build.yml/badge.svg)](https://github.com/Kevinlan888/GrassWallpapers/actions/workflows/build.yml)

面向 Android 的 Vulkan 动态壁纸。桌面会呈现一片持续摆动的草地，并根据本地时间、
季节自动切换清晨、白天、傍晚、夜晚，以及春、夏、秋、冬的场景。

- 启动器名称：`草地动态壁纸`
- 壁纸名称：`四季草地`
- 包名：`com.a65.grasswallpapers.vulkan`
- 当前版本：`0.12`（`versionCode 12`）
- 自动构建：GitHub Actions 上传 APK 和 SHA-256 校验文件

## 功能

- 512 片六段弯曲草叶，带风力摆动和季节颜色
- 18 株蒲公英，绒球由多层纤维、棉絮核心和羽状尖端组成
- 96 个季节粒子：春花瓣、夏蒲公英绒毛、秋枫叶、冬雪花
- 四种天空时段：清晨、白天、傍晚、夜晚
- 四种季节：春、夏、秋、冬
- 不绘制太阳和月亮，只改变天空、光照与场景颜色
- 默认按北半球月份和本地时钟自动切换
- 可关闭自动同步，改为 30 秒、1 分钟、5 分钟或 15 分钟轮转
- 关闭自动同步后，轻点桌面左半屏切换季节，右半屏切换时段
- 渲染固定限制为 30 FPS；动画按真实时间推进，不会降速
- 壁纸不可见或息屏时暂停渲染，回到桌面后自动恢复
- 不申请网络、定位或存储权限

## 环境要求

| 项目 | 要求 |
| --- | --- |
| Android | 7.0 及以上（API 24+） |
| 目标版本 | Android 15（API 35） |
| 图形接口 | Vulkan 1.0 及以上 |
| ABI | `arm64-v8a`、`armeabi-v7a` |
| 测试设备 | Android 15，MediaTek MT6895，Mali-G610 MC6 |

设备必须实际支持 Vulkan。清单中把 Vulkan 声明为非强制特性，是为了让不支持
Vulkan 的设备仍能安装并进入设置页；没有 Vulkan 时壁纸无法绘制场景。

## 安装与启用

从 [GitHub Actions](https://github.com/Kevinlan888/GrassWallpapers/actions/workflows/build.yml)
最近一次成功的 `Build APK` 运行中下载构建产物，或参考
[从源码构建](#从源码构建) 在本地生成 APK。

安装 APK：

```bash
adb install -r dist/GrassWallpapers.apk
```

启用壁纸：

1. 点击桌面上的 `草地动态壁纸` 图标，打开设置页。
2. 点击 `设为动态壁纸`，进入系统预览。
3. 在系统预览页点击 `设置壁纸`。

部分启动器不展示第三方动态壁纸入口，此时可以在设置页点击
`打开系统壁纸选择器`，再从系统设置或系统壁纸列表中选择 `四季草地`。

桌面图标只负责打开设置页，不会直接启动 Vulkan 预览，这是预期行为。

## 使用方式

### 跟随本地时间与季节

默认开启。壁纸按当前月份选择季节，按本地时钟选择时段：

- 清晨：05:00–09:00
- 白天：09:00–17:00
- 傍晚：17:00–20:00
- 夜晚：20:00–05:00

自动同步开启时，桌面轻点切换会被屏蔽，避免误触改变场景。

### 定时轮转

关闭 `跟随本地时间与季节` 后，可以选择 30 秒、1 分钟、5 分钟或 15 分钟轮转。
每个间隔切换一个时段，四个时段走完后进入下一季。

此时也可以直接在桌面操作：

- 左半屏：切换季节
- 右半屏：切换时段

设置会即时同步到正在运行的壁纸，不需要重新应用。时间同步模式还会根据返回墙面的
时间刷新当前季节和时段。

## 渲染与性能

渲染器使用原生 C++ 和 Vulkan 1.0：

- `VK_PRESENT_MODE_FIFO_KHR` 呈现模式
- 优先使用 4x MSAA，不支持时自动降低采样数
- 两个帧槽、双缓冲同步原语
- 固定 30 FPS 节拍，帧间隔约 `33.3 ms`
- Android 11 及以上会向系统声明 30 FPS Surface 帧率，减少刷新率反复切换
- 壁纸不可见时停止提交新帧，只保留约 40 ms 周期的暂停检查

在 Android 15 / MT6895 设备上的参考开销：

| 状态 | CPU | 内存 |
| --- | --- | --- |
| 动画可见，30 FPS | 约单核 11%–13% | PSS 约 90 MB，GPU 分配约 127 MB |
| 息屏或不可见 | 约单核 0.4% | 渲染停止，进程保持常驻 |

具体数值会随分辨率、MSAA、GPU 和系统省电策略变化。

## 项目结构

```text
.
├── .github/
│   └── workflows/
│       └── build.yml              # GitHub Actions APK 构建
├── build_vulkan_spike.sh          # 无 Gradle 的完整构建脚本
├── dist/
│   └── GrassWallpapers.apk       # 本地构建产物，不进入 Git
├── spike/
│   ├── AndroidManifest.xml
│   ├── README.md                 # 实现与版本细节
│   ├── cpp/
│   │   ├── vulkan_spike.cpp      # Vulkan 渲染器、场景状态与 JNI
│   │   └── shaders/              # GLSL 顶点/片段着色器
│   ├── res/                      # 图标、缩略图、字符串和壁纸元数据
│   └── src/com/a65/grasswallpapers/vulkan/
│       ├── VulkanGrassWallpaperService.java
│       ├── VulkanNative.java
│       ├── VulkanSpikeActivity.java
│       └── WallpaperSettings.java
└── README.md
```

- Java 层负责动态壁纸服务、设置页、偏好同步和触摸事件。
- JNI 层负责把 `Surface` 转换为 `ANativeWindow`，管理渲染线程生命周期。
- C++ 层负责 Vulkan 初始化、交换链、管线、绘制和场景时间状态。
- 着色器在构建时编译为 SPIR-V，并嵌入原生库。

## 从源码构建

构建脚本不依赖 Gradle，直接调用 Android SDK、NDK 和 APK 签名工具。

### 依赖

- JDK 17
- Android SDK Platform 35
- Android SDK Build-Tools 36.0.0
- Android NDK 27.2.12479018
- `glslc`
- `zip`、`xxd`、`keytool`、`apksigner`、`zipalign`

仓库根目录需要提供 `local.properties`：

```properties
sdk.dir=/path/to/Android/Sdk
```

### 构建

```bash
./build_vulkan_spike.sh
```

输出：

```text
dist/GrassWallpapers.apk
```

脚本会：

1. 编译资源并生成 `R.java`
2. 编译四组 GLSL 着色器为 SPIR-V
3. 编译 Java 并转换为 DEX
4. 为 `arm64-v8a` 和 `armeabi-v7a` 编译原生 Vulkan 库
5. 打包、对齐并使用调试密钥签名 APK
6. 校验签名和对齐，输出 SHA-256

当前脚本使用本机 Android 调试密钥签名，适合本地安装测试，不是发布签名流程。

### 可选环境变量

| 变量 | 作用 |
| --- | --- |
| `ANDROID_SDK_ROOT` | 覆盖 `local.properties` 中的 SDK 路径 |
| `ANDROID_NDK_HOME` | 指定 NDK 路径 |
| `BUILD_TOOLS` | 指定 Build-Tools 目录 |
| `ANDROID_JAR` | 指定 `android.jar` |
| `DEBUG_KEYSTORE` | 指定签名密钥库 |
| `APK_NAME` | 指定输出 APK 文件名 |

## 持续集成

GitHub Actions 工作流定义在 `.github/workflows/build.yml`，会在以下场景运行：

- 推送到任意分支
- 提交或更新 Pull Request
- 在 Actions 页面手动触发

工作流会安装 JDK 17、Android SDK Platform 35、Build-Tools 36.0.0 和
NDK 27.2.12479018，然后执行完整构建。每次成功运行会上传一个包含以下文件的
Artifact：

- `GrassWallpapers.apk`
- `GrassWallpapers.apk.sha256`

APK 被 `.gitignore` 排除，因此仓库不再跟踪二进制安装包。发布或测试时直接从
Actions 的成功运行下载 Artifact，或按本文档在本地构建。

## 故障排查

### 点击图标没有进入壁纸预览

这是预期行为。图标只打开设置页，需要点击 `设为动态壁纸`，再在系统预览页确认设置。

### 系统壁纸列表中找不到壁纸

先确认 APK 已正确安装，再打开应用设置页点击 `打开系统壁纸选择器`。
也可以从系统设置中的壁纸入口选择 `四季草地`。

### 壁纸显示黑屏或没有动画

确认设备支持 Vulkan，并查看日志：

```bash
adb logcat -s VulkanGrassSpike
```

常见错误包括没有 Vulkan 设备、交换链创建失败或 GPU 驱动不支持所需能力。

### 壁纸只有 30 FPS

这是设计要求。渲染线程和 Surface 都限制为 30 FPS，用于降低高刷新率屏幕上的
GPU 占用、发热和耗电。

### 重新安装后壁纸画面停滞

确认版本至少为 `0.11`。旧版本在切换系统预览时会错误停止当前壁纸的渲染器；
`0.11` 已改为按 Engine Owner ID 管理渲染生命周期。

## 版本记录

### 0.12

- 将渲染限制为 30 FPS
- 向 Android 声明固定 30 FPS Surface 帧率
- 更新 APK 和版本号

### 0.11

- 修复切换预览后旧 Engine 误停全局渲染器的问题
- 每个 Wallpaper Engine 使用独立 Owner ID

### 0.10

- 补齐应用图标、动态壁纸缩略图和 `WallpaperService`
- 启动器页面不再初始化 Vulkan
- 修复 Android 15 启动页 Insets 查询导致的闪退

更详细的实现说明和场景参数见 [`spike/README.md`](spike/README.md)。
