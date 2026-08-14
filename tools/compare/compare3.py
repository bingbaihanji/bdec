#!/usr/bin/env python3
"""BDEC vs CFR vs Vineflower 三方对比工具.

对每个样例:
  1. javac 编译源码 → class 文件
  2. 三个反编译器分别反编译主类
  3. BDEC 输出尝试重新编译
  4. 统计 BDEC 输出的质量信号:varN / Object / $ 合成名 / access$ / 空 lambda

用法:python tools/compare/compare3.py [--keep]
输出:对比矩阵 + 各反编译器输出落盘到临时目录(默认清理).
"""
import argparse
import re
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]


def maven_local_repo() -> Path:
    settings = Path.home() / ".m2" / "settings.xml"
    if settings.exists():
        try:
            for elem in ET.parse(settings).getroot().iter():
                if elem.tag.endswith("localRepository") and elem.text:
                    return Path(elem.text.strip())
        except Exception:
            pass
    return Path.home() / ".m2" / "repository"


BDEC_JAR = REPO_ROOT / "target/bdec.jar"
CFR_JAR = maven_local_repo() / "org/benf/cfr/0.152/cfr-0.152.jar"
VF_JAR = maven_local_repo() / "org/vineflower/vineflower/1.12.0/vineflower-1.12.0.jar"

# 覆盖已知差距领域的样例(Java 17+ 最终特性,无需 preview)
SAMPLES = {
    "LambdaBody": """
import java.util.function.Function;
class LambdaBody {
    static int run() {
        Function<Integer, Integer> f = x -> {
            int y = x * 2;
            return y + 1;
        };
        return f.apply(3);
    }
}
""",
    "MethodRefs": """
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.ArrayList;
class MethodRefs {
    static Object refs() {
        Function<String, Integer> f = String::length;
        Supplier<ArrayList<String>> c = ArrayList::new;
        return f.apply("hello");
    }
}
""",
    "AnonymousClass": """
class AnonymousClass {
    int field = 5;
    Runnable make() {
        int local = 10;
        return new Runnable() {
            public void run() { System.out.println(field + local); }
        };
    }
}
""",
    "InnerAccess": """
class InnerAccess {
    private int secret = 1;
    class Inner { int get() { return secret; } }
    int use() { return new Inner().get(); }
}
""",
    "PatternMatch": """
class PatternMatch {
    static String test(Object o) {
        if (o instanceof String s) { return s; }
        return switch (o) {
            case Integer i when i > 0 -> "pos";
            case Integer i -> "neg";
            case null -> "null";
            default -> "other";
        };
    }
    record Point(int x, int y) {}
    static int sum(Object o) {
        if (o instanceof Point(int x, int y)) { return x + y; }
        return 0;
    }
}
""",
    "SwitchExpr": """
class SwitchExpr {
    static int f(int n) {
        return switch (n) {
            case 1 -> 10;
            case 2, 3 -> 20;
            default -> { int r = n * 2; yield r; }
        };
    }
}
""",
    "TryResources": """
import java.io.BufferedReader;
import java.io.FileReader;
class TryResources {
    static String read() throws Exception {
        try (var r = new BufferedReader(new FileReader("x"))) {
            return r.readLine();
        }
    }
}
""",
    "ComplexFinally": """
class ComplexFinally {
    static int f(int n) {
        try { return n; }
        finally { System.out.println("cleanup"); }
    }
}
""",
    "SealedPermits": """
sealed interface Shape permits Circle, Square {}
record Circle(double r) implements Shape {}
record Square(double s) implements Shape {}
""",
    "GenericsPropagation": """
import java.util.ArrayList;
import java.util.List;
class GenericsPropagation {
    static List<String> make() {
        List<String> l = new ArrayList<>();
        l.add("a");
        return l;
    }
}
""",
    "ForEach": """
import java.util.List;
class ForEach {
    static int sum(List<Integer> l) {
        int s = 0;
        for (int x : l) { s += x; }
        return s;
    }
}
""",
    "BreakContinue": """
class BreakContinue {
    static int find(int[][] m, int t) {
        int r = -1;
        outer: for (int i = 0; i < m.length; i++) {
            for (int j = 0; j < m[i].length; j++) {
                if (m[i][j] == t) { r = i; break outer; }
            }
        }
        return r;
    }
}
""",
    "VarLocalType": """
import java.util.HashMap;
import java.util.List;
class VarLocalType {
    static void m() {
        var map = new HashMap<String, List<Integer>>();
        map.put("a", List.of(1, 2));
    }
}
""",
    "RecordCompact": """
record Range(int lo, int hi) {
    Range {
        if (lo > hi) { throw new IllegalArgumentException("lo>hi"); }
    }
}
""",
}


def run(cmd, **kw):
    return subprocess.run(cmd, capture_output=True, text=True, **kw)


def compile_sample(sample_dir: Path, out_dir: Path) -> bool:
    src = sample_dir / "S.java"
    src.write_text(SAMPLES[sample_dir.name], encoding="utf-8")
    r = run(["javac", "-g", "-d", str(out_dir), str(src)], timeout=120)
    return r.returncode == 0, r.stderr


def decompile_bdec(class_file: Path, out_dir: Path) -> bool:
    r = run(["java", "-jar", str(BDEC_JAR), "-class", str(class_file), str(out_dir)], timeout=120)
    return r.returncode == 0, r.stdout + r.stderr


def decompile_cfr(class_file: Path, out_dir: Path) -> bool:
    r = run(["java", "-jar", str(CFR_JAR), str(class_file), "--outputdir", str(out_dir)], timeout=120)
    return r.returncode == 0, r.stdout + r.stderr


def decompile_vf(class_file: Path, out_dir: Path) -> bool:
    r = run(["java", "-jar", str(VF_JAR), str(class_file), str(out_dir)], timeout=120)
    return r.returncode == 0, r.stdout + r.stderr


def recompile(java_files, classes_dir: Path, out_dir: Path) -> tuple[bool, str]:
    out_dir.mkdir(parents=True, exist_ok=True)
    r = run(["javac", "-d", str(out_dir), "-cp", str(classes_dir), "-proc:none",
             *[str(f) for f in java_files]], timeout=120)
    return r.returncode == 0, r.stderr


def quality(source: str) -> dict:
    return {
        "varN": len(re.findall(r"\bvar\d+\b", source)),
        "Object": len(re.findall(r"\bObject\b", source)),
        "dollar": len(re.findall(r"\w\$\w", source)),
        "access": len(re.findall(r"access\$", source)),
        "emptyLambda": len(re.findall(r"->\s*\{\s*\}", source))
        + len(re.findall(r"->\s*/\*", source)),
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--keep", action="store_true", help="保留临时目录")
    args = ap.parse_args()

    for j in (BDEC_JAR, CFR_JAR, VF_JAR):
        if not j.exists():
            print(f"错误:jar 不存在: {j}")
            sys.exit(2)

    work = Path(tempfile.mkdtemp(prefix="bdec-cmp3-"))
    print(f"workdir: {work}\n")
    header = f"{'sample':<22} {'recomp':<9} {'varN':<5} {'Obj':<5} {'$name':<6} {'access$':<8} {'emptyL':<7}"
    print(header)
    print("-" * len(header))

    rows = []
    for name in SAMPLES:
        sample_dir = work / "samples" / name
        sample_dir.mkdir(parents=True)
        classes_dir = work / "classes" / name
        classes_dir.mkdir(parents=True)
        ok, err = compile_sample(sample_dir, classes_dir)
        if not ok:
            print(f"{name:<22} compile-fail {err[:80]}")
            continue
        main_class = classes_dir / f"{name}.class"
        if not main_class.exists():
            print(f"{name:<22} no-main-class")
            continue

        b_out = work / "bdec" / name
        c_out = work / "cfr" / name
        v_out = work / "vf" / name
        for d in (b_out, c_out, v_out):
            d.mkdir(parents=True)

        decompile_bdec(main_class, b_out)
        decompile_cfr(main_class, c_out)
        decompile_vf(main_class, v_out)

        b_java = sorted(b_out.rglob("*.java"))
        src = ""
        if b_java:
            src = b_java[0].read_text(encoding="utf-8")
        rc = "n/a"
        if b_java:
            rc_ok, rc_err = recompile(b_java, classes_dir, work / "rc" / name)
            rc = "OK" if rc_ok else "FAIL"
            if not rc_ok:
                # 记录简短错误
                lines = [l for l in rc_err.splitlines() if "error" in l.lower()][:2]
                rc = "FAIL:" + ";".join(l.strip()[:40] for l in lines)

        q = quality(src)
        print(f"{name:<22} {rc:<9} {q['varN']:<5} {q['Object']:<5} "
              f"{q['dollar']:<5} {q['access']:<8} {q['emptyLambda']:<5}")
        rows.append((name, rc, q))

    if args.keep:
        print(f"\noutputs kept at: {work}")
    else:
        import shutil
        shutil.rmtree(work, ignore_errors=True)
        print("\n(temp dir cleaned)")


if __name__ == "__main__":
    main()
