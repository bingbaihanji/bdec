package com.bingbaihanji.bdec.ast.rewrite;

import com.bingbaihanji.bdec.DecompileContext;
import com.bingbaihanji.bdec.ast.AstNode;
import com.bingbaihanji.bdec.ast.CompilationUnit;
import com.bingbaihanji.bdec.ast.TypeDeclaration;
import com.bingbaihanji.bdec.ast.expr.AssignExpr;
import com.bingbaihanji.bdec.ast.expr.FieldAccessExpr;
import com.bingbaihanji.bdec.ast.expr.InvocationExpr;
import com.bingbaihanji.bdec.ast.expr.VarExpr;
import com.bingbaihanji.bdec.ast.stmt.BlockStatement;
import com.bingbaihanji.bdec.ast.stmt.ExpressionStatement;
import com.bingbaihanji.bdec.ast.stmt.FieldDeclaration;
import com.bingbaihanji.bdec.ast.stmt.MethodDeclaration;
import com.bingbaihanji.bdec.ast.stmt.ReturnStatement;
import com.bingbaihanji.bdec.ast.stmt.Statement;

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
                    // 规范构造器被移除,但其带有的紧凑构造器体(字段赋值之外的
                    // 显式语句)需还原为 "RecordName { ... }" 紧凑构造器.
                    List<Statement> compactBody = extractCompactBody(md, componentFields);
                    if (!compactBody.isEmpty()) {
                        members.add(buildCompactConstructor(md, td, compactBody));
                    }
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

    /**
     * 从规范构造器体中提取紧凑构造器的显式语句.
     * <p>
     * javac 将紧凑构造器 {@code Range { ... }} 反糖为规范构造器:
     * {@code Range(int lo, int hi) { super(); ...; this.lo = lo; this.hi = hi; }}.
     * 因此需剥离前导 {@code super()},字段赋值({@code this.f = f})与末尾
     * {@code return},余下语句即为紧凑构造器体.字段赋值可能被 BlockReducer
     * 收进一个尾块(与末尾 return 同处),需一并识别.
     * </p>
     *
     * @param md    规范构造器
     * @param fields record 组件字段名集合
     * @return 紧凑构造器体语句(按原顺序);若无显式语句则返回空列表
     */
    private List<Statement> extractCompactBody(MethodDeclaration md, Set<String> fields) {
        if (!(md.body() instanceof BlockStatement)) {
            return List.of();
        }
        // BlockReducer 可能把字段赋值与紧凑构造器语句交错收进嵌套块
        // (如 [super(), if1, Block[if2, this.a=a, this.b=b]]),需先递归展平
        // 才能把规范反糖(字段赋值)与用户语句彻底分离.
        List<Statement> flat = new ArrayList<>();
        flatten(md.body(), flat);
        // 剥离前导 super()(无参 super 调用)
        if (!flat.isEmpty() && isSuperCall(flat.get(0))) {
            flat.remove(0);
        }
        // 剥离末尾的无返回值 return(规范构造器的隐式收尾)
        if (!flat.isEmpty() && isBareReturn(flat.get(flat.size() - 1))) {
            flat.remove(flat.size() - 1);
        }
        // 剥离字段赋值(this.f = ...),它们由 record 关键字自动生成
        flat.removeIf(s -> isFieldAssignment(s, fields));
        return flat;
    }

    /** 递归展平块语句,把嵌套块内的语句提升到顶层(保序). */
    private void flatten(Statement s, List<Statement> out) {
        if (s instanceof BlockStatement bs) {
            for (Statement inner : bs.statements()) {
                flatten(inner, out);
            }
        } else {
            out.add(s);
        }
    }

    /**
     * 将提取出的紧凑构造器体构建为紧凑构造器声明节点.
     * 紧凑构造器无参数列表,发射为 {@code RecordName { ... }}.
     */
    private MethodDeclaration buildCompactConstructor(MethodDeclaration md,
                                                      TypeDeclaration td,
                                                      List<Statement> compactBody) {
        BlockStatement body = new BlockStatement(compactBody);
        return new MethodDeclaration(md.accessFlags(), td.simpleName(), md.returnType(),
                new String[0], new com.bingbaihanji.bdec.type.JavaType[0],
                List.of(), md.throwsTypes(), md.annotationDefault(), md.annotations(),
                md.parameterAnnotations(), md.typeAnnotations(), body, true);
    }

    /** 检查语句是否为无参 {@code super()} 调用. */
    private boolean isSuperCall(Statement s) {
        if (!(s instanceof ExpressionStatement es)
                || !(es.expression() instanceof InvocationExpr inv)) {
            return false;
        }
        return "super".equals(inv.methodName()) && inv.arguments().isEmpty();
    }

    /** 检查语句是否为 record 组件字段赋值({@code this.f = ...}). */
    private boolean isFieldAssignment(Statement s, Set<String> fields) {
        if (!(s instanceof ExpressionStatement es)
                || !(es.expression() instanceof AssignExpr a)
                || !(a.target() instanceof FieldAccessExpr fa)) {
            return false;
        }
        if (!fields.contains(fa.fieldName())) {
            return false;
        }
        // 接收者为 this(显式 this 或隐式 this)
        if (fa.target() == null) {
            return true;
        }
        return fa.target() instanceof VarExpr v && "this".equals(v.name());
    }

    /** 检查语句是否为无返回值的 {@code return;}. */
    private boolean isBareReturn(Statement s) {
        return s instanceof ReturnStatement r && r.value() == null;
    }
}
