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

        ExpressionEmitter exprs = new ExpressionEmitter(w, unit.imports());

        // Register a line-mapping hook: when the writer advances to a new line
        // the caller can associate bytecode offsets via the IndentWriter.
        // Phase 2b: populate from bytecode offset tracking during emission.
        boolean isInterface = !unit.types().isEmpty() && unit.types().getFirst().isInterface();
        StatementEmitter stmts = new StatementEmitter(w, exprs, unit.types().isEmpty()
                ? "Unknown" : unit.types().getFirst().simpleName(), isInterface);

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
        emitClassModifiers(type.accessFlags(), type.isInterface(), w);

        w.token(type.kindName()).space().write(type.simpleName());

        // Emit type parameters for generic classes, or record components for records
        if (!type.typeParameters().isEmpty()) {
            boolean isRecord = "record".equals(type.kindName());
            w.write(isRecord ? "(" : "<");
            w.write(String.join(", ", type.typeParameters()));
            w.write(isRecord ? ")" : ">");
        }

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

        boolean firstMember = true;
        for (AstNode member : type.children()) {
            // Emit enum constants as comma-separated list (special marker field)
            if (member instanceof com.bingbaihanji.bdec.ast.stmt.FieldDeclaration fd
                    && "$enumConstants$".equals(fd.name())) {
                if (fd.initializer() instanceof com.bingbaihanji.bdec.ast.expr.VarExpr ve) {
                    w.write(ve.name()).newLine();
                }
                continue;
            }
            if (member instanceof Statement s) {
                stmts.emit(s);
            } else {
                w.write("// " + member.kind()).newLine();
            }
            firstMember = false;
        }

        w.dedent();
        w.write("}").newLine();
    }

    private void emitClassModifiers(int flags, boolean isInterface, IndentWriter w) {
        if ((flags & 0x0001) != 0) {
            w.token("public").space();
        } else if ((flags & 0x0002) != 0) {
            w.token("private").space();
        } else if ((flags & 0x0004) != 0) {
            w.token("protected").space();
        }
        // Interfaces are implicitly abstract — don't emit the redundant keyword
        if ((flags & 0x0400) != 0 && !isInterface) {
            w.token("abstract").space();
        }
        if ((flags & 0x0010) != 0) {
            w.token("final").space();
        }
        // Note: 0x0020 = ACC_SUPER (not ACC_STATIC). ACC_STATIC = 0x0008.
        // ACC_SUPER is set on all modern class files and should NOT be emitted as a modifier.
        // ACC_STATIC only applies to nested classes.
        if ((flags & 0x0008) != 0) {
            w.token("static").space();
        }
    }
}
