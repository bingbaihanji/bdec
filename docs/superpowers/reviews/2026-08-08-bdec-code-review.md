# BDEC 代码审查记录

范围：当前仓库实现与测试结果。重点检查反编译主链路、CLI、SSA/IR、CFG、输出与诊断。

| 问题 | 所在位置 | 解决方法 |
|---|---|---|
| `Decompiler.decompile(Path)` 只取文件名当作类名，遇到包路径、嵌套类、目录结构时会丢失真实 internal name。 | `src/main/java/com/bingbaihanji/bdec/decompiler/Decompiler.java:75-85` | 改为从相对路径或 class 文件内容解析 internal name；至少在默认实现里保留目录层级，不要只用 `getFileName()`。 |
| CLI 的类名推导逻辑同样只截断 `.class` 后缀，打包目录或 `com/example/Foo.class` 会被写成错误输出路径语义。 | `src/main/java/com/bingbaihanji/bdec/BdecCli.java:92-103`, `:201-205` | 统一复用一个 `InternalNameResolver`，从输入路径推导 internal name，并区分单文件、目录、JAR 三种场景。 |
| SSA 流程是“半成品”：插入了 `PHI`、计算了版本计数，但没有真正完成变量重命名，也没有把 `typeInference` 的结果传给后续阶段。 | `src/main/java/com/bingbaihanji/bdec/ir/SsaBuilder.java:63-143`, `src/main/java/com/bingbaihanji/bdec/BdecEngine.java:107-116` | 要么把 SSA 阶段补完整，完成 def-use 重写、PHI 参数回填、版本替换；要么先移出生产链路，避免“看起来启用、实际没生效”。 |
| `TypeInference.infer()` 的结果被计算后直接丢弃，后续 IR/AST 仍使用原始类型信息，收益没有落到输出。 | `src/main/java/com/bingbaihanji/bdec/BdecEngine.java:110-116` | 将推导结果写回 `IrInstruction` / `Value` / `StructuredMethod`，或者把结果作为 structuring 和 AST 构造的输入。 |
| `SourceEmitter` 里创建了 `lineMapping`，但没有任何地方填充，最终返回的源代码行到字节码偏移映射是空表。 | `src/main/java/com/bingbaihanji/bdec/emit/SourceEmitter.java:16-22, 50` | 在 AST/IR 发射阶段接入真实的行号与 offset 追踪；如果暂时做不到，先删掉这个返回值，避免对外暴露“有映射但实际上没有”。 |
| 整体异常处理粒度过粗，多个阶段都直接 `catch (Exception)`，容易把结构性错误、输入错误和内部 bug 混成同一种失败。 | `src/main/java/com/bingbaihanji/bdec/BdecEngine.java:100-165`, `src/main/java/com/bingbaihanji/bdec/BdecCli.java:115-186` | 拆成解析、CFG、IR、SSA、结构化、输出几层独立异常；对外只吞可恢复错误，对内部 bug 保留堆栈和阶段信息。 |
| `DecompilerDiagnostic` 的结构是对的，但当前使用方式偏弱，很多错误只输出 message，没有把 phase、class、method、offset 充分用起来。 | `src/main/java/com/bingbaihanji/bdec/decompiler/diagnostic/DecompilerDiagnostic.java:1-33`, `src/main/java/com/bingbaihanji/bdec/BdecEngine.java:85-165`, `src/main/java/com/bingbaihanji/bdec/BdecCli.java:105-110` | 统一要求所有阶段报诊断时填写结构化字段；CLI 也应按 phase/class/method 分组输出，而不是只打印文本。 |
| CFG 构建依赖线性扫描和后续按 block 再遍历指令，复杂方法上会有不必要的 O(n^2) 成本。 | `src/main/java/com/bingbaihanji/bdec/cfg/CfgBuilder.java:20-160` | 先建立 `offset -> instruction` / `offset -> block` 索引，再用一次扫描完成 leader、block、edge 构建。 |
| `InstructionDecoder` 对未知 opcode 直接写 `System.err` 并返回 `null`，这会让错误流和正常流程混在一起。 | `src/main/java/com/bingbaihanji/bdec/bytecode/parser/InstructionDecoder.java:21-30` | 改成抛出受检异常或返回显式错误对象，由上层统一收集诊断，不要在底层直接打印。 |
| 当前测试能跑通，但集成测试和类文件定位仍依赖本地环境，回归时容易出现“测试通过但样本缺失”的假阳性。 | `src/test/java/com/bingbaihanji/bdec/BdecIntegrationTest.java`, `src/test/java/com/bingbaihanji/bdec/bytecode/parser/ClassFileReaderTest.java` | 补一组仓库内固定样本 class/jar，用测试资源控制输入，避免依赖开发机外部路径。 |

## 结论

当前版本已经具备可运行的主链路，架构方向基本合理，但还没完全达到“可稳定生产”的标准。优先修的是 internal name 解析、SSA 完整性、行号映射、以及异常/诊断分层；这几项会直接影响正确性和可维护性。



