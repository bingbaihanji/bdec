package com.bingbaihanji.bdec.ast;

import com.bingbaihanji.bdec.DecompileContext;
import com.bingbaihanji.bdec.ast.expr.Expression;
import com.bingbaihanji.bdec.ast.expr.LitExpr;
import com.bingbaihanji.bdec.ast.stmt.FieldDeclaration;
import com.bingbaihanji.bdec.ast.stmt.MethodDeclaration;
import com.bingbaihanji.bdec.bytecode.model.ClassFileModel;
import com.bingbaihanji.bdec.bytecode.model.FieldModel;
import com.bingbaihanji.bdec.bytecode.model.MethodModel;
import com.bingbaihanji.bdec.bytecode.parser.SignatureParser;
import com.bingbaihanji.bdec.structuring.StructuredMethod;
import com.bingbaihanji.bdec.type.JavaType;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class AstBuilder {

    /** Extract simple class name from internal name, using inner-class info
     *  when available. For {@code com/example/Outer$Inner} with inner-class
     *  entry name {@code "Inner"}, returns {@code "Inner"}. */
    private static String simpleName(String internal, List<com.bingbaihanji.bdec.bytecode.model.constantpool.InnerClassEntry> innerClasses) {
        int idx = internal.lastIndexOf('/');
        String raw = idx >= 0 ? internal.substring(idx + 1) : internal;
        // Check inner class table for a friendly simple name
        for (var ice : innerClasses) {
            if (internal.equals(ice.innerClass()) && ice.simpleName() != null) {
                return ice.simpleName();
            }
        }
        return raw;
    }

    /** Extract simple class name from internal name (no inner-class info). */
    private static String simpleName(String internal) {
        int idx = internal.lastIndexOf('/');
        return idx >= 0 ? internal.substring(idx + 1) : internal;
    }

    /** Collect imports from types referenced in method bodies (static calls,
     *  new expressions, etc.) by scanning IR instructions. */
    private void collectBodyImports(StructuredMethod sm, Set<String> imports, String thisClass) {
        if (sm.ir() == null || sm.ir().instructions() == null) {
            return; // abstract/native methods have no IR
        }
        for (var insn : sm.ir().instructions()) {
            // Static calls: DECLARING_CLASS annotation
            for (var ann : insn.annotations()) {
                if (ann.is(com.bingbaihanji.bdec.semantic.SemanticTag.DECLARING_CLASS)) {
                    String declClass = ann.getString(
                            com.bingbaihanji.bdec.semantic.SemanticAnnotation.KEY_DECLARING_CLASS);
                    if (declClass != null) {
                        collectImport(com.bingbaihanji.bdec.type.JavaType.classType(declClass),
                                imports, thisClass);
                    }
                }
            }
            // NEW instructions: type from resultType
            if (insn.opcode() == com.bingbaihanji.bdec.ir.IrOpcode.NEW) {
                collectImport(insn.resultType(), imports, thisClass);
            }
            // NEW_ARRAY: element type
            if (insn.opcode() == com.bingbaihanji.bdec.ir.IrOpcode.NEW_ARRAY) {
                collectImport(insn.resultType(), imports, thisClass);
            }
            // INSTANCE_OF: target type from nameHint
            if (insn.opcode() == com.bingbaihanji.bdec.ir.IrOpcode.INSTANCE_OF
                    && insn.nameHint() != null) {
                collectImport(com.bingbaihanji.bdec.type.JavaType.classType(insn.nameHint()),
                        imports, thisClass);
            }
        }
    }

    // ── helpers ──────────────────────────────────────────────────

    public CompilationUnit build(ClassFileModel classFile, List<StructuredMethod> methods,
                                 @SuppressWarnings("unused") DecompileContext ctx) {
        List<AstNode> members = new ArrayList<>();
        Set<String> imports = new LinkedHashSet<>();

        // Collect imports from field types and method signatures
        String simpleName = simpleName(classFile.internalName(), classFile.innerClasses());

        // Add fields
        for (FieldModel field : classFile.fields()) {
            Expression init = parseFieldInitializer(field);
            // If the field has a signature, parse it for generic type arguments
            JavaType displayType = field.type();
            if (field.signature() != null && !field.signature().isEmpty()) {
                JavaType parsed = SignatureParser.parseGenericType(field.signature());
                if (parsed != null) {
                    displayType = parsed;
                }
            }
            // Synthetic assertion field — give it a default value since we
            // strip its static-initializer assignment (it's a JVM artifact).
            if (init == null && "$assertionsDisabled".equals(field.name())) {
                init = new com.bingbaihanji.bdec.ast.expr.LitExpr(false, JavaType.BOOLEAN);
            }
            FieldDeclaration fd = new FieldDeclaration(
                    field.accessFlags(), field.name(), displayType, init);
            members.add(fd);
            collectImport(field.type(), imports, simpleName);
        }

        // Add method declarations
        for (StructuredMethod sm : methods) {
            MethodModel method = sm.method();
            String[] paramNames = buildParameterNames(method);
            String methodName = resolveMethodName(method.name(), simpleName,
                    classFile.accessFlags());

            // Extract method-level type parameters from signature
            List<String> methodTypeParams = method.signature() != null
                    && !method.signature().isEmpty()
                    ? SignatureParser.extractMethodTypeParams(method.signature())
                    : List.of();

            MethodDeclaration decl = new MethodDeclaration(
                    method.accessFlags(),
                    methodName,
                    method.returnType(),
                    paramNames,
                    method.parameterTypes(),
                    methodTypeParams,
                    sm.body()
            );
            members.add(decl);

            // Collect imports from method signatures
            collectImport(method.returnType(), imports, simpleName);
            for (JavaType pt : method.parameterTypes()) {
                collectImport(pt, imports, simpleName);
            }

            // Collect imports from types referenced in the method body.
            // Scan IR instructions for DECLARING_CLASS annotations on static calls.
            collectBodyImports(sm, imports, simpleName);
        }

        // Determine class kind
        String kind = (classFile.accessFlags() & 0x0200) != 0 ? "interface"
                : (classFile.accessFlags() & 0x4000) != 0 ? "@interface"
                : (classFile.accessFlags() & 0x2000) != 0 ? "enum" : "class";

        // Resolve super class name
        String superName = classFile.superInternalName() != null
                && !"java/lang/Object".equals(classFile.superInternalName())
                ? simpleName(classFile.superInternalName()) : null;
        if (superName != null) {
            collectImport(JavaType.classType(classFile.superInternalName()), imports, simpleName);
        }

        // Resolve interface names
        List<String> interfaceNames = new ArrayList<>();
        for (String ifName : classFile.interfaceInternalNames()) {
            String simple = simpleName(ifName);
            interfaceNames.add(simple);
            collectImport(JavaType.classType(ifName), imports, simpleName);
        }

        // Extract type parameters from class signature (e.g. "<E:Ljava/lang/Object;>" → ["E"])
        List<String> typeParams = SignatureParser.extractTypeParams(classFile.signature());

        TypeDeclaration td = new TypeDeclaration(
                classFile.accessFlags(), simpleName, kind,
                superName, interfaceNames, typeParams, members);

        // Build imports (filter java.lang.* and same-package types)
        List<String> importList = new ArrayList<>();
        String pkg = packageName(classFile.internalName());
        for (String imp : imports) {
            // Only skip types directly in java.lang package, not subpackages
            // (e.g., java.lang.annotation.Annotation needs an explicit import)
            if (imp.startsWith("java.lang.")
                    && imp.indexOf('.', "java.lang.".length()) < 0) {
                continue; // java.lang.* is auto-imported
            }
            if (!imp.contains(".")) {
                continue;
            }
            String impPkg = imp.substring(0, imp.lastIndexOf('.'));
            if (impPkg.equals(pkg)) {
                continue; // same package
            }
            importList.add(imp);
        }
        java.util.Collections.sort(importList);

        return new CompilationUnit(pkg, importList, List.of(td));
    }

    /** Build parameter names from the local variable table when available,
     *  falling back to sequential "paramN" names. */
    private String[] buildParameterNames(MethodModel method) {
        int paramCount = method.parameterTypes().length;
        String[] names = new String[paramCount];
        var lvt = method.localVarNames();

        // In non-static methods, slot 0 is "this", so parameters start at slot 1.
        // In static methods, parameters start at slot 0.
        // Category-2 types (long/double) take two slots.
        int slot = method.isStatic() ? 0 : 1;

        for (int i = 0; i < paramCount; i++) {
            // Try LVT name first
            String lvtName = lvt.get(slot);
            if (lvtName != null && !lvtName.isEmpty()) {
                names[i] = lvtName;
            } else {
                names[i] = "param" + i;
            }
            // Advance past this parameter's slot(s)
            JavaType pt = method.parameterTypes()[i];
            boolean cat2 = pt != null && (pt.kind() == com.bingbaihanji.bdec.type.TypeKind.LONG
                    || pt.kind() == com.bingbaihanji.bdec.type.TypeKind.DOUBLE);
            slot += cat2 ? 2 : 1;
        }
        return names;
    }

    /** Resolve method name: constructors use class name, static init is removed. */
    private String resolveMethodName(String methodName, String className,
                                     @SuppressWarnings("unused") int classFlags) {
        if ("<init>".equals(methodName)) {
            return className;
        }
        if ("<clinit>".equals(methodName)) {
            return null; // static init handled separately
        }
        return methodName;
    }

    private String packageName(String internalName) {
        int idx = internalName.lastIndexOf('/');
        return idx > 0 ? internalName.substring(0, idx).replace('/', '.') : "";
    }

    /** Parse a field's constant value as a LitExpr, or null. */
    private Expression parseFieldInitializer(FieldModel field) {
        Object cv = field.constantValue();
        if (cv == null) {
            return null;
        }
        if (cv instanceof String s) {
            return new LitExpr(s, JavaType.classType("java/lang/String"));
        }
        if (cv instanceof Number || cv instanceof Boolean || cv instanceof Character) {
            return new LitExpr(cv, field.type());
        }
        return null;
    }

    /** Collect import for a type if it's a class type from a different package. */
    private void collectImport(JavaType type, Set<String> imports, String thisClass) {
        if (type == null) {
            return;
        }
        if (type.kind() == com.bingbaihanji.bdec.type.TypeKind.CLASS) {
            String internalName = type.internalName();
            if (internalName != null && !simpleName(internalName).equals(thisClass)) {
                imports.add(internalName.replace('/', '.'));
            }
        }
    }
}
