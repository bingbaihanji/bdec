package com.bingbaihanji.bdec.emit;

import com.bingbaihanji.bdec.BdecConfig;
import com.bingbaihanji.bdec.ast.AstNode;
import com.bingbaihanji.bdec.ast.CompilationUnit;
import com.bingbaihanji.bdec.ast.TypeDeclaration;
import com.bingbaihanji.bdec.ast.stmt.Statement;

import java.util.HashMap;
import java.util.Map;

public class SourceEmitter {

    public SourceFile emit(CompilationUnit unit, BdecConfig config) {
        IndentWriter w = new IndentWriter(config.indentSize());
        Map<Integer, Integer> lineMapping = new HashMap<>();

        ExpressionEmitter exprs = new ExpressionEmitter(w);
        StatementEmitter stmts = new StatementEmitter(w, exprs);

        // Package
        if (unit.packageName() != null && !unit.packageName().isEmpty()) {
            w.token("package").space().write(unit.packageName()).write(';');
            w.newLine().newLine();
        }

        // Type declarations
        for (TypeDeclaration type : unit.types()) {
            emitType(type, w, stmts);
        }

        return new SourceFile(
                unit.types().isEmpty() ? "Unknown"
                        : unit.packageName() != null && !unit.packageName().isEmpty()
                        ? unit.packageName() + "." + unit.types().get(0).simpleName()
                        : unit.types().get(0).simpleName(),
                w.toString(), lineMapping
        );
    }

    private void emitType(TypeDeclaration type, IndentWriter w, StatementEmitter stmts) {
        w.token("public").space().token(type.kindName()).space().write(type.simpleName());
        w.space().write('{').newLine();
        w.indent();
        for (AstNode member : type.children()) {
            if (member instanceof Statement s) {
                stmts.emit(s);
            } else {
                w.write("// " + member.kind()).newLine();
            }
        }
        w.dedent();
        w.write('}').newLine();
    }
}
