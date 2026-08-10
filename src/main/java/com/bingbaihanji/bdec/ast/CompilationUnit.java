package com.bingbaihanji.bdec.ast;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 编译单元节点.
 * <p>
 * 表示一个Java源文件(.java文件)的AST顶层节点,包含包声明,导入语句列表
 * 以及其中定义的所有类型声明(类,接口,枚举等).一个编译单元通常对应
 * 一个被反编译的class文件输出的完整Java源文件.
 * </p>
 * @param packageName  包名,空字符串表示默认包
 * @param imports  导入语句列表(全限定类名)
 * @param types  该编译单元中包含的所有类型声明
 * @param innerClassNames  内部类友好名称映射:简单内部名称(如 TestClass2$1LocalClass) → 友好名称(如 LocalClass)
 */
public record CompilationUnit(String packageName, List<String> imports, List<TypeDeclaration> types,
                              Map<String, String> innerClassNames) implements AstNode {

    /**
     * 构造一个编译单元.
     *
     * @param pkg  包名
     * @param imps 导入列表
     * @param ts   类型声明列表
     */
    public CompilationUnit(String pkg, List<String> imps, List<TypeDeclaration> ts) {
        this(pkg, imps, ts, Collections.emptyMap());
    }

    /**
     * 构造一个编译单元(含内部类名称映射).
     *
     * @param packageName             包名
     * @param imports            导入列表
     * @param types              类型声明列表
     * @param innerClassNames 内部类友好名称映射
     */
    public CompilationUnit(String packageName, List<String> imports, List<TypeDeclaration> types,
                           Map<String, String> innerClassNames) {
        this.packageName = packageName;
        this.imports = List.copyOf(imports);
        this.types = List.copyOf(types);
        this.innerClassNames = Map.copyOf(innerClassNames);
    }

    /** @return 包名 */
    @Override
    public String packageName() {return packageName;}

    /** @return 导入列表(不可变) */
    @Override
    public List<String> imports() {return imports;}

    /** @return 类型声明列表(不可变) */
    @Override
    public List<TypeDeclaration> types() {return types;}

    /** @return 内部类友好名称映射 */
    @Override
    public Map<String, String> innerClassNames() {return innerClassNames;}

    @Override
    public AstKind kind() {return AstKind.COMPILATION_UNIT;}

    @Override
    public List<AstNode> children() {return List.copyOf(types);}

    @Override
    public <R, C> R accept(AstVisitor<R, C> v, C c) {return v.visit(this, c);}
}
