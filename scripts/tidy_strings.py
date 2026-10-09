#!/usr/bin/env python3
"""把 13 个 strings.xml 整理成"一行行对得上"。

**为什么**：13 个语言文件的条目顺序、注释、行数各不相同（默认 291 行、ja 229 行…），
改一条字符串得在 13 个文件里各找一遍。整理之后**同一个键在哪个文件里都是同一行**，
可以直接并排改。

规则（都是约定的，不是猜的）：
  * 顺序按 `name=` 的字母序（排序键用的是键名，所以 13 个文件顺序天然一致）。
  * 每个键在**默认文件**里的前置注释会跟着它走；每个语言都镜像同一批注释行。
  * 缺翻译的键：留一行 `<!-- 未翻译: 键 -->` 占位，运行时照旧回退到英文（行为不变），
    行数也不会错开。
  * 版权头一律重新生成本项目那一份（13 个文件统一，见 OWN_HEADER）。
  * 值里的换行与缩进会被收成一行 —— Android 编译时本来就会折叠 XML 里的空白，
    所以字符串本身不变（已确认没有 `xml:space` 也没有引号包裹的值）。

用法：
    python scripts/tidy_strings.py            # 只报告，不写文件
    python scripts/tidy_strings.py --write    # 落盘
"""

import glob
import io
import os
import re
import sys

RES = "app/src/main/res"
DEFAULT = RES + "/values/strings.xml"

# 全仓库搜不到任何引用的键（Java 的 R.string. / getIdentifier、res 的 @string/、
# assets 里 layout.json 的裸名字都算引用）。**这 35 个是逐条查过的**，整理时删掉。
#
# 特别注意：`palette_*` 那 7 个**不在这里** —— 它们写在 polarclock/layout.json 里当裸名字，
# 会被 resolveStringRef 回退到 res，所以是活的；`wallpaper_nixietube_desc` 同理
# （nixietube/info.json 的描述）。这两个家族差一点被误删。
DEAD_KEYS = {
    "wallpaper_flsorescence", "wallpaper_grass_vk", "wallpaper_galaxy_vk", "wallpaper_fall_vk",
    "wallpaper_nebula",
    "pref_live_preview", "pref_disabled_prefix",
    "nexus_bg_pyramid", "nexus_bg_pyramid_1", "nexus_bg_pyramid_2", "nexus_bg_pyramid_3",
    "nexus_bg_pyramid_4",
    "phasebeam_theme_phasebeam", "phasebeam_theme_sunbeam",
    "grass_color_default", "grass_color_green", "grass_color_blue", "grass_color_red",
    "grass_color_pink", "grass_color_purple", "grass_color_yellow", "grass_color_black",
    "grass_color_white",
    "about_project_intro", "miui_permission_continue",
    "musicvis_bg_black", "musicvis_bg_dark_red", "musicvis_bg_dark_blue", "musicvis_bg_dark_green",
    "musicvis_bg_pink", "musicvis_bg_light_green", "musicvis_bg_light_purple",
    "musicvis_recolor_mode_static", "musicvis_recolor_mode_dynamic",
}

# 条目行：<string name="k">值</string>，值里可能有换行
ENTRY = re.compile(r'[ \t]*<string name="([^"]+)">(.*?)</string>', re.S)
# AOSP 版权头：从文件开头到它的注释块结束。
# **认的是 AOSP 独有的那行版权声明**，不能认 "Licensed under the Apache License" ——
# 我们自己的头里也有那句，认它就会把我们的头误判成 AOSP 的（第二次跑就翻车）。
HEADER_END = re.compile(r'^.*?Copyright \(C\) \d{4} The Android Open Source Project'
                        r'.*?-->\s*', re.S | re.M)


def read(path):
    return io.open(path, encoding="utf-8", newline="").read()


def parse(path):
    """返回 (版权头, [(键, 值, 它前面那几行注释)], 其它元素的名字)。

    整段文本按偏移解析，**不按行** —— 有 14 处 <string> 的值里带换行，按行解析会把它们
    整个漏掉（漏掉的后果是当成"缺翻译"，然后拿占位行覆盖掉真内容）。
    """
    text = read(path)
    header = ""
    m = HEADER_END.match(text)
    if m:
        header = m.group(0)
    body = text[len(header):]

    entries = []
    other = []
    cursor = 0
    for match in ENTRY.finditer(body):
        # 这一段里，条目之前的注释行都属于这个条目
        between = body[cursor:match.start()]
        comments = [l.strip() for l in between.split("\n") if l.strip().startswith("<!--")]
        entries.append((match.group(1), normalise(match.group(2)), comments))
        cursor = match.end()

    for tagname in re.findall(r'<([a-z-]+)[ >]', body):
        if tagname not in ("string", "resources"):
            other.append(tagname)
    return header, entries, other


def normalise(value):
    """把值里的换行与多余空白收成单个空格 —— 与 Android 编译期的折叠一致。"""
    return " ".join(value.split())


# 只有"键名本身说不清"的键才配注释 —— 原来那些是给分组用的，按字母排完就全错位了。
KEEP_COMMENTS = {
    "grid_feedback_title": [
        "<!-- 壁纸磁贴占位格（反馈入口）：壁纸数量为奇数时补在最后一格 -->",
    ],
}

# 语言无关的值：**只写在默认文件里**，各语言留一行"沿用默认"占位。
# 否则改一次链接要在 13 个文件里各改一遍，而且迟早漏掉几个。
DEFAULT_ONLY = {
    "about_qq_url",          # 一个网址
    "grid_feedback_url",     # 一个网址
    "openweather_api_key",   # 一个占位 token，本来就该照抄
}

# 本项目自己的版权头，13 个语言文件**统一**用它。
#
# 早先这里是两分的：我们自己译的 8 个语言挂这个头，AOSP 派生的 5 个
# （values / ko / zh-rCN / zh-rHK / zh-rTW）保留它们原来的头，`HEADER_END` 就是那个判据。
# 现在那 5 个文件里已不含 AOSP 译文（逐条与原版比对，逐字相同者为 0 条），所以统一。
# 项目级的署名仍在根目录 NOTICE.md（那里写明产品部分派生自 AOSP）。
OWN_HEADER = [
    "Copyright (C) 2026 The Reborn Android Live Wallpapers authors",
    "",
    "Licensed under the Apache License, Version 2.0 (the \"License\");",
    "you may not use this file except in compliance with the License.",
    "You may obtain a copy of the License at",
    "",
    "     http://www.apache.org/licenses/LICENSE-2.0",
    "",
    "Unless required by applicable law or agreed to in writing, software",
    "distributed under the License is distributed on an \"AS IS\" BASIS,",
    "WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.",
    "See the License for the specific language governing permissions and",
    "limitations under the License.",
]

# 版权头总行数（含首尾的 <!-- 与 -->）。13 个文件逐行对齐全靠它是个定值 ——
# 早先这个数是**从默认文件的 AOSP 头量出来的**，默认文件一改就没有头可量、会生成空注释。
HEADER_LINES = len(OWN_HEADER) + 2


def placeholder_header(line_count):
    """生成本项目版权头。行数固定（{@link HEADER_LINES}），13 个文件靠它逐行对齐。"""
    body = list(OWN_HEADER)
    while len(body) + 2 < line_count:          # 2 = 首尾那两行 <!-- 与 -->
        body.append("")
    body = body[:max(0, line_count - 2)]
    lines = ["<!--"]
    lines += ["    " + b if b else "" for b in body]
    lines.append("-->")
    return "\n".join(lines)


def build(locale, canonical, entries):
    """按 canonical 顺序拼出这个语言的新文件内容。"""
    have = {k: v for k, v, _ in entries}
    # 版权头一律重新生成本项目那一份 —— 不再保留文件里原有的头
    out = [placeholder_header(HEADER_LINES), ""]
    out.append('<resources>')
    for key, comment in canonical:
        for line in comment:
            out.append("    " + line)
        if locale != "default" and key in DEFAULT_ONLY:
            # 语言无关的值不复制到各语言：留一行占位，运行时回退到默认文件。
            out.append("    <!-- 沿用默认: %s -->" % key)
            continue
        value = have.get(key)
        if value is None:
            out.append("    <!-- 未翻译: %s -->" % key)
            continue
        out.append('    <string name="%s">%s</string>' % (key, value))
    out.append("</resources>")
    return "\n".join(out) + "\n"


def main():
    write = "--write" in sys.argv

    header, entries, other = parse(DEFAULT)
    print("版权头：本项目那一份，%d 行（原来是按默认文件的 AOSP 头量出来的）" % HEADER_LINES)
    # 注释**默认丢弃**：原来的注释是"分段标题"式的（`<!-- Polar clock: palette name -->`），
    # 按字母排序之后它们要么指向了空、要么挂到了不相干的键上 —— 留着只会误导。
    # 只有"键名本身说不清"的才在 KEEP_COMMENTS 里点名保留。
    canonical = [(k, KEEP_COMMENTS.get(k, []))
                 for k, _, _ in sorted(entries, key=lambda e: e[0])
                 if k not in DEAD_KEYS]
    print("保留的注释：%d 条" % sum(len(v) for v in KEEP_COMMENTS.values()))
    print("默认文件：%d 个键（丢掉 %d 个没人用的），%s版权头"
          % (len(canonical), len(entries) - len(canonical), "有" if header else "无"))
    if other:
        print("  （默认文件里还有非 string 元素：%s）" % set(other))

    for path in sorted(glob.glob(RES + "/values*/strings.xml")):
        locale = os.path.basename(os.path.dirname(path))
        loc_tag = "default" if locale == "values" else locale[len("values-"):]
        own_header, own, own_other = parse(path)
        have = {k for k, _, _ in own}

        if own_other:
            print("  %-14s 另有非 string 元素 %s（整理时会丢弃，需你确认）"
                  % (locale, set(own_other)))
        missing = 0 if locale == "values" else len(
            [k for k, _ in canonical if k not in have])
        print("  %-14s 有 %3d 键，缺 %2d%s"
              % (locale, len(have), missing,
                 "" if locale == "values" else "（缺的留注释占位，运行时回退英文）"))

        if not write:
            continue
        text = build(loc_tag, canonical, own)
        with io.open(path, "w", encoding="utf-8", newline="") as f:
            f.write(text)

    if not write:
        print("\n（空跑。加 --write 才会写文件）")


if __name__ == "__main__":
    main()
