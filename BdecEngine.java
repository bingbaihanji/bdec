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
import com.bingbaihanji.bdec.decompiler.diagnostic.DiagnosticListener;
import com.bingbaihanji.bdec.emit.SourceEmitter;
import com.bingbaihanji.bdec.emit.SourceFile;
import com.bingbaihanji.bdec.structuring.StructuredMethod;

import java.util.ArrayList;
import java.util.List;

/**
 * BDEC 反编译引擎核心类,实现 {@link Decompiler} 接口.
 *
 * <p>负责协调反编译的各个阶段:解析 class 文件,构建控制流图(CFG),
 * 生成线性中间表示(LinearIr),SSA(静态单赋值)优化,结构化控制流,
 * 构建抽象语法树(AST),AST 重写以及源代码输出.</p>
 */
public class BdecEngine implements Decompiler {

    /** 反编译配置 */
    private final BdecConfig config;

    /** 诊断信息监听器 */
    private final DiagnosticListener diagnostics;

    /** Class 文件字节码读取器 */
    private final ClassFileReader classReader = new ClassFileReader();

    /** 逐方法反编译管线(CFG → IR → 语义重建 → SSA → 结构化) */
    private final MethodPipeline methodPipeline;

    /** 成员内部类反编译器 */
    private final InnerClassDecompiler innerClassDecompiler;

    /** 抽象语法树(AST)构建器 */
    private final AstBuilder astBuilder = new AstBuilder();

    /** 匿名类内联重写器(在内部类反编译完成后单独执行) */
    private final AnonymousClassRewriter anonymousClassRewriter = new AnonymousClassRewriter();

    /** 局部类 this$0 清理重写器(与匿名类内联同阶段执行) */
    private final LocalClassRewriter localClassRewriter = new LocalClassRewriter();

    /** AST 重写器,包含多个重写规则,按顺序应用 */
    private final AstRewriter astRewriter = new AstRewriter(
            List.of(new RecordRewriter(), new SealedClassRewriter(),
                    new LambdaRewriter(), new MethodRefRewriter(),
                    new StringConcatRewriter(), new TextBlockRewriter(),
                    new ForEachRewriter(), new ArrayInlineRewriter(),
                    new TryResourceRewriter(),
                    new SwitchExprRewriter(), new PatternMatchRewriter(),
                    new SwitchPatternMatchRewriter(),
                    new RecordPatternRewriter(),
                    new TernaryRewriter(), new BoxingRewriter(),
                    new StringSwitchRewriter(),
                    new EnumSwitchRewriter(),
                    new EnumRewriter(),
                    new InnerClassRewriter(),
                    new SourceCleanup()));

    /** 源代码输出器 */
    private final SourceEmitter sourceEmitter = new SourceEmitter();

    /**
     * 构造反编译引擎实例.
     *
     * @param config      反编译配置
     * @param diagnostics 诊断信息监听器
     */
    public BdecEngine(BdecConfig config, DiagnosticListener diagnostics) {
        this.config = config;
        this.diagnostics = diagnostics;
        this.methodPipeline = new MethodPipeline(config, diagnostics);
        this.innerClassDecompiler = new InnerClassDecompiler(
                classReader, methodPipeline, astBuilder, astRewriter, config, diagnostics);
    }

    @Override
    public String getName() {return BuildInfo.NAME;}

    @Override
    public String getVersion() {return BuildInfo.VERSION;}

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
     * @param internalName 类的内部名称(如 {@code com/example/Foo})
     * @param classBytes   class 文件的字节数组
     * @param context      反编译上下文
     * @return 反编译结果,包含生成的源代码和诊断信息
     */
    @Override
    public BdecResult decompile(String internalName, byte[] classBytes, DecompileContext context) {
        List<String> warnings = new ArrayList<>();

        try {
            // ===== 阶段 1:解析 class 文件 =====
            ClassFileModel classFile = classReader.read(internalName, classBytes);
            diagnostics.report(DecompilerDiagnostic.info("parser", internalName,
                    "parsed v" + classFile.majorVersion() + ", "
                            + classFile.methods().size() + " methods, "
                            + classFile.fields().size() + " fields"));

            // 用已解析的 class 文件模型和 BootstrapMethod 数据丰富上下文.
            // LambdaRewriter,MethodRefRewriter,EnumRewriter 等重写规则
            // 需要这些字节码级别的数据进行分析.
            context = new DecompileContext(context.config(), context::loadClassBytes,
                    classFile.bootstrapMethods(), classFile);

            // module-info.class(ACC_MODULE = 0x8000):无字段与方法,
            // 直接从 Module 属性构建模块声明,跳过方法级反编译管线.
            if ((classFile.accessFlags() & AccessFlags.ACC_MODULE) != 0) {
                ModuleDeclaration mod = ModuleDeclBuilder.build(classFile);
                CompilationUnit moduleUnit = new CompilationUnit(
                        "", List.of(), List.of(), java.util.Map.of(), mod);
                SourceFile source = sourceEmitter.emit(moduleUnit, config);
                return new BdecResult(true, source.source(), null, warnings,
                        source.sourceLineToBytecodeOffset());
            }

            // ===== 阶段 2-4:逐方法反编译 =====
            List<StructuredMethod> structuredMethods =
                    methodPipeline.decompileMethods(classFile, context, true, true);

            // 阶段 5:构建抽象语法树(AST)
            CompilationUnit unit = astBuilder.build(classFile, structuredMethods, context);

            // 阶段 5b:应用 AST 重写规则
            unit = astRewriter.rewrite(unit, config, context);

            // 阶段 5c:反编译内部类(成员内部类,非匿名/局部类)
            unit = innerClassDecompiler.decompileInnerClasses(unit, classFile, context);

            // 阶段 5d:内联匿名类 + 清理局部类 this$0(内部类反编译后
            // 类型声明已追加到编译单元,此时才能还原)
            unit = anonymousClassRewriter.rewrite(unit, context);
            unit = localClassRewriter.rewrite(unit, context);

            // 阶段 6:生成 Java 源代码
            SourceFile source = sourceEmitter.emit(unit, config);

            return new BdecResult(true, source.source(), null, warnings,
                    source.sourceLineToBytecodeOffset());

        } catch (Exception e) {
            diagnostics.report(DecompilerDiagnostic.error("emit", internalName,
                    null, -1, "decompilation failed: " + e.getMessage(), e));
            return BdecResult.error(e, warnings);
        }
    }

}
