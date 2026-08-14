# -*- coding: utf-8 -*-
"""Batch D: move translateBranchGroup + translateBranchBody from BlockReducer to IfTranslator.
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

def javadoc_start(i):
    j = i - 1
    while j >= 0:
        s = lines[j].strip()
        if s.startswith("/**") or s.startswith("*") or s.startswith("*/"):
            j -= 1
            continue
        break
    return j + 1

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

def cut(anchor):
    i = find_line(anchor)
    a = javadoc_start(i)
    b = body_end(i)
    chunk = lines[a:b + 1]
    for k in range(b, a - 1, -1):
        del lines[k]
    return chunk

def transform(chunk, pairs):
    t = "\n".join(chunk)
    for old, new, cnt in pairs:
        n = t.count(old)
        assert n == cnt, f"transform target found {n} times (expected {cnt}) in chunk: {old[:70]!r}"
        t = t.replace(old, new)
    return t.split("\n")

def replace_line(old, new_lines):
    i = find_line(old)
    lines[i:i + 1] = new_lines

# ---- 1. cut both methods ----
c_branchGroup = cut("    private Statement translateBranchGroup(BlockGroup group, LinearIr ir,")
c_branchBody = cut("    private Statement translateBranchBody(Set<BasicBlock> branchBlocks,")

# ---- 2. transform translateBranchGroup ----
c_branchGroup = transform(c_branchGroup, [
    ("    private Statement translateBranchGroup(BlockGroup group, LinearIr ir,\n"
     "                                           List<BlockGroup> allGroups,\n"
     "                                           Set<BlockGroup> consumed,\n"
     "                                           ControlFlowGraph graph,\n"
     "                                           PostDominatorTree postDom) {",
     "    static Statement translateBranchGroup(ReducerOps ops, BlockGroup group, LinearIr ir,\n"
     "                                          List<BlockGroup> allGroups,\n"
     "                                          Set<BlockGroup> consumed,\n"
     "                                          ControlFlowGraph graph,\n"
     "                                          PostDominatorTree postDom) {", 1),
    ("            SwitchInfo swInfo = currentSwitchAnns.get(gb);",
     "            SwitchInfo swInfo = ops.switchAnnotation(gb);", 1),
    ("                return SwitchTranslator.buildSwitch(this, swInfo, group, ir, allGroups, consumed, graph);",
     "                return SwitchTranslator.buildSwitch(ops, swInfo, group, ir, allGroups, consumed, graph);", 1),
    ("            LoopInfo lpInfo = currentLoopAnns.get(gb);",
     "            LoopInfo lpInfo = ops.loopAnnotation(gb);", 1),
    ("                    Statement body = LoopTranslator.translateLoopBodyStructured(this, lpInfo, allGroups, ir,",
     "                    Statement body = LoopTranslator.translateLoopBodyStructured(ops, lpInfo, allGroups, ir,", 1),
    ("                        return LoopTranslator.wrapLoopStatement(this, lpInfo, body, extractCondition(group, ir));",
     "                        return LoopTranslator.wrapLoopStatement(ops, lpInfo, body, ops.extractCondition(group, ir));", 1),
    ("                Statement body = translateGroup(group, ir);",
     "                Statement body = ops.translateGroup(group, ir);", 1),
    ("                return LoopTranslator.wrapLoopStatement(this, lpInfo, body, extractCondition(group, ir));",
     "                return LoopTranslator.wrapLoopStatement(ops, lpInfo, body, ops.extractCondition(group, ir));", 1),
    ("            Statement st = translateGroup(group, ir);",
     "            Statement st = ops.translateGroup(group, ir);", 1),
    ("            Statement syncBody = TryTranslator.collectSyncBody(this, group, allGroups, consumed, ir, graph);",
     "            Statement syncBody = TryTranslator.collectSyncBody(ops, group, allGroups, consumed, ir, graph);", 1),
    ("        IfInfo nestedIf = IfTranslator.detectIfHeader(group, graph, ir, postDom);",
     "        IfInfo nestedIf = detectIfHeader(group, graph, ir, postDom);", 1),
    ("            Expression cond = AstCleanup.simplifyCondition(extractCondition(group, ir));",
     "            Expression cond = AstCleanup.simplifyCondition(ops.extractCondition(group, ir));", 1),
    ("            List<Statement> preIfStmts = translateHeaderNonCondition(group, ir);",
     "            List<Statement> preIfStmts = ops.translateHeaderNonCondition(group, ir);", 1),
    ("            Statement thenBody = translateBranchBody(nestedIf.thenBlocks(), allGroups,",
     "            Statement thenBody = translateBranchBody(ops, nestedIf.thenBlocks(), allGroups,", 1),
    ("                elseBody = translateBranchBody(nestedIf.elseBlocks(), allGroups,",
     "                elseBody = translateBranchBody(ops, nestedIf.elseBlocks(), allGroups,", 1),
    ("        return translateGroup(group, ir);",
     "        return ops.translateGroup(group, ir);", 1),
])

# ---- 3. transform translateBranchBody ----
c_branchBody = transform(c_branchBody, [
    ("    private Statement translateBranchBody(Set<BasicBlock> branchBlocks,\n"
     "                                          List<BlockGroup> allGroups,\n"
     "                                          LinearIr ir,\n"
     "                                          Set<BlockGroup> consumed,\n"
     "                                          ControlFlowGraph graph,\n"
     "                                          PostDominatorTree postDom) {",
     "    static Statement translateBranchBody(ReducerOps ops, Set<BasicBlock> branchBlocks,\n"
     "                                         List<BlockGroup> allGroups,\n"
     "                                         LinearIr ir,\n"
     "                                         Set<BlockGroup> consumed,\n"
     "                                         ControlFlowGraph graph,\n"
     "                                         PostDominatorTree postDom) {", 1),
    ("        Set<Integer> prevBranchBlocks = currentBranchBlocks;",
     "        Set<Integer> prevBranchBlocks = ops.currentBranchBlocks();", 1),
    ("        currentBranchBlocks = branchBlockIds;",
     "        ops.setCurrentBranchBlocks(branchBlockIds);", 1),
    ("        declaredVarNameStack.push(new HashSet<>());",
     "        ops.pushDeclaredScope();", 1),
    ("                    Statement stmt = translateBranchGroup(g, ir, allGroups, consumed, graph, postDom);",
     "                    Statement stmt = translateBranchGroup(ops, g, ir, allGroups, consumed, graph, postDom);", 1),
    ("            List<TryCatchInfo> branchTcis = TryTranslator.collectBranchTryAnns(\n"
     "                    branchBlocks, currentTryCatchAnns, ir);",
     "            List<TryCatchInfo> branchTcis = TryTranslator.collectBranchTryAnns(\n"
     "                    branchBlocks, ops.tryCatchAnnotations(), ir);", 1),
    ("                bodyStmts = TryTranslator.wrapTryStatements(this, bodyStmts, bodyGroupIdx, allGroups,",
     "                bodyStmts = TryTranslator.wrapTryStatements(ops, bodyStmts, bodyGroupIdx, allGroups,", 1),
    ("            currentBranchBlocks = prevBranchBlocks;",
     "            ops.setCurrentBranchBlocks(prevBranchBlocks);", 1),
    ("            declaredVarNameStack.pop(); // 弹出分支作用域",
     "            ops.popDeclaredScope(); // 弹出分支作用域", 1),
])

# ---- 4. update remaining call sites in BlockReducer ----
replace_line("                Statement thenBody = translateBranchBody(ifInfo.thenBlocks(), groups, ir, consumed, graph, postDom);",
             ["                Statement thenBody = IfTranslator.translateBranchBody(this, ifInfo.thenBlocks(), groups, ir, consumed, graph, postDom);"])
replace_line("                    elseBody = translateBranchBody(ifInfo.elseBlocks(), groups, ir, consumed, graph, postDom);",
             ["                    elseBody = IfTranslator.translateBranchBody(this, ifInfo.elseBlocks(), groups, ir, consumed, graph, postDom);"])

open(SRC, "w", encoding="utf-8", newline="").write("\n".join(lines))

# ---- 5. append to IfTranslator.java ----
dst_lines = open(DST, encoding="utf-8").read().split("\n")
assert dst_lines[-1] == "}" and dst_lines[-2] == "", (dst_lines[-3], dst_lines[-2], dst_lines[-1])
dst_lines = dst_lines[:-1]  # drop closing '}'
dst_lines = dst_lines + c_branchGroup + [""] + c_branchBody + [""] + ["}"]
open(DST, "w", encoding="utf-8", newline="").write("\n".join(dst_lines))

print("BATCH D OK")
print(f"BlockReducer lines now: {len(lines)}")
print(f"IfTranslator lines: {len(dst_lines)}")
