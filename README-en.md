<p align="center">
  <img src="app/src/main/res/drawable/ic_launcher_wallpaper.png" width="120" alt="Reborn Android Live Wallpapers"/>
</p>

<h1 align="center">Reborn Android Live Wallpapers</h1>

<p align="center">
  <img alt="GitHub Repo stars" src="https://img.shields.io/github/stars/dynnbw/RE-Android-Live-Wallpapers?style=flat-square&color=green"/>
  <img alt="License" src="https://img.shields.io/badge/License-Apache_2.0-green"/>
  <img alt="Android" src="https://img.shields.io/badge/Android-7.0%2B-3DDC84?style=flat-square&logo=android&logoColor=white&color=green"/>
  <img alt="Wallpapers" src="https://img.shields.io/badge/Wallpapers-35-3DDC84?style=flat-square&color=green"/>
   <img alt="Wallpapers" src="https://img.shields.io/github/downloads/dynnbw/RE-Android-Live-Wallpapers/total?logo=github&logoColor=white&label=Release%20downloads&color=green"/>
</p>

<font color=#98FB98>An Android live wallpaper collection that ports classic AOSP / MediaTek wallpapers from RenderScript to OpenGL ES 2.0 and Vulkan, allowing them to run on modern Android versions.</font>

>  **Language**：[简体中文](README.md)

> **Quick Navigation**: [User Guide](#user-guide) · [Installation](#installation) · [Weather Setup](#weather-setup) · [Music Visualization](#music-visualization) · [Permissions](#permissions) · [Performance Reference](#performance-reference) · [FAQ](#faq) 　|　 [Developer Docs](ARCHITECTURE-en.md) · [Contributing](CONTRIBUTING-en.md)

---

<a id="user-guide"></a>
# User Guide

## Installation

**Download & Install**
1. Download the APK from [GitHub Releases](../../releases) or [ApkPure](https://apkpure.com/p/com.reandroid.wallpaper)
2. Allow "Install unknown apps" permission for your browser/file manager, then install
3. Supports Android 7.0 (API 24) and above

**Setting as Wallpaper**

Method 1 (recommended): Open the app → select a wallpaper → enter its settings page → tap "Open System Preview"

Method 2: Open the app → open wallpaper picker → find "REAndroid Live Wallpapers" (may not work on heavily customized Chinese ROMs)

Method 3: Long-press home screen → Wallpapers → find "REAndroid Live Wallpapers" (may not work on heavily customized Chinese ROMs)

> Method 1 skips the system wallpaper picker and goes directly to preview. MIUI users will see a permission guide popup on first setup.

**Each wallpaper has its own settings** — particle count, color, animation speed, etc. can all be adjusted. A real-time preview appears at the top of each settings page.

## Wallpaper List

**33** wallpapers total (2 more are hidden and do not appear in the app's list), with **4** (Galaxy, Galaxy4, Grass, Fall) offering an additional Vulkan backend

| Wallpaper | Type | VK | Description |
| --- | --- | --- | --- |
| Galaxy | Starfield | ✓ | Rotating star field, color gradients |
| Galaxy4 | Starfield | ✓ | Rotating star field, color gradients |
| NightSky | Starfield | | 9,000 real stars from the Hipparcos catalog, gyroscope tracking, long-press to accelerate star trails |
| Microbes | Particles | | Microbial swarm AI, touch to feed, reproduction/death cycle |
| Grass | Nature | ✓ | Wind-blown grass, solar/lunar eclipses, weather integration |
| WildWorld | Nature | | Prehistoric world with volcanoes, dinosaurs, pterosaurs, fireballs |
| WalkAround | Nature | | Camera perspective — what you see is what you get |
| Cube | 3D | | 8 wireframe shapes, 3D rotation, touch drag |
| Forest | Nature | | Forest with parallax scrolling |
| DeepSea | Nature | | Deep-sea jellyfish swarm, gyroscope tracking |
| BlueSea | Nature | | Floating jellyfish, rising particles, tap to light up |
| Fall | Nature | ✓ | Falling autumn leaves, water ripples |
| Ocean | Weather | | Ocean weather wallpaper — waves/clouds/precipitation linked to real weather |
| Windmill | Weather | | Windmill weather wallpaper — wind speed and sky change with weather |
| Nexus | Effects | | Pulsed halo |
| PhaseBeam | Effects | | Phase beam, HSL color tuning |
| NoiseField | Effects | | Perlin noise particles, touch perturbation |
| HoloSpiral | Effects | | Holographic spiral, 3D perspective rotation |
| MagicSmoke | Effects | | Multi-layer smoke blending, psychedelic effect |
| Aurora1 | Aurora | | Northern lights, 99-frame glow animation |
| Aurora2 | Aurora | | Northern lights v2, more brilliant colors |
| Fireworks | Effects | | Firework particles, tap to launch |
| LuminousDots | Effects | | Luminous particle field |
| CosmicFlow | Effects | | Grey ripple background with a GPU noise flow field, 8 colour themes |
| Silk | Effects | | Flowing silk ribbons, 3 colour themes |
| Droid | Effects | | Android robots in parts, driven by gravity, acceleration and touch (listed in the app as "Shake Them All") |
| PolarClock | Clock | | Polar clock, three palette styles |
| NixieTube | Clock | | Nixie tube clock, audio-reactive |
| vis2 | Music | | Audio spectrum visualization — FFT waveform |
| vis3 | Music | | Audio spectrum visualization — PCM waveform |
| vis4 | Music | | Audio visualization — VU meter style |
| vis5 | Music | | Audio visualization — Waveform + VU combo |
| vis6 | Music | | Audio visualization — Circular spectrum |

## Feature Highlights

Compared to the original AOSP/MediaTek wallpapers:
- **Modern OS Compatibility**: Originals relied on RenderScript (deprecated in Android 12+). Everything is rewritten in GLES/Vulkan.
- **Independent Settings**: Each wallpaper has its own settings page — adjust particle count, color, animation speed, etc.
- **Weather Integration**: Grass, Ocean, and Windmill change visuals based on real weather (requires API key).
- **Music Visualization**: 5 audio spectrum styles — wallpapers react to music in real time.
- **Vulkan Backend**: Galaxy, Galaxy4, Grass, and Fall support optional Vulkan rendering.

<a id="weather-setup"></a>
## Weather Setup

Three wallpapers — Grass (dynamic meadow), Ocean (ocean weather), Windmill — can change their visuals based on real weather: sunny skies, thicker clouds on overcast days, rain/snow particle effects.

**Setup Steps:**

1. Open the [OpenWeatherMap sign-up page](https://home.openweathermap.org/users/sign_up) and register a free account
2. After login, go to [API Keys](https://home.openweathermap.org/api_keys) and copy the default key
3. Open the app → tap the weather icon in the toolbar → "OpenWeather API Key" → paste your key → confirm
4. Return to the main screen — the weather icon should display current weather conditions

> Free tier: 1,000 calls/day. Default update interval is 30 minutes (can be changed to 15/30/60/180 min in "Update Interval"). If you don't register, weather wallpapers still function normally, but won't reflect real weather.

**Debugging**: Long-press the weather icon to manually override weather conditions (clear/cloudy/rain/snow etc. — 10 presets), useful for testing how wallpapers look under different weather.

<a id="music-visualization"></a>
## Music Visualization

vis2–vis6 are 5 audio spectrum visualization wallpapers. When music plays, the wallpaper reacts to the audio rhythm.

**How to Use:**
1. Set any of vis2–vis6 as your wallpaper
2. Grant the "Record Audio" permission (only used to read audio spectrum; no audio data is saved)
3. Play music or video on your phone
4. The wallpaper will automatically respond to the audio

| Plugin | Style | Mode | Characteristics |
| --- | --- | --- | --- |
| vis2 | Color Waveform | FFT Spectrum | Multi-band spectrum bars, HSL gradient |
| vis3 | Color Waveform | PCM Waveform | Continuous waveform curve, smoother |
| vis4 | VU Meter | PCM | Classic audio level meter, swinging needle |
| vis5 | Combo View | FFT | 3D rotation |
| vis6 | Circular Spectrum | FFT | 360° surrounding spectrum |

> Android system limits audio capture sample rate. AndroidX+ requires additional authorization to capture system audio.

<a id="permissions"></a>
## Permissions

Permissions requested by the app and their reasons:

| Permission | Used By | Reason | Optional |
| --- | --- | --- | --- |
| Storage Read | Fireworks (custom background) | Select a local image as fireworks background | ✓ |
| Location (fine/coarse) | Grass, NightSky | Calculate precise sunrise/sunset times based on location, etc. | ✓ |
| Camera | WalkAround | Use camera feed as wallpaper background (perspective effect) | ✓ |
| Record Audio | vis2–vis6 | Capture system audio output for spectrum visualization | ✓ |
| Internet | Weather, Update Check | Fetch weather data, check for updates | ✓ |
| Live Wallpaper Service | System | Required Android permission for live wallpapers | ✕ |

> ✓ = optional, denying won't break basic wallpaper functionality　✕ = required on some systems

<a id="performance-reference"></a>
## Performance Reference

Hardware consumption varies significantly across wallpapers:

| Level | Wallpapers | Notes |
| --- | --- | --- |
| Low Power | PolarClock, HoloSpiral, Forest | Mostly static, minimal animation updates |
| Medium | Ocean, Windmill, Aurora1/2, Cube, DeepSea, BlueSea, Nexus, PhaseBeam, NoiseField, MagicSmoke, WalkAround, WildWorld | Continuous animation or particles, but manageable load |
| High Power | Galaxy, Galaxy4, NightSky, Microbes, Grass, Fall, Fireworks, vis2–6 | Large particle count / entity AI / real-time audio processing |

**Power-saving Tips:**
- Lower particle count or grass blade count via wallpaper settings
- Set global framerate to 30 FPS (Settings page overflow menu → Global Framerate)
- For Galaxy/Galaxy4/Grass/Fall, try enabling Vulkan rendering
- Weather integration can be turned off (Grass settings → disable "Enable Weather Effects")

<a id="faq"></a>
## FAQ

**Q: Can't open wallpaper preview**
MIUI/HyperOS users need to grant "Live Wallpaper Service" permission in system settings. The app automatically detects MIUI on first launch and shows a guide.

**Q: Weather not showing / showing incorrectly?**
Check: ① Is the OpenWeather API Key filled in correctly? ② Is location permission granted? ③ Is the network working? (Some regions/ISPs may not reach OpenWeather) ④ Has the free quota run out? (1,000 calls/day) Long-press the weather icon to see the last refresh time.

**Q: Why can't I see the Vulkan toggle?**
Only Galaxy, Galaxy4, Grass, and Fall have Vulkan backends. If your device doesn't support Vulkan, the toggle will be visible but switching may have no effect or cause crashes.

**Q: Music visualization isn't responding?**
Confirm: ① Record Audio permission is granted ② Audio is currently playing (media volume is not zero)

**Q: What's the ideal global framerate setting?**
Default 60 FPS suits most devices. Low-end devices: 24 FPS recommended. High-refresh-rate screens: 90/120 FPS. Note: higher framerate = more battery consumption.

---

<a id="developer-docs"></a>
# Developer Docs

Architecture, render paths, the Vulkan and MusicVis implementations, and the
authoritative version of each schema now live in **[ARCHITECTURE-en.md](ARCHITECTURE-en.md)**.

For how to contribute — code conventions, settings access, i18n, verification
checklist, commit rules — see [CONTRIBUTING-en.md](CONTRIBUTING-en.md).

## License

[LICENSE](LICENSE) · [NOTICE](NOTICE.md)