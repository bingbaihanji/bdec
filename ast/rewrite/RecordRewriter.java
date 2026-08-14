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
 * Record 类重写器,检测 Java record 类(ACC_RECORD 标志),
 * 还原 {@code record} 关键字,规范构造器和组件列表.
 *
 * <p>可识别的模式:
 * <pre>
 *   final class Point extends java/lang/Record {
 *       private final int x;
 *       private final int y;
 *       Point(int x, int y) { this.x = x; this.y = y; }  // 规范构造器
 *       int x() { return x; }  // 合成访问器
 *       int y() { return y; }  // 合成访问器
 *       // toString/hashCode/equals 覆写或合成方法
 *   }
 *
 *   → record Point(int x, int y) { }
 * </pre>
 *
 * <p>设计参考 Vineflower 的 {@code RecordHelper}.
 */
public class RecordRewriter implements RewriteRule {

    /**
     * ACC_RECORD 标志位(0x0010).
     * 在类上下文中此标志与 ACC_FINAL(0x0010)共用同一比特位.
     */
    private static final int ACC_RECORD = 0x0010;

    /** record 的合成方法名称集合 */
    private static final Set<String> SYNTHETIC_RECORD_METHODS = Set.of(
            "toString", "hashCode", "equals");

    /** record 合成方法所需的参数个数 */
    private static final java.util.Map<String, Integer> RECORD_METHOD_PARAM_COUNT =
            java.util.Map.of("toString", 0, "hashCode", 0, "equals", 1);

    @Override
    public String name() {return "record";}

    @Override
    public CompilationUnit rewrite(CompilationUnit unit, DecompileContext context) {
        List<TypeDeclaration> types = new ArrayList<>();
        Set<String> collectedImports = new HashSet<>();
        for (TypeDeclaration td : unit.types()) {
            types.add(rewriteType(td, unit, collectedImports));
        }
        return new CompilationUnit(unit.packageName(),
                com.bingbaihanji.bdec.util.TypeText.mergeImports(
                        unit.imports(), collectedImports),
                types, unit.innerClassNames());
    }

    /**
     * 重写类型声明.若为 record 类,则转换为 record 声明.
     */
    private TypeDeclaration rewriteType(TypeDeclaration td, CompilationUnit unit,
                                        Set<String> collectedImports) {
        if (!isRecord(td)) {
            return td;
        }

        // 收集 record 组件字段名
        Set<String> componentFields = new HashSet<>();
        List<String> componentNames = new ArrayList<>();
        for (AstNode m : td.children()) {
            if (m instanceof FieldDeclaration fd && isPrivateFinal(fd)) {
                componentFields.add(fd.name());
                componentNames.add(fd.name());
            }
        }

        // 过滤出合成成员和组件字段
        List<AstNode> members = new ArrayList<>();
        for (AstNode m : td.children()) {
            if (m instanceof FieldDeclaration fd) {
                // 移除组件字段——record 关键字会自动生成这些字段
                if (componentFields.contains(fd.name())) {
                    continue;
                }
            }
            if (m instanceof MethodDeclaration md) {
                if (isCanonicalConstructor(md, componentFields)) {
                    continue; // 移除规范构造器
                }
                if (isSyntheticAccessor(md, componentFields)) {
                    continue; // 移除合成访问器
                }
                // 过滤合成 record 方法:toString(),hashCode(),equals(Object)
                Integer expectedParams = RECORD_METHOD_PARAM_COUNT.get(md.name());
                if (expectedParams != null && md.parameterNames().length == expectedParams) {
                    continue;
                }
            }
            members.add(m);
        }

        // 从组件构建类型参数列表:"int x, int y"
        // 组件类型用 import 感知的短名渲染(TypeText),并收集缺失的 import,
        // 避免输出 record R(Box<java.util.Map<...>>) 形式的全限定名.
        List<String> recordComponents = new ArrayList<>();
        for (String name : componentNames) {
            // 查找对应字段的类型
            for (AstNode m : td.children()) {
                if (m instanceof FieldDeclaration fd && name.equals(fd.name())) {
                    String typeText = com.bingbaihanji.bdec.util.TypeText.render(
                            fd.type(), unit.packageName(), unit.innerClassNames(),
                            collectedImports);
                    recordComponents.add(typeText + " " + name);
                    break;
                }
            }
        }

        // record 不显式展示 "extends Record"——将 super 名称置为 null
        return new TypeDeclaration(td.accessFlags() & ~ACC_RECORD,
                td.simpleName(), "record", null,
                td.interfaceNames(), recordComponents, members, td.annotations(),
                td.superAnnotations(), td.interfaceAnnotations());
    }

    /**
     * 判断类型声明是否为 record 类.
     * ACC_RECORD 与 ACC_FINAL 共用比特位(0x0010),
     * record 类必须继承 java.lang.Record.
     */
    private boolean isRecord(TypeDeclaration td) {
        if ((td.accessFlags() & ACC_RECORD) == 0) {
            return false;
        }
        String superName = td.superName();
        return "Record".equals(superName) || "java/lang/Record".equals(superName);
    }

    /** 判断字段是否为 private final 类型的 record 组件 */
    private boolean isPrivateFinal(FieldDeclaration fd) {
        int flags = fd.accessFlags();
        return (flags & 0x0002) != 0 && (flags & 0x0010) != 0; // private + final
    }

    /** 检查构造器是否为规范(全字段)构造器 */
    private boolean isCanonicalConstructor(MethodDeclaration md, Set<String> fields) {
        // 构造器名可能为 "<init>" 或类名(由 AstBuilder 名称解析决定)
        if (!"<init>".equals(md.name())) {
            // 若非 <init>,通过 void 返回类型进一步检查是否为构造器
            if (md.returnType() != null && md.returnType().kind() != com.bingbaihanji.bdec.type.TypeKind.VOID) {
                return false; // 是普通方法,不是构造器
            }
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

    /** 检查方法是否为合成访问器(方法名与字段名相同,且无参数) */
    private boolean isSyntheticAccessor(MethodDeclaration md, Set<String> fields) {
        if (md.parameterNames().length != 0) {
            return false;
        }
        return fields.contains(md.name());
    }
}
