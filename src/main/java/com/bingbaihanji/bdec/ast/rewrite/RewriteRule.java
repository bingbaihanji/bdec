package com.bingbaihanji.bdec.ast.rewrite;

import com.bingbaihanji.bdec.DecompileContext;
import com.bingbaihanji.bdec.ast.AstNode;
import com.bingbaihanji.bdec.ast.CompilationUnit;
import com.bingbaihanji.bdec.ast.TypeAnnotationSet;
import com.bingbaihanji.bdec.ast.TypeDeclaration;
import com.bingbaihanji.bdec.ast.expr.Expression;
import com.bingbaihanji.bdec.ast.stmt.FieldDeclaration;
import com.bingbaihanji.bdec.ast.stmt.LoopStatement;
import com.bingbaihanji.bdec.ast.stmt.MethodDeclaration;
import com.bingbaihanji.bdec.ast.stmt.Statement;
import com.bingbaihanji.bdec.bytecode.model.TypePathElement;
import com.bingbaihanji.bdec.type.JavaType;

import java.util.List;
import java.util.Map;

/**
 * 重写规则接口,定义 AST 重写规则的标准契约.
 * <p>
 * 所有具体的重写规则(如字符串拼接还原,三元表达式还原等)均实现此接口,
 * 通过 {@link #rewrite(CompilationUnit, DecompileContext)} 方法对编译单元进行 AST 级别的变换.
 * </p>
 *
 * <p><b>元数据保持重建助手</b>(参考 CFR 的 StructuredStatementTransformer
 * 与 Vineflower 的 copy-with 模式):重写器应只修改自己关心的部分
 * (方法体/成员/初始化器/循环体),其余元数据(注解、类型参数、throws、泛型等)
 * 通过以下 {@code withXxx} 助手原样保留. AST 节点新增字段时,
 * 只需更新这些助手的实现——不再需要逐个改动重写器的重建调用点.
 * 历史上 annotationDefault / annotations / parameterAnnotations 三个字段
 * 曾各自破坏全部 ~21 处 MethodDeclaration 重建点,这些助手就是根治方案.</p>
 */
public interface RewriteRule {

    /**
     * 获取该重写规则的名称标识.
     *
     * @return 规则名称,用于日志输出和调试追踪
     */
    String name();

    /**
     * 获取该重写规则的调度类别,用于配置开关分发.
     *
     * @return 规则类别,默认为 {@link RewriteRuleKind#ALWAYS_ON}
     */
    default RewriteRuleKind kind() {return RewriteRuleKind.ALWAYS_ON;}

    /**
     * 获取该重写规则的描述信息.
     *
     * @return 规则描述文本,默认为空字符串
     */
    default String description() {return "";}

    /**
     * 对给定的编译单元执行重写操作.
     *
     * @param unit    待重写的编译单元 AST
     * @param context 反编译上下文,提供类加载,配置等环境信息
     * @return 重写后的编译单元 AST
     */
    CompilationUnit rewrite(CompilationUnit unit, DecompileContext context);

    // ── 元数据保持重建助手 ──

    /** 仅替换方法体,保留注解/类型参数/throws/参数注解等全部元数据. */
    default MethodDeclaration withBody(MethodDeclaration md, Statement newBody) {
        return new MethodDeclaration(md.accessFlags(), md.name(), md.returnType(),
                md.parameterNames(), md.parameterTypes(), md.typeParameters(),
                md.throwsTypes(), md.annotationDefault(), md.annotations(),
                md.parameterAnnotations(), md.typeAnnotations(), newBody);
    }

    /**
     * 替换方法体与参数列表(用于移除合成参数的场景,如枚举构造器的
     * name/ordinal、局部类的 this$0).调用方需同时提供裁剪后的参数注解
     * 与类型注解数组(与裁剪后的参数列表对齐).
     */
    default MethodDeclaration withParamsAndBody(MethodDeclaration md,
                                                String[] names, JavaType[] types,
                                                String[] paramAnns, Statement newBody) {
        return withParamsAndBody(md, names, types, paramAnns,
                md.typeAnnotations().onParameters(), newBody);
    }

    /**
     * 替换方法体与参数列表,并显式指定裁剪后的参数类型注解
     * (枚举构造器丢弃前 2 参、局部类丢弃 this$0 时需移位或过滤).
     */
    default MethodDeclaration withParamsAndBody(MethodDeclaration md,
                                                String[] names, JavaType[] types,
                                                String[] paramAnns,
                                                List<Map<List<TypePathElement>, List<String>>> paramTypeAnns,
                                                Statement newBody) {
        TypeAnnotationSet tas = new TypeAnnotationSet(md.typeAnnotations().onType(),
                paramTypeAnns, md.typeAnnotations().onThrows());
        return new MethodDeclaration(md.accessFlags(), md.name(), md.returnType(),
                names, types, md.typeParameters(), md.throwsTypes(),
                md.annotationDefault(), md.annotations(), paramAnns, tas, newBody);
    }

    /** 仅替换类型成员,保留注解/父类/接口/类型参数等全部元数据. */
    default TypeDeclaration withMembers(TypeDeclaration td, List<AstNode> newMembers) {
        return new TypeDeclaration(td.accessFlags(), td.simpleName(), td.kindName(),
                td.superName(), td.interfaceNames(), td.typeParameters(),
                newMembers, td.annotations(), td.superAnnotations(),
                td.interfaceAnnotations());
    }

    /** 替换类型成员并修改访问标志(用于 RecordRewriter/SealedClassRewriter 的标志位清理). */
    default TypeDeclaration withMembersAndFlags(TypeDeclaration td, int flags,
                                                List<AstNode> newMembers) {
        return new TypeDeclaration(flags, td.simpleName(), td.kindName(),
                td.superName(), td.interfaceNames(), td.typeParameters(),
                newMembers, td.annotations(), td.superAnnotations(),
                td.interfaceAnnotations());
    }

    /** 仅替换字段初始化器,保留字段注解与类型注解等元数据. */
    default FieldDeclaration withInitializer(FieldDeclaration fd, Expression newInit) {
        return new FieldDeclaration(fd.accessFlags(), fd.name(), fd.type(),
                newInit, fd.annotations(), fd.typeAnnotations());
    }

    /**
     * 仅替换循环体,保留循环类型与 for 循环的 init/cond/incr、
     * for-each 的变量与元素类型等全部元数据.
     */
    default LoopStatement withLoopBody(LoopStatement l, Statement newBody) {
        if (l.loopKind() == LoopStatement.LoopKind.FOR_EACH) {
            return new LoopStatement(l.loopKind(), l.forEachVar(), l.condition(),
                    newBody, l.forEachVarType());
        }
        if (l.initExpr() != null || l.incrExpr() != null) {
            return new LoopStatement(l.loopKind(), l.initExpr(), l.condition(),
                    l.incrExpr(), newBody);
        }
        return new LoopStatement(l.loopKind(), l.condition(), newBody);
    }
}
