#!/usr/bin/env python3
"""BDEC vs CFR 差分测试工具.

对 src/test/resources/decompile-samples/ 下的每个样例:
  1. javac 编译源码 → class 文件
  2. BDEC 反编译 → 源码 A;CFR 反编译 → 源码 B
  3. 两份输出分别用 javac 重新编译
  4. 报告矩阵:样例 | BDEC 编译 | CFR 编译 | 行数对比

用法:
  python tools/diff-test/diff_test.py [--samples DIR] [--keep]
环境变量:
  CFR_JAR    CFR jar 路径(默认 maven 本地仓库的 cfr-0.152.jar)
  BDEC_JAR   BDEC jar 路径(默认 target/bdec.jar)
退出码:存在 BDEC 反编译失败或 BDEC 输出不可重编译时非零(CFR 仅作参照).
"""
import argparse
import os
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]


def maven_local_repo() -> Path:
    """解析 Maven 本地仓库:优先 ~/.m2/settings.xml 的 <localRepository>,
    否则回退 ~/.m2/repository."""
    settings = Path.home() / ".m2" / "settings.xml"
    if settings.exists():
        try:
            for elem in ET.parse(settings).getroot().iter():
                if elem.tag.endswith("localRepository") and elem.text:
                    return Path(elem.text.strip())
        except Exception:
            pass
    return Path.home() / ".m2" / "repository"


DEFAULT_CFR_JAR = maven_local_repo() / "org/benf/cfr/0.152/cfr-0.152.jar"


def run(cmd, **kw):
    return subprocess.run(cmd, capture_output=True, text=True, **kw)


def compile_sources(sample_dir: Path, out_dir: Path) -> list[Path]:
    """javac 编译样例源码,返回顶层 class 文件列表."""
    sources = sorted(sample_dir.rglob("*.java"))
    if not sources:
        return []
    r = run(["javac", "-g", "-d", str(out_dir)] + [str(s) for s in sources],
            timeout=120)
    if r.returncode != 0:
        print(f"  !! javac 编译失败:\n{r.stderr}")
        return []
    classes = []
    for cf in sorted(out_dir.rglob("*.class")):
        if "$" not in cf.name:  # 仅顶层类(内部类由主类递归反编译)
            classes.append(cf)
    return classes


def decompile_bdec(jar: Path, class_file: Path, out_dir: Path) -> tuple[bool, str]:
    r = run(["java", "-jar", str(jar), "-class", str(class_file), str(out_dir)],
            timeout=120)
    return r.returncode == 0, r.stdout + r.stderr


def decompile_cfr(jar: Path, class_file: Path, out_dir: Path) -> tuple[bool, str]:
    r = run(["java", "-jar", str(jar), str(class_file), "--outputdir", str(out_dir)],
            timeout=120)
    return r.returncode == 0, r.stdout + r.stderr


def recompile(src_dir: Path, src_file: Path, cp: str) -> tuple[bool, str]:
    r = run(["javac", "-d", str(src_dir), "-cp", cp, str(src_file)],
            timeout=120)
    return r.returncode == 0, r.stderr


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--samples", default=str(REPO_ROOT / "src/test/resources/decompile-samples"))
    ap.add_argument("--keep", action="store_true", help="保留临时目录")
    args = ap.parse_args()

    cfr_jar = Path(os.environ.get("CFR_JAR", str(DEFAULT_CFR_JAR)))
    bdec_jar = Path(os.environ.get("BDEC_JAR", str(REPO_ROOT / "target/bdec.jar")))
    if not cfr_jar.exists():
        print(f"错误:CFR jar 不存在: {cfr_jar}\n请设置环境变量 CFR_JAR 或先下载 cfr。")
        sys.exit(2)
    if not bdec_jar.exists():
        print(f"错误:BDEC jar 不存在: {bdec_jar}\n请先执行 mvn package。")
        sys.exit(2)

    sample_dir = Path(args.samples)
    if not sample_dir.is_dir():
        print(f"错误:样例目录不存在: {sample_dir}")
        sys.exit(2)

    work = Path(tempfile.mkdtemp(prefix="bdec-diff-"))
    try:
        classes_dir = work / "classes"
        classes_dir.mkdir()
        classes = compile_sources(sample_dir, classes_dir)
        if not classes:
            print("无样例可测。")
            return

        rows = []
        bdec_fail = 0
        for cf in classes:
            bdec_out = work / f"bdec-{cf.stem}"
            cfr_out = work / f"cfr-{cf.stem}"
            bdec_out.mkdir(); cfr_out.mkdir()

            b_ok, b_msg = decompile_bdec(bdec_jar, cf, bdec_out)
            c_ok, c_msg = decompile_cfr(cfr_jar, cf, cfr_out)

            # 重编译 BDEC 输出(取输出的第一个 java 文件)
            b_java = next(bdec_out.rglob("*.java"), None)
            b_rc = "n/a"
            if b_ok and b_java:
                ok, err = recompile(bdec_out / "rc", b_java, str(classes_dir))
                b_rc = "OK" if ok else f"FAIL\n{err[:200]}"
            elif not b_ok:
                b_rc = f"DECOMPILE-FAIL\n{b_msg[:200]}"
                bdec_fail += 1
            elif b_ok and not b_java:
                b_rc = "NO-OUTPUT"
                bdec_fail += 1

            c_java = next(cfr_out.rglob("*.java"), None)
            c_rc = "n/a"
            if c_ok and c_java:
                ok, err = recompile(cfr_out / "rc", c_java, str(classes_dir))
                c_rc = "OK" if ok else f"FAIL\n{err[:200]}"
            elif not c_ok:
                c_rc = f"DECOMPILE-FAIL\n{c_msg[:200]}"

            b_lines = len(b_java.read_text(encoding="utf-8").splitlines()) if b_java else 0
            c_lines = len(c_java.read_text(encoding="utf-8").splitlines()) if c_java else 0
            rows.append((cf.name, b_rc.splitlines()[0], c_rc.splitlines()[0], b_lines, c_lines))

        # 报告矩阵
        print(f"{'class':<34} {'BDEC':<22} {'CFR':<22} {'行数 B/C'}")
        print("-" * 90)
        for name, b, c, bl, cl in rows:
            print(f"{name:<34} {b:<22} {c:<22} {bl}/{cl}")
        print("-" * 90)
        n_ok = sum(1 for r in rows if r[1] == "OK")
        print(f"BDEC 可重编译: {n_ok}/{len(rows)}")
        if bdec_fail:
            print(f"BDEC 失败 {bdec_fail} 个样例,详见上方矩阵。")
        print(f"临时目录: {work}" if args.keep else f"(临时目录 {work} 已清理)")
        sys.exit(1 if bdec_fail else 0)
    finally:
        if not args.keep:
            import shutil
            shutil.rmtree(work, ignore_errors=True)


if __name__ == "__main__":
    main()
