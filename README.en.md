# Reborn Android Live Wallpapers — WebGL Previews

Browser-based WebGL previews of live wallpapers ported from AOSP RenderScript to OpenGL ES / Vulkan.

**Live site:** [dynnbw.github.io/RE-Android-Live-Wallpapers](https://dynnbw.github.io/RE-Android-Live-Wallpapers/)

## Running locally

Any static HTTP server works:

```bash
python3 -m http.server 8080
# then open http://localhost:8080
```

## Demos

| Demo | Description |
|------|-------------|
| **Fall** — Autumn Leaves | Wind-blown leaves drifting across a pond. Click to create ripples. |
| **Grass** — Dynamic Meadow | Real-time wind simulation with day/night cycles and fireflies. |
| **Galaxy** — Spiral Nebula | 12,000 particles in elliptical orbit forming spiral arms. |

## Controls

- **Mouse / touch:** move horizontally to shift the camera perspective
- **ESC** or click backdrop to close the iframe overlay

## Source

The full Android project — 31 wallpapers with OpenGL ES 2.0 and Vulkan backends — is on the `main` branch of this repository:

[github.com/dynnbw/RE-Android-Live-Wallpapers](https://github.com/dynnbw/RE-Android-Live-Wallpapers)
