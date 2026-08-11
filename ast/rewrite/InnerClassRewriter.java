package com.bingbaihanji.bdec.ast.rewrite;

import com.bingbaihanji.bdec.DecompileContext;
import com.bingbaihanji.bdec.ast.AstNode;
import com.bingbaihanji.bdec.ast.CompilationUnit;
import com.bingbaihanji.bdec.ast.TypeDeclaration;
import com.bingbaihanji.bdec.ast.expr.*;
import com.bingbaihanji.bdec.ast.stmt.*;

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
                members.add(new MethodDeclaration(md.accessFlags(), md.name(),
                        md.returnType(), md.parameterNames(), md.parameterTypes(),
                        transformMethodBody(md.body())));
            } else {
                members.add(m);
            }
        }
        return new TypeDeclaration(td.accessFlags(), td.simpleName(), td.kindName(),
                td.superName(), td.interfaceNames(), td.typeParameters(), members);
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
        if (isNonStaticInner && outerThisFieldName != null) {
            // this$0.outerField → outerField(直接访问外围字段)
            // 例如:this$0.counter → counter
            if (e.target() instanceof VarExpr v
                    && outerThisFieldName.equals(v.name())) {
                return new VarExpr(e.fieldName());
            }
            // 注意:不将 this.this$0 重写为 this,因为在赋值左侧时
            // this = X 是非法Java代码.this$0 字段初始化需要保留为
            // this.this$0 = this$0 的形式.
        }
        return super.transformFieldAccess(e);
    }

    // 注意:不再过滤 this.this$0 = this$0 赋值,因为 this$0 字段和构造函数参数
    // 都被 AstBuilder 保留,字段需要被初始化.此赋值在构造函数中产生且是必需的.
}
