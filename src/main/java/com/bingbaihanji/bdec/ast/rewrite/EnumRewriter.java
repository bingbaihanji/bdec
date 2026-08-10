package com.bingbaihanji.bdec.ast.rewrite;

import com.bingbaihanji.bdec.DecompileContext;
import com.bingbaihanji.bdec.ast.AstNode;
import com.bingbaihanji.bdec.ast.CompilationUnit;
import com.bingbaihanji.bdec.ast.TypeDeclaration;
import com.bingbaihanji.bdec.ast.stmt.BlockStatement;
import com.bingbaihanji.bdec.ast.stmt.ExpressionStatement;
import com.bingbaihanji.bdec.ast.stmt.FieldDeclaration;
import com.bingbaihanji.bdec.ast.stmt.MethodDeclaration;
import com.bingbaihanji.bdec.ast.stmt.Statement;
import com.bingbaihanji.bdec.ast.expr.Expression;
import com.bingbaihanji.bdec.ast.expr.InvocationExpr;
import com.bingbaihanji.bdec.ast.expr.VarExpr;

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
                // Skip the static initializer (<clinit>) for enum classes.
                // It only contains synthetic enum constant creation (NEW+DUP+INVOKESPECIAL)
                // and $VALUES array construction — all replaced by the enum constant list.
                if (isEnumStaticInit(md)) {
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

    /** Check for synthetic $VALUES field: private static final X[] $VALUES. */
    private boolean isValuesField(FieldDeclaration fd) {
        return "$VALUES".equals(fd.name());
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

    /** Check if this is the enum static initializer (<clinit>).
     *  Enum static initializers only create enum constants and $VALUES array —
     *  these are fully replaced by the enum constant list marker.
     *  AstBuilder sets method name to null for <clinit> methods. */
    private boolean isEnumStaticInit(MethodDeclaration md) {
        return md.name() == null;
    }

    /** Check if this is the synthetic enum constructor with name/ordinal params. */
    private boolean isEnumConstructor(MethodDeclaration md, String enumName) {
        if (!enumName.equals(md.name()) && !"<init>".equals(md.name())) {
            return false;
        }
        // Enum constructors have at least 2 params (name + ordinal) before user params
        return md.parameterNames().length >= 2;
    }

    /** Fix enum constructor: strip the first two synthetic parameters (name, ordinal)
     *  and remove the corresponding {@code super(name, ordinal)} call from the body. */
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

        // Clean the body: strip synthetic param temps and super(name, ordinal) call
        Statement body = md.body() != null ? cleanEnumConstructorBody(md.body()) : null;

        return new MethodDeclaration(md.accessFlags(), md.name(), md.returnType(),
                newNames, newTypes, md.typeParameters(), body);
    }

    /** Remove synthetic parameter declarations and the implicit super() call
     *  from enum constructor bodies. Enum constructors always call
     *  {@code super(name, ordinal)} implicitly — this is invisible in source. */
    private Statement cleanEnumConstructorBody(Statement body) {
        if (!(body instanceof BlockStatement bs)) {
            return body;
        }
        List<Statement> filtered = new ArrayList<>();
        for (Statement s : bs.statements()) {
            // Strip "int varN = 0" declarations — synthetic param temps
            if (s instanceof com.bingbaihanji.bdec.ast.stmt.VariableDeclaration vd
                    && vd.name().startsWith("var")
                    && vd.initializer() != null
                    && vd.initializer() instanceof com.bingbaihanji.bdec.ast.expr.LitExpr l
                    && l.value() instanceof Integer i && i == 0) {
                continue;
            }
            // Strip "super(var1, var2)" call — implicit in enum constructors
            if (s instanceof ExpressionStatement es
                    && es.expression() instanceof InvocationExpr inv
                    && "super".equals(inv.methodName())) {
                continue;
            }
            filtered.add(s);
        }
        if (filtered.isEmpty()) {
            return new BlockStatement(List.of());
        }
        return new BlockStatement(filtered);
    }
}
