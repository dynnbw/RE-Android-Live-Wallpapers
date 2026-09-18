# 贡献指南

>  **语言**：[English](CONTRIBUTING-en.md)

感谢你愿意为 **Reborn Android Live Wallpapers** 贡献代码
本指南面向**外部贡献者**,覆盖从环境搭建到提交 Pull Request 的完整流程
架构细节见 [ARCHITECTURE.md](ARCHITECTURE.md)本指南不再重复

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

工具链版本与构建命令见 [ARCHITECTURE.md 构建配置](ARCHITECTURE.md#构建配置)，这里不重复。**只改 GLES 壁纸不需要 NDK。**

`app/src/main/jniLibs/` 下的 16 个 `.so`(4 个 Vulkan 壁纸 × 4 个 ABI)**是刻意纳入版本库的**,不是误提交的构建产物——这样没装 NDK 的贡献者也能直接构建。

注意 ndk-build 的输出目录就是这里(见 [app/build.gradle](app/build.gradle) 里 `NDK_LIBS_OUT=../jniLibs`),所以**重新编译 native 代码会直接改写这些被跟踪的文件**。

> ⚠️ 一个例外要记住:`./gradlew clean` 会**删掉整个 `jniLibs/`**(`clean` 任务里显式 delete),此时 `git status` 会显示这 16 个文件被删除。这是正常的,下一次构建会原样重新生成——**不要把那批删除提交上去**。

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
├── assets/{wallpaper}/  每壁纸独立资产(35 个)
└── res/values*/         壁纸名称字符串(13 种语言)
```

**插件模型**:应用启动时自动枚举 `assets/*/info.json` 注册壁纸。从**壁纸作者**的角度看是三层接口(框架侧的接口全集与调度细节见 [ARCHITECTURE.md 核心架构](ARCHITECTURE.md#核心架构)):

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
├── icon.png           壁纸图标(正方形,预览网格使用)
└── data/              可选:CSV 网格/顶点数据
```

> 图标两种后缀都支持:加载器按 `icon.png` → `icon.jpg` 依次尝试,都没有才用占位图。现有壁纸里多数是 `icon.jpg`。

`info.json` 的**字段表与用法**见 [ARCHITECTURE.md 的 info.json Schema](ARCHITECTURE.md#infojson-schema)。

**`hidden` 与打包**:标了 `"hidden": true` 的壁纸,`assembleRelease` 时整份资产不会被编入 APK(由 [app/build.gradle](app/build.gradle) 解析各 `info.json` 自动推导,无需改构建脚本);`assembleDebug` 仍会保留,方便继续开发。判定取「纯 debug 构建才保留」,失败方向是安全的。同时请求 debug 与 release(如 `./gradlew assemble`)时按正式包处理并打印提示。

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

- **Scene**:纯逻辑,动画数学、参数表、状态、prefs 全部在此,便于单测和预览复用。**判据是"能不能在 JVM 上单独编译运行"**,不是"有没有 import android"。
- **GL**:仅做渲染。`onCreate` 只做非 GL 初始化(GL 上下文可能未就绪且会被调用两次);着色器/program/VBO/纹理在 **GL 线程首次 drawFrame** 惰性创建。
- 参考模式:`silk/SilkScene + SilkGL`、`musicvis/WaveScene + MusicVisWaveGL`。
- **什么时候不拆**:看的是「逻辑是否与平台脱钩」。若非 GL 那部分本身就是平台 API 的管道
  (例如 `walkaround` 的 Camera 调用),搬进 Scene 也跑不了 JVM 测试,拆分收益为零 ——
  别为了凑够 4 个类而拆,那只是把文件分开放。

**关于 `android.opengl.Matrix`**:它有 `orthoM` / `multiplyMM` / `rotateM` 这类纯矩阵运算,不碰 GL 状态,**现有 10 个 Scene 都在用它构造投影矩阵**。用它不算破坏"不碰 GL"这条,但**会让该 Scene 无法在 JVM 上跑**(它是 Android 类,测试时得造替身)——也就是牺牲了这条规则本来要换来的可测性。

所以:新写的 Scene 优先把矩阵留给 GL 层;确实要用时,清楚自己在放弃什么。想给 Scene 写 JVM 测试的话,矩阵构造留在 GL 侧会让事情简单很多(`tools/` 下的测试就是靠挑"不依赖 Android 类"的 Scene 才跑起来的)。

### 性能纪律

- 每帧**零分配**:顶点/颜色缓冲预分配复用,`glBufferSubData` 更新,不 new 数组。
  注意 `arr.clone()` 和隐式初始化 `float[] v = {…}` 同样每次分配,只搜 `new` 会漏掉。
- 动画按真实 `dt`(帧间隔)推进,钳制上限(如 0.1s)防止卡顿后跳帧;支持用户速度倍率时在 Scene 层统一乘。
  **帧率是用户可设的全局项**(`global_frame_rate`,默认 60),所以「每帧加一个固定量」是个坑:
  用户把它从 60 调到 30,动画就慢一半、调到 120 就快一倍。想既保留原观感又与帧率无关,
  就乘 `dt * 60`(60fps 下系数为 1,逐帧与原实现一致)。
- **纹理解码**统一走 `AssetLoader`,它显式设 `inPremultiplied = false`(为消除透明纹理的黑边)。
  **别看到 `glBlendFunc(GL_ONE, ...)` 就以为"漏了预乘解码"**:本项目着色器输出的是
  **直通 alpha**(模拟 GLES 1.x 的 `GL_MODULATE`,RGB 不乘 alpha),`GL_ONE` 在这里不是预乘混合。
  **按"补上 inPremultiplied"去改,会把画面弄泛白。**
- GLES 2.0 无 `#version` 控制流宏,注意 `pow(x, 2.0)` 对负底数是 **NaN**(现代驱动行为,旧驱动优化为 `x*x` 反而正常)——用 `x*x` 或防护分支。

### 设置读取

**先分清设置该存在哪**:壁纸自己的设置存 `plugin_{id}`;应用级设置(天气 API、调试开关、
帧率、MIUI 提示标记…)存**应用默认 prefs**。不要写错地方。

设置由引擎注入,Scene **不要自己 `getSharedPreferences`**:

```java
public void setPluginPrefs(SharedPreferences prefs) { ... }
// 内部:读 key → 字段,默认值兜底;设置变更由宿主实时重新注入
```

三条容易踩的:

1. **注入的目标是 `info.json` 里的 `previewClass`,不是 Scene 本身。** 多数壁纸的
   `previewClass` 是 GL 类,由它转发给 Scene;vis2/vis3 的 `previewClass` 直接指向 Scene,
   所以那两个 Scene 的 `setPluginPrefs` 必须是 `public`。新增壁纸照 GL 类转发写即可。

2. **不要写 `mPluginPrefs != null ? mPluginPrefs : getSharedPreferences(...)` 这种兜底。**
   `createScene()` 之后紧接着就是 `tryInjectPrefs()`,而 `init()/start()` 是之后在渲染线程
   延迟执行的 —— 注入**总是**先发生,兜底永远走不到;而它读的旧文件名多半早已没有写入方。
   一旦哪天真的被走到,它会**静默**返回默认值,用户设置全部失效且不报错 —— 比没有兜底更糟。

3. **要读别的壁纸的设置**(只有合成类壁纸需要:vis5 把 vis2/vis3 的画面合在一起显示、
   fireworks 的草地夜景背景要模仿 grass),实现 `setPluginPrefsProvider(PluginPrefsProvider)`,
   由宿主注入,**不要自己按 `plugin_<id>` 直读**。

> 另有一个**共享静态注入点**容易漏看:`WallpaperSettings.setSharedPreferences(...)`
> 由 `BaseVKPluginEngine` 设置,`WallpaperSettings.getXxx()` 优先读它。
> 判断"某个设置到底会不会生效"时,不能只看直接的 `setPluginPrefs` 调用。

---

## 设置项与多语言

### layout.json

支持五种偏好控件(支持 `dependency` 条件显示)。每个条目的 `title` 就是语言文件里的**键名**;list 的标签按 `{key}_label_{value}` 解析(缺失时回落到 `labels` 数组)。

控件的**字段表**见 [ARCHITECTURE.md 的 layout.json Schema](ARCHITECTURE.md#layoutjson-schema)。两条表里没写的:

- `color` 的 `labels` 给的是滑块标签,**个数决定显示 2 个还是 3 个滑块**
- `button` **不存值**,靠 `action` 触发行为

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

`color` 与 `button` 的写法可参考 [grass/layout.json](app/src/main/assets/grass/layout.json) 与 [fireworks/layout.json](app/src/main/assets/fireworks/layout.json)。

### 语言文件

- `language/` 下 13 个 JSON,`default.json` 为基准,**必须维护**;locale 文件缺键自动回落 default,再缺才显示裸键(难看)。
- 合并链:`default.json` → `{lang}-r{COUNTRY}` → `{lang}`。
- 新增键需同步全部 13 个文件;同一键在不同壁纸可译不同文案(如 vis2「模拟声波」vs vis3「模拟频谱」)。

---

## Git 与提交约定

- **提交信息**:Conventional Commits 前缀(`feat` / `fix` / `refactor` / `docs` / `delete` …),使用英文,参考近期历史风格。
- 提交前确认 `git diff` 只含本次改动;不提交构建产物与本地脚本。
  **唯一的例外是 `app/src/main/jniLibs/*.so`**——那是刻意纳入版本库的预编译 native 库,理由与注意事项见[环境与构建](#环境与构建),不要当成本地构建产物清理掉。

---

## 验证清单

改动完成后逐项确认:

- [ ] `./gradlew assembleDebug` 通过,无新增警告
- [ ] 动了 Scene 逻辑的,跑一遍对应的 JVM 测试:`tools/<名字>-test/` 下每个测试的
      文件头注释里写着它的 `javac` / `java` 命令(Scene 不依赖 Android 类才跑得起来,
      见 [代码约定](#代码约定))。没有对应测试时,优先补一个 —— 这正是 Scene/GL 分离换来的东西
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
