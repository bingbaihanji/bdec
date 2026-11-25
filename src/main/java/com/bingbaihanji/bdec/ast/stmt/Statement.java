package com.bingbaihanji.bdec.ast.stmt;

import com.bingbaihanji.bdec.ast.AstNode;

/**
 * 语句节点的抽象基类,所有具体语句类型均继承此类.
 *
 * <p>实现了 {@link AstNode} 接口,定义了语句节点在 AST 层级中的基本契约.
 * 语句还携带可选的起始字节码偏移,供发射阶段建立源码行号到字节码的映射;
 * 该元数据不参与相等性/哈希,默认 -1 表示未知.
 */
public abstract class Statement implements AstNode {

    /** 语句起始字节码偏移,未知为 -1 */
    private int sourceOffset = -1;

    /**
     * 获取语句起始字节码偏移.
     *
     * @return 字节码偏移,未知返回 -1
     */
    public int sourceOffset() {
        return sourceOffset;
    }

    /**
     * 设置语句起始字节码偏移.
     *
     * @param sourceOffset 字节码偏移,未知传 -1
     */
    public void setSourceOffset(int sourceOffset) {
        this.sourceOffset = sourceOffset;
    }

    /**
     * 把旧语句的起始偏移继承到重建的新语句.
     *
     * <p>重写器以不可变方式重建语句时,新节点默认偏移为 -1;调用本方法
     * 可保留原偏移,供发射层建立行号映射.新语句已有偏移或旧偏移未知时不覆盖.</p>
     *
     * @param oldStmt 旧语句,可为 null
     * @param newStmt 新语句,可为 null
     * @param <T>     语句类型
     * @return 传入的新语句
     */
    public static <T extends Statement> T inheritSourceOffset(Statement oldStmt, T newStmt) {
        if (oldStmt != null && newStmt != null && newStmt != oldStmt
                && newStmt.sourceOffset() < 0 && oldStmt.sourceOffset() >= 0) {
            newStmt.setSourceOffset(oldStmt.sourceOffset());
        }
        return newStmt;
    }
}
