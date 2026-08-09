package com.bingbaihanji.bdec.ast.rewrite;

import com.bingbaihanji.bdec.BdecConfig;
import com.bingbaihanji.bdec.DecompileContext;
import com.bingbaihanji.bdec.ast.CompilationUnit;

import java.util.List;

public class AstRewriter {

    private final List<RewriteRule> rules;

    public AstRewriter(List<RewriteRule> rules) {this.rules = List.copyOf(rules);}

    public CompilationUnit rewrite(CompilationUnit unit, BdecConfig config, DecompileContext ctx) {
        CompilationUnit result = unit;
        for (RewriteRule rule : rules) {
            if (isEnabled(rule.name(), config)) {
                result = rule.rewrite(result, ctx);
            }
        }
        return result;
    }

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
