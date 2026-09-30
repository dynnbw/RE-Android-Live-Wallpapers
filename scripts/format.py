#!/usr/bin/env python3
"""只格式化**改动过的** Java 文件 —— 不做全仓库重排。

全仓库 382 个文件、7.3 万行，一次全格式化会得到一个无法审阅的提交，而且 `git blame`
从此失效。所以走"棘轮"：谁被改到，谁就被格式化；没碰过的文件原样不动，格式检查也不会
因为历史遗留而报红。

    python scripts/format.py            # 检查（CI 用这个；有文件需要格式化就退出码 1）
    python scripts/format.py --apply    # 就地格式化
    python scripts/format.py --all      # 全部 Java 文件（等哪天想整体收口时用）
    python scripts/format.py --base <ref>   # 换比较基准，默认 origin/main

格式化器是 **palantir-java-format**（4 空格，与仓库现有风格一致；google-java-format 是
2 空格，会把每个文件都重排）。它是瘦 jar，需要几个运行期依赖，所以第一次运行会从 Maven
Central 下到 `build/format-jars/`（该目录已被 .gitignore 挡住）。
"""

import argparse
import glob
import os
import subprocess
import sys

# 版本与依赖清单固定在下面。依赖是从 palantir-java-format 的 POM 里挑出来的
# compile + runtime 那几条（POM 里那一大串 spotless/gradle/eclipse 是它**Gradle 插件**
# 的依赖，CLI 用不到）。换版本号时，`{v}` 那两行会跟着走，其余几条要自己核对。
FORMATTER_VERSION = "2.99.0"
JARS = [
    # (本地文件名, Maven Central 上的路径；会补 .jar)
    ("palantir-java-format-{v}",
     "com/palantir/javaformat/palantir-java-format/{v}/palantir-java-format-{v}"),
    ("palantir-java-format-spi-{v}",
     "com/palantir/javaformat/palantir-java-format-spi/{v}/palantir-java-format-spi-{v}"),
    ("guava-33.7.1-jre", "com/google/guava/guava/33.7.1-jre/guava-33.7.1-jre"),
    ("failureaccess-1.0.3", "com/google/guava/failureaccess/1.0.3/failureaccess-1.0.3"),
    ("functionaljava-5.0", "org/functionaljava/functionaljava/5.0/functionaljava-5.0"),
    ("jackson-core-2.22.1", "com/fasterxml/jackson/core/jackson-core/2.22.1/jackson-core-2.22.1"),
    ("jackson-databind-2.22.1",
     "com/fasterxml/jackson/core/jackson-databind/2.22.1/jackson-databind-2.22.1"),
    ("jackson-annotations-2.22",
     "com/fasterxml/jackson/core/jackson-annotations/2.22/jackson-annotations-2.22"),
    ("jackson-datatype-jdk8-2.22.1",
     "com/fasterxml/jackson/datatype/jackson-datatype-jdk8/2.22.1/jackson-datatype-jdk8-2.22.1"),
    ("jackson-datatype-guava-2.22.1",
     "com/fasterxml/jackson/datatype/jackson-datatype-guava/2.22.1"
     "/jackson-datatype-guava-2.22.1"),
    ("jackson-module-parameter-names-2.22.1",
     "com/fasterxml/jackson/module/jackson-module-parameter-names/2.22.1"
     "/jackson-module-parameter-names-2.22.1"),
    ("jsr305-3.0.2", "com/google/code/findbugs/jsr305/3.0.2/jsr305-3.0.2"),
]
MAVEN = "https://repo1.maven.org/maven2"
CACHE = os.path.join("build", "format-jars")
MAIN_CLASS = "com.palantir.javaformat.java.Main"

# JDK 16 起模块系统默认封住 jdk.compiler 的内部包，而格式化器要靠它解析源码。
# 这几条是 google-java-format 一系的标准开法，JDK 21 上验过。
JVM_FLAGS = [
    "--add-exports", "jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
    "--add-exports", "jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED",
    "--add-exports", "jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED",
    "--add-exports", "jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED",
    "--add-exports", "jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED",
    "--add-exports", "jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED",
]


def java_files(paths):
    return sorted(p for p in paths if p.endswith(".java") and os.path.isfile(p))


def git(*args):
    result = subprocess.run(["git"] + list(args), capture_output=True, text=True)
    if result.returncode != 0:
        sys.exit("git %s 失败：%s" % (" ".join(args), result.stderr.strip()))
    return result.stdout.split()


def changed_files(base):
    """工作区相对 base 的所有改动 —— 含已提交与未提交，本地和 CI 同一条命令。"""
    return java_files(git("diff", "--name-only", "--diff-filter=ACMR", base))


def ensure_jars():
    os.makedirs(CACHE, exist_ok=True)
    local_names = []
    for template, remote in JARS:
        name = template.format(v=FORMATTER_VERSION)
        local_names.append(name)
        local = os.path.join(CACHE, name + ".jar")
        if os.path.exists(local) and os.path.getsize(local) > 0:
            continue
        print("下载 %s" % name)
        result = subprocess.run(["curl", "-sSL", "--fail", "--max-time", "300",
                                 "-o", local, "%s/%s.jar" % (MAVEN, remote.format(v=FORMATTER_VERSION))])
        if result.returncode != 0 or not os.path.exists(local):
            sys.exit("下载失败：%s（离线环境请先联网跑一次，或手动放进 %s）"
                     % (remote, CACHE))
    return [os.path.join(CACHE, name + ".jar") for name in local_names]


def run_formatter(paths, jars, apply_changes):
    """两种模式共用一条 java 命令：就地写回，或者只检查。"""
    args = ["java"] + JVM_FLAGS + ["-cp", os.pathsep.join(jars), MAIN_CLASS, "--aosp"]
    args += ["-i"] if apply_changes else ["--dry-run", "--set-exit-if-changed"]
    result = subprocess.run(args + list(paths), capture_output=True, text=True)
    output = (result.stdout + result.stderr).strip()
    if output:
        print(output)
    return result.returncode


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apply", action="store_true", help="就地格式化，而不是只检查")
    parser.add_argument("--all", action="store_true", help="全部 Java 文件，不只是改动过的")
    parser.add_argument("--base", default="origin/main", help="比较基准（默认 origin/main）")
    options = parser.parse_args()

    if options.all:
        targets = java_files(glob.glob("app/src/**/*.java", recursive=True))
        print("全部 %d 个 Java 文件" % len(targets))
    else:
        targets = changed_files(options.base)
        print("相对 %s 改动过的 Java 文件：%d 个" % (options.base, len(targets)))

    if not targets:
        print("没有需要处理的文件")
        return 0

    jars = ensure_jars()
    code = run_formatter(targets, jars, options.apply)

    if options.apply:
        print("格式化完成" if code == 0 else "格式化失败（退出码 %d）" % code)
        return code
    if code == 0:
        print("格式检查通过")
        return 0
    print()
    print("以上文件需要格式化。跑 `python scripts/format.py --apply` 再提交。")
    return 1


if __name__ == "__main__":
    sys.exit(main())
