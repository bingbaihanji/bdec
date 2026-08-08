package com.bingbaihanji.bdec.ast;

import com.bingbaihanji.bdec.DecompileContext;
import com.bingbaihanji.bdec.ast.expr.Expression;
import com.bingbaihanji.bdec.ast.stmt.FieldDeclaration;
import com.bingbaihanji.bdec.ast.stmt.MethodDeclaration;
import com.bingbaihanji.bdec.bytecode.model.ClassFileModel;
import com.bingbaihanji.bdec.bytecode.model.FieldModel;
import com.bingbaihanji.bdec.bytecode.model.MethodModel;
import com.bingbaihanji.bdec.structuring.StructuredMethod;
import com.bingbaihanji.bdec.type.JavaType;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class AstBuilder {

    /** Extract simple class name from internal name. */
    private static String simpleName(String internal) {
        int idx = internal.lastIndexOf('/');
        return idx >= 0 ? internal.substring(idx + 1) : internal;
    }

    // ── helpers ──────────────────────────────────────────────────

    public CompilationUnit build(ClassFileModel classFile, List<StructuredMethod> methods,
                                 DecompileContext ctx) {
        List<AstNode> members = new ArrayList<>();
        Set<String> imports = new LinkedHashSet<>();

        // Collect imports from field types and method signatures
        String simpleName = simpleName(classFile.internalName());

        // Add fields
        for (FieldModel field : classFile.fields()) {
            Expression init = null; // TODO: parse constant value attribute
            FieldDeclaration fd = new FieldDeclaration(
                    field.accessFlags(), field.name(), field.type(), init);
            members.add(fd);
            collectImport(field.type(), imports, simpleName);
        }

        // Add method declarations
        for (StructuredMethod sm : methods) {
            MethodModel method = sm.method();
            String[] paramNames = buildParameterNames(method);
            String methodName = resolveMethodName(method.name(), simpleName,
                    classFile.accessFlags());

            MethodDeclaration decl = new MethodDeclaration(
                    method.accessFlags(),
                    methodName,
                    method.returnType(),
                    paramNames,
                    method.parameterTypes(),
                    sm.body()
            );
            members.add(decl);

            // Collect imports from method signatures
            collectImport(method.returnType(), imports, simpleName);
            for (JavaType pt : method.parameterTypes()) {
                collectImport(pt, imports, simpleName);
            }
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

        TypeDeclaration td = new TypeDeclaration(
                classFile.accessFlags(), simpleName, kind,
                superName, interfaceNames, members);

        // Build imports (filter java.lang.* and same-package types)
        List<String> importList = new ArrayList<>();
        String pkg = packageName(classFile.internalName());
        for (String imp : imports) {
            if (imp.startsWith("java.lang.")) {
                continue; // auto-imported
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

    /** Build parameter names from slot indices. Uses local var table when available. */
    private String[] buildParameterNames(MethodModel method) {
        String[] names = new String[method.parameterTypes().length];
        int slot = method.isStatic() ? 0 : 1;
        for (int i = 0; i < names.length; i++) {
            names[i] = "param" + i;
            slot += method.parameterTypes()[i].slotCount();
        }
        return names;
    }

    /** Resolve method name: constructors use class name, static init is removed. */
    private String resolveMethodName(String methodName, String className, int classFlags) {
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
