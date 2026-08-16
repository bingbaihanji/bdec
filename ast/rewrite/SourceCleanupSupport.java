package com.bingbaihanji.bdec.ast.rewrite;

import com.bingbaihanji.bdec.ast.AstNode;
import com.bingbaihanji.bdec.ast.TypeDeclaration;
import com.bingbaihanji.bdec.ast.stmt.BlockStatement;
import com.bingbaihanji.bdec.ast.stmt.ExpressionStatement;
import com.bingbaihanji.bdec.ast.stmt.FieldDeclaration;
import com.bingbaihanji.bdec.ast.stmt.ReturnStatement;
import com.bingbaihanji.bdec.ast.stmt.Statement;
import com.bingbaihanji.bdec.ast.stmt.ThrowStatement;
import com.bingbaihanji.bdec.ast.stmt.VariableDeclaration;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * {@link SourceCleanup} 的纯静态支撑工具(里程碑 Phase 3).
 *
 * <p>承载字段名收集,名称分类,顺序块扁平化与终止语句死代码截断等
 * 无实例状态的辅助逻辑,与 {@link SourceCleanup} 主递归修复逻辑解耦.</p>
 */
final class SourceCleanupSupport {

    private SourceCleanupSupport() {
    }

    /**
     * 收集类型声明中的所有字段名称,用于防止变量自动声明时发生字段遮蔽.
     *
     * @param td 待收集的类型声明
     * @return 字段名称集合
     */
    static Set<String> collectFieldNames(TypeDeclaration td) {
        Set<String> names = new HashSet<>();
        for (AstNode m : td.children()) {
            if (m instanceof FieldDeclaration fd && fd.name() != null) {
                names.add(fd.name());
            }
        }
        return names;
    }

    /**
     * 检查名称是否看起来像类名(以大写字母开头).
     * 像 "Math" 或 "String" 这样的静态方法目标不应被自动声明为局部变量.
     *
     * @param name 待检查的名称
     * @return 若以大写字母开头返回 {@code true}
     */
    static boolean looksLikeClassName(String name) {
        return name != null && !name.isEmpty()
                && Character.isUpperCase(name.charAt(0));
    }

    /** 判断名称是否为类型名(类名以大写开头,限定名含点号,基本类型关键字).
     *  用于字段访问目标(如 System.out 的 System)与普通变量的区分.
     *  数组类型名(如 int[] 的 int[].class 目标)先剥离 "[]" 后缀再判断. */
    static boolean isTypeName(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        String base = name;
        while (base.endsWith("[]")) {
            base = base.substring(0, base.length() - 2);
        }
        if (base.isEmpty()) {
            return false;
        }
        if (Character.isUpperCase(base.charAt(0)) || base.indexOf('.') >= 0) {
            return true;
        }
        return switch (base) {
            case "int", "long", "float", "double", "boolean", "byte", "short", "char", "void" -> true;
            default -> false;
        };
    }

    /**
     * 扁平化纯顺序的嵌套块到父块中.
     *
     * <p>仅展开不含控制流(if/loop/try/return/throw)且内部仅为
     * 变量声明与表达式语句的直接子块.展开前提:被展开块声明的变量名
     * 不与父块其余位置声明的名字冲突——冲突时保守保留原结构.
     * 展开后后续语句可以看到块内声明(嵌套块是独立作用域).</p>
     */
    static List<Statement> flattenPlainNestedBlocks(List<Statement> stmts) {
        List<Statement> result = new ArrayList<>();
        for (Statement c : stmts) {
            if (c instanceof BlockStatement nested
                    && isPlainBlock(nested)
                    && !shadowedNames(nested, stmts)) {
                result.addAll(nested.statements());
            } else {
                result.add(c);
            }
        }
        return result;
    }

    /** 嵌套块是否仅含变量声明与表达式语句(无控制流). */
    static boolean isPlainBlock(BlockStatement bs) {
        for (Statement c : bs.statements()) {
            if (!(c instanceof VariableDeclaration)
                    && !(c instanceof ExpressionStatement)) {
                return false;
            }
        }
        return true;
    }

    /** 嵌套块声明的变量名是否与父块中其他位置声明的名字冲突(遮蔽). */
    static boolean shadowedNames(BlockStatement nested, List<Statement> parentStmts) {
        for (Statement c : nested.statements()) {
            if (!(c instanceof VariableDeclaration vd)) {
                continue;
            }
            for (Statement other : parentStmts) {
                if (other != nested && other instanceof VariableDeclaration ov
                        && vd.name().equals(ov.name())) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 截断 return/throw 之后的死代码.
     *
     * <p>记录模式去糖化和 MatchException 处理器抑制后,return 语句后
     * 可能遗留不可达语句,导致类型不匹配和重复声明错误.
     */
    static List<Statement> truncateAfterTerminator(List<Statement> stmts) {
        for (int i = 0; i < stmts.size(); i++) {
            Statement s = stmts.get(i);
            if (s instanceof ReturnStatement || s instanceof ThrowStatement) {
                if (i + 1 < stmts.size()) {
                    return new ArrayList<>(stmts.subList(0, i + 1));
                }
            }
        }
        return stmts;
    }
}
