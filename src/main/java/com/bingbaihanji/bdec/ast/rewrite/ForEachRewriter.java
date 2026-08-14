package com.bingbaihanji.bdec.ast.rewrite;

import com.bingbaihanji.bdec.DecompileContext;
import com.bingbaihanji.bdec.ast.AstNode;
import com.bingbaihanji.bdec.ast.CompilationUnit;
import com.bingbaihanji.bdec.ast.TypeDeclaration;
import com.bingbaihanji.bdec.ast.expr.ArrayAccessExpr;
import com.bingbaihanji.bdec.ast.expr.AssignExpr;
import com.bingbaihanji.bdec.ast.expr.BinExpr;
import com.bingbaihanji.bdec.ast.expr.BinaryOperator;
import com.bingbaihanji.bdec.ast.expr.CastExpr;
import com.bingbaihanji.bdec.ast.expr.Expression;
import com.bingbaihanji.bdec.ast.expr.FieldAccessExpr;
import com.bingbaihanji.bdec.ast.expr.InvocationExpr;
import com.bingbaihanji.bdec.ast.expr.LitExpr;
import com.bingbaihanji.bdec.ast.expr.UnExpr;
import com.bingbaihanji.bdec.ast.expr.UnaryOperator;
import com.bingbaihanji.bdec.ast.expr.VarExpr;
import com.bingbaihanji.bdec.ast.stmt.BlockStatement;
import com.bingbaihanji.bdec.ast.stmt.ExpressionStatement;
import com.bingbaihanji.bdec.ast.stmt.IfStatement;
import com.bingbaihanji.bdec.ast.stmt.LoopStatement;
import com.bingbaihanji.bdec.ast.stmt.MethodDeclaration;
import com.bingbaihanji.bdec.ast.stmt.ReturnStatement;
import com.bingbaihanji.bdec.ast.stmt.Statement;
import com.bingbaihanji.bdec.ast.stmt.VariableDeclaration;
import com.bingbaihanji.bdec.type.TypeKind;

import java.util.ArrayList;
import java.util.List;

/**
 * 增强 for-each 循环重写器,检测基于 Iterator 的循环模式,
 * 将其转换为 Java 的 {@code for (E element : collection)} 增强 for-each 循环.
 *
 * <p>可识别的模式:
 * <pre>
 *   Iterator iter = collection.iterator();
 *   while (iter.hasNext()) { E element = iter.next(); ...body... }
 *
 *   → for (E element : collection) { ...body... }
 * </pre>
 *
 * <p>设计参考 CFR 的 {@code IterLoopRewriter}.
 */
public class ForEachRewriter implements RewriteRule {

    /** 未转型引用的哨兵(调用方按 null 处理) */
    private static final com.bingbaihanji.bdec.type.JavaType UNCAST_MARKER =
            com.bingbaihanji.bdec.type.JavaType.INT;

    @Override
    public String name() {return "for-each";}

    @Override
    public RewriteRuleKind kind() {return RewriteRuleKind.FOR_EACH;}

    @Override
    public CompilationUnit rewrite(CompilationUnit unit, DecompileContext context) {
        List<TypeDeclaration> types = new ArrayList<>();
        for (TypeDeclaration td : unit.types()) {
            types.add(rewriteType(td));
        }
        return new CompilationUnit(unit.packageName(), unit.imports(), types, unit.innerClassNames());
    }

    /** 递归重写类型声明中的每个方法体 */
    private TypeDeclaration rewriteType(TypeDeclaration td) {
        List<AstNode> members = new ArrayList<>();
        for (AstNode m : td.children()) {
            if (m instanceof MethodDeclaration md) {
                members.add(withBody(md, md.body() != null ? rewriteBlock(md.body()) : null));
            } else {
                members.add(m);
            }
        }
        return withMembers(td, members);
    }

    /** 递归重写语句块,检测 for-each 模式 */
    private Statement rewriteBlock(Statement s) {
        if (s instanceof BlockStatement bs) {
            List<Statement> rewritten = new ArrayList<>();
            for (Statement child : bs.statements()) {
                rewritten.add(rewriteBlock(child));
            }
            return detectForEach(new BlockStatement(rewritten));
        }
        if (s instanceof LoopStatement ls) {
            return new LoopStatement(ls.loopKind(), ls.initExpr(),
                    ls.condition(), ls.incrExpr(), rewriteBlock(ls.body()));
        }
        if (s instanceof IfStatement i) {
            return new IfStatement(i.condition(),
                    rewriteBlock(i.thenBranch()),
                    i.elseBranch() != null ? rewriteBlock(i.elseBranch()) : null);
        }
        return s;
    }

    /**
     * 遍历代码块中的语句,查找相邻的 Iterator 声明 + while 循环模式,
     * 将其合并为增强 for-each 循环.
     */
    private Statement detectForEach(BlockStatement bs) {
        // 先展开嵌套的 BlockStatement(CFG 分组可能产生不必要的块作用域,
        // 例如将循环前导语句包装在单独的 { } 块中)
        List<Statement> stmts = new ArrayList<>();
        for (Statement s : bs.statements()) {
            if (s instanceof BlockStatement inner) {
                stmts.addAll(inner.statements());
            } else {
                stmts.add(s);
            }
        }
        boolean changed;
        do {
            changed = false;
            for (int i = 0; i < stmts.size(); i++) {
                // 数组 for-each:int[] var2 = a; int var4 = 0; while(var4 < var2.length){...}
                ForEachCandidate arrayResult = matchArrayForEach(stmts, i);
                if (arrayResult != null) {
                    stmts.remove(i + 2);
                    stmts.remove(i + 1);
                    stmts.remove(i);
                    stmts.add(i, buildForEach(arrayResult));
                    changed = true;
                    break;
                }

                if (i >= stmts.size() - 1) {
                    continue;
                }
                Statement s = stmts.get(i);
                // 查找:包含 iterator() 方法调用的表达式语句
                ForEachCandidate candidate = matchIteratorDecl(s);
                if (candidate == null) {
                    continue;
                }

                // 验证下一条语句为 while 循环
                if (!(stmts.get(i + 1) instanceof LoopStatement loop)
                        || loop.loopKind() != LoopStatement.LoopKind.WHILE) {
                    continue;
                }

                // 匹配:while(iter.hasNext())
                ForEachCandidate result = matchWhileLoop(loop, candidate);
                if (result == null) {
                    continue;
                }

                // 用增强 for-each 循环替换 Iterator 声明和 while 循环
                stmts.remove(i + 1);
                stmts.remove(i);
                stmts.add(i, buildForEach(result));
                changed = true;
                break;
            }
        } while (changed);

        return new BlockStatement(stmts);
    }

    /**
     * 根据匹配结果构建增强 for-each 循环:元素类型优先取自循环体转型
     * (如 println((String) element)),否则回退到元素声明类型
     * (如 int x = ((Integer)it.next()).intValue()).
     */
    private LoopStatement buildForEach(ForEachCandidate result) {
        String elemName = ((VarExpr) result.elementVar).name();
        com.bingbaihanji.bdec.type.JavaType elemType = findCastType(result.body, elemName);
        if (elemType == null) {
            elemType = result.elementType;
        }
        if (System.getenv("BDEC_DEBUG_TMP") != null) {
            System.err.println("[FEDBG] elemName=" + elemName + " elemType=" + elemType);
        }
        if (elemType != null) {
            return new LoopStatement(LoopStatement.LoopKind.FOR_EACH,
                    result.elementVar, result.iterableExpr,
                    stripCasts(result.body, elemName), elemType);
        }
        return new LoopStatement(LoopStatement.LoopKind.FOR_EACH,
                result.elementVar, result.iterableExpr, result.body);
    }

    /**
     * 匹配数组增强 for-each 的去糖化形态:
     * <pre>
     *   T[] arr = expr;
     *   int idx = 0;
     *   while (idx &lt; arr.length) { T elem = arr[idx]; ...body...; idx++; }
     * </pre>
     * 还原为 {@code for (T elem : expr) { ...body... }}.
     */
    private ForEachCandidate matchArrayForEach(List<Statement> stmts, int i) {
        if (i + 2 >= stmts.size()) {
            return null;
        }
        // 数组声明:T[] var2 = a;
        Statement s0 = stmts.get(i);
        if (!(s0 instanceof VariableDeclaration arrVd)
                || arrVd.initializer() == null
                || arrVd.type().kind() != TypeKind.ARRAY) {
            return null;
        }
        String arrVar = arrVd.name();
        Expression arrayExpr = arrVd.initializer();

        // 索引声明:int var4 = 0;
        Statement s1 = stmts.get(i + 1);
        if (!(s1 instanceof VariableDeclaration idxVd)
                || idxVd.initializer() == null
                || idxVd.type().kind() != TypeKind.INT
                || !isIntLiteral(idxVd.initializer(), 0)) {
            return null;
        }
        String idxVar = idxVd.name();

        // while(idxVar < arrVar.length)
        Statement s2 = stmts.get(i + 2);
        if (!(s2 instanceof LoopStatement loop)
                || loop.loopKind() != LoopStatement.LoopKind.WHILE
                || !(loop.condition() instanceof BinExpr cond)
                || cond.operator() != BinaryOperator.LT
                || !(cond.left() instanceof VarExpr leftIdx)
                || !idxVar.equals(leftIdx.name())
                || !(cond.right() instanceof FieldAccessExpr len)
                || !"length".equals(len.fieldName())
                || !(len.target() instanceof VarExpr lenArr)
                || !arrVar.equals(lenArr.name())) {
            return null;
        }

        // 循环体:首条 T elem = arr[idx]; 末条 idx++;
        List<Statement> bodyStmts = getBodyStatements(loop.body());
        if (bodyStmts.size() < 2) {
            return null;
        }
        Statement first = bodyStmts.get(0);
        if (!(first instanceof VariableDeclaration elemVd)
                || !(elemVd.initializer() instanceof ArrayAccessExpr aae)
                || !(aae.array() instanceof VarExpr aav)
                || !arrVar.equals(aav.name())
                || !(aae.index() instanceof VarExpr aiv)
                || !idxVar.equals(aiv.name())) {
            return null;
        }
        Statement last = bodyStmts.get(bodyStmts.size() - 1);
        if (!(last instanceof ExpressionStatement incEs)
                || !(incEs.expression() instanceof UnExpr inc)
                || inc.operator() != UnaryOperator.POST_INC
                || !(inc.operand() instanceof VarExpr incVar)
                || !idxVar.equals(incVar.name())) {
            return null;
        }

        // 剩余循环体(去掉首条元素声明与末条 idx++)
        List<Statement> rest = new ArrayList<>(bodyStmts.subList(1, bodyStmts.size() - 1));
        Statement body = rest.isEmpty() ? new BlockStatement(List.of())
                : rest.size() == 1 ? rest.get(0) : new BlockStatement(rest);

        // 元素类型:引用数组的 aaload 被类型推断擦除为 Object,
        // 而数组声明类型携带正确的组件类型(如 String[] → String);
        // 仅在元素变量被推断为 Object 时回退到组件类型,保留协变
        // 场景(Number n : Integer[])的声明类型.
        com.bingbaihanji.bdec.type.JavaType elemType = elemVd.type();
        if (isObjectType(elemType)) {
            com.bingbaihanji.bdec.type.JavaType componentType =
                    com.bingbaihanji.bdec.type.JavaType.elementOf(arrVd.type());
            if (componentType != null && !isObjectType(componentType)) {
                elemType = componentType;
            }
        }
        return new ForEachCandidate(arrVar, arrayExpr,
                new VarExpr(elemVd.name()), body, elemType);
    }

    /** 判断类型是否为裸 java.lang.Object */
    private boolean isObjectType(com.bingbaihanji.bdec.type.JavaType t) {
        return t.kind() == TypeKind.CLASS && "java/lang/Object".equals(t.internalName());
    }

    /** 判断表达式是否为指定整数值的字面量(如 0) */
    private boolean isIntLiteral(Expression e, int value) {
        return e instanceof LitExpr lit
                && lit.value() instanceof Number n
                && n.intValue() == value;
    }

    /** 若循环体中元素变量的所有引用均转型为同一类型,返回该类型;
     *  存在未转型引用或转型目标不一致时返回 null. */
    private com.bingbaihanji.bdec.type.JavaType findCastType(Statement body, String elemName) {
        com.bingbaihanji.bdec.type.JavaType result = null;
        boolean found = false;
        for (Statement st : flatten(body)) {
            com.bingbaihanji.bdec.type.JavaType t = findCastTypeInStmt(st, elemName);
            if (t == UNCAST_MARKER) {
                return null; // 存在未转型的使用——无法合并
            }
            if (t != null) {
                if (found && !result.descriptor().equals(t.descriptor())) {
                    return null; // 转型目标不一致
                }
                result = t;
                found = true;
            }
        }
        return found ? result : null;
    }

    /** 语句中元素变量的转型目标类型;无引用返回 null,未转型返回哨兵 */
    private com.bingbaihanji.bdec.type.JavaType findCastTypeInStmt(Statement st, String elemName) {
        if (st instanceof ExpressionStatement es) {
            return findCastTypeInExpr(es.expression(), elemName);
        }
        if (st instanceof com.bingbaihanji.bdec.ast.stmt.ReturnStatement rs && rs.value() != null) {
            return findCastTypeInExpr(rs.value(), elemName);
        }
        if (st instanceof com.bingbaihanji.bdec.ast.stmt.VariableDeclaration vd && vd.initializer() != null) {
            return findCastTypeInExpr(vd.initializer(), elemName);
        }
        return null;
    }

    private com.bingbaihanji.bdec.type.JavaType findCastTypeInExpr(Expression e, String elemName) {
        if (e == null) {
            return null;
        }
        if (e instanceof com.bingbaihanji.bdec.ast.expr.CastExpr cast
                && cast.operand() instanceof com.bingbaihanji.bdec.ast.expr.VarExpr v
                && elemName.equals(v.name())) {
            return cast.targetType();
        }
        if (e instanceof com.bingbaihanji.bdec.ast.expr.VarExpr v
                && elemName.equals(v.name())) {
            return UNCAST_MARKER;
        }
        if (e instanceof com.bingbaihanji.bdec.ast.expr.BinExpr b) {
            com.bingbaihanji.bdec.type.JavaType l = findCastTypeInExpr(b.left(), elemName);
            if (l == UNCAST_MARKER) {
                return UNCAST_MARKER;
            }
            com.bingbaihanji.bdec.type.JavaType r = findCastTypeInExpr(b.right(), elemName);
            if (r == UNCAST_MARKER) {
                return UNCAST_MARKER;
            }
            if (l != null && r != null && !l.descriptor().equals(r.descriptor())) {
                return UNCAST_MARKER;
            }
            return l != null ? l : r;
        }
        if (e instanceof com.bingbaihanji.bdec.ast.expr.InvocationExpr inv) {
            com.bingbaihanji.bdec.type.JavaType acc = null;
            if (inv.target() != null) {
                acc = findCastTypeInExpr(inv.target(), elemName);
                if (acc == UNCAST_MARKER) {
                    return UNCAST_MARKER;
                }
            }
            for (Expression a : inv.arguments()) {
                com.bingbaihanji.bdec.type.JavaType t = findCastTypeInExpr(a, elemName);
                if (t == UNCAST_MARKER) {
                    return UNCAST_MARKER;
                }
                if (t != null) {
                    if (acc != null && !acc.descriptor().equals(t.descriptor())) {
                        return UNCAST_MARKER;
                    }
                    acc = t;
                }
            }
            return acc;
        }
        return null;
    }

    /** 移除循环体中对元素变量的转型 */
    private Statement stripCasts(Statement s, String elemName) {
        if (s instanceof BlockStatement bs) {
            List<Statement> stmts = new ArrayList<>();
            for (Statement c : bs.statements()) {
                stmts.add(stripCasts(c, elemName));
            }
            return new BlockStatement(stmts);
        }
        if (s instanceof ExpressionStatement es) {
            return new ExpressionStatement(stripCastsInExpr(es.expression(), elemName));
        }
        if (s instanceof com.bingbaihanji.bdec.ast.stmt.ReturnStatement rs && rs.value() != null) {
            return new com.bingbaihanji.bdec.ast.stmt.ReturnStatement(
                    stripCastsInExpr(rs.value(), elemName));
        }
        if (s instanceof com.bingbaihanji.bdec.ast.stmt.VariableDeclaration vd && vd.initializer() != null) {
            return new com.bingbaihanji.bdec.ast.stmt.VariableDeclaration(vd.type(), vd.name(),
                    stripCastsInExpr(vd.initializer(), elemName));
        }
        if (s instanceof com.bingbaihanji.bdec.ast.stmt.IfStatement i) {
            return new com.bingbaihanji.bdec.ast.stmt.IfStatement(
                    stripCastsInExpr(i.condition(), elemName),
                    stripCasts(i.thenBranch(), elemName),
                    i.elseBranch() != null ? stripCasts(i.elseBranch(), elemName) : null);
        }
        return s;
    }

    private Expression stripCastsInExpr(Expression e, String elemName) {
        if (e == null) {
            return null;
        }
        if (e instanceof com.bingbaihanji.bdec.ast.expr.CastExpr cast
                && cast.operand() instanceof com.bingbaihanji.bdec.ast.expr.VarExpr v
                && elemName.equals(v.name())) {
            return v;
        }
        if (e instanceof com.bingbaihanji.bdec.ast.expr.BinExpr b) {
            return new com.bingbaihanji.bdec.ast.expr.BinExpr(b.operator(),
                    stripCastsInExpr(b.left(), elemName),
                    stripCastsInExpr(b.right(), elemName));
        }
        if (e instanceof com.bingbaihanji.bdec.ast.expr.InvocationExpr inv) {
            List<Expression> args = new ArrayList<>();
            for (Expression a : inv.arguments()) {
                args.add(stripCastsInExpr(a, elemName));
            }
            return new com.bingbaihanji.bdec.ast.expr.InvocationExpr(
                    inv.target() != null ? stripCastsInExpr(inv.target(), elemName) : null,
                    inv.methodName(), args, inv.returnType());
        }
        return e;
    }

    /** 将语句展开为扁平列表 */
    private List<Statement> flatten(Statement s) {
        if (s instanceof BlockStatement bs) {
            List<Statement> result = new ArrayList<>();
            for (Statement c : bs.statements()) {
                result.addAll(flatten(c));
            }
            return result;
        }
        return List.of(s);
    }

    /** 匹配:{@code Iterator iter = collection.iterator();}
     *  支持两种形式:ExpressionStatement(AssignExpr) 和 VariableDeclaration. */
    private ForEachCandidate matchIteratorDecl(Statement s) {
        String varName;
        Expression iterableExpr;

        // 形式1:VariableDeclaration——BlockReducer 输出
        //   Iterator var2 = items.iterator()
        if (s instanceof VariableDeclaration vd
                && vd.initializer() instanceof InvocationExpr inv
                && "iterator".equals(inv.methodName())
                && inv.target() != null) {
            varName = vd.name();
            iterableExpr = inv.target();
            return new ForEachCandidate(varName, iterableExpr);
        }

        // 形式2:ExpressionStatement(AssignExpr)——旧的模式
        if (!(s instanceof ExpressionStatement es)) {
            return null;
        }
        if (!(es.expression() instanceof AssignExpr assign)) {
            return null;
        }
        if (!(assign.value() instanceof InvocationExpr inv2)) {
            return null;
        }
        if (!"iterator".equals(inv2.methodName())) {
            return null;
        }
        if (inv2.target() == null) {
            return null;
        }

        // 提取迭代器变量名
        if (assign.target() instanceof VarExpr vx) {
            varName = vx.name();
        } else {
            return null;
        }

        return new ForEachCandidate(varName, inv2.target());
    }

    /** 匹配:{@code while(iter.hasNext()) { E e = iter.next(); ... }} */
    private ForEachCandidate matchWhileLoop(LoopStatement loop, ForEachCandidate candidate) {
        // 检查循环条件:iter.hasNext()
        if (!(loop.condition() instanceof InvocationExpr condInv)) {
            return null;
        }
        if (!"hasNext".equals(condInv.methodName())) {
            return null;
        }
        if (!(condInv.target() instanceof VarExpr var)) {
            return null;
        }
        if (!candidate.iterVar.equals(var.name())) {
            return null;
        }

        // 检查循环体首条语句:E element = iter.next()
        List<Statement> bodyStmts = getBodyStatements(loop.body());
        if (bodyStmts.isEmpty()) {
            return null;
        }

        Statement first = bodyStmts.get(0);
        Expression elementVar = null;
        com.bingbaihanji.bdec.type.JavaType elementType = null;

        // 形式1:VariableDeclaration——如 "String item = (String) iter.next()"
        // 或 "int x = ((Integer) iter.next()).intValue()"(转型/拆箱包裹的 next())
        if (first instanceof VariableDeclaration vd
                && vd.initializer() != null) {
            VarExpr nextIter = extractNextIterVar(vd.initializer());
            if (nextIter != null && candidate.iterVar.equals(nextIter.name())) {
                elementVar = new VarExpr(vd.name());
                elementType = vd.type();
            }
        }
        // 形式2:ExpressionStatement(AssignExpr)——如 "item = iter.next()"
        if (elementVar == null
                && first instanceof ExpressionStatement es
                && es.expression() instanceof AssignExpr assign
                && assign.value() instanceof InvocationExpr inv2
                && "next".equals(inv2.methodName())
                && inv2.target() instanceof VarExpr nextVar2
                && candidate.iterVar.equals(nextVar2.name())) {
            elementVar = assign.target();
        }
        // 形式3:next() 被内联到表达式中——如 "println(iter.next())"
        if (elementVar == null) {
            // 查找循环体中所有 iterVar.next() 调用并替换为元素变量
            String elementName = "element";
            if (!containsNextCall(loop.body(), candidate.iterVar)) {
                return null;
            }
            elementVar = new VarExpr(elementName);
        }

        // 构建新循环体(将 iterVar.next() 调用替换为元素变量引用,
        // 若首个语句为 next() 赋值则同时移除该语句)
        List<Statement> newBodyStmts = new ArrayList<>(bodyStmts);
        // 若首条语句是 next() 赋值,则移除它(已提取为 for-each 元素变量)
        boolean firstIsNextAssign = (first instanceof VariableDeclaration vd
                && vd.initializer() != null
                && isNextOf(vd.initializer(), candidate.iterVar))
                || (first instanceof ExpressionStatement es
                && es.expression() instanceof AssignExpr assign
                && assign.value() instanceof InvocationExpr inv2
                && "next".equals(inv2.methodName())
                && inv2.target() instanceof VarExpr nv2
                && candidate.iterVar.equals(nv2.name()));
        if (firstIsNextAssign) {
            newBodyStmts.remove(0);
        }
        // 将剩余语句中的 iterVar.next() 替换为元素变量
        List<Statement> replacedStmts = new ArrayList<>();
        for (Statement stmt : newBodyStmts) {
            replacedStmts.add(replaceNextCalls(stmt, candidate.iterVar,
                    (VarExpr) elementVar));
        }
        newBodyStmts = replacedStmts;
        Statement newBody;
        if (newBodyStmts.isEmpty()) {
            newBody = new BlockStatement(List.of());
        } else if (newBodyStmts.size() == 1) {
            newBody = newBodyStmts.get(0);
        } else {
            newBody = new BlockStatement(newBodyStmts);
        }

        return new ForEachCandidate(candidate.iterVar, candidate.iterableExpr,
                elementVar, newBody, elementType);
    }

    /**
     * 剥离转型与拆箱包裹层,返回元素声明初始化表达式核心的 {@code iter.next()}
     * 调用目标迭代器变量(形如 {@code ((Integer) iter.next()).intValue()});
     * 若核心不是 {@code iter.next()} 则返回 {@code null}.
     */
    private VarExpr extractNextIterVar(Expression e) {
        Expression cur = e;
        while (true) {
            if (cur instanceof CastExpr c) {
                cur = c.operand();
            } else if (cur instanceof InvocationExpr inv
                    && inv.arguments().isEmpty()
                    && isUnboxingMethod(inv.methodName())
                    && inv.target() != null) {
                cur = inv.target();
            } else {
                break;
            }
        }
        if (cur instanceof InvocationExpr inv
                && "next".equals(inv.methodName())
                && inv.target() instanceof VarExpr v) {
            return v;
        }
        return null;
    }

    /** 判断表达式核心是否为 {@code iterVar.next()}(剥离转型/拆箱) */
    private boolean isNextOf(Expression e, String iterVar) {
        VarExpr v = extractNextIterVar(e);
        return v != null && iterVar.equals(v.name());
    }

    /** 是否为基本类型包装类的拆箱方法(如 {@code intValue()},{@code longValue()}) */
    private boolean isUnboxingMethod(String name) {
        return switch (name) {
            case "intValue", "longValue", "floatValue", "doubleValue",
                 "shortValue", "byteValue", "charValue", "booleanValue" -> true;
            default -> false;
        };
    }

    /** 提取语句中的子语句列表(若为块语句则展开,否则包装为单元素列表) */
    private List<Statement> getBodyStatements(Statement s) {
        if (s instanceof BlockStatement bs) {
            return new ArrayList<>(bs.statements());
        }
        return new ArrayList<>(List.of(s));
    }

    /** 检查语句树中是否包含 {@code iterVar.next()} 调用 */
    private boolean containsNextCall(Statement s, String iterVar) {
        if (s instanceof ExpressionStatement es) {
            return containsNextCallInExpr(es.expression(), iterVar);
        }
        if (s instanceof BlockStatement bs) {
            return bs.statements().stream()
                    .anyMatch(child -> containsNextCall(child, iterVar));
        }
        if (s instanceof LoopStatement ls) {
            return containsNextCall(ls.body(), iterVar);
        }
        if (s instanceof IfStatement i) {
            return containsNextCall(i.thenBranch(), iterVar)
                    || (i.elseBranch() != null && containsNextCall(i.elseBranch(), iterVar));
        }
        return false;
    }

    /** 在表达式中查找 iterVar.next() */
    private boolean containsNextCallInExpr(Expression e, String iterVar) {
        if (e instanceof InvocationExpr inv
                && "next".equals(inv.methodName())
                && inv.target() instanceof VarExpr v
                && iterVar.equals(v.name())) {
            return true;
        }
        if (e instanceof InvocationExpr inv) {
            if (inv.target() != null && containsNextCallInExpr(inv.target(), iterVar)) {
                return true;
            }
            return inv.arguments().stream()
                    .anyMatch(a -> containsNextCallInExpr(a, iterVar));
        }
        if (e instanceof BinExpr bin) {
            return containsNextCallInExpr(bin.left(), iterVar)
                    || containsNextCallInExpr(bin.right(), iterVar);
        }
        if (e instanceof UnExpr un) {
            return containsNextCallInExpr(un.operand(), iterVar);
        }
        if (e instanceof CastExpr cast) {
            return containsNextCallInExpr(cast.operand(), iterVar);
        }
        return false;
    }

    /** 将语句中的 {@code iterVar.next()} 调用替换为 {@code replacement} */
    private Statement replaceNextCalls(Statement s, String iterVar, VarExpr replacement) {
        if (s instanceof ExpressionStatement es) {
            return new ExpressionStatement(
                    replaceNextInExpr(es.expression(), iterVar, replacement));
        }
        if (s instanceof BlockStatement bs) {
            return new BlockStatement(bs.statements().stream()
                    .map(child -> replaceNextCalls(child, iterVar, replacement))
                    .toList());
        }
        if (s instanceof ReturnStatement rs) {
            return new ReturnStatement(rs.value() != null
                    ? replaceNextInExpr(rs.value(), iterVar, replacement) : null);
        }
        if (s instanceof LoopStatement ls) {
            return new LoopStatement(ls.loopKind(), ls.initExpr(),
                    ls.condition(), ls.incrExpr(),
                    replaceNextCalls(ls.body(), iterVar, replacement));
        }
        if (s instanceof IfStatement i) {
            return new IfStatement(
                    i.condition() != null
                            ? replaceNextInExpr(i.condition(), iterVar, replacement) : null,
                    replaceNextCalls(i.thenBranch(), iterVar, replacement),
                    i.elseBranch() != null
                            ? replaceNextCalls(i.elseBranch(), iterVar, replacement) : null);
        }
        return s;
    }

    /** 将表达式中的 {@code iterVar.next()} 替换为 {@code replacement} */
    private Expression replaceNextInExpr(Expression e, String iterVar, VarExpr replacement) {
        if (e instanceof InvocationExpr inv
                && "next".equals(inv.methodName())
                && inv.target() instanceof VarExpr v
                && iterVar.equals(v.name())) {
            return replacement;
        }
        if (e instanceof InvocationExpr inv) {
            List<Expression> newArgs = new ArrayList<>();
            for (Expression arg : inv.arguments()) {
                newArgs.add(replaceNextInExpr(arg, iterVar, replacement));
            }
            return new InvocationExpr(
                    inv.target() != null
                            ? replaceNextInExpr(inv.target(), iterVar, replacement) : null,
                    inv.methodName(), newArgs, inv.returnType());
        }
        if (e instanceof BinExpr bin) {
            return new BinExpr(bin.operator(),
                    replaceNextInExpr(bin.left(), iterVar, replacement),
                    replaceNextInExpr(bin.right(), iterVar, replacement));
        }
        if (e instanceof UnExpr un) {
            return new UnExpr(un.operator(),
                    replaceNextInExpr(un.operand(), iterVar, replacement));
        }
        if (e instanceof CastExpr cast) {
            return new CastExpr(cast.targetType(),
                    replaceNextInExpr(cast.operand(), iterVar, replacement),
                    cast.typeAnnotations());
        }
        return e;
    }

    /**
     * for-each 候选模式数据类,用于在匹配过程中传递上下文信息.
     */
    private static class ForEachCandidate {

        /** 迭代器变量名 */
        final String iterVar;

        /** 可迭代集合/数组表达式 */
        final Expression iterableExpr;

        /** for-each 的循环变量表达式(声明模式中为 null) */
        final Expression elementVar;

        /** 元素变量声明类型(形式1 VariableDeclaration 中提取,其余为 null) */
        final com.bingbaihanji.bdec.type.JavaType elementType;

        /** 循环体语句(声明模式中为 null) */
        final Statement body;

        /** 用于 Iterator 声明模式的构造器 */
        ForEachCandidate(String iterVar, Expression iterableExpr) {
            this.iterVar = iterVar;
            this.iterableExpr = iterableExpr;
            this.elementVar = null;
            this.elementType = null;
            this.body = null;
        }

        /** 用于 while 循环匹配模式的构造器 */
        ForEachCandidate(String iterVar, Expression iterableExpr,
                         Expression elementVar, Statement body,
                         com.bingbaihanji.bdec.type.JavaType elementType) {
            this.iterVar = iterVar;
            this.iterableExpr = iterableExpr;
            this.elementVar = elementVar;
            this.elementType = elementType;
            this.body = body;
        }
    }
}
