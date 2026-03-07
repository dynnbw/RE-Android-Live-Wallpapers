# Reborn Android Live Wallpapers

一个基于 Android 动态壁纸（Live Wallpaper）的合集项目，包含多种 OpenGL/粒子/天气类壁纸效果，并提供统一设置入口。

## 功能概览

- 多壁纸引擎：如 `Galaxy`、`Nexus`、`Ocean`、`Grass`、`Fireworks`、`WildWorld`、`Windmill`、`BlueSea`、`DeepSea`、`MagicSmoke`、`HoloSpiral`、`PolarClock`、`MusicVis` 等。
- 独立设置页与预览配置。
- 支持根据构建任务自动维护版本号（`assembleRelease` 成功后自动递增）。

## 技术栈

- Android Gradle Plugin: `8.1.0`
- Gradle Wrapper（项目自带）
- Java 8（`sourceCompatibility/targetCompatibility = 1.8`）
- `compileSdk 33` / `targetSdk 33` / `minSdk 19`

## 环境要求

- Windows / macOS / Linux
- JDK 17（推荐，与 AGP 8.x 更匹配）
- Android SDK 33
- 已安装可用的 Android Build Tools（由 Android Studio 自动管理即可）

## 快速开始

### 1) 克隆仓库

```bash
git clone https://github.com/dynnbw/RE-Android-Live-Wallpapers.git
cd RE-Android-Live-Wallpapers
```

### 2) 本地配置（重要）

以下文件已被忽略，不会上传：

- `local.properties`
- `gradle.properties`
- `*.jks` / `*.keystore`

请在本地创建或维护 `gradle.properties`（示例）：

```properties
APP_VERSION_CODE=1
APP_VERSION_NAME=1.0

RELEASE_STORE_FILE=./your-release.jks
RELEASE_STORE_PASSWORD=your_password
RELEASE_KEY_ALIAS=your_alias
RELEASE_KEY_PASSWORD=your_key_password
```

> 不要提交包含密码或签名信息的配置文件。

### 3) 构建 Debug

```bash
./gradlew assembleDebug
```

Windows PowerShell:

```powershell
.\gradlew.bat assembleDebug
```

### 4) 构建 Release

```bash
./gradlew assembleRelease
```

## 版本号规则

项目在 `assembleRelease` 成功后会自动执行版本号递增逻辑：

- `APP_VERSION_CODE`：`+1`
- `APP_VERSION_NAME`：按 `0.1` 递增（保留 1 位小数）

请确保本地 `gradle.properties` 中存在以上字段。

## 目录说明（简）

- `app/src/main/java`：壁纸引擎与设置代码
- `app/src/main/res/xml`：壁纸服务与偏好配置 XML
- `app/src/main/res/drawable`：贴图与资源
- `png/`、`RESOURCE/`、`DebugTools/`：本地资源/工具目录（默认不上传）

## 开源协议与来源说明

### 项目许可证

本项目采用 **Apache License 2.0** 发布，详见 [LICENSE](LICENSE)。

### Android / MediaTek 来源代码说明

- 本项目为对部分 Android 动态壁纸实现的重制与移植，重点是将原有 **RenderScript/旧渲染实现** 重制为 **OpenGL ES** 壁纸实现。
- 项目中若包含来自 AOSP（Android Open Source Project）或 MediaTek 相关源码/衍生实现的文件，应遵循其原始许可证与版权声明。
- 具体到单个文件时，以该文件头部版权与许可声明为准。

### 再分发要求（简述）

- 保留原作者版权声明与许可文本。
- 对修改过的文件或行为保持清晰说明。
- 不得删除或篡改第三方许可证要求的 NOTICE/声明。

> 若某些文件带有额外或更严格的授权条款（例如特定平台厂商条款），则该文件按其原条款执行，不自动受本仓库统一许可证覆盖。

## 常见问题

### Q1: `git push` 提示 `fetch first`

如果你刚重写了历史，需使用：

```bash
git push -u --force-with-lease origin main
```

### Q2: 为什么某些文件在本地可见但不参与提交？

因为它们被 `.gitignore` 排除了（例如构建产物、签名文件、本地配置与大资源目录）。

## 免责声明

本项目包含动态壁纸相关资源与效果代码。请在分发或商用前自行确认资源授权与合规要求。
