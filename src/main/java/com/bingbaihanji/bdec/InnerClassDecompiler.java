package com.bingbaihanji.bdec;

import com.bingbaihanji.bdec.ast.AstBuilder;
import com.bingbaihanji.bdec.ast.AstNode;
import com.bingbaihanji.bdec.ast.CompilationUnit;
import com.bingbaihanji.bdec.ast.TypeDeclaration;
import com.bingbaihanji.bdec.ast.rewrite.AstRewriter;
import com.bingbaihanji.bdec.bytecode.model.AccessFlags;
import com.bingbaihanji.bdec.bytecode.model.ClassFileModel;
import com.bingbaihanji.bdec.bytecode.model.constantpool.InnerClassEntry;
import com.bingbaihanji.bdec.bytecode.parser.ClassFileReader;
import com.bingbaihanji.bdec.decompiler.diagnostic.DecompilerDiagnostic;
import com.bingbaihanji.bdec.decompiler.diagnostic.DiagnosticListener;
import com.bingbaihanji.bdec.structuring.StructuredMethod;

import java.util.ArrayList;
import java.util.List;

/**
 * 成员内部类反编译器(里程碑 Phase 3).
 *
 * <p>反编译成员内部类,将其 {@link TypeDeclaration} 追加到编译单元中.
 * 仅处理成员内部类(非匿名类,非局部类,非枚举);匿名/局部类在字节码中
 * 名称以 $数字 开头,由后续重写器内联处理.</p>
 */
public final class InnerClassDecompiler {

    private final ClassFileReader classReader;

    private final MethodPipeline methodPipeline;

    private final AstBuilder astBuilder;

    private final AstRewriter astRewriter;

    private final BdecConfig config;

    private final DiagnosticListener diagnostics;

    /**
     * 构造成员内部类反编译器.
     */
    public InnerClassDecompiler(ClassFileReader classReader, MethodPipeline methodPipeline,
                                AstBuilder astBuilder, AstRewriter astRewriter,
                                BdecConfig config, DiagnosticListener diagnostics) {
        this.classReader = classReader;
        this.methodPipeline = methodPipeline;
        this.astBuilder = astBuilder;
        this.astRewriter = astRewriter;
        this.config = config;
        this.diagnostics = diagnostics;
    }

    /**
     * 反编译成员内部类,将其 TypeDeclaration 追加到编译单元中.
     *
     * @param unit      主类编译单元(内部类追加到此)
     * @param classFile 主类的 class 文件模型
     * @param context   反编译上下文
     * @return 追加了内部类 TypeDeclaration 的编译单元
     */
    public CompilationUnit decompileInnerClasses(CompilationUnit unit,
                                                 ClassFileModel classFile,
                                                 DecompileContext context) {
        List<InnerClassEntry> innerClasses = classFile.innerClasses();
        if (innerClasses.isEmpty()) {
            return unit;
        }

        List<TypeDeclaration> allTypes = new ArrayList<>(unit.types());

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
            if (isAnonymous && (classFile.accessFlags() & AccessFlags.ACC_ENUM) != 0) {
                // 跳过枚举外围类中的匿名类(由 EnumRewriter 处理)
                continue;
            }

            // 跳过枚举(由 EnumRewriter 处理)
            if ((ice.accessFlags() & AccessFlags.ACC_ANNOTATION) != 0) {
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
                // 解析并反编译内部类,将其作为嵌套类型嵌入主类
                ClassFileModel innerCfm = classReader.read(innerName, innerBytes);
                // 为内部类创建新的反编译上下文,并携带内部类自身的 ClassFileModel:
                // 否则 EnumRewriter 等依赖 ctx.classFile() 的规则(如扫描枚举自身
                // <clinit> 提取常量体映射)拿不到内部枚举的字节码模型,嵌套枚举的
                // 常量匿名体(覆写抽象方法的 E$N 类)会全部丢失.
                DecompileContext innerCtx = new DecompileContext(
                        context.config(), context::loadClassBytes,
                        innerCfm.bootstrapMethods(), innerCfm);
                CompilationUnit innerUnit = buildInnerClassUnit(innerCfm, innerCtx);
                if (innerUnit != null && !innerUnit.types().isEmpty()) {
                    // 将内部类 TypeDeclaration 作为嵌套类型嵌入主类
                    TypeDeclaration innerType = innerUnit.types().getFirst();
                    // 嵌套类的权威源码级标志位于外围类 InnerClasses 属性的条目中
                    // (嵌套类自身 class 文件的 access_flags 不含 ACC_STATIC/ACC_PRIVATE,
                    // 如 public static 嵌套类的 class 文件仅有 ACC_PUBLIC|ACC_SUPER).
                    // 可见性与 static 取自条目标志,其余(abstract/final/interface/record 等)取自 class 文件.
                    int flags = ice.accessFlags()
                            | (innerType.accessFlags()
                            & ~(AccessFlags.ACC_PUBLIC | AccessFlags.ACC_PRIVATE
                            | AccessFlags.ACC_PROTECTED | AccessFlags.ACC_STATIC));
                    // 枚举的 ACC_ABSTRACT 由 javac 在含抽象方法时隐式置位,但源码
                    // 中枚举常量体已实现抽象方法,不应输出 abstract(否则 "abstract
                    // enum" 非法)。EnumRewriter 已剥离,此处合并条目标志不得加回.
                    if ("enum".equals(innerType.kindName())) {
                        flags &= ~AccessFlags.ACC_ABSTRACT;
                    }
                    TypeDeclaration nestedType =
                            new TypeDeclaration(
                                    flags, innerType.simpleName(), innerType.kindName(),
                                    innerType.superName(), innerType.interfaceNames(),
                                    innerType.typeParameters(), innerType.children());
                    // 将嵌套类型添加到主类的成员列表中
                    TypeDeclaration mainType = allTypes.getFirst();
                    List<AstNode> mainMembers = new ArrayList<>(mainType.children());
                    mainMembers.add(nestedType);
                    allTypes.set(0, new TypeDeclaration(
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
            List<StructuredMethod> methods =
                    methodPipeline.decompileMethods(cfm, ctx, false, false);
            CompilationUnit innerUnit = astBuilder.build(cfm, methods, ctx);
            innerUnit = astRewriter.rewrite(innerUnit, config, ctx);
            // 递归处理该内部类自身的内部类(二级+嵌套,如 Host$Using).
            // 此前仅处理一级嵌套,二级的内部类(OuterClass 不是顶层类)被
            // decompileInnerClasses 的归属判断直接跳过,声明整个丢失.
            return decompileInnerClasses(innerUnit, cfm, ctx);
        } catch (Exception e) {
            return null;
        }
    }
}
