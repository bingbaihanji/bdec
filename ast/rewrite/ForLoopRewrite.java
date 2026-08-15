package com.bingbaihanji.bdec.ast.rewrite;

import com.bingbaihanji.bdec.DecompileContext;
import com.bingbaihanji.bdec.ast.AstNode;
import com.bingbaihanji.bdec.ast.CompilationUnit;
import com.bingbaihanji.bdec.ast.TypeDeclaration;
import com.bingbaihanji.bdec.ast.stmt.MethodDeclaration;
import com.bingbaihanji.bdec.ir.ForLoopRecognizer;

import java.util.ArrayList;
import java.util.List;

/**
 * for 循环重写器:把 {@code int i = 0; while (cond) { ...; i++; }} 还原为
 * {@code for (int i = 0; cond; i++) { ... }}.
 *
 * <p>关键:for 循环的 {@code continue} 会执行增量,而 while 形式中增量在循环体
 * 底部会被 {@code continue} 跳过——带 continue 的 for 若还原成 while 会死循环.
 * 委托 {@link ForLoopRecognizer} 处理(该识别器此前未接线,为死代码).</p>
 */
public final class ForLoopRewrite implements RewriteRule {

    private final ForLoopRecognizer recognizer = new ForLoopRecognizer();

    @Override
    public String name() {return "for-loop";}

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
        List<AstNode> members = new ArrayList<>();
        for (AstNode m : td.children()) {
            if (m instanceof MethodDeclaration md && md.body() instanceof com.bingbaihanji.bdec.ast.stmt.BlockStatement bs) {
                members.add(withBody(md, recognizer.recognize(bs)));
            } else if (m instanceof TypeDeclaration nested) {
                members.add(rewriteType(nested));
            } else {
                members.add(m);
            }
        }
        return withMembers(td, members);
    }
}
