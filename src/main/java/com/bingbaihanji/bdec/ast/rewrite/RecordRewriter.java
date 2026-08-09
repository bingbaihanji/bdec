package com.bingbaihanji.bdec.ast.rewrite;

import com.bingbaihanji.bdec.DecompileContext;
import com.bingbaihanji.bdec.ast.AstNode;
import com.bingbaihanji.bdec.ast.CompilationUnit;
import com.bingbaihanji.bdec.ast.TypeDeclaration;
import com.bingbaihanji.bdec.ast.stmt.FieldDeclaration;
import com.bingbaihanji.bdec.ast.stmt.MethodDeclaration;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Detects Java records (ACC_RECORD flag) and restores the
 * {@code record} keyword, canonical constructor, and component list.
 *
 * <p>Pattern:
 * <pre>
 *   final class Point extends java/lang/Record {
 *       private final int x;
 *       private final int y;
 *       Point(int x, int y) { this.x = x; this.y = y; }  // canonical ctor
 *       int x() { return x; }  // synthetic accessor
 *       int y() { return y; }  // synthetic accessor
 *       // toString/hashCode/equals overrides or synthetic
 *   }
 *
 *   → record Point(int x, int y) { }
 * </pre>
 *
 * <p>Inspired by Vineflower's {@code RecordHelper}.
 */
public class RecordRewriter implements RewriteRule {

    /** ACC_RECORD flag (0x0010 in class context, distinct from ACC_FINAL 0x0010). */
    private static final int ACC_RECORD = 0x0010;

    private static final Set<String> SYNTHETIC_METHODS = Set.of(
            "toString", "hashCode", "equals");

    @Override
    public String name() {return "record";}

    @Override
    public CompilationUnit rewrite(CompilationUnit unit, DecompileContext context) {
        List<TypeDeclaration> types = new ArrayList<>();
        for (TypeDeclaration td : unit.types()) {
            types.add(rewriteType(td));
        }
        return new CompilationUnit(unit.packageName(), unit.imports(), types);
    }

    private TypeDeclaration rewriteType(TypeDeclaration td) {
        if (!isRecord(td)) {
            return td;
        }

        // Collect field names for component identification
        Set<String> componentFields = new HashSet<>();
        List<String> componentNames = new ArrayList<>();
        for (AstNode m : td.children()) {
            if (m instanceof FieldDeclaration fd && isPrivateFinal(fd)) {
                componentFields.add(fd.name());
                componentNames.add(fd.name());
            }
        }

        // Filter out synthetic members
        List<AstNode> members = new ArrayList<>();
        for (AstNode m : td.children()) {
            if (m instanceof MethodDeclaration md) {
                if (isCanonicalConstructor(md, componentFields)) {
                    continue;
                }
                if (isSyntheticAccessor(md, componentFields)) {
                    continue;
                }
                // Keep only custom (non-synthetic) toString/hashCode/equals
                if (SYNTHETIC_METHODS.contains(md.name())
                        && md.parameterNames().length == 0) {
                    continue;
                }
            }
            members.add(m);
        }

        // Build type parameters from components: "int x, int y"
        List<String> recordComponents = new ArrayList<>();
        for (String name : componentNames) {
            // Find the field type
            for (AstNode m : td.children()) {
                if (m instanceof FieldDeclaration fd && name.equals(fd.name())) {
                    recordComponents.add(fd.type().displayName() + " " + name);
                    break;
                }
            }
        }

        return new TypeDeclaration(td.accessFlags() & ~ACC_RECORD,
                td.simpleName(), "record", td.superName(),
                td.interfaceNames(), recordComponents, members);
    }

    private boolean isRecord(TypeDeclaration td) {
        return (td.accessFlags() & ACC_RECORD) != 0
                && "java/lang/Record".equals(td.superName());
    }

    private boolean isPrivateFinal(FieldDeclaration fd) {
        int flags = fd.accessFlags();
        return (flags & 0x0002) != 0 && (flags & 0x0010) != 0; // private + final
    }

    /** Check if a constructor is the canonical (all-fields) constructor. */
    private boolean isCanonicalConstructor(MethodDeclaration md, Set<String> fields) {
        if (!"<init>".equals(md.name())) {
            return false;
        }
        if (md.parameterNames().length != fields.size()) {
            return false;
        }
        for (String param : md.parameterNames()) {
            if (!fields.contains(param)) {
                return false;
            }
        }
        return true;
    }

    /** Check if a method is a synthetic accessor (name matches a field). */
    private boolean isSyntheticAccessor(MethodDeclaration md, Set<String> fields) {
        if (md.parameterNames().length != 0) {
            return false;
        }
        return fields.contains(md.name());
    }
}
