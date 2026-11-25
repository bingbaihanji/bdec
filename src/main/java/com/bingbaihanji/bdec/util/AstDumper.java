package com.bingbaihanji.bdec.util;

import com.bingbaihanji.bdec.ast.AstNode;

import java.io.PrintStream;
import java.util.List;

/**
 * AST 调试打印工具,将抽象语法树以缩进文本形式输出.
 *
 * <p>每个节点输出为 "{@code kind 类简单名}",子节点相对父节点缩进 2 个空格.
 * 纯工具类,禁止实例化.</p>
 */
public final class AstDumper {

    /** 每层缩进的空格数 */
    private static final String INDENT = "  ";

    private AstDumper() {}

    /**
     * 将 AST 树打印到标准错误流.
     *
     * @param root 根节点,可为 null
     */
    public static void dump(AstNode root) {
        dump(root, System.err);
    }

    /**
     * 将 AST 树打印到指定输出流.
     *
     * @param root 根节点,可为 null(此时不输出任何内容)
     * @param out  输出目标,可为 null(此时忽略)
     */
    public static void dump(AstNode root, PrintStream out) {
        if (out == null) {
            return;
        }
        dumpNode(root, 0, out);
    }

    /**
     * 递归输出单个节点及其所有子节点.
     *
     * @param node  当前节点,可为 null
     * @param depth 当前缩进层级
     * @param out   输出目标
     */
    private static void dumpNode(AstNode node, int depth, PrintStream out) {
        if (node == null) {
            return;
        }
        out.println(INDENT.repeat(depth) + node.kind() + " " + node.getClass().getSimpleName());
        List<AstNode> children = node.children();
        if (children == null) {
            return;
        }
        for (AstNode child : children) {
            dumpNode(child, depth + 1, out);
        }
    }
}
