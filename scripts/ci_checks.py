#!/usr/bin/env python3
"""不需要编译的代码检查。

CI 上只跑这些：**不构建**（不装 Android SDK / NDK、不跑 Gradle），所以它几十秒就能出结果。
检查的都是"错了会静默发布出去、构建却照样成功"的东西 —— 这一整个开发过程里真正抓到过问题的
几类：

  1. 资源里的 JSON / XML 能不能解析
  2. `layout.json` 里的每个设置项，`language/default.json` 里有没有对应条目
  3. `info.json` 的 `label`（`@string/x`）在 `res/values*/strings.xml` 里有没有定义
  4. 着色器能不能编译、名字成对的 vs/fs 能不能链接
  5. 有没有人把签名文件提交进来

本地跑：`python scripts/ci_checks.py`（Windows 上会自己去 VulkanSDK 目录里找
glslangValidator，与 `GrassShaderCompileTest` 同一套找法；找不到就跳过着色器那一步）。
"""

import glob
import io
import json
import os
import re
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET

ASSETS = "app/src/main/assets"
RES = "app/src/main/res"

problems = []


def fail(kind, message):
    problems.append("[%s] %s" % (kind, message))


def find_validator():
    """与 GrassShaderCompileTest 同一套：先按名字找，再扫 SDK 目录（**不写死版本号**）。"""
    for name in ("glslangValidator", "glslangValidator.exe"):
        found = shutil.which(name)
        if found:
            return found
    env = os.environ.get("VULKAN_SDK")
    roots = [os.path.join(env, "Bin")] if env else []
    roots += ["F:/VulkanSDK", "C:/VulkanSDK"]
    for root in roots:
        if not os.path.isdir(root):
            continue
        versions = sorted(os.listdir(root), reverse=True)
        for version in versions:
            candidate = os.path.join(root, version, "Bin", "glslangValidator.exe")
            if os.path.isfile(candidate):
                return candidate
    return None


def check_json():
    count = 0
    for path in glob.glob(ASSETS + "/**/*.json", recursive=True):
        count += 1
        try:
            json.load(io.open(path, encoding="utf-8"))
        except Exception as error:
            fail("json", "%s: %s" % (path, error))
    print("JSON：%d 个文件" % count)


def check_xml():
    count = 0
    for path in glob.glob(RES + "/**/*.xml", recursive=True):
        count += 1
        try:
            ET.parse(path)
        except Exception as error:
            fail("xml", "%s: %s" % (path, error))
    print("XML：%d 个文件" % count)


def key_shaped(value):
    """像不像一个"键"。

    <p>{@code labels} 里**本来就允许写字面文字**（{@code "Cube"}、{@code "256"}、
    {@code "Black & White (Classic)"}），那些不是键、查不到也正常。只有长得像标识符的
    （小写、带下划线）才当键来查 —— 否则这一项会把全仓库的字面标签都报成缺翻译。
    """
    return bool(re.fullmatch(r"[a-z][a-z0-9_#]*", value)) and "_" in value


def check_language_keys():
    """每个设置项**显示出来的字**都要解析得到。

    <p>查的是 {@code title} / {@code summary} / {@code labels}，**不是 {@code key}** ——
    key 只是内部 id（{@code musicvis_wave_mode}），标题另有其名（{@code pref_vis5_wave_mode}）。
    解析顺序与 {@code DynamicPreferenceFactory} 对齐：

    <ul>
      <li>语言表里查一次，查不到再按 res 字符串名查一次，两边都算数；</li>
      <li>列表项（{@code type: list}）的标签**先查 {@code <key>_label_<value>}**，
          只有它不在时才回落到 {@code labels} 数组那一项。</li>
    </ul>

    两处都查不到时，界面上会直接把那个键名显示出来。
    """
    res_names = set()
    for path in glob.glob(RES + "/values*/strings.xml"):
        for element in ET.parse(path).getroot():
            if element.get("name"):
                res_names.add(element.get("name"))

    def resolves(value, have):
        if value.startswith("@string/"):
            return value[len("@string/"):] in res_names
        if not key_shaped(value):
            return True                       # 字面文字，不是键
        return value in have or value in res_names

    plugins = 0
    for layout in glob.glob(ASSETS + "/*/layout.json"):
        prefs = json.load(io.open(layout, encoding="utf-8")).get("prefs", [])
        if not prefs:
            continue
        plugins += 1
        default = os.path.join(os.path.dirname(layout), "language", "default.json")
        exists = os.path.exists(default)
        have = json.load(io.open(default, encoding="utf-8")) if exists else {}
        if not exists:
            fail("lang", "%s 有 %d 个设置项，却没有 language/default.json"
                 % (layout, len(prefs)))

        for pref in prefs:
            key = pref.get("key", "")
            for field in ("title", "summary"):
                value = pref.get(field)
                if value and not resolves(value, have):
                    fail("lang", "%s 的 %s=%s 解析不到（语言表和 res 里都没有）"
                         % (layout, field, value))

            labels = pref.get("labels") or []
            values = pref.get("values") or []
            for index, label in enumerate(labels):
                # 列表项：主路径是 <key>_label_<value>，命中就不看 labels 了
                if index < len(values) and "%s_label_%s" % (key, values[index]) in have:
                    continue
                if not resolves(label, have):
                    fail("lang", "%s 的 label=%s 解析不到（主路径 %s_label_%s 也没有）"
                         % (layout, label, key, values[index] if index < len(values) else "?"))
    print("设置项文案：%d 个插件的 title/summary/labels" % plugins)


def check_labels():
    """每个壁纸的名字要**解析得到** —— 显示不出来是构建不会报错的那类问题。

    解析顺序与 {@code PluginResources.resolveLabel} 一致：插件自己语言包的 {@code title}
    （名字的主要来源）→ {@code info.json} 里的字面 label → {@code @string/} 去 res 查。
    三者都没有时界面显示的是插件 id，这里就算不合格。
    """
    defined = set()
    for path in glob.glob(RES + "/values*/strings.xml"):
        for element in ET.parse(path).getroot():
            if element.get("name"):
                defined.add(element.get("name"))

    count = 0
    for info in glob.glob(ASSETS + "/*/info.json"):
        count += 1
        plugin_dir = os.path.dirname(info)
        label = json.load(io.open(info, encoding="utf-8")).get("label", "")

        if label and not label.startswith("@string/"):
            continue                                   # 字面文字，够用
        if label.startswith("@string/"):
            if label[len("@string/"):] in defined:
                continue                               # @string/ 能在 res 里查到
            fail("label", "%s 的 label=%s 在 res/values*/strings.xml 里没有定义"
                 % (info, label))
            continue

        default = os.path.join(plugin_dir, "language", "default.json")
        title = None
        if os.path.exists(default):
            title = json.load(io.open(default, encoding="utf-8")).get("title")
        if not title:
            fail("label", "%s 既没有 label，language/default.json 里也没有 title —— "
                 "界面上会显示插件 id" % info)
    print("壁纸名字：%d 个 info.json" % count)


def check_shaders():
    validator = find_validator()
    if not validator:
        print("着色器：没找到 glslangValidator，**这一步被跳过**（本地装 Vulkan SDK 或 "
              "glslang-tools 才会真跑）")
        return

    def run(args):
        return subprocess.run([validator] + args, capture_output=True, text=True)

    shaders = glob.glob(ASSETS + "/**/*.glsl", recursive=True)
    checked = skipped = 0
    for path in shaders:
        source = io.open(path, encoding="utf-8", errors="replace").read()
        if "$" in source:
            # 构建期模板：`$DROP_SIZE` 这类占位符由 Java 侧 replace 后再编译
            # （见 FallGL），原样喂给 glslang 必然报语法错
            skipped += 1
            continue
        # 命名不统一：grass/musicvis 用 _vs/_fs，holospiral 用 _vertex_/_fragment_。
        # 所以不猜阶段 —— 两个都试，能作为一个合法着色器编过就行。
        if not any(run(["-S", stage, path]).returncode == 0 for stage in ("vert", "frag")):
            result = run(["-S", "frag", path])
            fail("shader", "%s 既编不成顶点也编不成片元:\n%s"
                 % (path, result.stdout + result.stderr))
        checked += 1

    # 名字成对的 vs/fs 还要能链接 —— 单条编过、链接失败照样是黑屏
    linked = 0
    for path in shaders:
        if not path.endswith("_vs.glsl"):
            continue
        mate = path[:-len("_vs.glsl")] + "_fs.glsl"
        if not os.path.exists(mate):
            continue
        if any("$" in io.open(f, encoding="utf-8", errors="replace").read()
               for f in (path, mate)):
            continue
        # glslang 靠扩展名认阶段，我们的文件叫 .glsl，所以拷成 .vert/.frag 再链
        tmp_vs = path + ".tmp.vert"
        tmp_fs = mate + ".tmp.frag"
        try:
            shutil.copyfile(path, tmp_vs)
            shutil.copyfile(mate, tmp_fs)
            result = run(["-l", tmp_vs, tmp_fs])
            linked += 1
            if result.returncode != 0:
                fail("shader", "%s + %s 链接失败:\n%s"
                     % (path, mate, result.stdout + result.stderr))
        finally:
            for tmp in (tmp_vs, tmp_fs):
                if os.path.exists(tmp):
                    os.remove(tmp)
    print("着色器：%d 条编译、%d 对链接、%d 条构建期模板跳过"
          % (checked, linked, skipped))


def check_no_secrets():
    tracked = subprocess.run(["git", "ls-files"], capture_output=True,
                             text=True).stdout.split()
    for path in tracked:
        if path.endswith((".jks", ".keystore")):
            fail("secret", "签名文件被提交进来了：%s" % path)
    print("签名文件：%d 个被跟踪的文件" % len(tracked))


def main():
    check_json()
    check_xml()
    check_language_keys()
    check_labels()
    check_shaders()
    check_no_secrets()

    print()
    if problems:
        print("发现 %d 个问题：" % len(problems))
        for problem in problems:
            print("  - " + problem)
        return 1
    print("全部通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
