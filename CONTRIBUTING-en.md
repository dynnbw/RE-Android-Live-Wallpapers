# Contributing

Thanks for wanting to contribute to **Reborn Android Live Wallpapers**.
This guide is for **outside contributors** and covers everything from setting up the
environment to opening a pull request.

>  **Language**: [简体中文](CONTRIBUTING.md)
>
> The Chinese version is the authoritative one. If the two ever disagree, follow
> [CONTRIBUTING.md](CONTRIBUTING.md) and open an issue so this translation can catch up.

Architecture details live in [ARCHITECTURE-en.md](ARCHITECTURE-en.md); this guide does not
repeat them.

---

## Contents

- [Environment and Build](#environment-and-build)
- [Project Navigation](#project-navigation)
- [Wallpaper Inclusion Criteria](#wallpaper-inclusion-criteria)
- [Reporting Bugs](#reporting-bugs)
- [Adding a Wallpaper](#adding-a-wallpaper)
- [Code Conventions](#code-conventions)
- [Settings and i18n](#settings-and-i18n)
- [Git and Commit Conventions](#git-and-commit-conventions)
- [Verification Checklist](#verification-checklist)
- [Submitting a Pull Request](#submitting-a-pull-request)
- [After the PR](#after-the-pr)

---

## Environment and Build

Toolchain versions and build commands are in
[ARCHITECTURE-en.md](ARCHITECTURE-en.md#build-configuration) and are not repeated here.
**Changing a GLES wallpaper does not require the NDK.**

The 16 `.so` files under `app/src/main/jniLibs/` (4 Vulkan wallpapers × 4 ABIs) **are
committed on purpose**, not left there by mistake — that way a contributor without the NDK
can still build.

Note that ndk-build writes into that same directory (see `NDK_LIBS_OUT=../jniLibs` in
[app/build.gradle](app/build.gradle)), so **recompiling native code rewrites files that are
tracked by git**.

> ⚠️ One exception to remember: `./gradlew clean` **deletes the whole `jniLibs/`** (the
> `clean` task deletes it explicitly), and `git status` then shows those 16 files as
> deleted. That is expected; the next build regenerates them — **do not commit that
> deletion.**

Install to a device: `adb install -r app/build/outputs/apk/debug/app-debug.apk`

---

## Project Navigation

```
app/src/main/
├── java/com/reandroid/
│   ├── gles/            GLES framework base classes (GLESScene)
│   ├── plugin/          Plugin architecture core (WallpaperPlugin / BasePluginEngine)
│   ├── vulkan/          Vulkan utility classes
│   ├── settings/        Settings UI (dynamic preference rendering)
│   ├── weather/         Weather data layer
│   └── wallpaper/       All wallpapers, one subpackage each
├── assets/{wallpaper}/  Per-wallpaper assets (35 of them)
└── res/values*/         Wallpaper name strings (13 languages)
```

**Plugin model**: at startup the app enumerates `assets/*/info.json` and registers what it
finds. From a **wallpaper author's** point of view there are three layers (for the full set
of framework interfaces and the dispatch logic, see
[ARCHITECTURE-en.md](ARCHITECTURE-en.md#core-architecture)):

1. **Plugin** — `WallpaperPlugin`: metadata (`getId` / `getDisplayName`), entry point
   (`createEngine`)
2. **Engine** — `BasePluginEngine`: wallpaper service lifecycle; its one abstract method is
   `createScene(width, height, context)`
3. **Scene** — `GLESScene`: the rendering core (`onCreate` / `drawFrame` / `release`), with
   `setPluginPrefs` receiving settings and `setOffset` receiving the scroll offset

All interface signatures are in
[app/src/main/java/com/reandroid/plugin/](app/src/main/java/com/reandroid/plugin/).
**No manifest changes are needed** — plugins are discovered automatically.

---

## Wallpaper Inclusion Criteria

The project only accepts **new wallpaper** proposals that meet the following criteria.
Submissions that do not are rejected outright, without discussion:

1. **Verifiable origin** — the wallpaper must have a clear, checkable source (original APK /
   source code / where it came from, down to the phone model and OS version). Vague or
   unidentifiable material is not accepted. **This project only ports AOSP, OEM and ROM
   wallpapers from the Android 2–5 era** (the ones that need migrating off the obsolete
   OpenGL ES 1.0 / Canvas / RenderScript pipelines); newer wallpapers are out of scope.
2. **Complete, runnable material** — the submitted material must be complete, including
   bytecode (odex / dex; if odex, provide the system's `/system/framework/`). A bare shell
   APK with no bytecode and no way to run is not a valid contribution.
3. **Porting means rewriting** — this project's approach to old wallpapers is to **rewrite
   the code from the original visual behaviour**, migrating the obsolete OpenGL ES 1.0 /
   Canvas / RenderScript pipeline to Vulkan / OpenGL ES 2.0. Heavily obfuscated build
   output and outdated `.so` libraries cannot be used directly; the reverse-engineering cost
   is disproportionate and such submissions are not accepted.
4. **Respect the project's plans** — the maintainer plans the project around their own
   interests and pace. Batch lists of wallpaper suggestions are not accepted; what gets
   ported and when is the maintainer's call, and outside proposals cannot set the agenda or
   push for progress.
5. **Boundaries of discussion** — for anyone who repeatedly submits material that does not
   meet the criteria, only specific [bug reports](#reporting-bugs) about **existing
   wallpapers** will be accepted afterwards; further resource proposals will not be
   discussed.

> In short: only wallpapers with a clear origin and complete material are accepted. Vet your
> own material before contributing rather than leaving the identification work to the
> maintainer.

---

## Reporting Bugs

Bug reports about **existing wallpapers** are a supported form of contribution. A report
should include:

1. **Device information** — phone model, Android version, system language.
2. **Wallpaper** — which wallpaper, and which setting.
3. **Reproduction steps and symptoms** — what you did and what went wrong (crash / graphical
   corruption / stutter / a setting having no effect …).
4. **Debug log (required)** — from the app's main screen, **long-press the menu button in
   the top-right corner** → choose "Export debug log" from the menu and share the exported
   file. It helps to pick "Clear debug log" first → reproduce → then export, so the log
   covers only the problem at hand.

Reports without a log are hard to act on and may not be handled.

---

## Adding a Wallpaper

Take adding a wallpaper `{id}` (for example `silk`). There are four layers.

### 1. Assets `app/src/main/assets/{id}/`

```
assets/{id}/
├── info.json          Plugin metadata (required)
├── layout.json        Dynamic preference UI definition
├── language/          Settings translations, 13 files (bn de default es fr hi ja ko pt-rBR ru zh-rCN zh-rHK zh-rTW)
├── drawable/          Texture images
├── shaders/GLES/      GLSL ES 2.0 shaders (vertex + fragment)
├── icon.png           Wallpaper icon (square, used in the preview grid)
└── data/              Optional: CSV mesh / vertex data
```

> Both extensions work for the icon: the loader tries `icon.png`, then `icon.jpg`, and only
> falls back to a placeholder if neither exists. Most existing wallpapers use `icon.jpg`.

The **field table and usage** for `info.json` are in
[ARCHITECTURE-en.md](ARCHITECTURE-en.md#infojson-schema).

**`hidden` and packaging**: for a wallpaper marked `"hidden": true`, `assembleRelease`
leaves the whole asset directory out of the APK (derived automatically from each `info.json`
by [app/build.gradle](app/build.gradle) — the build script needs no changes);
`assembleDebug` keeps it so you can keep working on it. The rule is "only a purely debug
build keeps it", which fails in the safe direction. If you ask for both debug and release at
once (e.g. `./gradlew assemble`) it is treated as a release build and says so.

### 2. Java `app/src/main/java/com/reandroid/wallpaper/{id}/`

Four classes with separated responsibilities:

| Class | Extends / implements | Responsibility |
| --- | --- | --- |
| `XxxPlugin` | `WallpaperPlugin` | `getId()` returns `{id}`, `getDisplayName` returns the English name |
| `XxxEngine` | `BasePluginEngine` | Implements only `createScene` → `new XxxGL(w, h, context)` |
| `XxxGL` | `GLESScene` | Rendering: shaders, VBOs, textures, `drawFrame` |
| `XxxScene` | Plain logic (no GL) | Animation math, state, parameter tables, reading prefs |

Minimal references: [cube/](app/src/main/java/com/reandroid/wallpaper/cube/) or
[musicvis/vis2/](app/src/main/java/com/reandroid/wallpaper/musicvis/vis2/).

### 3. Names

Add one `<string name="wallpaper_{id}">` to each of the 13 `res/values-*/strings.xml`:

```xml
<string name="wallpaper_silk">丝语流年</string>
```

> **AAPT2 trap**: a string containing a single quote must escape it as `\'`, otherwise the
> build fails with a distinctly unhelpful error message.

### 4. Registration

**No registration code is needed** — the presence of `assets/{id}/info.json` is what gets it
enumerated. After `gradlew assembleDebug` the new wallpaper should appear in the settings
list.

---

## Code Conventions

### Scene/GL separation (mandatory)

- **Scene**: pure logic — animation math, parameter tables, state and prefs all live here, so
  it can be unit-tested and reused by the preview. **The test is "can it compile and run on
  the JVM"**, not "does it import android".
- **GL**: rendering only. `onCreate` does non-GL initialisation only (the GL context may not
  be ready, and it is called twice); shaders/programs/VBOs/textures are created lazily on
  the **GL thread, on the first `drawFrame`**.
- Reference pattern: `silk/SilkScene + SilkGL`, `musicvis/WaveScene + MusicVisWaveGL`.
- **When not to split**: the question is "is the logic free of the platform", not "is there
  a class for it". If the non-GL part is itself a pipe for a platform API (the Camera calls
  in `walkaround`, say), moving it into a Scene still leaves it unrunnable on the JVM and
  the split buys nothing — do not invent four classes just to have four files.

**About `android.opengl.Matrix`**: it has genuinely pure matrix maths (`orthoM` /
`multiplyMM` / `rotateM`) and touches no GL state, and **ten existing Scene classes use it
for their projection matrices**. Using it does not break the "no GL" rule, but **it makes
that Scene unrunnable on the JVM** (it is an Android class, so tests have to fake it) —
which gives up exactly the testability the rule exists to buy.

So: prefer to leave matrices in the GL layer in a new Scene, and if you do use it, know what
you are giving up. If you want JVM tests for a Scene, keeping matrix construction on the GL
side makes things much easier (the tests under `tools/` only run because they pick Scenes
that do not depend on Android classes).

### Performance discipline

- **Zero allocation per frame**: pre-allocate and reuse vertex/colour buffers, update with
  `glBufferSubData`, do not `new` arrays.
  Note that `arr.clone()` and implicit initialisation `float[] v = {…}` allocate just as
  often — a search for `new` misses both.
- Advance animation by the real `dt` (frame interval), clamped to an upper bound (0.1s, say)
  so a stutter does not make it jump; when there is a user speed multiplier, apply it in the
  Scene.
  **The frame rate is a user-settable global** (`global_frame_rate`, default 60), which makes
  "add a fixed amount every frame" a trap: a user moving it from 60 to 30 halves the
  animation speed, and 120 doubles it. To keep the original look while becoming
  frame-rate independent, multiply by `dt * 60` (the factor is 1 at 60fps, so it matches the
  old behaviour frame for frame).
- **Texture decoding** goes through `AssetLoader`, which sets `inPremultiplied = false` (to
  avoid black halos on transparent textures).
  **Do not see `glBlendFunc(GL_ONE, ...)` and assume "the premultiplied decode is missing"**:
  this project's shaders output **straight alpha** (they emulate GLES 1.x `GL_MODULATE` — RGB
  is not multiplied by alpha), so `GL_ONE` there is not premultiplied blending. **"Fixing"
  it by adding `inPremultiplied` will wash the picture out.**
- GLES 2.0 has no `#version` preprocessor control flow, and `pow(x, 2.0)` is **NaN** for a
  negative base (that is modern driver behaviour; older drivers optimised it to `x*x` and
  happened to be fine) — use `x*x` or guard the branch.

### Reading settings

**First decide which settings file each setting belongs in**: a wallpaper's own settings go
in `plugin_{id}`; app-wide settings (weather API, debug switches, frame rate, the MIUI
prompt flag …) go in the **app's default prefs**. Do not put them in the wrong one.

Settings are injected by the engine, and a Scene must **not** call `getSharedPreferences`
itself:

```java
public void setPluginPrefs(SharedPreferences prefs) { ... }
// inside: read keys -> fields, with defaults as a backstop; changes are re-injected by the host
```

Three things that are easy to get wrong:

1. **Injection targets the `previewClass` from `info.json`, not the Scene itself.** For most
   wallpapers that is the GL class, which forwards to the Scene; vis2/vis3 point
   `previewClass` straight at a Scene, so those two Scenes' `setPluginPrefs` has to be
   `public`. For a new wallpaper, writing it the GL-class-forwarding way is fine.

2. **Do not write a fallback like `mPluginPrefs != null ? mPluginPrefs : getSharedPreferences(...)`.**
   `createScene()` is immediately followed by `tryInjectPrefs()`, and `init()`/`start()` run
   later on the render thread — so injection **always** happens first and the fallback is
   unreachable. The old file name it reads usually has no writers left either. If it ever
   did run, it would **silently** return defaults and every one of the user's settings would
   be ignored without an error — worse than having no fallback at all.

3. **To read another wallpaper's settings** (only the compositing wallpapers need this: vis5
   draws vis2's and vis3's output together, the fireworks grass-night backdrop imitates
   grass), implement `setPluginPrefsProvider(PluginPrefsProvider)` and let the host inject
   it — **do not read `plugin_<id>` directly**.

> There is also a **shared static injection point** that is easy to miss:
> `WallpaperSettings.setSharedPreferences(...)`, set by `BaseVKPluginEngine`, which
> `WallpaperSettings.getXxx()` prefers over everything else. When working out whether a
> setting actually takes effect, do not look only at the direct `setPluginPrefs` call.

---

## Settings and i18n

### layout.json

Five preference control types are supported (`dependency` for conditional display). Each
entry's `title` is the **key name** in the language files; a list's labels resolve as
`{key}_label_{value}` (falling back to the `labels` array).

The **field table** for the controls is in
[ARCHITECTURE-en.md](ARCHITECTURE-en.md#layoutjson-schema). Two things that table does not
say:

- `color`'s `labels` are the slider labels, and **how many there are decides whether 2 or 3
  sliders are shown**
- `button` **stores no value**; it triggers behaviour through `action`

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

For `color` and `button` examples, see [grass/layout.json](app/src/main/assets/grass/layout.json)
and [fireworks/layout.json](app/src/main/assets/fireworks/layout.json).

### Language files

- 13 JSON files under `language/`, with `default.json` as the baseline that **must be
  maintained**. A missing key falls back to default, and only then shows the raw key (which
  looks bad).
- Merge chain: `default.json` → `{lang}-r{COUNTRY}` → `{lang}`.
- A new key has to be added to all 13 files. The same key may be worded differently per
  wallpaper (vis2's "simulated waveform" against vis3's "simulated spectrum", say).

---

## Git and Commit Conventions

- **Commit messages**: Conventional Commits prefixes (`feat` / `fix` / `refactor` / `docs` /
  `delete` …), written in English, following the style of recent history.
- Before committing, check that `git diff` contains only your change; do not commit build
  output or local scripts.
  **The one exception is `app/src/main/jniLibs/*.so`** — prebuilt native libraries that are
  deliberately tracked. See [Environment and Build](#environment-and-build) for why and for
  the caveats; do not clean them up as if they were build output.

---

## Verification Checklist

Confirm each of these once your change is in place:

- [ ] `./gradlew assembleDebug` passes with no new warnings
- [ ] If you touched Scene logic, run the matching JVM tests. Each test under
      `tools/<name>-test/` has its `javac` / `java` command in its header comment (they only
      run because the Scene avoids Android classes — see
      [Code Conventions](#code-conventions)). If there is no matching test, adding one is
      the preferred outcome: that is what the Scene/GL split is for
- [ ] The new wallpaper appears in the settings list (icon + localised name)
- [ ] The preview at the top of the settings page renders, and changing a setting takes
      effect **immediately**, without a restart
- [ ] It runs correctly once applied; switching wallpapers/settings repeatedly produces no
      GL errors (watch logcat for EGL/GL errors)
- [ ] For audio wallpapers (vis2/3/5): verify all three states — no permission, muted, and
      playing
- [ ] Switch the system language to de / ja / zh-rCN etc. and check the settings text is
      complete with no raw keys
- [ ] Regression: other wallpapers are unaffected

---

## Submitting a Pull Request

1. **Fork + branch**: fork the repository and branch from `main` (e.g.
   `feat/add-xxx-wallpaper`).
2. **Verify locally**: work through the [verification checklist](#verification-checklist),
   and at minimum make sure `./gradlew assembleDebug` passes.
3. **Commit**: follow [Git and Commit Conventions](#git-and-commit-conventions) — one commit
   per logical change, and do not mix unrelated changes into the same PR.
4. **Push and open the PR**:
   - **Title**: matching the commit message — a `feat:` / `fix:` / `docs:` prefix plus a
     short English description.
   - **Description**: say what changed, why, and how you verified it. Screenshots from a
     device are welcome. Reference the issue if you are fixing one (`Fixes #123`).
     **A new-wallpaper PR must meet the
     [wallpaper inclusion criteria](#wallpaper-inclusion-criteria), and must state where the
     material came from and how it was verified.**
5. **Review**: the maintainer reviews item by item. Add commits or amend **on the same
   branch** to keep the discussion in one piece; rebase when the branch falls behind.
6. **Merge**: the maintainer merges once review passes. For changes involving translations,
   it helps to note in the PR description where each language's wording came from.

---

## After the PR

- When adding a wallpaper or a feature, update the wallpaper list table and count in
  [README-en.md](README-en.md) as part of the PR.
- Cutting a release, bumping the version and updating the changelog are the maintainer's
  job; contributors do not need to handle them.

---

If anything is unclear, ask in an issue or on the PR. Happy building!
