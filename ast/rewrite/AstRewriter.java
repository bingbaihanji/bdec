package com.bingbaihanji.bdec.ast.rewrite;

import com.bingbaihanji.bdec.BdecConfig;
import com.bingbaihanji.bdec.DecompileContext;
import com.bingbaihanji.bdec.ast.CompilationUnit;

import java.util.List;

/**
 * AST 重写器,负责按顺序执行一组重写规则对编译单元进行变换.
 *
 * <p>重写器根据 {@link BdecConfig} 配置决定每条重写规则是否启用.
 * 每条规则实现 {@link RewriteRule} 接口,对 AST 进行模式识别和转换.
 */
public class AstRewriter {

    /** 已注册的重写规则列表(按执行顺序排列) */
    private final List<RewriteRule> rules;

    /**
     * 构造一个重写器.
     *
     * @param rules 重写规则列表
     */
    public AstRewriter(List<RewriteRule> rules) {this.rules = List.copyOf(rules);}

    /**
     * 对编译单元依次执行所有启用的重写规则.
     *
     * @param unit   待重写的编译单元
     * @param config 配置对象,控制各规则的启用状态
     * @param ctx    反编译上下文
     * @return 重写后的编译单元
     */
    public CompilationUnit rewrite(CompilationUnit unit, BdecConfig config, DecompileContext ctx) {
        CompilationUnit result = unit;
        for (RewriteRule rule : rules) {
            if (isEnabled(rule.name(), config)) {
                result = rule.rewrite(result, ctx);
            }
        }
        return result;
    }

    /**
     * 根据配置判断指定名称的规则是否启用.
     *
     * @param name   规则名称
     * @param config 配置对象
     * @return {@code true} 表示该规则已启用
     */
    private boolean isEnabled(String name, BdecConfig config) {
        return switch (name) {
            case "enum", "enum-switch" -> config.decodeEnums();
            case "lambda" -> config.decodeLambdas();
            case "ternary" -> config.decodeTernary();
            case "string-concat" -> config.decodeStringConcat();
            case "try-resource" -> config.decodeTryResource();
            case "for-each" -> config.decodeForEach();
            case "string-switch" -> config.decodeStringSwitch();
            default -> true;
        };
    }
}
