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
    public String name() {return "sealed";}

    @Override
    public CompilationUnit rewrite(CompilationUnit unit, DecompileContext context) {
        List<TypeDeclaration> types = new ArrayList<>();
        for (TypeDeclaration td : unit.types()) {
            types.add(rewriteType(td, unit.packageName(), context));
        }
        return new CompilationUnit(unit.packageName(), unit.imports(), types);
    }

    private TypeDeclaration rewriteType(TypeDeclaration td, String pkg, DecompileContext context) {
        boolean isSealed = (td.accessFlags() & ACC_SEALED) != 0;
        if (isSealed) {
            return rewriteSealedType(td);
        }
        // Check if this is a non-sealed subclass of a sealed parent
        return rewriteNonSealedType(td, pkg, context);
    }

    private TypeDeclaration rewriteSealedType(TypeDeclaration td) {
        String kindName = td.isInterface() ? "sealed interface" : "sealed class";
        List<String> typeParams = new ArrayList<>(td.typeParameters());
        return new TypeDeclaration(td.accessFlags() & ~ACC_SEALED,
                td.simpleName(), kindName, td.superName(),
                td.interfaceNames(), typeParams, td.children());
    }

    /** Detect and mark non-sealed subclasses of sealed parent classes. */
    private TypeDeclaration rewriteNonSealedType(TypeDeclaration td, String pkg, DecompileContext context) {
        // Only applies to regular non-final, non-abstract, non-sealed classes
        if (td.isInterface() || (td.accessFlags() & 0x0010) != 0
                || (td.accessFlags() & 0x0400) != 0
                || (td.accessFlags() & ACC_SEALED) != 0) {
            return td;
        }
        // Need superclass name to check
        if (td.superName() == null) {
            return td;
        }
        // Build internal name from package + simple name
        String internalName = pkg != null && !pkg.isEmpty()
                ? pkg.replace('.', '/') + "/" + td.superName()
                : td.superName();
        // Check if superclass is sealed by loading its bytecode
        if (!isSuperclassSealed(internalName, context)) {
            return td;
        }
        // This is a non-sealed subclass
        return new TypeDeclaration(td.accessFlags(), td.simpleName(),
                "non-sealed class", td.superName(),
                td.interfaceNames(), td.typeParameters(), td.children());
    }

    /** Load the superclass and check if it's sealed using JVM reflection. */
    private boolean isSuperclassSealed(String internalName, DecompileContext context) {
        try {
            // First try the context's class loader
            byte[] bytes = context.loadClassBytes(internalName);
            if (bytes == null) {
                // Fall back: try JVM reflection
                String className = internalName.replace('/', '.');
                Class<?> c = Class.forName(className);
                return c.isSealed();
            }
            var reader = new com.bingbaihanji.bdec.bytecode.parser.ClassFileReader();
            var model = reader.read(internalName, bytes);
            return (model.accessFlags() & ACC_SEALED) != 0;
        } catch (Exception e) {
            return false;
        }
    }
}
