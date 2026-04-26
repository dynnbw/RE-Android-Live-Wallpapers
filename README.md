# Reborn Android Live Wallpapers

一个 Android 动态壁纸合集工程，聚焦高流畅度视觉效果与统一设置体验。

## 仓库壁纸特点

1. 壁纸类型覆盖广：
	粒子与星空类（Galaxy、NightSky、Nebula、Microbes）、自然场景类（Grass、WildWorld、WalkAround、DeepSea、BlueSea）、特效类（MagicSmoke、HoloSpiral、Nexus、PhaseBeam、Noisefield）等。
2. 双渲染路径：
	以 OpenGL ES 为主，同时包含 Vulkan 版本实验实现（如 Fall/Galaxy/Grass 的 VK 变体），便于性能对比与效果迭代。
3. 每张壁纸独立可配：
	亮度、速度、粒子数量、触控反馈等参数可在设置页单独调整，适合按机型性能做取舍。
4. 统一设置入口：
	所有壁纸在同一套 Settings 界面中管理，切换、预览、恢复默认配置流程一致。
5. 面向移动端优化：
	最低支持 Android 4.4（API 19），保留对低端设备的兼容，同时支持高帧率设备上的增强效果。

## 壁纸清单速览

| 分类 | 代表壁纸 | 对应模块目录 |
| --- | --- | --- |
| 粒子/星空 | Galaxy、Galaxy4、NightSky、Nebula、Microbes | app/src/main/java/com/reandroid/wallpaper/galaxy 等 |
| 自然/环境 | Grass、WildWorld、WalkAround、DeepSea、BlueSea | app/src/main/java/com/reandroid/wallpaper/grass 等 |
| 程序化特效 | Nexus、PhaseBeam、Noisefield、HoloSpiral、MagicSmoke | app/src/main/java/com/reandroid/wallpaper/nexus 等 |
| 天气与可视化 | WeatherWallpapers、MusicVis、Fall | app/src/main/java/com/reandroid/wallpaper/weatherwallpapers 等 |
| Aurora 系列 | Aurora1、Aurora2 | app/src/main/java/com/reandroid/wallpaper/aurora1 与 aurora2 |

常用资源入口：

- 壁纸服务声明 XML：app/src/main/res/xml
- 每个壁纸的偏好页面：app/src/main/res/xml/prefs_*.xml
- 着色器目录：app/src/main/shaders

## 开发环境配置

### 1. 必备工具

1. Android Studio（建议最新稳定版）
2. JDK 17
3. Android SDK Platform 33
4. Android Build-Tools（由 Android Studio SDK Manager 安装）
5. Android NDK 25.2.9519653（项目内已固定此版本）

### 2. 获取并导入工程

```bash
git clone https://github.com/dynnbw/RE-Android-Live-Wallpapers.git
cd RE-Android-Live-Wallpapers
```

在 Android Studio 中选择“Open”，打开仓库根目录。

### 3. 本地 SDK/NDK 路径

首次打开后，Android Studio 会生成或更新 local.properties。

确认以下路径可用：

```properties
sdk.dir=你的AndroidSdk路径
```

NDK 版本由工程配置指定为 25.2.9519653，无需在代码中手动改动。

### 4. 构建 Debug

Windows PowerShell：

```powershell
.\gradlew.bat assembleDebug
```

macOS/Linux：

```bash
./gradlew assembleDebug
```

输出 APK 默认位于：

```text
app/build/outputs/apk/debug/
```

### 5. 运行到设备

1. 连接真机（开启 USB 调试）或启动模拟器。
2. 在 Android Studio 点击 Run 安装应用。
3. 打开应用设置页选择目标壁纸并应用。

## 项目结构（与开发直接相关）

- app/src/main/java/com/reandroid/wallpaper：各壁纸渲染实现（按壁纸目录划分）
- app/src/main/res/xml：壁纸服务声明与对应偏好页面
- app/src/main/jni 与 app/src/main/jniLibs：Vulkan/Native 相关代码与产物
- app/src/main/shaders：着色器资源

## 许可证

项目许可证见 [LICENSE](LICENSE) 与 [NOTICE.md](NOTICE.md)。
