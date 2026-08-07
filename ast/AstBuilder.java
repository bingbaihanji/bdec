package com.bingbaihanji.bdec.ast;

import com.bingbaihanji.bdec.DecompileContext;
import com.bingbaihanji.bdec.ast.stmt.ExpressionStatement;
import com.bingbaihanji.bdec.bytecode.model.ClassFileModel;
import com.bingbaihanji.bdec.structuring.StructuredMethod;

import java.util.ArrayList;
import java.util.List;

public class AstBuilder {

    public CompilationUnit build(ClassFileModel classFile, List<StructuredMethod> methods,
                                 DecompileContext ctx) {
        List<AstNode> members = new ArrayList<>();

        // Add fields as VariableDeclaration stub
        for (var field : classFile.fields()) {
            members.add(new ExpressionStatement(
                    new com.bingbaihanji.bdec.ast.expr.VarExpr(
                            "// field: " + field.type().displayName() + " " + field.name())));
        }

        // Add structured method bodies
        for (StructuredMethod sm : methods) {
            members.add(sm.body());
        }

        TypeDeclaration td = new TypeDeclaration(
                classFile.accessFlags(),
                classFile.internalName().substring(
                        classFile.internalName().lastIndexOf('/') + 1),
                (classFile.accessFlags() & 0x0200) != 0 ? "interface" : "class",
                members
        );

        return new CompilationUnit(
                packageName(classFile.internalName()),
                List.of(),
                List.of(td)
        );
    }

    private String packageName(String internalName) {
        int idx = internalName.lastIndexOf('/');
        return idx > 0 ? internalName.substring(0, idx).replace('/', '.') : "";
    }
}
