package com.bingbaihanji.bdec.emit;

import com.bingbaihanji.bdec.BdecConfig;
import com.bingbaihanji.bdec.ast.AstNode;
import com.bingbaihanji.bdec.ast.CompilationUnit;
import com.bingbaihanji.bdec.ast.TypeDeclaration;
import com.bingbaihanji.bdec.ast.stmt.Statement;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 源代码发射器,将编译单元(CompilationUnit)的 AST 输出为完整的 Java 源文件文本.
 * 负责输出包声明,导入语句,类型声明(含修饰符,泛型参数,继承关系等),
 * 并将成员委托给 StatementEmitter 处理.
 */
public class SourceEmitter {

    /**
     * 裁剪未使用的导入语句:仅当导入的简单类名在类型体文本中以单词形式出现时才保留.
     * <p>
     * 反编译过程中,后续重写器可能消除某个类型的所有用法(如 ForEachRewriter 把
     * {@code Iterator} 循环还原为增强 for-each),使对应的 import 变成死导入,这里据此清除.
     * 保守策略:无法静态判定时(通配符导入)一律保留,避免误删仍在使用的导入.
     */
    private static List<String> pruneUnusedImports(List<String> imports, String bodyText) {
        if (imports.isEmpty()) {
            return imports;
        }
        List<String> pruned = new ArrayList<>();
        for (String imp : imports) {
            String simple = simpleNameOf(imp);
            if (simple.isEmpty() || "*".equals(simple) || containsWord(bodyText, simple)) {
                pruned.add(imp);
            }
        }
        return pruned;
    }

    /** 取全限定名的最后一段(简单类名),如 {@code java.util.Map.Entry} → {@code Entry}. */
    private static String simpleNameOf(String importName) {
        int dot = importName.lastIndexOf('.');
        return dot < 0 ? importName : importName.substring(dot + 1);
    }

    /** 判断文本中是否包含指定标识符(单词边界匹配,避免子串误判,如 Map 不匹配 HashMap). */
    private static boolean containsWord(String text, String word) {
        return Pattern.compile("\\b" + Pattern.quote(word) + "\\b").matcher(text).find();
    }

    /**
     * 将编译单元 AST 发射为源代码文件.
     *
     * @param unit   编译单元 AST 节点
     * @param config 反编译配置
     * @return 生成的源文件对象,包含类名,源代码文本和行映射
     */
    public SourceFile emit(CompilationUnit unit, BdecConfig config) {
        Map<Integer, Integer> lineMapping = new HashMap<>();

        // module-info.class:输出模块声明(无包声明/导入/类型声明)
        if (unit.module() != null) {
            IndentWriter w = new IndentWriter(config.indentSize());
            emitModule(unit.module(), w);
            return new SourceFile("module-info", w.toString(), lineMapping);
        }

        // 两遍发射:先渲染类型体,据此裁剪未使用的 import
        // (如 for-each 重建消除 Iterator 用法后残留的 import java.util.Iterator;)
        IndentWriter bodyW = new IndentWriter(config.indentSize());
        ExpressionEmitter exprs = new ExpressionEmitter(bodyW, unit.packageName(), unit.imports());
        exprs.setInnerClassNames(unit.innerClassNames());

        // 判断是否为接口类型
        boolean isInterface = !unit.types().isEmpty() && unit.types().getFirst().isInterface();
        // 创建语句发射器,传入首类型的简单类名和接口标记
        String kind = unit.types().isEmpty() ? "class" : unit.types().getFirst().kindName();
        boolean isAnnotation = "@interface".equals(kind);
        StatementEmitter stmts = new StatementEmitter(bodyW, exprs, unit.types().isEmpty()
                ? "Unknown" : unit.types().getFirst().simpleName(), isInterface,
                isAnnotation);

        // 将语句发射器注入表达式发射器,用于 lambda 块体等需要语句级别输出的场景
        exprs.setStmtEmitter(stmts);

        // 输出所有类型声明(到临时缓冲区)
        for (TypeDeclaration type : unit.types()) {
            emitType(type, bodyW, stmts);
        }
        String bodyText = bodyW.toString();

        // 正式输出:包声明 + 裁剪后的导入 + 类型体
        IndentWriter w = new IndentWriter(config.indentSize());

        if (unit.packageName() != null && !unit.packageName().isEmpty()) {
            w.token("package").space().write(unit.packageName()).write(';');
            w.newLine().newLine();
        }

        List<String> imports = pruneUnusedImports(unit.imports(), bodyText);
        if (!imports.isEmpty()) {
            for (String imp : imports) {
                w.token("import").space().write(imp).write(';').newLine();
            }
            w.newLine();
        }

        w.write(bodyText);

        // 构建完整类名(包名 + 简单类名)
        String className = unit.types().isEmpty() ? "Unknown"
                : unit.packageName() != null && !unit.packageName().isEmpty()
                ? unit.packageName() + "." + unit.types().getFirst().simpleName()
                : unit.types().getFirst().simpleName();

        return new SourceFile(className, w.toString(), lineMapping);
    }

    /**
     * 发射模块声明(module-info.java).
     *
     * @param mod 模块声明节点
     * @param w   缩进写入器
     */
    private void emitModule(com.bingbaihanji.bdec.ast.ModuleDeclaration mod, IndentWriter w) {
        if (mod.isOpen()) {
            w.token("open").space();
        }
        w.token("module").space().write(mod.name());
        if (mod.version() != null) {
            // JLS 9 §7.7:模块声明可带版本,形如 "module m @ 9.0 {"
            w.space().write("@").space().write(mod.version());
        }
        w.space().write("{").newLine();
        w.indent();
        for (var r : mod.requires()) {
            w.token("requires").space();
            if (r.transitive()) {
                w.token("transitive").space();
            }
            if (r.staticPhase()) {
                w.token("static").space();
            }
            w.write(r.module()).write(";").newLine();
        }
        for (var e : mod.exports()) {
            w.token("exports").space().write(e.packageName());
            emitToModules(e.toModules(), w);
            w.write(";").newLine();
        }
        for (var o : mod.opens()) {
            w.token("opens").space().write(o.packageName());
            emitToModules(o.toModules(), w);
            w.write(";").newLine();
        }
        for (String u : mod.uses()) {
            w.token("uses").space().write(u).write(";").newLine();
        }
        for (var p : mod.provides()) {
            w.token("provides").space().write(p.service()).space()
                    .token("with").space()
                    .write(String.join(", ", p.withImplementations()))
                    .write(";").newLine();
        }
        w.dedent();
        w.write("}").newLine();
    }

    /** 发射 exports/opens 子句的 to 目标模块列表(空列表表示无限制). */
    private void emitToModules(java.util.List<String> toModules, IndentWriter w) {
        if (!toModules.isEmpty()) {
            w.space().token("to").space().write(String.join(", ", toModules));
        }
    }

    /**
     * 发射单个类型声明,包括修饰符,类名,泛型参数,继承关系和成员.
     *
     * @param type  类型声明节点
     * @param w     缩进写入器
     * @param stmts 语句发射器
     */
    private void emitType(TypeDeclaration type, IndentWriter w, StatementEmitter stmts) {
        // 输出类级注解(如 @Retention(RetentionPolicy.RUNTIME))
        for (String ann : type.annotations()) {
            w.write(ann).newLine();
        }
        // 输出访问修饰符
        ModifierRenderer.emitClassModifiers(type.accessFlags(), type.isInterface(),
                "enum".equals(type.kindName()), w);

        w.token(type.kindName()).space().write(type.simpleName());

        // 输出泛型类型参数,或 record 的组件参数
        if (!type.typeParameters().isEmpty()) {
            boolean isRecord = "record".equals(type.kindName());
            w.write(isRecord ? "(" : "<");
            w.write(String.join(", ", type.typeParameters()));
            w.write(isRecord ? ")" : ">");
        }

        // 输出父类(父类型 JSR-308 注解内联在 extends 之后,如 "extends @A Parent")
        if (type.superName() != null) {
            w.space().token("extends").space();
            for (String ann : type.superAnnotations()) {
                w.write(ann).space();
            }
            w.write(type.superName());
        }

        // 输出实现的接口(接口级 JSR-308 注解内联在接口名之前,
        // 如 "implements @A Runnable, Serializable")
        if (!type.interfaceNames().isEmpty()) {
            w.space().token(type.isInterface() ? "extends" : "implements").space();
            java.util.List<String> ifAnns = type.interfaceAnnotations();
            for (int i = 0; i < type.interfaceNames().size(); i++) {
                if (i > 0) {
                    w.write(", ");
                }
                if (i < ifAnns.size()) {
                    String ann = ifAnns.get(i);
                    if (ann != null && !ann.isEmpty()) {
                        w.write(ann).space();
                    }
                }
                w.write(type.interfaceNames().get(i));
            }
        }

        // 输出密封类 permits 子句(如 " permits Circle, Square")
        if (!type.permitsNames().isEmpty()) {
            w.space().token("permits").space();
            w.write(String.join(", ", type.permitsNames()));
        }

        w.space().write("{").newLine();
        w.indent();

        // 对于嵌套类型,使用新的 StatementEmitter 以确保构造函数检测
        // 使用正确的类名(而非外层类的类名)
        StatementEmitter nestedStmts = stmts;
        if (!type.simpleName().equals(stmts.className())) {
            nestedStmts = new StatementEmitter(w, stmts.exprs(),
                    type.simpleName(), type.isInterface(),
                    "@interface".equals(type.kindName()));
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

}
