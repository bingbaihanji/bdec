package com.bingbaihanji.bdec.emit;

import com.bingbaihanji.bdec.BdecConfig;
import com.bingbaihanji.bdec.ast.AstNode;
import com.bingbaihanji.bdec.ast.CompilationUnit;
import com.bingbaihanji.bdec.ast.TypeDeclaration;
import com.bingbaihanji.bdec.ast.stmt.Statement;

import java.util.HashMap;
import java.util.Map;

/**
 * 源代码发射器,将编译单元(CompilationUnit)的 AST 输出为完整的 Java 源文件文本.
 * 负责输出包声明,导入语句,类型声明(含修饰符,泛型参数,继承关系等),
 * 并将成员委托给 StatementEmitter 处理.
 */
public class SourceEmitter {

    /**
     * 将编译单元 AST 发射为源代码文件.
     *
     * @param unit   编译单元 AST 节点
     * @param config 反编译配置
     * @return 生成的源文件对象,包含类名,源代码文本和行映射
     */
    public SourceFile emit(CompilationUnit unit, BdecConfig config) {
        IndentWriter w = new IndentWriter(config.indentSize());
        Map<Integer, Integer> lineMapping = new HashMap<>();

        ExpressionEmitter exprs = new ExpressionEmitter(w, unit.imports());
        exprs.setInnerClassNames(unit.innerClassNames());

        // 判断是否为接口类型
        boolean isInterface = !unit.types().isEmpty() && unit.types().getFirst().isInterface();
        // 创建语句发射器,传入首类型的简单类名和接口标记
        StatementEmitter stmts = new StatementEmitter(w, exprs, unit.types().isEmpty()
                ? "Unknown" : unit.types().getFirst().simpleName(), isInterface);

        // 将语句发射器注入表达式发射器,用于 lambda 块体等需要语句级别输出的场景
        exprs.setStmtEmitter(stmts);

        // 输出包声明
        if (unit.packageName() != null && !unit.packageName().isEmpty()) {
            w.token("package").space().write(unit.packageName()).write(';');
            w.newLine().newLine();
        }

        // 输出导入语句
        if (!unit.imports().isEmpty()) {
            for (String imp : unit.imports()) {
                w.token("import").space().write(imp).write(';').newLine();
            }
            w.newLine();
        }

        // 输出所有类型声明
        for (TypeDeclaration type : unit.types()) {
            emitType(type, w, stmts);
        }

        // 构建完整类名(包名 + 简单类名)
        String className = unit.types().isEmpty() ? "Unknown"
                : unit.packageName() != null && !unit.packageName().isEmpty()
                ? unit.packageName() + "." + unit.types().getFirst().simpleName()
                : unit.types().getFirst().simpleName();

        return new SourceFile(className, w.toString(), lineMapping);
    }

    /**
     * 发射单个类型声明,包括修饰符,类名,泛型参数,继承关系和成员.
     *
     * @param type  类型声明节点
     * @param w     缩进写入器
     * @param stmts 语句发射器
     */
    private void emitType(TypeDeclaration type, IndentWriter w, StatementEmitter stmts) {
        // 输出访问修饰符
        emitClassModifiers(type.accessFlags(), type.isInterface(), w);

        w.token(type.kindName()).space().write(type.simpleName());

        // 输出泛型类型参数,或 record 的组件参数
        if (!type.typeParameters().isEmpty()) {
            boolean isRecord = "record".equals(type.kindName());
            w.write(isRecord ? "(" : "<");
            w.write(String.join(", ", type.typeParameters()));
            w.write(isRecord ? ")" : ">");
        }

        // 输出父类
        if (type.superName() != null) {
            w.space().token("extends").space().write(type.superName());
        }

        // 输出实现的接口
        if (!type.interfaceNames().isEmpty()) {
            w.space().token(type.isInterface() ? "extends" : "implements").space();
            w.write(String.join(", ", type.interfaceNames()));
        }

        w.space().write("{").newLine();
        w.indent();

        // 对于嵌套类型,使用新的 StatementEmitter 以确保构造函数检测
        // 使用正确的类名(而非外层类的类名)
        StatementEmitter nestedStmts = stmts;
        if (!type.simpleName().equals(stmts.className())) {
            nestedStmts = new StatementEmitter(w, stmts.exprs(),
                    type.simpleName(), type.isInterface());
        }

        boolean firstMember = true;
        for (AstNode member : type.children()) {
            // 将枚举常量作为逗号分隔的列表输出(字段名为特殊标记 $enumConstants$)
            if (member instanceof com.bingbaihanji.bdec.ast.stmt.FieldDeclaration fd
                    && "$enumConstants$".equals(fd.name())) {
                if (fd.initializer() instanceof com.bingbaihanji.bdec.ast.expr.VarExpr ve) {
                    w.write(ve.name()).newLine();
                }
                continue;
            }
            if (member instanceof Statement s) {
                nestedStmts.emit(s);
            } else if (member instanceof TypeDeclaration nestedType) {
                // 输出嵌套类型(内部类,内部接口等)
                if (!firstMember) {
                    w.newLine();
                }
                emitType(nestedType, w, nestedStmts);
            } else {
                w.write("// " + member.kind()).newLine();
            }
            firstMember = false;
        }

        w.dedent();
        w.write("}").newLine();
    }

    /**
     * 根据访问标志输出类/接口的修饰符关键字.
     * 使用 JVM 规范的 ACC_* 常量进行位判断.
     *
     * @param flags       访问标志位掩码
     * @param isInterface 是否为接口(接口不输出 abstract 修饰符)
     * @param w           缩进写入器
     */
    private void emitClassModifiers(int flags, boolean isInterface, IndentWriter w) {
        // 0x0001 = ACC_PUBLIC
        if ((flags & 0x0001) != 0) {
            w.token("public").space();
        } else if ((flags & 0x0002) != 0) {
            // 0x0002 = ACC_PRIVATE
            w.token("private").space();
        } else if ((flags & 0x0004) != 0) {
            // 0x0004 = ACC_PROTECTED
            w.token("protected").space();
        }
        // 0x0400 = ACC_ABSTRACT,接口隐式为 abstract,不重复输出该关键字
        if ((flags & 0x0400) != 0 && !isInterface) {
            w.token("abstract").space();
        }
        // 0x0010 = ACC_FINAL
        if ((flags & 0x0010) != 0) {
            w.token("final").space();
        }
        // 0x0020 = ACC_SUPER,非 ACC_STATIC.ACC_SUPER 在所有现代 class 文件中均设置,
        // 不应作为修饰符输出.
        // ACC_STATIC = 0x0008,仅适用于嵌套类.
        if ((flags & 0x0008) != 0) {
            w.token("static").space();
        }
    }
}
