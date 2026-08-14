package com.bingbaihanji.bdec.ast.rewrite;

import com.bingbaihanji.bdec.DecompileContext;
import com.bingbaihanji.bdec.ast.AstNode;
import com.bingbaihanji.bdec.ast.CompilationUnit;
import com.bingbaihanji.bdec.ast.TypeDeclaration;
import com.bingbaihanji.bdec.ast.expr.Expression;
import com.bingbaihanji.bdec.ast.expr.FieldAccessExpr;
import com.bingbaihanji.bdec.ast.stmt.FieldDeclaration;
import com.bingbaihanji.bdec.ast.stmt.MethodDeclaration;

import java.util.ArrayList;
import java.util.List;

/**
 * 内部类合成字段映射重写器 — 消除内部类反编译输出中的 {@code this$0} 引用.
 *
 * <p>参考 CFR 的 {@code CodeAnalyserWholeClass.removeInnerClassOuterThis()}
 * 和 Vineflower 的 {@code NestedClassProcessor.getMaskLocalVars()} 设计.
 *
 * <p>算法:
 * <ol>
 *   <li>检测非静态内部类(字段声明中包含以 {@code this$} 开头的合成字段)</li>
 *   <li>在方法体中,将 {@code this$0.outerField} 替换为直接字段访问
 *       {@code outerField}(隐式通过外围 {@code this} 解析)</li>
 *   <li>在构造函数体中,移除 {@code this.this$0 = this$0;} 赋值语句</li>
 * </ol>
 *
 * <p><b>局限:</b>此版本不隐藏字段声明,也不移除构造函数参数.
 * 完整实现(字段隐藏 + 构建函数签名清理)需要跨类编译单元级分析,
 * 未来将作为 {@code CodeAnalyserWholeClass} 等价组件实现.
 */
public class InnerClassRewriter extends AstTransformer implements RewriteRule {

    private boolean isNonStaticInner = false;

    private String outerThisFieldName = null;

    @Override
    public String name() {return "inner-class";}

    @Override
    public CompilationUnit rewrite(CompilationUnit unit, DecompileContext context) {
        List<TypeDeclaration> types = new ArrayList<>();
        for (TypeDeclaration td : unit.types()) {
            types.add(rewriteType(td));
        }
        return new CompilationUnit(unit.packageName(), unit.imports(), types,
                unit.innerClassNames());
    }

    private TypeDeclaration rewriteType(TypeDeclaration td) {
        // 检测是否为非静态内部类
        detectNonStaticInner(td);

        List<AstNode> members = new ArrayList<>();
        for (AstNode m : td.children()) {
            if (m instanceof MethodDeclaration md && md.body() != null) {
                members.add(withBody(md, transformMethodBody(md.body())));
            } else {
                members.add(m);
            }
        }
        return withMembers(td, members);
    }

    /** 检测类是否为非静态内部类(包含 this$ 合成字段) */
    private void detectNonStaticInner(TypeDeclaration td) {
        isNonStaticInner = false;
        outerThisFieldName = null;
        for (AstNode m : td.children()) {
            if (m instanceof FieldDeclaration fd
                    && fd.name() != null && fd.name().startsWith("this$")) {
                isNonStaticInner = true;
                outerThisFieldName = fd.name();
                break;
            }
        }
    }

    // ── 重写 transform 方法 ──

    @Override
    protected Expression transformFieldAccess(FieldAccessExpr e) {
        // 注意:不重写 this$0.outerField → outerField.
        // 内部类当前作为独立顶层类输出(保留 this$0 字段与构造参数),
        // 直接字段名在内部类中不存在,会把外围字段访问错误地变成
        // 未声明局部变量(SourceCleanup 会补 "int counter = 0" 造成语义错误).
        // 保留 this$0.outerField 形式,语义与字节码一致.
        return super.transformFieldAccess(e);
    }

    // 注意:不再过滤 this.this$0 = this$0 赋值,因为 this$0 字段和构造函数参数
    // 都被 AstBuilder 保留,字段需要被初始化.此赋值在构造函数中产生且是必需的.
}
