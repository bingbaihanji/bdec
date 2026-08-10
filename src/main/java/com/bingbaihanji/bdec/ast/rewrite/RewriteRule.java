package com.bingbaihanji.bdec.ast.rewrite;

import com.bingbaihanji.bdec.DecompileContext;
import com.bingbaihanji.bdec.ast.CompilationUnit;

/**
 * 重写规则接口,定义 AST 重写规则的标准契约.
 * <p>
 * 所有具体的重写规则(如字符串拼接还原,三元表达式还原等)均实现此接口,
 * 通过 {@link #rewrite(CompilationUnit, DecompileContext)} 方法对编译单元进行 AST 级别的变换.
 * </p>
 */
public interface RewriteRule {

    /**
     * 获取该重写规则的名称标识.
     *
     * @return 规则名称,用于日志输出和调试追踪
     */
    String name();

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
}
