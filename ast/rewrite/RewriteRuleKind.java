package com.bingbaihanji.bdec.ast.rewrite;

import com.bingbaihanji.bdec.BdecConfig;

/**
 * 重写规则的调度标识(里程碑 Phase 3).
 *
 * <p>替代 {@link AstRewriter} 中按魔法字符串匹配的启用开关.每条重写规则
 * 通过 {@link RewriteRule#kind()} 声明自身类别,由本枚举集中映射到
 * {@link BdecConfig} 对应的功能开关,消除字符串与配置项的隐式耦合.</p>
 */
public enum RewriteRuleKind {

    /** 无独立配置开关,始终启用. */
    ALWAYS_ON,

    /** 枚举还原({@link EnumRewriter}). */
    ENUM,

    /** 枚举 switch 还原({@link EnumSwitchRewriter}). */
    ENUM_SWITCH,

    /** lambda 还原({@link LambdaRewriter}). */
    LAMBDA,

    /** 三元表达式还原({@link TernaryRewriter}). */
    TERNARY,

    /** 字符串拼接还原({@link StringConcatRewriter}). */
    STRING_CONCAT,

    /** try-with-resources 还原({@link TryResourceRewriter}). */
    TRY_RESOURCE,

    /** for-each 还原({@link ForEachRewriter}). */
    FOR_EACH,

    /** 字符串 switch 还原({@link StringSwitchRewriter}). */
    STRING_SWITCH;

    /**
     * 依据配置判断该类别规则是否启用.
     *
     * @param config 反编译配置
     * @return {@code true} 表示该类别规则启用
     */
    public boolean isEnabled(BdecConfig config) {
        return switch (this) {
            case ENUM, ENUM_SWITCH -> config.decodeEnums();
            case LAMBDA -> config.decodeLambdas();
            case TERNARY -> config.decodeTernary();
            case STRING_CONCAT -> config.decodeStringConcat();
            case TRY_RESOURCE -> config.decodeTryResource();
            case FOR_EACH -> config.decodeForEach();
            case STRING_SWITCH -> config.decodeStringSwitch();
            case ALWAYS_ON -> true;
        };
    }
}
