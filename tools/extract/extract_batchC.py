# -*- coding: utf-8 -*-
"""Batch C: extract reduce()'s if-branch into IfTranslator.translateIf.
Deterministic: exact anchors, bracket-pair scanning, exact replacement counts."""

SRC = "D:/bingbaihanji/fxdecomplie/bdec-agentB/src/main/java/com/bingbaihanji/bdec/structuring/BlockReducer.java"
DST = "D:/bingbaihanji/fxdecomplie/bdec-agentB/src/main/java/com/bingbaihanji/bdec/structuring/IfTranslator.java"

text = open(SRC, encoding="utf-8").read()
assert "\n" in text
lines = text.split("\n")

def find_line(needle):
    idxs = [i for i, l in enumerate(lines) if l == needle]
    assert len(idxs) == 1, f"anchor {needle!r} found {len(idxs)} times"
    return idxs[0]

def body_end(i):
    depth = 0
    in_block = in_str = in_char = False
    k = i
    while k < len(lines):
        line = lines[k]
        in_line = False
        p = 0
        while p < len(line):
            c = line[p]
            nxt = line[p + 1] if p + 1 < len(line) else ""
            if in_block:
                if c == "*" and nxt == "/":
                    in_block = False; p += 2; continue
                p += 1; continue
            if in_line:
                p += 1; continue
            if in_str:
                if c == "\\": p += 2; continue
                if c == '"': in_str = False
                p += 1; continue
            if in_char:
                if c == "\\": p += 2; continue
                if c == "'": in_char = False
                p += 1; continue
            if c == "/" and nxt == "/":
                in_line = True; p += 2; continue
            if c == "/" and nxt == "*":
                in_block = True; p += 2; continue
            if c == '"': in_str = True; p += 1; continue
            if c == "'": in_char = True; p += 1; continue
            if c == "{": depth += 1
            if c == "}":
                depth -= 1
                if depth == 0:
                    return k
            p += 1
        k += 1
    raise AssertionError(f"no matching close brace after line {i}")

# ---- 1. cut the if-branch chunk from reduce() ----
start = find_line("            // if-else:构建包含 then 和 else 体的完整 IfStatement")
if_open = find_line("            if (ifInfo != null) {")
end = body_end(if_open)
assert lines[end].strip() == "}", lines[end]
chunk = lines[start:end + 1]
del lines[start:end + 1]
lines[start:start] = [
    "            // if-else:构建包含 then 和 else 体的完整 IfStatement",
    "            //(翻译逻辑见 IfTranslator.translateIf).",
    "            if (ifInfo != null) {",
    "                s = IfTranslator.translateIf(this, ifInfo, group, ir, groups, consumed, graph, postDom);",
    "                statements.add(s);",
    "                stmtGroupIdx.add(gi);",
    "                continue;",
    "            }",
]

# ---- 2. transform the chunk into translateIf (keep original indentation; stripped later) ----
t = "\n".join(chunk)
pairs = [
    ("                Expression rawCond = extractCondition(group, ir);",
     "                Expression rawCond = ops.extractCondition(group, ir);", 1),
    ("                if (rawCond == null && ifInfo != null) {",
     "                if (rawCond == null) {", 2),
    ("                    rawCond = extractConditionFromHeader(ifInfo.header(), ir);",
     "                    rawCond = ops.extractConditionFromHeader(ifInfo.header(), ir);", 1),
    ("                    rawCond = extractConditionFromAllGroups(groups, ir);",
     "                    rawCond = ops.extractConditionFromAllGroups(groups, ir);", 1),
    ("                List<Statement> preIfStmts = translateHeaderNonCondition(group, ir);",
     "                List<Statement> preIfStmts = ops.translateHeaderNonCondition(group, ir);", 1),
    ("                Statement thenBody = IfTranslator.translateBranchBody(this, ifInfo.thenBlocks(), groups, ir, consumed, graph, postDom);",
     "                Statement thenBody = translateBranchBody(ops, ifInfo.thenBlocks(), groups, ir, consumed, graph, postDom);", 1),
    ("                    elseBody = IfTranslator.translateBranchBody(this, ifInfo.elseBlocks(), groups, ir, consumed, graph, postDom);",
     "                    elseBody = translateBranchBody(ops, ifInfo.elseBlocks(), groups, ir, consumed, graph, postDom);", 1),
    ("                    Set<Integer> prevCtx = currentBranchBlocks;\n"
     "                    try {\n"
     "                        currentBranchBlocks = new HashSet<>();\n"
     "                        for (BasicBlock tb : ifInfo.thenBlocks()) {\n"
     "                            currentBranchBlocks.add(tb.id());\n"
     "                        }\n"
     "                        trueVal = resolvePhiAt(ifFollow, ir);\n"
     "                    } finally {\n"
     "                        currentBranchBlocks = prevCtx;\n"
     "                    }",
     "                    Set<Integer> prevCtx = ops.currentBranchBlocks();\n"
     "                    Set<Integer> branchCtx = new HashSet<>();\n"
     "                    for (BasicBlock tb : ifInfo.thenBlocks()) {\n"
     "                        branchCtx.add(tb.id());\n"
     "                    }\n"
     "                    try {\n"
     "                        ops.setCurrentBranchBlocks(branchCtx);\n"
     "                        trueVal = ops.resolvePhiAt(ifFollow, ir);\n"
     "                    } finally {\n"
     "                        ops.setCurrentBranchBlocks(prevCtx);\n"
     "                    }", 1),
    ("                        s = new com.bingbaihanji.bdec.ast.stmt.ReturnStatement(retCond);\n"
     "                        if (s != null) {\n"
     "                            statements.add(s);\n"
     "                            stmtGroupIdx.add(gi);\n"
     "                        }\n"
     "                        continue; // 跳过常规 IfStatement 构建",
     "                        return new com.bingbaihanji.bdec.ast.stmt.ReturnStatement(retCond);", 1),
    ("                    s = new BlockStatement(combined);\n"
     "                    if (s != null) {\n"
     "                        statements.add(s);\n"
     "                        stmtGroupIdx.add(gi);\n"
     "                    }\n"
     "                    continue;",
     "                    return new BlockStatement(combined);", 1),
    ("                s = new IfStatement(cond != null ? cond : new com.bingbaihanji.bdec.ast.expr.LitExpr(true, JavaType.BOOLEAN),",
     "                Statement s = new IfStatement(cond != null ? cond : new com.bingbaihanji.bdec.ast.expr.LitExpr(true, JavaType.BOOLEAN),", 1),
]
for old, new, cnt in pairs:
    n = t.count(old)
    assert n == cnt, f"transform target found {n} times (expected {cnt}) in chunk: {old[:70]!r}"
    t = t.replace(old, new)

# ---- 3. re-indent: strip 8 leading spaces from every non-blank line ----
chunk2 = t.split("\n")
chunk2 = [l[8:] if l.strip() else l for l in chunk2]
# chunk2 = [comment, 'if (ifInfo != null) {', ...body..., '}' (if-wrapper close)]
assert chunk2[1].strip() == "if (ifInfo != null) {", chunk2[1]
assert chunk2[-1].strip() == "}", chunk2[-1]

# ---- 4. wrap with javadoc + signature (drop the redundant if-wrapper), append 'return s;' ----
method = [
    "    /**",
    "     * 翻译 if 结构(ifInfo → IfStatement),reduce() 主循环的 if 分支.",
    "     *",
    "     * <p>包含:条件提取回退链(组内 → IfInfo 头部 → 全局扫描)、布尔 return",
    "     * 折叠(空分支 + 尾部 PHI → {@code return cond})、short-branch-first",
    "     * 规范化(大 then + 简单终止 else → {@code if (!cond) { 小 } 大块}).</p>",
    "     */",
    "    static Statement translateIf(ReducerOps ops, IfInfo ifInfo, BlockGroup group, LinearIr ir,",
    "                                 List<BlockGroup> groups, Set<BlockGroup> consumed,",
    "                                 ControlFlowGraph graph, PostDominatorTree postDom) {",
] + chunk2[2:-1] + [
    "        return s;",
    "    }",
]

open(SRC, "w", encoding="utf-8", newline="").write("\n".join(lines))

# ---- 5. insert translateIf into IfTranslator right after the constructor ----
dst_lines = open(DST, encoding="utf-8").read().split("\n")
i_ctor = dst_lines.index("    private IfTranslator() {}")
assert dst_lines[i_ctor + 1] == "", dst_lines[i_ctor + 1]
dst_lines[i_ctor + 1:i_ctor + 1] = method + [""]
open(DST, "w", encoding="utf-8", newline="").write("\n".join(dst_lines))

print("BATCH C OK")
print(f"BlockReducer lines now: {len(lines)}")
print(f"IfTranslator lines: {len(dst_lines)}")
