# Reborn Android Live Wallpapers — WebGL 预览

浏览器端 WebGL 动态壁纸预览，源自 AOSP RenderScript → OpenGL ES / Vulkan 移植。

**在线地址：** [dynnbw.github.io/RE-Android-Live-Wallpapers](https://dynnbw.github.io/RE-Android-Live-Wallpapers/)

## 本地运行

任意静态 HTTP 服务即可：

```bash
python3 -m http.server 8080
# 浏览器打开 http://localhost:8080
```

## 演示

| 演示 | 说明 |
|------|------|
| **Fall** — 秋叶 | 风吹树叶飘过池塘，点击产生水波纹。 |
| **Grass** — 草地 | 实时风场模拟，昼夜循环与萤火虫效果。 |
| **Galaxy** — 星系 | 12,000 粒子椭圆轨道运动，形成螺旋旋臂。 |

## 操作

- **鼠标 / 触摸：** 横向移动切换视角
- **ESC** 或点击遮罩背景关闭演示窗口

## 源码

完整的 Android 项目 — 31 款壁纸，OpenGL ES 2.0 与 Vulkan 双后端 — 位于仓库 `main` 分支：

[github.com/dynnbw/RE-Android-Live-Wallpapers](https://github.com/dynnbw/RE-Android-Live-Wallpapers)
