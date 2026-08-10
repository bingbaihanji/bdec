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

        // Separate enum constant fields from other members
        List<String> enumConstants = new ArrayList<>();
        List<AstNode> otherMembers = new ArrayList<>();
        List<AstNode> regularMembers = new ArrayList<>();

        for (AstNode m : td.children()) {
            if (m instanceof FieldDeclaration fd) {
                if (isValuesField(fd)) {
                    continue;
                }
                if (isEnumConstantField(fd, td.simpleName())) {
                    enumConstants.add(fd.name());
                    continue; // remove the field declaration, add as constant
                }
            }
            if (m instanceof MethodDeclaration md) {
                if (isEnumSyntheticMethod(md)) {
                    continue;
                }
                if (isEnumConstructor(md, td.simpleName())) {
                    regularMembers.add(fixEnumConstructor(md));
                    continue;
                }
            }
            regularMembers.add(m);
        }

        // Emit enum constants as a special field marker.
        // The SourceEmitter detects fields with $enumConstants$ name and emits them
        // as comma-separated identifiers ending with semicolon.
        List<AstNode> members = new ArrayList<>();
        if (!enumConstants.isEmpty()) {
            String constList = String.join(", ", enumConstants) + ";";
            members.add(new FieldDeclaration(0, "$enumConstants$",
                    com.bingbaihanji.bdec.type.JavaType.VOID,
                    new com.bingbaihanji.bdec.ast.expr.VarExpr(constList)));
        }
        members.addAll(regularMembers);

        // Change kind from "class" to "enum"; remove ACC_ABSTRACT, null superName
        int flags = (td.accessFlags() & ~(ACC_ENUM | 0x0400));
        return new TypeDeclaration(flags, td.simpleName(), "enum", null,
                td.interfaceNames(), td.typeParameters(), members);
    }

    /** Check if a field is an enum constant (public static final of the enum type). */
    private boolean isEnumConstantField(FieldDeclaration fd, String enumName) {
        int flags = fd.accessFlags();
        // public static final = 0x0001 | 0x0008 | 0x0010 = 0x0019
        boolean isPublicStaticFinal = (flags & 0x0019) == 0x0019;
        if (!isPublicStaticFinal) {
            return false;
        }
        // Check if the field type matches the enum type name
        String typeStr = fd.type() != null ? fd.type().displayName() : "";
        return typeStr.contains(enumName);
    }

    private boolean isEnum(TypeDeclaration td) {
        return (td.accessFlags() & ACC_ENUM) != 0;
    }

    /** Check for synthetic $VALUES field: private static final X[] $VALUES.
     *  NOT removed for now — the static initializer may reference it.
     *  TODO: detect and remove when the static init is properly cleaned up. */
    private boolean isValuesField(FieldDeclaration fd) {
        return false; // keep $VALUES to avoid undefined references in static init
    }

    /** Check for synthetic values(), valueOf(), and $values() methods. */
    private boolean isEnumSyntheticMethod(MethodDeclaration md) {
        String name = md.name();
        if ("values".equals(name) || name != null && name.startsWith("$values")) {
            return md.parameterNames().length == 0 && md.isStatic();
        }
        if ("valueOf".equals(name)) {
            return md.parameterNames().length == 1 && md.isStatic();
        }
        return false;
    }

    /** Check if this is the synthetic enum constructor with name/ordinal params. */
    private boolean isEnumConstructor(MethodDeclaration md, String enumName) {
        if (!enumName.equals(md.name()) && !"<init>".equals(md.name())) {
            return false;
        }
        // Enum constructors have at least 2 params (name + ordinal) before user params
        return md.parameterNames().length >= 2;
    }

    /** Fix enum constructor: strip the first two synthetic parameters (name, ordinal). */
    private MethodDeclaration fixEnumConstructor(MethodDeclaration md) {
        int origLen = md.parameterNames().length;
        if (origLen < 2) {
            return md;
        }
        int newLen = origLen - 2;
        String[] newNames = new String[newLen];
        com.bingbaihanji.bdec.type.JavaType[] newTypes = new com.bingbaihanji.bdec.type.JavaType[newLen];
        System.arraycopy(md.parameterNames(), 2, newNames, 0, newLen);
        System.arraycopy(md.parameterTypes(), 2, newTypes, 0, newLen);
        return new MethodDeclaration(md.accessFlags(), md.name(), md.returnType(),
                newNames, newTypes, md.typeParameters(), md.body());
    }
}
