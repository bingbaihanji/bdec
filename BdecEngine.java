package com.bingbaihanji.bdec;

import com.bingbaihanji.bdec.ast.AstBuilder;
import com.bingbaihanji.bdec.ast.CompilationUnit;
import com.bingbaihanji.bdec.ast.rewrite.*;
import com.bingbaihanji.bdec.bytecode.model.ClassFileModel;
import com.bingbaihanji.bdec.bytecode.model.MethodModel;
import com.bingbaihanji.bdec.bytecode.model.constantpool.InnerClassEntry;
import com.bingbaihanji.bdec.bytecode.parser.ClassFileReader;
import com.bingbaihanji.bdec.cfg.CfgBuilder;
import com.bingbaihanji.bdec.cfg.ControlFlowGraph;
import com.bingbaihanji.bdec.decompiler.Decompiler;
import com.bingbaihanji.bdec.decompiler.diagnostic.DecompilerDiagnostic;
import com.bingbaihanji.bdec.decompiler.diagnostic.DiagnosticListener;
import com.bingbaihanji.bdec.emit.SourceEmitter;
import com.bingbaihanji.bdec.emit.SourceFile;
import com.bingbaihanji.bdec.ir.CopyPropagation;
import com.bingbaihanji.bdec.ir.DeadCodeElimination;
import com.bingbaihanji.bdec.ir.IrBuilder;
import com.bingbaihanji.bdec.ir.IrInstruction;
import com.bingbaihanji.bdec.ir.LinearIr;
import com.bingbaihanji.bdec.ir.SsaBuilder;
import com.bingbaihanji.bdec.ir.SsaForm;
import com.bingbaihanji.bdec.ir.TypeInference;
import com.bingbaihanji.bdec.semantic.SemanticReconstructor;
import com.bingbaihanji.bdec.structuring.ControlFlowStructurer;
import com.bingbaihanji.bdec.structuring.StructuredMethod;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    /** 控制流图构建器 */
    private final CfgBuilder cfgBuilder = new CfgBuilder();

    /** 中间表示(IR)构建器 */
    private final IrBuilder irBuilder = new IrBuilder();

    /** 控制流结构化器,将 IR 转为结构化方法 */
    private final ControlFlowStructurer structurer = new ControlFlowStructurer();

    /** SSA(静态单赋值)构建器 */
    private final SsaBuilder ssaBuilder = new SsaBuilder();

    /** 类型推断器 */
    private final TypeInference typeInference = new TypeInference();

    /** 复制传播优化器 */
    private final CopyPropagation copyPropagation = new CopyPropagation();

    /** 死代码消除器 */
    private final DeadCodeElimination dce = new DeadCodeElimination();

    /** 语义重建器,负责恢复高级语义信息 */
    private final SemanticReconstructor semanticReconstructor = new SemanticReconstructor();

    /** 抽象语法树(AST)构建器 */
    private final AstBuilder astBuilder = new AstBuilder();

    /** AST 重写器,包含多个重写规则,按顺序应用 */
    private final AstRewriter astRewriter = new AstRewriter(
            List.of(new RecordRewriter(), new SealedClassRewriter(),
                    new LambdaRewriter(), new MethodRefRewriter(),
                    new StringConcatRewriter(), new TextBlockRewriter(),
                    new ForEachRewriter(), new TryResourceRewriter(),
                    new SwitchExprRewriter(), new PatternMatchRewriter(),
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
    }

    /** 检查简单类名是否为匿名类或局部类($ 后紧跟数字) */
    private static boolean isAnonymousOrLocalClass(String simpleName) {
        int idx = simpleName.lastIndexOf('$');
        if (idx >= 0 && idx + 1 < simpleName.length()) {
            char c = simpleName.charAt(idx + 1);
            return c >= '0' && c <= '9';
        }
        return false;
    }

    @Override
    public String getName() {return "bdec";}

    @Override
    public String getVersion() {return "0.1.0";}

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

            // ===== 阶段 2-4:逐方法反编译 =====
            List<StructuredMethod> structuredMethods = new ArrayList<>();
            for (MethodModel method : classFile.methods()) {
                if (method.isAbstract() || method.isNative()) {
                    // 保留抽象方法和本地方法声明(无方法体,无需 IR)
                    structuredMethods.add(new StructuredMethod(method, null, null));
                    continue;
                }
                if (method.instructions() == null || method.instructions().isEmpty()) {
                    continue;
                }

                try {
                    // 阶段 2:构建控制流图(CFG)
                    ControlFlowGraph cfg = cfgBuilder.build(method);

                    // 阶段 3:生成线性中间表示(LinearIr)
                    LinearIr ir = irBuilder.build(cfg, method, classFile.constantPool(),
                            classFile.bootstrapMethods());

                    // 阶段 3.5:语义重建
                    ir = semanticReconstructor.reconstruct(ir, method, cfg, classFile);

                    // 阶段 3b:SSA 构建 + 类型推断 + 优化
                    if (config.ssaThreshold() > 0 && ir.instructions().size() >= config.ssaThreshold()) {
                        try {
                            SsaForm ssa = ssaBuilder.build(ir);
                            Map<Integer, com.bingbaihanji.bdec.type.JavaType> inferred =
                                    typeInference.infer(ssa);
                            // 对 SSA 指令执行死代码消除和复制传播优化
                            List<IrInstruction> optimized =
                                    dce.eliminate(copyPropagation.propagate(ssa.instructions()));
                            ir = new LinearIr(method, cfg, optimized, ir.variables());
                            ir.setSsaOptimized(true);
                            diagnostics.report(DecompilerDiagnostic.info("ssa", internalName,
                                    "SSA: " + ssa.instructions().size() + " insns ("
                                            + ssa.varVersionCount().size() + " vars)"));
                        } catch (Exception e) {
                            diagnostics.report(DecompilerDiagnostic.warning("ssa", internalName,
                                    method.name() + method.descriptor(), -1,
                                    "SSA construction failed: " + e.getMessage()));
                        }
                    }

                    // 阶段 4:结构化控制流
                    StructuredMethod sm = structurer.structure(ir, context);
                    structuredMethods.add(sm);
                } catch (Exception e) {
                    diagnostics.report(DecompilerDiagnostic.warning("structuring",
                            internalName, method.name() + method.descriptor(),
                            -1, "failed to decompile method: " + e.getMessage()));
                }
            }

            // 阶段 5:构建抽象语法树(AST)
            CompilationUnit unit = astBuilder.build(classFile, structuredMethods, context);

            // 阶段 5b:应用 AST 重写规则
            unit = astRewriter.rewrite(unit, config, context);

            // 阶段 5c:反编译内部类(成员内部类,非匿名/局部类)
            unit = decompileInnerClasses(unit, classFile, context, new HashSet<>());

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

    /**
     * 反编译成员内部类,将其 TypeDeclaration 追加到编译单元中.
     *
     * <p>仅处理成员内部类(非匿名类,非局部类,非枚举).
     * 匿名类和局部类在字节码中的名称以 $数字 开头(如 TestClass2$1),
     * 在 Java 源码中没有直接的名称,需要特殊处理(内联匿名类体).</p>
     */
    private CompilationUnit decompileInnerClasses(CompilationUnit unit,
                                                  ClassFileModel classFile,
                                                  DecompileContext context,
                                                  Set<String> processed) {
        // 防止无限递归
        if (!processed.add(classFile.internalName())) {
            return unit;
        }

        List<InnerClassEntry> innerClasses = classFile.innerClasses();
        if (innerClasses.isEmpty()) {
            return unit;
        }

        List<com.bingbaihanji.bdec.ast.TypeDeclaration> allTypes = new ArrayList<>(unit.types());

        for (InnerClassEntry ice : innerClasses) {
            String innerName = ice.innerClass();
            if (innerName == null) {
                continue;
            }

            // 仅处理当前类的内部类
            // 成员内部类:outerClass 指向外围类
            // 局部类和匿名类:outerClass 为 null,通过名称前缀判断归属
            String outerName = ice.outerClass();
            boolean isMemberOfThis = outerName != null
                    && outerName.equals(classFile.internalName());
            boolean isInnerOfThis = outerName == null
                    && innerName.startsWith(classFile.internalName() + "$");
            if (!isMemberOfThis && !isInnerOfThis) {
                continue;
            }

            // 匿名类的 inner_name_index 为 0(null),无源码友好名称.
            // 将其作为嵌套类型反编译(使用字节码名称如 TestClass2$1),
            // 以确保类型引用可解析.后续改进将实现匿名类体内联.
            // 例外:枚举常量的匿名类体由 EnumRewriter 处理,不在此反编译.
            boolean isAnonymous = ice.simpleName() == null
                    || ice.simpleName().isEmpty();
            if (isAnonymous && (classFile.accessFlags() & 0x4000) != 0) {
                // 跳过枚举外围类中的匿名类(由 EnumRewriter 处理)
                continue;
            }

            // 跳过枚举(由 EnumRewriter 处理)
            if ((ice.accessFlags() & 0x2000) != 0) {
                continue;
            }

            // 跳过已添加到主类中的类型
            boolean alreadyPresent = allTypes.stream()
                    .anyMatch(td -> innerName.endsWith("/" + td.simpleName())
                            || innerName.endsWith("$" + td.simpleName()));
            if (alreadyPresent) {
                continue;
            }

            // 加载内部类字节码
            byte[] innerBytes = context.loadClassBytes(innerName);
            if (innerBytes == null) {
                continue;
            }

            try {
                // 为内部类创建新的反编译上下文
                DecompileContext innerCtx = new DecompileContext(
                        context.config(), context::loadClassBytes);

                // 解析并反编译内部类,将其作为嵌套类型嵌入主类
                ClassFileModel innerCfm = classReader.read(innerName, innerBytes);
                CompilationUnit innerUnit = buildInnerClassUnit(innerCfm, innerCtx);
                if (innerUnit != null && !innerUnit.types().isEmpty()) {
                    // 将内部类 TypeDeclaration 作为嵌套类型嵌入主类
                    com.bingbaihanji.bdec.ast.TypeDeclaration innerType = innerUnit.types().getFirst();
                    // 去掉 public 修饰符(嵌套类不需要自己的文件)
                    int flags = innerType.accessFlags() & ~0x0001;
                    com.bingbaihanji.bdec.ast.TypeDeclaration nestedType =
                            new com.bingbaihanji.bdec.ast.TypeDeclaration(
                                    flags, innerType.simpleName(), innerType.kindName(),
                                    innerType.superName(), innerType.interfaceNames(),
                                    innerType.typeParameters(), innerType.children());
                    // 将嵌套类型添加到主类的成员列表中
                    com.bingbaihanji.bdec.ast.TypeDeclaration mainType = allTypes.getFirst();
                    List<com.bingbaihanji.bdec.ast.AstNode> mainMembers = new ArrayList<>(mainType.children());
                    mainMembers.add(nestedType);
                    allTypes.set(0, new com.bingbaihanji.bdec.ast.TypeDeclaration(
                            mainType.accessFlags(), mainType.simpleName(), mainType.kindName(),
                            mainType.superName(), mainType.interfaceNames(),
                            mainType.typeParameters(), mainMembers));
                }
            } catch (Exception e) {
                diagnostics.report(DecompilerDiagnostic.warning("inner",
                        classFile.internalName(), null, -1,
                        "failed to decompile inner class " + innerName + ": " + e.getMessage()));
            }
        }

        return new CompilationUnit(unit.packageName(), unit.imports(),
                allTypes, unit.innerClassNames());
    }

    /** 为内部类构建 CompilationUnit(仅类型声明,跳过源代码生成) */
    private CompilationUnit buildInnerClassUnit(ClassFileModel cfm, DecompileContext ctx) {
        try {
            List<StructuredMethod> methods = new ArrayList<>();
            for (MethodModel method : cfm.methods()) {
                if (method.isAbstract() || method.isNative()) {
                    methods.add(new StructuredMethod(method, null, null));
                    continue;
                }
                if (method.instructions() == null || method.instructions().isEmpty()) {
                    continue;
                }
                try {
                    ControlFlowGraph cfg = cfgBuilder.build(method);
                    LinearIr ir = irBuilder.build(cfg, method, cfm.constantPool(),
                            cfm.bootstrapMethods());
                    ir = semanticReconstructor.reconstruct(ir, method, cfg, cfm);
                    StructuredMethod sm = structurer.structure(ir, ctx);
                    methods.add(sm);
                } catch (Exception e) {
                    // 跳过反编译失败的方法
                }
            }
            CompilationUnit innerUnit = astBuilder.build(cfm, methods, ctx);
            return astRewriter.rewrite(innerUnit, config, ctx);
        } catch (Exception e) {
            return null;
        }
    }
}
