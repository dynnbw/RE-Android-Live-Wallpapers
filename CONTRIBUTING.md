# 贡献指南

感谢你愿意为 **Reborn Android Live Wallpapers** 贡献代码
本指南面向**外部贡献者**,覆盖从环境搭建到提交 Pull Request 的完整流程
架构细节见 [README.md 开发文档](README.md#开发文档)本指南不再重复

---

## 目录

- [环境与构建](#环境与构建)
- [项目快速导航](#项目快速导航)
- [壁纸收录标准](#壁纸收录标准)
- [问题反馈(Bug 报告)](#问题反馈bug-报告)
- [添加一个新壁纸(核心流程)](#添加一个新壁纸核心流程)
- [代码约定](#代码约定)
- [设置项与多语言](#设置项与多语言)
- [Git 与提交约定](#git-与提交约定)
- [验证清单](#验证清单)
- [提交 Pull Request](#提交-pull-request)
- [PR 之后](#pr-之后)

---

## 环境与构建

| 组件 | 版本 |
| --- | --- |
| Android Studio | 最新稳定版 |
| JDK | 21 |
| Android Gradle Plugin / Gradle | 8.7.3 / 8.10 |
| Android SDK | Platform 35 |
| Android NDK | 25.2.9519653(固定,仅 Vulkan 壁纸需要) |
| minSdk / targetSdk | 24 / 35 |

```bash
# Debug 包
./gradlew assembleDebug

# Release 包(自动递增版本号)
./gradlew assembleRelease
```

Vulkan 壁纸需要 4 个 ABI(`arm64-v8a` / `armeabi-v7a` / `x86_64` / `x86`),native 代码走 [Android.mk](app/src/main/jni/Android.mk)(ndk-build,`preBuild` 阶段自动触发)。**只改 GLES 壁纸不需要 NDK。**

安装到设备:`adb install -r app/build/outputs/apk/debug/app-debug.apk`

---

## 项目快速导航

```
app/src/main/
├── java/com/reandroid/
│   ├── gles/            GLES 框架基类(GLESScene)
│   ├── plugin/          插件架构核心(WallpaperPlugin / BasePluginEngine)
│   ├── vulkan/          Vulkan 工具类
│   ├── settings/        设置 UI(动态偏好渲染)
│   ├── weather/         天气数据层
│   └── wallpaper/       所有壁纸,每壁纸一个子包
├── assets/{wallpaper}/  每壁纸独立资产(30+ 个)
└── res/values*/         壁纸名称字符串(13 种语言)
```

**插件模型**:应用启动时自动枚举 `assets/*/info.json` 注册壁纸,三层接口:

1. **Plugin** — `WallpaperPlugin`:元数据(`getId` / `getDisplayName`),入口(`createEngine`)
2. **Engine** — `BasePluginEngine`:壁纸服务生命周期,唯一抽象方法 `createScene(width, height, context)`
3. **Scene** — `GLESScene`:渲染核心(`onCreate` / `drawFrame` / `release`),`setPluginPrefs` 注入设置、`setOffset` 接收偏移

所有接口签名见 [app/src/main/java/com/reandroid/plugin/](app/src/main/java/com/reandroid/plugin/)。**不需要改 AndroidManifest.xml**——插件自动发现。

---

## 壁纸收录标准

项目只接受符合以下标准的**新壁纸**提案;不符标准的提交会被直接拒绝,不予讨论:

1. **来源明确可验证** — 壁纸必须有清晰出处(原始 APK / 源码 / 提供壁纸来源，精确到手机型号、系统版本),能被维护者验证。来源模糊、无法鉴定的资源不予接受。**本项目只移植 AOSP、各大手机厂商、各 ROM 自带的壁纸,且限定在 Android 2~5 时代的老旧壁纸**(需要从过时的 OpenGL ES 1.0 / Canvas / RenderScript 渲染管线迁移的那些);较新的壁纸不在移植范围内。
2. **素材完整可运行** — 提交的素材必须完整:含字节码(odex / dex 等，如果有odex需要提供系统/system/framework/)。孤立的壳 APK(缺字节码、无法运行)不构成有效贡献。
3. **移植 = 代码重写** — 本项目对老旧壁纸的做法是**基于原始视觉效果重写代码**,将过时的 OpenGL ES 1.0 / Canvas / RenderScript渲染管线迁移至 Vulkan / OpenGL ES 2.0。混淆严重的编译产物与过时的 .so 库不能直接利用,逆向成本不成比例,不予接受。
4. **尊重项目规划** — 维护者按自身兴趣与节奏规划项目。不接受批量壁纸清单式投喂;是否移植、何时移植由维护者评估决定,外部提议不能设定议程或催促进度。
5. **沟通边界** — 多次提交不符标准资源的,此后仅接受针对**已有壁纸**的具体 [问题反馈](#问题反馈bug-报告);新的资源提议不再讨论。

> 简单说:只收「来源清楚、素材完整」的壁纸。贡献前请先自行审核素材,不要将鉴定工作留给维护者。

---

## 问题反馈(Bug 报告)

针对**已有壁纸**的问题反馈是受支持的贡献方式。提交 Issue 时请包含:

1. **设备信息** — 手机型号、Android 版本、系统语言。
2. **壁纸名称** — 具体到哪个壁纸、哪个设置项。
3. **复现步骤与现象** — 做了什么操作、看到什么异常(闪退 / 花屏 / 卡顿 / 设置不生效…)。
4. **调试日志(必附)** — 应用主界面顶部**长按右上角菜单键** → 弹出菜单选择「导出调试日志」,分享导出的日志文件。建议先选「清空调试日志」→ 复现问题 → 再导出,确保日志只包含本次问题。

缺少日志的问题报告难以定位,可能不会被处理。

---

## 添加一个新壁纸(核心流程)

以添加壁纸 `{id}`(如 `silk`)为例,四个层面:

### 1. 资产层 `app/src/main/assets/{id}/`

```
assets/{id}/
├── info.json          插件元数据(必填)
├── layout.json        动态偏好界面定义
├── language/          设置项翻译,13 个文件(bn de default es fr hi ja ko pt-rBR ru zh-rCN zh-rHK zh-rTW)
├── drawable/          纹理图片
├── shaders/GLES/      GLSL ES 2.0 着色器(顶点 + 片段)
├── icon.png           壁纸图标(正方形 PNG,预览网格使用)
└── data/              可选:CSV 网格/顶点数据
```

`info.json` 示例:

```json
{
  "label": "@string/wallpaper_silk",
  "plugin": "com.reandroid.wallpaper.silk.SilkPlugin",
  "previewClass": "com.reandroid.wallpaper.silk.SilkGL",
  "permissions": ["RECORD_AUDIO"]
}
```

- `label`:壁纸名称,引用 `res/values-*/strings.xml`
- `plugin`:插件类全限定名(必填,自动发现入口)
- `previewClass`:设置页顶部实时预览用的 GLESScene 子类
- `permissions`:运行时请求的权限(需在 AndroidManifest 中已声明)

### 2. Java 层 `app/src/main/java/com/reandroid/wallpaper/{id}/`

4 个类,职责分离:

| 类 | 继承/实现 | 职责 |
| --- | --- | --- |
| `XxxPlugin` | `WallpaperPlugin` | `getId()` 返回 `{id}`,`getDisplayName` 返回英文名 |
| `XxxEngine` | `BasePluginEngine` | 仅实现 `createScene` → `new XxxGL(w, h, context)` |
| `XxxGL` | `GLESScene` | 渲染:着色器、VBO、纹理、drawFrame |
| `XxxScene` | 纯逻辑(无 GL 依赖) | 动画数学、状态、参数表、prefs 读取 |

参考最小实现:[cube/](app/src/main/java/com/reandroid/wallpaper/cube/) 或 [musicvis/vis2/](app/src/main/java/com/reandroid/wallpaper/musicvis/vis2/)。

### 3. 名称层

13 个 `res/values-*/strings.xml` 各加一条 `<string name="wallpaper_{id}">`:

```xml
<string name="wallpaper_silk">丝语流年</string>
```

> **AAPT2 陷阱**:字符串含单引号时必须转义为 `\'`,否则构建报错且错误信息极具误导性。

### 4. 注册

**无需任何注册代码**——`assets/{id}/info.json` 存在即被枚举。构建后 `gradlew assembleDebug`,设置列表应出现新壁纸。

---

## 代码约定

### Scene/GL 分离(强制)

- **Scene**:纯逻辑,`import android.opengl.*` 一律禁止。动画数学、参数表、状态、prefs 全部在此,便于单测和预览复用。
- **GL**:仅做渲染。`onCreate` 只做非 GL 初始化(GL 上下文可能未就绪且会被调用两次);着色器/program/VBO/纹理在 **GL 线程首次 drawFrame** 惰性创建。
- 参考模式:`silk/SilkScene + SilkGL`、`musicvis/WaveScene + MusicVisWaveGL`。

### 性能纪律

- 每帧**零分配**:顶点/颜色缓冲预分配复用,`glBufferSubData` 更新,不 new 数组。
- 动画按真实 `dt`(帧间隔)推进,钳制上限(如 0.1s)防止卡顿后跳帧;支持用户速度倍率时在 Scene 层统一乘。
- 纹理注意解码参数:`GL_ONE` 预乘混合时须 `inPremultiplied = true` 解码,否则泛白。
- GLES 2.0 无 `#version` 控制流宏,注意 `pow(x, 2.0)` 对负底数是 **NaN**(现代驱动行为,旧驱动优化为 `x*x` 反而正常)——用 `x*x` 或防护分支。

### 设置读取

设置由引擎注入,Scene **不要自己 `getSharedPreferences`**(预览场景无注入路径):

```java
public void setPluginPrefs(SharedPreferences prefs) { ... }
// 内部:读 key → 字段,默认值兜底;设置变更由宿主实时重新注入
```

---

## 设置项与多语言

### layout.json

支持三种偏好控件:switch / list / seekbar(支持 `dependency` 条件显示)。每个条目的 `title` 就是语言文件里的**键名**;list 的标签按 `{key}_label_{value}` 解析(缺失时回落到 `labels` 数组)。

```json
{
  "type": "list",
  "key": "musicvis_idle_mode",
  "title": "musicvis_idle_mode",
  "default": "wave",
  "values": ["wave", "simulate", "flat"],
  "labels": ["musicvis_idle_mode_label_wave", "musicvis_idle_mode_label_simulate", "musicvis_idle_mode_label_flat"]
}
```

### 语言文件

- `language/` 下 13 个 JSON,`default.json` 为基准,**必须维护**;locale 文件缺键自动回落 default,再缺才显示裸键(难看)。
- 合并链:`default.json` → `{lang}-r{COUNTRY}` → `{lang}`。
- 新增键需同步全部 13 个文件;同一键在不同壁纸可译不同文案(如 vis2「模拟声波」vs vis3「模拟频谱」)。

---

## Git 与提交约定

- **提交信息**:Conventional Commits 前缀(`feat` / `fix` / `refactor` / `docs` / `delete` …),使用英文,参考近期历史风格。
- 提交前确认 `git diff` 只含本次改动;不提交构建产物与本地脚本。

---

## 验证清单

改动完成后逐项确认:

- [ ] `./gradlew assembleDebug` 通过,无新增警告
- [ ] 设置列表出现新壁纸(图标 + 本地化名称)
- [ ] 设置页顶部预览正常渲染;改设置**实时生效**、不重启
- [ ] 应用到壁纸后运行正常;反复切换壁纸/开关无 GL 报错(注意 logcat 的 EGL/GL 错误)
- [ ] 带音频的壁纸(vis2/3/5):无权限、静音、播放中三态都验证
- [ ] 系统语言切到 de / ja / zh-rCN 等,设置页文案齐全、无裸键
- [ ] 回归:其他壁纸不受影响

---

## 提交 Pull Request

1. **Fork + 分支**:Fork 本仓库,从 `main` 新建功能分支(如 `feat/add-xxx-wallpaper`)
2. **本地验证**:按 [验证清单](#验证清单) 逐项确认,至少保证 `./gradlew assembleDebug` 通过。
3. **提交**:遵循 [Git 与提交约定](#git-与提交约定),一条提交对应一个逻辑改动,不要把无关改动混进同一个 PR。
4. **推送并创建 PR**:
   - **标题**:与提交信息一致,`feat:` / `fix:` / `docs:` 前缀 + 英文简述。
   - **描述**:说明「改了什么 / 为什么 / 如何验证」,附设备实测截图更佳;修复 Issue 时引用(`Fixes #123`)。**新壁纸 PR 须符合 [壁纸收录标准](#壁纸收录标准),并注明素材来源与验证方式。**
5. **评审**:维护者会逐条 review,请在**同一分支**上追加提交或 amend,保持讨论串完整;分支落后时用 rebase 更新。
6. **合并**:评审通过后由维护者合并。涉及多语言的改动,建议在 PR 描述中注明各语言文案的来源。

---

## PR 之后

- 新增壁纸或功能时,在 PR 中同步更新 [README.md](README.md) 的壁纸清单表格与数量。
- 发布 Release、递增版本号、更新 CHANGELOG 由维护者执行,贡献者无需处理。

---

有任何疑问,在 Issue 或 PR 中提出即可。祝开发愉快!
