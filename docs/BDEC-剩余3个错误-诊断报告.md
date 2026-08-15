# BDEC 剩余 3 个错误 — 完整诊断报告

**日期：** 2026-08-12  
**状态：** 96 测试，95 通过，1 失败（TestClass2 round-trip），共 3 个子错误

---

## 错误 1/3：patternSwitch 类型转换优先级（第 82 行）

**反编译输出（错误）：**
```java
if ((Integer) var2.intValue() > 0) {
```
cast `(Integer)` 包裹了整个 `var2.intValue()` 调用结果，而不是包裹 `var2`。

**应该是（正确）：**
```java
if (((Integer) var2).intValue() > 0) {
```

### 字节码链路

```
ALOAD obj → CHECKCAST Integer → ASTORE var2 → ALOAD var2
→ INVOKEVIRTUAL intValue() → IF_ICMPGT 0
```

### IR 指令在 BlockReducer 中的流转

1. CONDITION 指令的操作数是 `[INVOKE结果, 常量0]`
2. `translateExpr(CONDITION)` 先调用 `valueToExpr(INVOKE结果)`
3. `valueToExpr` 拿到 `InstructionRef(INVOKE)`，调用 `translateExpr(INVOKE)`
4. `translateExpr(INVOKE)` 在 **BlockReducer.java 第 2347-2351 行** 取 receiver：
   ```java
   Value firstOp = insn.operands().getFirst();
   target = valueToExpr(firstOp);  // <-- 这里解析 receiver
   ```
5. 如果 `firstOp` 是 `Variable("var2")`，`valueToExpr` 走 **ExpressionTranslator.java 第 258-263 行**，通过 `varStoreSource.get(var)` 追溯 store 链

### 根因

`valueToExpr` 解析 `Variable("var2")` 时，`varStoreSource` 没有正确映射 `"var2 → CAST(obj)"`，而是映射到了原始 LOAD（丢失了 CAST）。

所以 INVOKE 的 receiver 变成了裸 `VarExpr("var2")` 而不是 `CastExpr(Integer, VarExpr("obj"))`。CAST 指令被单独翻译成了表达式语句，然后在 AST 输出时错误地包裹了整个 `intValue()` 调用。

### 排查点 A（最可能）

`BlockReducer.java` 行 1386 附近，`currentVarStoreSource` 的构建逻辑。

**检查：** 在 typeSwitch 中，ASTORE 存储 CAST 结果时，是否在 varStoreSource 中记录了 `Variable → CAST_instruction` 的映射？

具体验证：当 bytecode 是 `ALOAD obj → CHECKCAST → ASTORE var2 → ALOAD var2` 时，varStoreSource 中 `Variable(var2)` 对应的 Value 是 `CAST_instruction` 还是原始的 `LOAD(obj)`？

### 排查点 B（如果 A 已正确）

`translateExpr(CONDITION)` 在 **BlockReducer.java 第 2209-2275 行** 处理条件时，是否走了某个特殊路径（如布尔+零简化、COMPARE 合并），绕过了正常的 INVOKE 翻译流程？

### 备选修复方案（不依赖上述排查）

在 **BlockReducer.java INVOKE case 第 2350 行之后**，增加收尾检查：

如果 `target` 是 `VarExpr` 且变量的声明类型是 `Object`（不是方法所需的类型），向上游追溯 IR 指令，找到匹配的 CHECKCAST，手动包裹为 `CastExpr`。

### 关键文件

| 文件 | 行号 | 内容 |
|------|------|------|
| `BlockReducer.java` | 1380-1450 | varStoreSource 构建 |
| `BlockReducer.java` | 2347-2351 | INVOKE receiver 解析 |
| `BlockReducer.java` | 2209-2275 | CONDITION 处理 |
| `ExpressionTranslator.java` | 254-278 | valueToExpr 的 Variable 追溯逻辑 |
| `IrBuilder.java` | 1357-1370 | handleCheckCast——确认 CAST resultType 正确 |

---

## 错误 2/3 & 3/3：unnamedDemo lambda 类型推断（第 99-100 行）

这两个错误是同一个根因的连锁反应。

### 原始 Java 源码

```java
BiFunction<Integer, Integer, Integer> add = (a, _) -> a + 5;
System.out.println(add.apply(10, 20));
```

### 反编译输出（错误）

```java
int add = 0;                                               // 第99行之前，SourceCleanup 自动声明
add = (a, arg1) -> Integer.valueOf(a.intValue() + 5);      // 第99行，lambda 赋值给 int
System.out.println(add.apply(Integer.valueOf(10), ...));    // 第100行，int 上调用 .apply()
```

### 错误链

1. AST 中没有 `VariableDeclaration("add", BiFunction, LambdaExpr)`，只有 `ExpressionStatement(AssignExpr(VarExpr("add"), LambdaExpr))`
2. SourceCleanup 扫描时发现 `add.apply(...)` 使用了未声明变量 `add`
3. SourceCleanup 自动声明 `int add = 0`（默认整型）
4. lambda 赋值给 `int` 变量 → 编译错误（第 99 行）
5. `add.apply()` 对 `int` 调用方法 → 编译错误（第 100 行）

### 根因（第 1 层——源头，必须修这里）

BlockReducer 的变量声明插入逻辑没有把 **ASTORE（存储 INDY lambda 结果）** 识别为新变量声明。

本应产生：
```
VariableDeclaration(BiFunction, "add", LambdaExpr)
```

但实际产生了：
```
ExpressionStatement(AssignExpr(VarExpr("add"), LambdaExpr))
```

**为什么没识别？两个可能：**

1. **A) Slot 复用问题：** try-catch 块内 `x` 复用了 slot 1，BlockReducer 认为 slot 1 变量已经"声明过"，所以对 `add` 也走赋值路径而非声明路径。

2. **B) 类型缺失：** INDY 翻译产生的 LambdaExpr 没有携带目标函数式接口类型（BiFunction），所以即使走声明路径，也需要额外步骤获取类型。

### 根因（第 2 层——不要修这里）

SourceCleanup.java 第 167-179 行的自动声明逻辑正确执行了（看到未声明变量就补声明），但它不知道变量的真正类型，默认为 `int`。

**重要：** 之前 6 次尝试在 SourceCleanup 加类型推断都破坏了 `streamDemo` 测试。这块代码是最后的安全网，不是用来修根因的地方。不要再改 SourceCleanup。

### 修复方案

在 BlockReducer 的变量声明插入逻辑中（处理 ASTORE 的地方），增加对 INDY/LambdaExpr 的特殊处理：

1. 如果 ASTORE 存储的是 INDY 结果（lambda/方法引用），标记这个变量为"首次声明"
2. 从 LambdaExpr 中提取目标函数式接口类型（LambdaExpr 可通过 invokedynamic 的 method type 获取）
3. 生成 `VariableDeclaration(functionalInterfaceType, name, LambdaExpr)` 而不是 `ExpressionStatement(AssignExpr(...))`

同时检查 BlockReducer 的变量版本管理：try-catch 之后 slot 1 的新变量 `add` 是否被正确识别为新版本（而非沿用 try 块内的 `x`），确保 `currentVarStoreSource` 不把 `add` 混同于 `x`。

### 关键文件

| 文件 | 行号 | 内容 |
|------|------|------|
| `BlockReducer.java` | 搜索 "VariableDeclaration" | 变量声明插入逻辑 |
| `BlockReducer.java` | 1380-1450 | varStoreSource、store 处理 |
| `IndyTranslator.java` | 完整文件 | 确认 LambdaExpr 携带了类型信息 |
| `SourceCleanup.java` | 167-179 | **不要改**——这是安全网，不是根因 |
| `IrBuilder.java` | 1544-1568 | lookupReadVar——LVT 作用域查找 |
| `IrBuilder.java` | 搜索 "handleInvokeDynamic" | lambda INDY 生成的 IR 指令 |

---

## 修复顺序建议

两个错误互相独立，可并行修复：

| 顺序 | 任务 | 难度 | 说明 |
|------|------|------|------|
| 1 | 修 unnamedDemo | 中 | 修 BlockReducer 变量声明逻辑；让 INDY lambda 产生 VariableDeclaration + 正确类型；影响范围小，不涉及 CONDITION/CAST 链路 |
| 2 | 修 patternSwitch | 中-高 | 追踪 IR store-load 链中 CAST 信息的丢失；在 BlockReducer INVOKE case 或 valueToExpr 中修复 |

**验证命令：**
```bash
mvn test
```

预期结果：**96/96 全部通过。**

---

## 项目结构速查

```
src/main/java/com/bingbaihanji/bdec/
├── BdecEngine.java              — 反编译管线主引擎
├── ast/                         — AST 节点（不可变 record/class）
│   ├── expr/                    —   表达式：VarExpr, CastExpr, InvocationExpr, LambdaExpr, ...
│   ├── stmt/                    —   语句：VariableDeclaration, ExpressionStatement, IfStatement, ...
│   └── rewrite/                 —   AST 重写规则
│       ├── SourceCleanup.java   —     安全网（不要改）
│       └── RecordPatternRewriter.java — 记录模式重写
├── bytecode/                    — 字节码解析（ClassFileReader, StructureParser, ...）
│   └── model/
│       ├── MethodModel.java     —     含 lookupVarName(slot, pc) 作用域感知 LVT 查找
│       └── LocalVariableEntry.java —  LVT 条目 (startPc, length, name, slot, typeDesc)
├── ir/                          — 中间表示
│   ├── IrBuilder.java           —     字节码栈 → IR 指令（含 handleCheckCast, handleInvoke, handleCondition）
│   ├── IrInstruction.java       —     IR 指令（含 binaryOpFromBytecode）
│   └── SsaBuilder.java          —     SSA 构造（Cytron 算法）
├── structuring/                 — 结构化控制流 → AST
│   ├── BlockReducer.java        —     核心：IR 指令 → AST 表达式/语句（3354 行 → 已拆分）
│   ├── ExpressionTranslator.java —    值→表达式、布尔检测、变量→表达式（从 BlockReducer 提取）
│   ├── IndyTranslator.java      —     INDY → LambdaExpr/MethodRef（从 BlockReducer 提取）
│   └── BranchAnalyzer.java      —     条件分支分析（TRUE_BRANCH/FALSE_BRANCH）
└── emit/
    ├── ExpressionEmitter.java   —     表达式 → Java 源码
    └── StatementEmitter.java    —     语句 → Java 源码
```

## 反编译管线（BDEC Pipeline）

```
class bytes
  → ClassFileReader（解析字节码）
    → CfgBuilder（控制流图）
      → IrBuilder（线性 IR）
        → SsaBuilder → TypeInference → CopyPropagation → DeadCodeElimination（可选 SSA 优化）
          → ControlFlowStructurer（结构化控制流）
            → BlockReducer（IR → AST）★ 两个错误都在这一步
              → AstBuilder（方法 AST → 编译单元）
                → AstRewriter 链（16 个 RewriteRule）
                  → SourceEmitter（生成 Java 源码）
```
