package com.bingbaihanji.bdec.ast.rewrite;

import com.bingbaihanji.bdec.DecompileContext;
import com.bingbaihanji.bdec.ast.AstNode;
import com.bingbaihanji.bdec.ast.CompilationUnit;
import com.bingbaihanji.bdec.ast.TypeDeclaration;
import com.bingbaihanji.bdec.ast.stmt.FieldDeclaration;
import com.bingbaihanji.bdec.ast.stmt.MethodDeclaration;

import java.util.ArrayList;
import java.util.List;

/**
 * Detects enum classes and restores the {@code enum} keyword,
 * removing synthetic {@code $VALUES}, {@code values()}, and
 * {@code valueOf(String)} members.
 *
 * <p>Inspired by Vineflower's {@code EnumProcessor}.
 */
public class EnumRewriter implements RewriteRule {

    /** ACC_ENUM flag (0x4000). */
    private static final int ACC_ENUM = 0x4000;

    @Override
    public String name() {return "enum";}

    @Override
    public CompilationUnit rewrite(CompilationUnit unit, DecompileContext context) {
        List<TypeDeclaration> types = new ArrayList<>();
        for (TypeDeclaration td : unit.types()) {
            types.add(rewriteType(td));
        }
        return new CompilationUnit(unit.packageName(), unit.imports(), types);
    }

    private TypeDeclaration rewriteType(TypeDeclaration td) {
        if (!isEnum(td)) {
            return td;
        }

        // Filter out synthetic enum members
        List<AstNode> members = new ArrayList<>();
        for (AstNode m : td.children()) {
            if (m instanceof FieldDeclaration fd && isValuesField(fd)) {
                continue;
            }
            if (m instanceof MethodDeclaration md && isEnumSyntheticMethod(md)) {
                continue;
            }
            members.add(m);
        }

        // Change kind from "class" to "enum"
        return new TypeDeclaration(td.accessFlags() & ~ACC_ENUM, // clear enum flag for emission
                td.simpleName(), "enum", td.superName(),
                td.interfaceNames(), td.typeParameters(), members);
    }

    private boolean isEnum(TypeDeclaration td) {
        return (td.accessFlags() & ACC_ENUM) != 0;
    }

    /** Check for synthetic $VALUES field: private static final X[] $VALUES */
    private boolean isValuesField(FieldDeclaration fd) {
        String name = fd.name();
        return name != null && name.contains("$VALUES");
    }

    /** Check for synthetic values() and valueOf() methods. */
    private boolean isEnumSyntheticMethod(MethodDeclaration md) {
        String name = md.name();
        if ("values".equals(name)) {
            // Synthetic values(): returns array of this enum type, no params
            return md.parameterNames().length == 0 && md.isStatic();
        }
        if ("valueOf".equals(name)) {
            // Synthetic valueOf(String): one String param, static
            return md.parameterNames().length == 1 && md.isStatic();
        }
        return false;
    }
}
