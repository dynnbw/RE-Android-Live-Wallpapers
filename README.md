<p align="center">
  <img src="app/src/main/res/drawable/ic_launcher_wallpaper.png" width="120" alt="Reborn Android Live Wallpapers"/>
</p>

<h1 align="center">Reborn Android Live Wallpapers</h1>

<p align="center">
  <img alt="GitHub Repo stars" src="https://img.shields.io/github/stars/dynnbw/RE-Android-Live-Wallpapers?style=flat-square&color=green"/>
  <img alt="License" src="https://img.shields.io/badge/License-Apache_2.0-green"/>
  <img alt="Android" src="https://img.shields.io/badge/安卓-7.0%2B-3DDC84?style=flat-square&logo=android&logoColor=white&color=green"/>
  <img alt="Wallpapers" src="https://img.shields.io/badge/壁纸总数-35-3DDC84?style=flat-square&color=green"/>
 <img alt="Wallpapers" src="https://img.shields.io/github/downloads/dynnbw/RE-Android-Live-Wallpapers/total?logo=github&logoColor=white&label=资源下载数&color=green"/>

<font color=#98FB98>Android 动态壁纸合集，将 AOSP / MediaTek 经典壁纸从 RenderScript 移植到 OpenGL ES 3.0 和 Vulkan，在新时代 Android 上继续运行。</font>

>  **语言**：[English](README-en.md)

>  **快速导航**：[用户使用](#用户指南) · [天气配置](#天气配置) · [音乐可视化](#音乐可视化) · [权限说明](#权限说明) · [性能参考](#性能参考) · [常见问题](#常见问题)　|　[开发者文档](ARCHITECTURE.md) · [贡献指南](CONTRIBUTING.md)

---

# 用户指南

## 安装使用

**下载安装**
1. 从 [GitHub Releases](../../releases) [ApkPure](https://apkpure.com/p/com.reandroid.wallpaper)下载 APK
2. 允许浏览器/文件管理器的"安装未知应用"权限后安装
3. 支持 Android 7.0（API 24）及以上系统

**设置为壁纸**

方法一（推荐）：打开应用 → 选择壁纸 → 进入设置页 → 点击"打开系统预览"

方法二：打开应用 → 打开壁纸选择器 → 找到"REAndroid 动态壁纸"（中国手机系统由于深度定制无法使用此方法）

方法三：系统桌面长按 → 背景 → 找到"REAndroid 动态壁纸"（中国手机系统由于深度定制无法使用此方法）

> 方法一会跳过系统壁纸选择器，直接预览。MIUI 用户首次设置时会弹出权限引导。

**每个壁纸有独立的设置选项** — 粒子数量、颜色、动画速度等都可调整。设置实时预览在页面顶部。

## 壁纸清单

共 **33 个**壁纸（另有 2 个隐藏壁纸不在应用列表中，此处不列），其中 **4 个**（Galaxy、Galaxy4、Grass、Fall）额外提供 Vulkan 后端

| 壁纸 | 类型 | VK | 说明 |
| --- | --- | --- | --- |
| Galaxy | 星空 | ✓ | 旋转星场，色彩渐变 |
| Galaxy4 | 星空 | ✓ | 旋转星场，色彩渐变 |
| NightSky | 星空 | | 依巴谷星表 9000 真实恒星，陀螺仪追踪，长按加速星轨 |
| Microbes | 粒子 | | 微生物群体 AI，触摸投喂食物，繁殖/死亡循环 |
| Grass | 自然 | ✓ | 风吹草动，日食/月相，支持天气联动 |
| WildWorld | 自然 | | 远古世界，火山/恐龙/翼龙/火球 |
| WalkAround | 自然 | | 相机透视，所见即所得 |
| Cube | 3D | | 8 种线框形状，3D 旋转，触摸拖拽 |
| Forest | 自然 | | 森林视差滚动 |
| DeepSea | 自然 | | 深海水母群，陀螺仪追踪视角 |
| BlueSea | 自然 | | 水母漂浮，粒子上升，触摸点亮 |
| Fall | 自然 | ✓ | 秋叶飘落，水面涟漪 |
| Ocean | 天气 | | 海洋天气壁纸，波浪/云层/降水联动 |
| Windmill | 天气 | | 风车天气壁纸，根据天气改变风车转速和天空 |
| Nexus | 特效 | | 脉冲光晕 |
| PhaseBeam | 特效 | | 相位光束，HSL 调色 |
| NoiseField | 特效 | | Perlin 噪声粒子，触摸产生扰动 |
| HoloSpiral | 特效 | | 全息螺旋，3D 透视旋转 |
| MagicSmoke | 特效 | | 多层烟雾叠加，迷幻效果 |
| Aurora1 | 极光 | | 北极光，99 帧光晕动画 |
| Aurora2 | 极光 | | 北极光第二版，色彩更加绚烂 |
| Fireworks | 特效 | | 烟花粒子，触摸发射烟花 |
| LuminousDots | 特效 | | 发光粒子点阵 |
| CosmicFlow | 特效 | | 灰底波纹 + GPU 噪声流场，8 种主题配色 |
| Silk | 特效 | | 流动的丝带，3 种主题配色 |
| Droid | 特效 | | 拆成部件的安卓机器人，受重力/加速度/触摸驱动（应用内名为「坠落的安卓机器人」） |
| PolarClock | 时钟 | | 极地时钟，三种调色板风格 |
| NixieTube | 时钟 | | 辉光管数码时钟，音频响应 |
| vis2 | 音乐 | | 音频频谱可视化 — FFT 波形 |
| vis3 | 音乐 | | 音频频谱可视化 — PCM 波形 |
| vis4 | 音乐 | | 音频可视化 — VU 表头风格 |
| vis5 | 音乐 | | 音频可视化 — 波形 + VU 组合 |
| vis6 | 音乐 | | 音频可视化 — 圆形频谱 |

## 功能亮点

与 AOSP/MediaTek 原版壁纸相比：
- **兼容新系统**：原版依赖 RenderScript（Android 12+ 已废弃），这里全部用 GLES/Vulkan 重写
- **独立设置**：每个壁纸有独立的设置页面，调整粒子数、颜色、动画速度等
- **天气联动**：Grass、Ocean、Windmill 可根据真实天气改变画面；两路数据源可选，中国气象局一路**不需要 API Key**
- **音乐可视化**：5 种音频频谱风格，播放音乐时壁纸随节奏变化
- **Vulkan 后端**：Galaxy、Galaxy4、Grass、Fall 可选 Vulkan 渲染

## 天气配置

Grass（动态草地）、Ocean（海洋天气）、Windmill（风车）三个壁纸支持根据真实天气改变画面效果：晴天阳光明媚、阴天云层加厚、下雨/下雪有粒子效果。

应用内置三路天气数据源，点工具栏天气图标 →「**天气数据源**」切换：

| 数据源 | 密钥 | 可用地区 |
|---|---|---|
| Open-Meteo | **不需要** | 全球 |
| 中国气象局（中国天气网） | **不需要** | 仅中国大陆 |
| OpenWeather | 需自行申请 | 全球 |

### Open-Meteo（默认）

**默认就是这一路**；点工具栏天气图标 →「天气数据源」可以切换。**不用注册任何账号**。一次请求同时拿到实时天气、当天最低/最高温与日出日落。

### 中国气象局（大陆用户）

点工具栏天气图标 →「天气数据源」→ 选「中国气象局」，**不用注册任何账号**。

> 这一路的观测数据只有大陆站点才有，判定看的**系统地区**：地区不是中国大陆（港澳台同理）时这一项为灰色不可选，取数也会自动回退到 OpenWeather。
> 带着大陆地区出国时会取不到数据，画面保持上一次的结果。
> 另外，国内网络访问 OpenWeather 经常超时，所以大陆用户用这一路通常更稳。

### OpenWeather

1. 打开 [OpenWeatherMap 注册页](https://home.openweathermap.org/users/sign_up)，注册免费账号
2. 登录后进入 [API Keys](https://home.openweathermap.org/api_keys)，复制默认 Key
3. 点工具栏天气图标 →「天气数据源」→ OpenWeather 那一行的「**配置API**」→ 粘贴 Key → 确定
4. 回到主页面，天气图标应显示当前天气状况

同一行还有「**教程**」，是注册与配置的完整图文步骤。免费额度为每天 1000 次调用。

两路都默认每 30 分钟更新一次（可在「更新间隔」中改为 15/30/60/180 分钟）。没有可用数据源时（既没填 Key、也没选中国气象局），天气壁纸仍可正常使用，只是不随真实天气变化。

**调试**：长按天气图标可手动覆盖天气状态（晴朗/多云/雨/雪等 10 种），方便测试壁纸在不同天气下的表现。

## 音乐可视化

vis2–vis6 是 5 种音频频谱可视化壁纸，播放音乐时壁纸会随音频节奏动态变化。

**使用方法：**
1. 选择 vis2–vis6 任意一个设为壁纸
2. 授予"录制音频"权限（仅用于读取音频频谱，不保存任何音频数据）
3. 播放手机上的音乐或视频
4. 壁纸会自动随音频变化

| 插件 | 风格 | 模式 | 特点 |
| --- | --- | --- | --- |
| vis2 | 彩色波形 | FFT 频谱 | 多段频谱柱状图，HSL 渐变 |
| vis3 | 彩色波形 | PCM 波形 | 连续波形曲线，更平滑 |
| vis4 | VU 表头 | PCM | 经典音频电平表，指针摆动 |
| vis5 | 组合视图 | FFT | 3D旋转 |
| vis6 | 环形频谱 | FFT | 360° 环绕频谱 |

> Android 系统限制音频捕获采样率。 AndroidX以上需要额外授权才能捕获系统音频。

## 权限说明

应用申请的权限及其原因：

| 权限 | 使用者 | 原因 | 可选 |
| --- | --- | --- | --- |
| 存储读取 | Fireworks（自定义背景） | 选择本地图片作为烟花背景 | ✓ |
| 定位（精确/粗略） | Grass、NightSky | 根据地理位置计算精确的日出日落时间等等 | ✓ |
| 相机 | WalkAround | 将相机画面作为壁纸背景（透视效果） | ✓ |
| 录音 | vis2–vis6 | 捕获系统音频输出用于频谱可视化 | ✓ |
| 网络 | 天气、更新检查 | 获取天气数据、检查版本更新 | ✓ |
| 动态壁纸服务 | 系统 | Android 动态壁纸必需权限 | × |

> ✓ = 可选，拒绝不影响壁纸基本功能　× = 某些系统必需

## 性能参考

不同壁纸的硬件消耗差异较大，供参考：

| 级别 | 壁纸 | 说明 |
| --- | --- | --- |
| 低功耗 | PolarClock, HoloSpiral, Forest | 静态为主，仅少量动画更新 |
| 中等 | Ocean, Windmill, Aurora1/2, Cube, DeepSea, BlueSea, Nexus, PhaseBeam, NoiseField, MagicSmoke, WalkAround, WildWorld | 有持续动画或粒子，但负载可控 |
| 高功耗 | Galaxy, Galaxy4, NightSky, Microbes, Grass, Fall, Fireworks, vis2–6 | 大量粒子/实体 AI/音频实时处理 |

**省电：**
- 通过壁纸设置页降低粒子数量、减少草叶数等
- 全局帧率设置为 30 FPS（设置页右上角菜单 → 全局帧率）
- Galaxy/Grass/Fall 可尝试开启 Vulkan 渲染
- 天气联动功能可以关闭（Grass 设置页 → 关闭"启用天气效果"）

## 常见问题

**Q: 无法打开壁纸预览界面**
MIUI/HyperOS 用户需要在系统设置中授予"动态壁纸服务"权限。应用首次检测到 MIUI 会自动弹出引导。

**Q: 天气不显示 / 显示不正确？**
检查：① 数据源选得对不对 —— Open-Meteo 与「中国气象局」都不需要密钥（前者全球可用，后者仅大陆）② 选 OpenWeather 的话 Key 有没有填对（工具栏天气图标 →「天气数据源」→ OpenWeather →「配置API」）③ 是否给予位置权限 ④ 网络是否正常，**国内网络经常连不上 OpenWeather**，这也是内置了另外两路数据源的原因 ⑤ 免费额度是否用完（1000 次/天）。长按天气图标可查看上次刷新时间

**Q: Vulkan 开关为什么看不到？**
只有 Galaxy、Galaxy4、Grass、Fall 四个壁纸有 Vulkan 后端。如果你的设备不支持 Vulkan，开关虽然可见但切换后可能无效果或崩溃。

**Q: 音乐可视化没有反应？**
确认：① 已授予录音权限 ② 正在播放音频（媒体音量不为零）

**Q: 全局帧率设置多少合适？**
默认 60 FPS 适合大多数设备。低端机建议 24 FPS。高刷新率屏幕可选 90/120 FPS。注意：帧率越高越耗电。

---

# 开发文档

架构、渲染路径、Vulkan 与 MusicVis 实现、各 schema 的权威说明，都移到了 **[ARCHITECTURE.md](ARCHITECTURE.md)**。

贡献代码（代码约定、设置读取、多语言、验证清单、提交约定）见 [CONTRIBUTING.md](CONTRIBUTING.md)。

## 许可证

[LICENSE](LICENSE) · [NOTICE](NOTICE.md)
