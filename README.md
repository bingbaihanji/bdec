<div align="center">

# BDEC · Bingbaihanji Decompiler Engine Core

**面向 Java 25 的字节码反编译器** — *A Java 25 bytecode decompiler*

输入 `.class` / `.jar`，输出可读且可重新编译的 Java 源码。
Accept `.class` / `.jar` and produce readable, re-compilable Java source.

**Java 25** · **Maven** · **Apache-2.0**

</div>

---

## 简介 / Introduction

BDEC（Bingbaihanji Decompiler Engine Core）是一个面向 **Java 25** 的 Java 字节码反编译器。它接受单个 `.class` 文件或整个 JAR 包作为输入，通过分层管线将字节码逐步提升为结构化 AST，最终生成可读且尽可能接近原意的 Java 源码。

BDEC is a Java 25 bytecode decompiler. It takes a `.class` file or a whole JAR, lifts bytecode to a structured AST through a layered pipeline, and emits readable Java source that stays as close to the original intent as possible.

设计上参考了 CFR / Vineflower 的「每模式一处理器」（one handler per pattern）思路：每个高层语义（for-each、try-with-resources、switch 表达式、模式匹配、三元……）由独立的分析/重写器负责，管线各阶段可独立替换、可单独调试。

---

## 特性 / Features

- **完整的反编译管线**：ClassFileReader → CFG → 线性 IR → 语义重建 → 可选 SSA → 控制流结构化 → AST → 18 条重写链 → 源码发射
- **高层语义还原**：
  - 控制流：if-else、嵌套/多重循环、for-each（`Iterator`/数组/增强 for）、do-while、switch（字符串/枚举/表达式/箭头/模式匹配）
  - 异常处理：try-catch（含 multi-catch）、try-finally 去重、try-with-resources
  - 语言特性：lambda、方法引用、record、sealed、枚举、内部类、泛型、注解、字符串拼接、文本块、三元、装箱/拆箱、instanceof 模式匹配、record 解构
- **可嵌入 API**：`BdecEngine` / `BdecConfig` / `BdecResult` / `DecompileContext`
- **命令行 CLI**：单类、JAR 批处理、诊断输出
- **往返测试框架**：编译 → 反编译 → 重新编译 → 精确断言，覆盖 239 个用例
- **与 CFR 的差分测试**：`tools/diff-test/diff_test.py` 对 16 个控制流样例做「BDEC vs CFR 双份输出均可重新编译」矩阵
- **调试支持**：`BdecConfig.debug()` 可导出 CFG DOT 图与 AST 树转储

---

## 快速开始 / Quick Start

### 环境要求 / Requirements

- **JDK 25**（往返测试需要 `javac` 参与编译，JRE 不可用）
- Maven 3.9+

### 构建 / Build

```bash
mvn package
```

生成 `target/bdec.jar`（主类 `com.bingbaihanji.bdec.BdecCli`）。

### 命令行使用 / CLI

```bash
# 反编译单个 class
java -jar bdec.jar -class "Foo.class" "./out"

# 反编译整个 JAR(输出目录可选,默认当前目录)
java -jar bdec.jar -jar "app.jar" "./out"

# 帮助 / 版本
java -jar bdec.jar --help
java -jar bdec.jar --version
```

输出按包结构落盘：`com.example.Foo` → `./out/com/example/Foo.java`。

### 作为库嵌入 / Embed as a library

```java
BdecConfig config = BdecConfig.builder()
        .debugDumpCfg(false)
        .build();

// 加载内部类/兄弟类字节码(可返回 null 跳过)
Function<String, byte[]> loader = innerName -> { /* ... */ };
DecompileContext ctx = new DecompileContext(config, loader);

BdecEngine engine = new BdecEngine(config, diagnostics -> { });
BdecResult result = engine.decompile("com/example/Foo", classBytes, ctx);

if (result.success()) {
    System.out.println(result.decompiledCode());
}
```

---

## 架构 / Architecture

`BdecEngine.decompile()` 编排固定顺序的管线，每个阶段都是独立、可替换的组件：

```
字节码 (.class bytes)
  → ClassFileReader         解析 class 文件头、常量池、方法、字段
  → CfgBuilder              指令 → 基本块,连接 ControlFlowEdge
  → IrBuilder               栈模拟 → 寄存器式 LinearIr
  → SemanticReconstructor   恢复高层语义(字符串拼接、for-each 模式等)
  → [SsaBuilder → TypeInference → CopyPropagation → DeadCodeElimination]  (可选,ssaThreshold 控制)
  → ControlFlowStructurer   迭代折叠循环、合并顺序块、标注 if/switch/try-catch
  → AstBuilder              从模型 + 结构化方法构建 CompilationUnit AST
  → AstRewriter             ~18 条重写规则链(record、lambda、switch 表达式、模式匹配、三元……)
  → SourceEmitter           美化输出为 Java 源码(缩进、import、行号映射)
```

控制流结构化（`ControlFlowStructurer`）基于支配树/后支配树预分析 switch、try-catch、if-else 与循环，然后**迭代折叠**循环与顺序块——每次折叠创建新的 CFG 快照，从不原地修改。

### AST 重写链（18 条）

`RecordRewriter` · `SealedClassRewriter` · `LambdaRewriter` · `MethodRefRewriter` · `StringConcatRewriter` · `TextBlockRewriter` · `ForEachRewriter` · `TryResourceRewriter` · `SwitchExprRewriter` · `PatternMatchRewriter` · `RecordPatternRewriter` · `TernaryRewriter` · `BoxingRewriter` · `StringSwitchRewriter` · `EnumSwitchRewriter` · `EnumRewriter` · `InnerClassRewriter` · `SourceCleanup`

---

## 测试 / Testing

```bash
mvn test                  # 全部往返测试 + 执行级语义等价(261)
mvn test -Dtest=CfrDiffTest   # 与 CFR 的差分测试(9 个样例,需 CFR jar)
mvn verify                # 含 checkstyle 报告
# 可选:行为样例全套差分(需先 mvn package 重建 bdec.jar)
python tools/diff-test/diff_test.py --samples src/test/resources/behavior-samples
```

测试分三层（后层严格加强前层，能编译 ≠ 行为正确）：

1. **往返测试**：`DecompileTestHarness` 编译 Java 源码 → BDEC 反编译 → 断言输出包含预期高层结构、不包含底层痕迹 → 重新编译确认合法。
2. **CFR 差分**：`CfrDiffTest` 与 `tools/diff-test/diff_test.py` 对同一样例分别用 BDEC 与 CFR 反编译，两份输出都必须可重新编译。
3. **执行级语义等价（check() round-trip）**：`SemanticEquivalenceHarness` 对 `src/test/resources/behavior-samples/` 下 15 个行为样例（`public static String check()` + `main`）执行 **反编译 → 重编译 → 用同一输入运行两份、比对退出码与 stdout**。这是 JADX 风格（CFR/Vineflower 仅做文本回归）——系统性捕获「能编译但行为不同」的静默错误。

- **checkstyle**：`config/checkstyle/checkstyle.xml`，报告不阻断构建（当前仅剩 `BdecCli` 4 处既有警告）。

---

## 已知限制 / Known Limitations

发布版如实声明，避免使用者踩坑：

- **混淆字节码不可用**：不可归约控制流（irreducible CFG）当前是透传占位（`IrreducibleHandler`），goto 密集的混淆类会产出错误结构。
- **switch 发射存在降级占位**：少数 switch 形态会走到 `StatementEmitter` 的占位分支，输出不可重编译的空壳。
- **JAR 模式跨类解析不完整**：`-jar` 批处理使用空字节码加载器，匿名类/内部类的内联在 JAR 场景下拿不到兄弟类字节。
- **测试以「可重编译」为主，语义等价校验仍在发展**：能通过编译、但运行结果不同的静默错误在复杂场景仍有存量；欢迎协助补充执行级差分。
- **已知外观/边角残留**：invisible 注解丢失、部分全限定名残留、混合多维数组括号、for-each 形式 3 的 `element` 名丢失等（不影响可重编译）。

---

## 路线图 / Roadmap

- [x] 执行级语义等价差分（`SemanticEquivalenceHarness` 的 check() round-trip，15 个行为样例）
- [ ] 扩充行为样例集：泛型、匿名类、同步、复杂 try、varargs、装箱交互等真实形态
- [ ] 为行为样例补充执行级比对的 Python 通道（`diff_test.py` 运行比对列）
- [ ] 不可归约控制流降级处理
- [ ] JAR 模式跨类字节码加载
- [ ] Lengauer-Tarjan 支配树（当前为迭代不动点算法）

---

## 贡献 / Contributing

1. Fork 本仓库，基于 `main` 分支工作
2. 新增/修复特性请同时添加**往返测试**（`DecompileTestHarness`），断言使用精确文本匹配，禁止正则启发式
3. 本地验证通过后再提 PR：
   ```bash
   mvn test && mvn verify
   ```
4. 提交信息说明根因与验证结果

---

## 致谢 / Credits

- [CFR](https://www.benf.org/other/cfr/) —— 反编译差分参照 oracle
- [Vineflower](https://github.com/Vineflower/vineflower) —— 架构思路参考（每模式一处理器）

---

## 许可证 / License

[Apache-2.0](./LICENSE)

Copyright 2026 冰白寒祭 (Bingbaihanji)
