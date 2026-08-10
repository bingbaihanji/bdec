package com.bingbaihanji.bdec.ast;

import java.util.List;

/**
 * 编译单元节点.
 * <p>
 * 表示一个Java源文件(.java文件)的AST顶层节点,包含包声明,导入语句列表
 * 以及其中定义的所有类型声明(类,接口,枚举等).一个编译单元通常对应
 * 一个被反编译的class文件输出的完整Java源文件.
 * </p>
 */
public final class CompilationUnit implements AstNode {

    /** 包名,空字符串表示默认包 */
    private final String packageName;

    /** 导入语句列表(全限定类名) */
    private final List<String> imports;

    /** 该编译单元中包含的所有类型声明 */
    private final List<TypeDeclaration> types;

    /**
     * 构造一个编译单元.
     *
     * @param pkg  包名
     * @param imps 导入列表
     * @param ts   类型声明列表
     */
    public CompilationUnit(String pkg, List<String> imps, List<TypeDeclaration> ts) {
        packageName = pkg;
        imports = List.copyOf(imps);
        types = List.copyOf(ts);
    }

    /** @return 包名 */
    public String packageName() {return packageName;}

    /** @return 导入列表(不可变) */
    public List<String> imports() {return imports;}

    /** @return 类型声明列表(不可变) */
    public List<TypeDeclaration> types() {return types;}

    @Override
    public AstKind kind() {return AstKind.COMPILATION_UNIT;}

    @Override
    public List<AstNode> children() {return List.copyOf(types);}

    @Override
    public <R, C> R accept(AstVisitor<R, C> v, C c) {return v.visit(this, c);}
}
