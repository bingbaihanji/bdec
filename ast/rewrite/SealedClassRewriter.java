package com.bingbaihanji.bdec.ast.rewrite;

import com.bingbaihanji.bdec.DecompileContext;
import com.bingbaihanji.bdec.ast.CompilationUnit;
import com.bingbaihanji.bdec.ast.TypeDeclaration;

import java.util.ArrayList;
import java.util.List;

/**
 * Detects sealed classes/interfaces (ACC_SEALED flag, Java 17+)
 * and restores the {@code sealed}/{@code non-sealed}/{@code permits} syntax.
 *
 * <p>Inspired by Vineflower's {@code hasSealedClasses()} detection.
 */
public class SealedClassRewriter implements RewriteRule {

    /** ACC_SEALED = 0x1000 (not in JVM standard yet, preview flag area). */
    private static final int ACC_SEALED = 0x1000;

    @Override
    public String name() { return "sealed"; }

    @Override
    public CompilationUnit rewrite(CompilationUnit unit, DecompileContext context) {
        List<TypeDeclaration> types = new ArrayList<>();
        for (TypeDeclaration td : unit.types()) {
            types.add(rewriteType(td));
        }
        return new CompilationUnit(unit.packageName(), unit.imports(), types);
    }

    private TypeDeclaration rewriteType(TypeDeclaration td) {
        boolean isSealed = (td.accessFlags() & ACC_SEALED) != 0;
        if (!isSealed) return td;

        // Add "permits" to kind name or emit as annotation
        // The permitted subclass names come from the class file attribute
        // For now, we detect the flag and mark the kind appropriately
        String kindName;
        if (td.isInterface()) {
            kindName = "sealed interface";
        } else {
            kindName = "sealed class";
        }

        // Preserve original type parameters but add permits info if available
        List<String> typeParams = new ArrayList<>(td.typeParameters());
        // Append permits clause from permitted subclasses (if we had access to them)
        // The TypeDeclaration model doesn't carry this yet; for now detect flag only

        return new TypeDeclaration(td.accessFlags() & ~ACC_SEALED,
                td.simpleName(), kindName, td.superName(),
                td.interfaceNames(), typeParams, td.children());
    }
}
