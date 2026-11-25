package com.bingbaihanji.bdec;

import com.bingbaihanji.bdec.ast.AstBuilder;
import com.bingbaihanji.bdec.ast.CompilationUnit;
import com.bingbaihanji.bdec.ast.ModuleDeclBuilder;
import com.bingbaihanji.bdec.ast.ModuleDeclaration;
import com.bingbaihanji.bdec.ast.rewrite.*;
import com.bingbaihanji.bdec.bytecode.model.AccessFlags;
import com.bingbaihanji.bdec.bytecode.model.ClassFileModel;
import com.bingbaihanji.bdec.bytecode.parser.ClassFileReader;
import com.bingbaihanji.bdec.decompiler.Decompiler;
import com.bingbaihanji.bdec.decompiler.diagnostic.DecompilerDiagnostic;
import com.bingbaihanji.bdec.decompiler.diagnostic.DiagnosticLevel;
import com.bingbaihanji.bdec.decompiler.diagnostic.DiagnosticListener;
import com.bingbaihanji.bdec.emit.SourceEmitter;
import com.bingbaihanji.bdec.emit.SourceFile;
import com.bingbaihanji.bdec.structuring.StructuredMethod;
import com.bingbaihanji.bdec.util.AstDumper;

import java.util.ArrayList;
import java.util.List;

/**
 * BDEC 反编译引擎核心类,实现 {@link Decompiler} 接口.
 *
 * <p>负责协调反编译的各个阶段:解析 class 文件,构建控制流图(CFG),
 * 生成线性中间表示(LinearIr),SSA(静态单赋值)优化,结构化控制流,
 * 构建抽象语法树(AST),AST 重写以及源代码输出.</p>
 *
 * <p><b>线程安全:</b> 引擎实例仅持有不可变配置与诊断监听器引用;每次
 * {@link #decompile} 调用都会创建本次调用专用的解析器,逐方法管线,AST
 * 重写器,内部类反编译器与发射器,因此同一引擎实例可被复用并并发调用.
 * 调用方共享的 {@link DiagnosticListener} 必须由调用方自行保证线程安全;
 * 其中的 WARNING/ERROR 事件会同时收集到 {@link BdecResult#warnings()}.</p>
 */
public class BdecEngine implements Decompiler {

    /** 反编译配置 */
    private final BdecConfig config;

    /** 诊断信息监听器(由调用方提供,其线程安全由调用方负责) */
    private final DiagnosticListener diagnostics;

    /**
     * 构造反编译引擎实例.
     *
     * <p>构造实例仅保存配置与监听器引用,不创建可变的反编译组件,
     * 因而构造后即可并发调用 {@link #decompile}.</p>
     *
     * @param config      反编译配置
     * @param diagnostics 诊断信息监听器,调用方需保证其线程安全
     */
    public BdecEngine(BdecConfig config, DiagnosticListener diagnostics) {
        this.config = config;
        this.diagnostics = diagnostics;
    }

    @Override
    public String getName() {return Decompiler.super.getName();}

    @Override
    public String getVersion() {return Decompiler.super.getVersion();}

    /**
     * 创建本次反编译调用专用的 AST 重写器.
     *
     * <p>重写规则可能持有可变状态,因此不在引擎实例上共享;规则顺序与依赖
     * 关系保持不变(如 {@code RedundantCastRewriter} 必须在
     * {@code ForEachRewriter} 之后,{@code SourceCleanup} 最后).</p>
     *
     * @return 包含完整规则链的新重写器实例
     */
    private AstRewriter createAstRewriter() {
        return new AstRewriter(
                List.of(new RecordRewriter(), new SealedClassRewriter(),
                        new LambdaRewriter(), new MethodRefRewriter(),
                        new StringConcatRewriter(), new TextBlockRewriter(),
                        new ForEachRewriter(), new ArrayInlineRewriter(),
                        new TryResourceRewriter(),
                        new SwitchExprRewriter(), new RecordPatternRewriter(),
                        new PatternMatchRewriter(),
                        new SwitchPatternMatchRewriter(),
                        new TernaryRewriter(), new BoxingRewriter(),
                        new StringSwitchRewriter(),
                        new EnumSwitchRewriter(),
                        new EnumRewriter(),
                        new ForLoopRewrite(),
                        new RedundantCastRewriter(),
                        new SourceCleanup()));
    }

    /**
     * 执行完整的反编译流程.
     *
     * <p>反编译流程分为以下阶段:</p>
     * <ol>
     *   <li>解析 class 文件</li>
     *   <li>逐方法构建控制流图(CFG)</li>
     *   <li>生成线性中间表示(LinearIr),并进行语义重建</li>
     *   <li>SSA(静态单赋值)构建与优化(复制传播,死代码消除)</li>
     *   <li>结构化控制流</li>
     *   <li>构建抽象语法树(AST)</li>
     *   <li>AST 重写(应用各类语义恢复规则)</li>
     *   <li>生成 Java 源代码</li>
     * </ol>
     *
     * <p>每次调用都创建独立的可变组件,方法本身可并发执行.产生的
     * WARNING/ERROR 诊断消息会收集到返回值的 {@link BdecResult#warnings()}.</p>
     *
     * @param internalName 类的内部名称(如 {@code com/example/Foo})
     * @param classBytes   class 文件的字节数组
     * @param context      反编译上下文
     * @return 反编译结果,包含生成的源代码和诊断信息
     */
    @Override
    public BdecResult decompile(String internalName, byte[] classBytes, DecompileContext context) {
        List<String> warnings = new ArrayList<>();
        // 调用方传入的上下文(用于保持其警告接收器链路);方法后续会重新绑定 context.
        DecompileContext callerContext = context;
        // 当前反编译阶段,用于异常归因(parser/structuring/ast/rewrite/inner/emit).
        String phase = "parser";
        // 记录型监听器:WARNING/ERROR 消息写入本次结果的 warnings,再转发给调用方监听器.
        DiagnosticListener recordingDiagnostics = diagnostic -> {
            if (diagnostic.level() == DiagnosticLevel.WARNING
                    || diagnostic.level() == DiagnosticLevel.ERROR) {
                warnings.add(diagnostic.message());
            }
            if (diagnostics != null) {
                diagnostics.report(diagnostic);
            }
        };

        try {
            // ===== 阶段 1:解析 class 文件 =====
            ClassFileReader classReader = new ClassFileReader();
            ClassFileModel classFile = classReader.read(internalName, classBytes);
            recordingDiagnostics.report(DecompilerDiagnostic.info("parser", internalName,
                    "parsed v" + classFile.majorVersion() + ", "
                            + classFile.methods().size() + " methods, "
                            + classFile.fields().size() + " fields"));

            // 用已解析的 class 文件模型和 BootstrapMethod 数据丰富上下文.
            // LambdaRewriter,MethodRefRewriter,EnumRewriter 等重写规则
            // 需要这些字节码级别的数据进行分析.
            // 警告接收器同时写入本次结果的 warnings,并转发给调用方上下文的接收器,
            // 使不可归约 CFG 与 SourceCleanup 兜底产生的警告可见.
            context = new DecompileContext(context.config(), context::loadClassBytes,
                    classFile.bootstrapMethods(), classFile, message -> {
                        warnings.add(message);
                        callerContext.reportWarning(message);
                    });

            // module-info.class(ACC_MODULE = 0x8000):无字段与方法,
            // 直接从 Module 属性构建模块声明,跳过方法级反编译管线.
            if ((classFile.accessFlags() & AccessFlags.ACC_MODULE) != 0) {
                ModuleDeclaration mod = ModuleDeclBuilder.build(classFile);
                CompilationUnit moduleUnit = new CompilationUnit("", List.of(), List.of(), java.util.Map.of(), mod);
                phase = "emit";
                SourceEmitter sourceEmitter = new SourceEmitter();
                SourceFile source = sourceEmitter.emit(moduleUnit, config);
                return new BdecResult(true, source.source(), null, warnings, source.sourceLineToBytecodeOffset());
            }

            // ===== 阶段 2-4:逐方法反编译 =====
            phase = "structuring";
            MethodPipeline methodPipeline = new MethodPipeline(config, recordingDiagnostics);
            List<StructuredMethod> structuredMethods = methodPipeline.decompileMethods(classFile, context, true, true);

            // 阶段 5:构建抽象语法树(AST)
            phase = "ast";
            AstBuilder astBuilder = new AstBuilder();
            CompilationUnit unit = astBuilder.build(classFile, structuredMethods, context);
            if (config.debugDumpAst()) {
                System.err.println("=== AST (pre-rewrite) ===");
                AstDumper.dump(unit);
            }

            // 阶段 5b:应用 AST 重写规则
            phase = "rewrite";
            AstRewriter astRewriter = createAstRewriter();
            unit = astRewriter.rewrite(unit, config, context);
            if (config.debugDumpAst()) {
                System.err.println("=== AST (post-rewrite) ===");
                AstDumper.dump(unit);
            }

            // 阶段 5c:反编译内部类(成员内部类,非匿名/局部类)
            phase = "inner";
            InnerClassDecompiler innerClassDecompiler = new InnerClassDecompiler(
                    classReader, methodPipeline, astBuilder, astRewriter, config, recordingDiagnostics);
            unit = innerClassDecompiler.decompileInnerClasses(unit, classFile, context);

            // 阶段 5d:内联匿名类 + 清理成员内部类 this$0(内部类反编译后
            // 类型声明已追加到编译单元,此时才能还原)
            phase = "rewrite";
            unit = new AnonymousClassRewriter().rewrite(unit, context);
            unit = new InnerClassRewriter().rewrite(unit, context);

            // 阶段 6:生成 Java 源代码
            phase = "emit";
            SourceEmitter sourceEmitter = new SourceEmitter();
            SourceFile source = sourceEmitter.emit(unit, config);

            return new BdecResult(true, source.source(), null, warnings, source.sourceLineToBytecodeOffset());

        } catch (Exception e) {
            recordingDiagnostics.report(DecompilerDiagnostic.error(phase, internalName, null, -1,
                    "decompilation failed: " + e.getMessage(), e));
            return BdecResult.error(e, warnings);
        }
    }

}
