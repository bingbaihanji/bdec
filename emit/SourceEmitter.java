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
        StatementEmitter stmts = new StatementEmitter(w, exprs, unit.types().isEmpty()
                ? "Unknown" : unit.types().getFirst().simpleName());

        // Package
        if (unit.packageName() != null && !unit.packageName().isEmpty()) {
            w.token("package").space().write(unit.packageName()).write(';');
            w.newLine().newLine();
        }

        // Imports
        if (!unit.imports().isEmpty()) {
            for (String imp : unit.imports()) {
                w.token("import").space().write(imp).write(';').newLine();
            }
            w.newLine();
        }

        // Type declarations
        for (TypeDeclaration type : unit.types()) {
            emitType(type, w, stmts);
        }

        String className = unit.types().isEmpty() ? "Unknown"
                : unit.packageName() != null && !unit.packageName().isEmpty()
                ? unit.packageName() + "." + unit.types().getFirst().simpleName()
                : unit.types().getFirst().simpleName();

        return new SourceFile(className, w.toString(), lineMapping);
    }

    private void emitType(TypeDeclaration type, IndentWriter w, StatementEmitter stmts) {
        // Access modifiers
        emitClassModifiers(type.accessFlags(), w);

        w.token(type.kindName()).space().write(type.simpleName());

        // Super class
        if (type.superName() != null) {
            w.space().token("extends").space().write(type.superName());
        }

        // Interfaces
        if (!type.interfaceNames().isEmpty()) {
            w.space().token(type.isInterface() ? "extends" : "implements").space();
            w.write(String.join(", ", type.interfaceNames()));
        }

        w.space().write("{").newLine();
        w.indent();

        for (AstNode member : type.children()) {
            if (member instanceof Statement s) {
                stmts.emit(s);
            } else {
                w.write("// " + member.kind()).newLine();
            }
        }

        w.dedent();
        w.write("}").newLine();
    }

    private void emitClassModifiers(int flags, IndentWriter w) {
        if ((flags & 0x0001) != 0) {
            w.token("public").space();
        } else if ((flags & 0x0002) != 0) {
            w.token("private").space();
        } else if ((flags & 0x0004) != 0) {
            w.token("protected").space();
        }
        if ((flags & 0x0400) != 0) {
            w.token("abstract").space();
        }
        if ((flags & 0x0010) != 0) {
            w.token("final").space();
        }
        if ((flags & 0x0020) != 0) {
            w.token("static").space();
        }
    }
}
