package com.bingbaihanji.bdec.ast.rewrite;

import com.bingbaihanji.bdec.DecompileContext;
import com.bingbaihanji.bdec.ast.CompilationUnit;
import com.bingbaihanji.bdec.ast.TypeDeclaration;
import com.bingbaihanji.bdec.bytecode.model.AccessFlags;

import java.util.ArrayList;
import java.util.List;

/**
 * 密封类/接口重写器,用于检测并还原 Java 17+ 的密封类语法.
 * <p>
 * 通过检测字节码中的 ACC_SEALED 标志位({@code 0x1000})识别密封类,
 * 并将其还原为 Java 源码中的 {@code sealed},{@code non-sealed} 和 {@code permits} 关键字.
 * 对于非密封子类,通过加载父类字节码检查父类是否为密封类来判定.
 * </p>
 */
public class SealedClassRewriter implements RewriteRule {

    @Override
    public String name() {return "sealed";}

    @Override
    public CompilationUnit rewrite(CompilationUnit unit, DecompileContext context) {
        List<TypeDeclaration> types = new ArrayList<>();
        for (TypeDeclaration td : unit.types()) {
            types.add(rewriteType(td, unit.packageName(), context));
        }
        return new CompilationUnit(unit.packageName(), unit.imports(), types, unit.innerClassNames());
    }

    /**
     * 递归重写类型声明,检测并标记密封类或非密封子类.
     *
     * @param td      待重写的类型声明
     * @param pkg     当前包名
     * @param context 反编译上下文
     * @return 重写后的类型声明
     */
    private TypeDeclaration rewriteType(TypeDeclaration td, String pkg, DecompileContext context) {
        boolean isSealed = (td.accessFlags() & AccessFlags.ACC_SEALED) != 0;
        if (isSealed) {
            return rewriteSealedType(td);
        }
        // 检查是否为密封父类的非密封子类
        return rewriteNonSealedType(td, pkg, context);
    }

    /**
     * 将带有 ACC_SEALED 标志的类型转换为 sealed 声明.
     * 根据是否为接口分别生成 "sealed interface" 或 "sealed class".
     *
     * @param td 原始类型声明
     * @return 标记为 sealed 的类型声明
     */
    private TypeDeclaration rewriteSealedType(TypeDeclaration td) {
        String kindName = td.isInterface() ? "sealed interface" : "sealed class";
        List<String> typeParams = new ArrayList<>(td.typeParameters());
        return new TypeDeclaration(td.accessFlags() & ~AccessFlags.ACC_SEALED,
                td.simpleName(), kindName, td.superName(),
                td.interfaceNames(), typeParams, td.children(), td.annotations(),
                td.superAnnotations(), td.interfaceAnnotations());
    }

    /**
     * 检测并标记密封父类的非密封子类.
     * <p>
     * 仅适用于非 final,非 abstract,非 sealed 的普通类.
     * 通过加载父类字节码判断父类是否声明为 sealed.
     * </p>
     *
     * @param td      待检测的类型声明
     * @param pkg     当前包名
     * @param context 反编译上下文
     * @return 若父类为密封类则返回标记为 non-sealed 的类型声明,否则返回原类型
     */
    private TypeDeclaration rewriteNonSealedType(TypeDeclaration td, String pkg, DecompileContext context) {
        // 只适用于非 final,非 abstract,非 sealed 的普通类
        if (td.isInterface() || (td.accessFlags() & AccessFlags.ACC_FINAL) != 0
                || (td.accessFlags() & AccessFlags.ACC_ABSTRACT) != 0
                || (td.accessFlags() & AccessFlags.ACC_SEALED) != 0) {
            return td;
        }
        // 必须有父类名才能继续检查
        if (td.superName() == null) {
            return td;
        }
        // 根据包名和父类简称构建 JVM 内部名称
        // (父类名可能带泛型参数如 "Parent<String>",查找密封父类时需剥离)
        String superBase = td.superName();
        int lt = superBase.indexOf('<');
        if (lt >= 0) {
            superBase = superBase.substring(0, lt);
        }
        String internalName = pkg != null && !pkg.isEmpty()
                ? pkg.replace('.', '/') + "/" + superBase
                : superBase;
        // 检查父类是否为密封类
        if (!isSuperclassSealed(internalName, context)) {
            return td;
        }
        // 标记为非密封子类
        return new TypeDeclaration(td.accessFlags(), td.simpleName(),
                "non-sealed class", td.superName(),
                td.interfaceNames(), td.typeParameters(), td.children(), td.annotations(),
                td.superAnnotations(), td.interfaceAnnotations());
    }

    /**
     * 加载父类字节码并检查其是否为密封类.
     * <p>
     * 先尝试通过反编译上下文的类加载器获取字节码,若失败则回退到 JVM 反射.
     * 检测方式包括:ACC_SEALED 标志位(Java 17-21 预览特性)和 PermittedSubclasses 属性(Java 22+).
     * </p>
     *
     * @param internalName 父类的 JVM 内部名称
     * @param context      反编译上下文
     * @return 若父类为密封类返回 {@code true},否则返回 {@code false}
     */
    private boolean isSuperclassSealed(String internalName, DecompileContext context) {
        try {
            // 优先使用反编译上下文的类加载器
            byte[] bytes = context.loadClassBytes(internalName);
            if (bytes == null) {
                // 回退方案:尝试通过 JVM 反射获取
                String className = internalName.replace('/', '.');
                Class<?> c = Class.forName(className);
                return c.isSealed();
            }
            var reader = new com.bingbaihanji.bdec.bytecode.parser.ClassFileReader();
            var model = reader.read(internalName, bytes);
            // Java 17-21 预览特性:ACC_SEALED 标志位(0x1000)
            // Java 22+:PermittedSubclasses 属性(无类标志位)
            return (model.accessFlags() & AccessFlags.ACC_SEALED) != 0
                    || !model.permittedSubclasses().isEmpty();
        } catch (Exception e) {
            return false;
        }
    }
}
