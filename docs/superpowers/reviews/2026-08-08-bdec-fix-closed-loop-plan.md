# BDEC 修复闭环方案

目标：把当前“能反编译，但语义不完整”的主链路补齐，解决 `<init>` 泄露、`requireNonNull` 误输出、`return 0`/`return false` 类型错配、`synchronized` 虚假 `try/catch` 等问题，并让整个流程形成闭环。

## 现状问题

| 问题 | 根因 | 结果 |
|---|---|---|
| `<init>` 以普通调用形式泄露 | 构造器调用没有独立语义节点，仍按普通 `invoke`/IR 发射 | 输出中出现 `<init>()`、`this.<init>` 之类的中间态 |
| `Objects.requireNonNull` 被当普通调用 | 缺少模式识别层，未区分“保留调用”和“可消除调用” | 源码里残留无意义的辅助调用 |
| `return 0` 代替 `return false` | 类型信息没有贯穿到 AST/Emitter | boolean 方法输出错误、可读性差 |
| `synchronized` 变成虚假 `try/catch(Throwable)` | `monitorenter/monitorexit` 未被识别为专门结构 | 控制流看起来合法，但语义不对 |
| 表达式跨基本块无法合并 | 只做局部块内重建，缺少 def-use 驱动的表达式恢复 | 中间变量、临时栈值大量外泄 |

## 闭环目标流程

```text
class bytes
  -> class/constant parser
  -> CFG builder
  -> linear IR builder
  -> semantic reconstruction
  -> structured method/AST
  -> rewrite / cleanup
  -> type-aware emitter
  -> diagnostics + line map + regression tests
```

关键点：

1. 不是直接从 CFG 发射源码。
2. 先恢复“语义节点”，再做结构化。
3. 发射器只负责输出，不负责猜语义。
4. 诊断和测试必须能反向验证每个修复点。

## 建议新增的中间层

新增一个独立阶段：

`SemanticReconstruction`

职责：

- 构造器折叠
- `requireNonNull` / `getClass` / 纯辅助调用消除
- `monitorenter/monitorexit` 同步块识别
- 跨基本块表达式合并
- 类型感知常量折叠
- 统一生成可供 AST 使用的高层节点

建议位置：

```text
CFG -> LinearIR -> SemanticReconstruction -> StructuredMethod -> AST -> Rewrite -> Emit
```

## 具体修复方案

### 1. 构造器语义恢复

| 项 | 内容 |
|---|---|
| 问题 | `<init>`、`this(...)`、`super(...)`、匿名类构造、字段初始化混在普通调用里 |
| 方案 | 新增 `ConstructorCallNode` / `ConstructorChainNode`，专门表示构造器链 |
| 识别条件 | 首条有效语句、`invokespecial`、目标方法名 `<init>`、实例初始化上下文、`this/super` 目标关系 |
| 输出规则 | `this(...)` / `super(...)` 只能在构造器首句输出，且不能带中间 IR 痕迹 |
| 失败策略 | 识别不确定时保守保留为普通调用，但必须打诊断，不能静默污染 AST |

### 2. `requireNonNull` / 辅助调用消除

| 项 | 内容 |
|---|---|
| 问题 | `Objects.requireNonNull`、部分 `invokedynamic` 相关辅助调用被直接打印 |
| 方案 | 新增 `PatternEngine`，对表达式树做模式匹配和折叠 |
| 识别条件 | 纯静态方法、单参数、返回值仅作中间值、无副作用、结果被立即消费或丢弃 |
| 输出规则 | 若仅用于 null check 或构造器前置保护，则消除或内联为语义注释节点 |
| 失败策略 | 保留原调用，但禁止把它提升为业务语义节点 |

### 3. 类型感知常量折叠

| 项 | 内容 |
|---|---|
| 问题 | boolean、byte、char、int 的字面值被统一按整数发射 |
| 方案 | 让 `TypeInference` 的结果进入 AST 和 Emitter，建立 `TypedConstant` |
| 识别条件 | 方法返回类型、局部变量类型、条件表达式上下文、PHI 节点推导结果 |
| 输出规则 | `boolean` 方法返回 `0/1` 必须转成 `false/true`；`char`/`byte` 必须按目标类型发射 |
| 失败策略 | 当类型不确定时，优先保守输出原始值，但要在诊断里说明推导失败 |

### 4. `synchronized` 结构恢复

| 项 | 内容 |
|---|---|
| 问题 | `monitorenter/monitorexit` 被拆成 `try/catch(Throwable)` 的假结构 |
| 方案 | 单独建立 `SynchronizedRecognizer`，识别监视器进入、退出、异常边界 |
| 识别条件 | `monitorenter` 与匹配的 `monitorexit`、异常处理器覆盖范围、退出块一致性 |
| 输出规则 | 生成 `SynchronizedStmt`，而不是伪 `try/catch` |
| 失败策略 | 若无法完整恢复，则保留底层块结构并标记为未结构化，不要伪装成同步块 |

### 5. 跨基本块表达式重建

| 项 | 内容 |
|---|---|
| 问题 | 中间值在块边界泄露，表达式无法向上合并 |
| 方案 | 基于 def-use 图做表达式回溯，并把结果写回高层表达式树 |
| 识别条件 | 单次定义、单次消费、无别名歧义、跨块依赖链可追踪 |
| 输出规则 | 允许把 `store/load/temp` 折回 `assign/binop/call/field-access` |
| 失败策略 | 保留局部变量，但禁止把临时 IR 原样输出到源码层 |

## 闭环设计

要让修复真正闭环，必须满足下面四个回路。

### 回路 1：IR 到 AST

IR 阶段不要只生成“能跑”的结构，要生成“可重建”的语义信息：

- 每个 `IrInstruction` 都要能回溯 def/use
- 每个高层节点都要携带原始 bytecode offset
- 每个失败点都要保留降级路径

### 回路 2：AST 到 Rewrite

Rewrite 不能只是修饰文本，它必须承担最终语义清理：

- 移除冗余 `requireNonNull`
- 消除可还原的构造器辅助节点
- 折叠条件和常量
- 统一布尔返回、空值返回、默认值返回

### 回路 3：Emitter 到 Line Map

Emitter 必须是“类型感知”的，而不是字符串拼接器：

- `boolean` 输出 `true/false`
- `void` 输出无值返回
- `synchronized` 输出专门语法
- 方法和字段引用必须按类语义输出

### 回路 4：Diagnostics 到 Regression

每个降级和失败都要进入诊断体系，并反哺测试：

- 构造器没识别
- `requireNonNull` 没消除
- 同步块没还原
- 类型推导失败

这些都要形成固定样本，加入回归测试。

## 推荐实现顺序

1. 先补 `SemanticReconstruction` 层。
2. 先实现构造器折叠和 `synchronized` 识别。
3. 再接 `requireNonNull`、布尔常量折叠、类型感知发射。
4. 最后补跨块表达式重建和更复杂的 pattern engine。

## 验收标准

满足以下条件后，可以认为闭环成立：

- 构造函数输出不再出现 `<init>` 中间态
- `Objects.requireNonNull` 不再作为业务调用泄露
- boolean 方法稳定输出 `true/false`
- `synchronized` 不再退化成虚假 `try/catch(Throwable)`
- 复杂方法的临时变量数量明显下降
- 对应样本能在测试中稳定回归

## 结论

当前 BDEC 的问题不是“少几个特判”，而是缺少一层统一的语义恢复层。只要把 `SemanticReconstruction` 独立出来，并把构造器、辅助调用、同步块、类型折叠、跨块表达式合并都纳入这一层，整个反编译链路就能从“能输出源码”升级到“能输出可维护源码”。
