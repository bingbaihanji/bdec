# -*- coding: utf-8 -*-
"""Batch A: extract try/synchronized cluster from BlockReducer into TryTranslator.
Deterministic: exact anchors, bracket-pair scanning, exact replacement counts."""
import sys

SRC = "D:/bingbaihanji/fxdecomplie/bdec-agentB/src/main/java/com/bingbaihanji/bdec/structuring/BlockReducer.java"
DST = "D:/bingbaihanji/fxdecomplie/bdec-agentB/src/main/java/com/bingbaihanji/bdec/structuring/TryTranslator.java"

text = open(SRC, encoding="utf-8").read()
assert "\n" in text, "expected LF"
lines = text.split("\n")

def find_line(needle):
    idxs = [i for i, l in enumerate(lines) if l == needle]
    assert len(idxs) == 1, f"anchor {needle!r} found {len(idxs)} times"
    return idxs[0]

def javadoc_start(i):
    """scan upward over contiguous comment lines (stop at first non-comment line)."""
    j = i - 1
    while j >= 0:
        s = lines[j].strip()
        if s.startswith("/**") or s.startswith("*") or s.startswith("*/"):
            j -= 1
            continue
        break
    return j + 1

def body_end(i):
    """bracket pairing scan from the first '{' at or after the signature line."""
    depth = 0
    in_block = in_line = in_str = in_char = False
    k = i
    while k < len(lines):
        line = lines[k]
        in_line = False  # line comments end at line boundary
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

def replace_line(old, new_lines):
    """Replace the single line == old with the list new_lines (spliced)."""
    i = find_line(old)
    lines[i:i + 1] = new_lines

def transform(chunk, pairs):
    """pairs: list of (old, new, expected_count). Apply exact replacements to '\n'-joined chunk."""
    t = "\n".join(chunk)
    for old, new, cnt in pairs:
        n = t.count(old)
        assert n == cnt, f"transform target found {n} times (expected {cnt}) in chunk: {old[:70]!r}"
        t = t.replace(old, new)
    return t.split("\n")

# ---- 1. cut all 12 try-cluster methods ----
c_wrapTryCatch = cut("    private BlockStatement wrapTryCatchBlocks(BlockStatement root,")
c_wrapTryStmts = cut("    private List<Statement> wrapTryStatements(List<Statement> stmts,")
c_isSyncHandler = cut("    private boolean isSyncHandlerGroup(BlockGroup group, LinearIr ir) {")
c_groupHasSync = cut("    private boolean groupHasSynchronizedAnnotation(BlockGroup group, LinearIr ir) {")
c_isSync = cut("    private boolean isSynchronizedHandler(TryCatchInfo info, LinearIr ir) {")
c_isMatch = cut("    private boolean isMatchExceptionHandler(TryCatchInfo info, LinearIr ir) {")
c_extractMon = cut("    private String extractMonitorObject(TryCatchInfo info, LinearIr ir) {")
c_collectSync = cut("    private Statement collectSyncBody(BlockGroup group, List<BlockGroup> allGroups,")
c_wrapSync = cut("    private SynchronizedStatement wrapSynchronized(Statement body,")
c_buildHandler = cut("    private BlockGroup buildHandlerBlockGroup(TryCatchInfo info, LinearIr ir) {")
c_collectHandler = cut("    private List<IrInstruction> collectHandlerInstructions(TryCatchInfo info, LinearIr ir) {")
c_buildTryCatch = cut("    private TryStatement buildTryCatch(TryCatchInfo info, Statement tryBody, LinearIr ir) {")

# ---- 2. TCI block in translateBranchBody -> TryTranslator.collectBranchTryAnns call ----
tci_start = find_line("            // 将分支体内的 try 区域包装为 TryStatement.")
tci_end = None
for k in range(tci_start, len(lines)):
    if lines[k].strip() == "if (!branchTcis.isEmpty()) {":
        tci_end = k + 4
        break
assert tci_end is not None, "TCI block end not found"
tci_k = tci_end - 4  # line index of the 'if (!branchTcis.isEmpty()) {' wrapper
# skip the 4 leading comment lines and the branchTcis declaration line
assert lines[tci_start + 4].strip().startswith("List<TryCatchInfo> branchTcis"), lines[tci_start + 4]
tci_chunk = lines[tci_start + 5:tci_k]  # collection loop only
del lines[tci_start:tci_end]
lines[tci_start:tci_start] = [
    "            // 将分支体内的 try 区域包装为 TryStatement(区域收集逻辑见",
    "            // TryTranslator.collectBranchTryAnns).",
    "            List<TryCatchInfo> branchTcis = TryTranslator.collectBranchTryAnns(",
    "                    branchBlocks, currentTryCatchAnns, ir);",
    "            if (!branchTcis.isEmpty()) {",
    "                bodyStmts = TryTranslator.wrapTryStatements(this, bodyStmts, bodyGroupIdx, allGroups,",
    "                        branchTcis, ir);",
    "            }",
]

# ---- 3. transform cut chunks for TryTranslator ----
c_wrapTryCatch = transform(c_wrapTryCatch, [
    ("    private BlockStatement wrapTryCatchBlocks(BlockStatement root,\n"
     "                                              List<Integer> stmtGroupIdxSrc,\n"
     "                                              List<BlockGroup> groups,\n"
     "                                              List<TryCatchInfo> tryCatchAnns,\n"
     "                                              LinearIr ir) {",
     "    static BlockStatement wrapTryCatchBlocks(ReducerOps ops,\n"
     "                                             BlockStatement root,\n"
     "                                             List<Integer> stmtGroupIdxSrc,\n"
     "                                             List<BlockGroup> groups,\n"
     "                                             List<TryCatchInfo> tryCatchAnns,\n"
     "                                             LinearIr ir) {", 1),
    ("        List<Statement> wrapped = wrapTryStatements(stmts, stmtGroupIdxSrc, groups,\n"
     "                tryCatchAnns, ir);",
     "        List<Statement> wrapped = wrapTryStatements(ops, stmts, stmtGroupIdxSrc, groups,\n"
     "                tryCatchAnns, ir);", 1),
])

c_wrapTryStmts = transform(c_wrapTryStmts, [
    ("    private List<Statement> wrapTryStatements(List<Statement> stmts,\n"
     "                                              List<Integer> stmtGroupIdx,\n"
     "                                              List<BlockGroup> groups,\n"
     "                                              List<TryCatchInfo> tryCatchAnns,\n"
     "                                              LinearIr ir) {",
     "    static List<Statement> wrapTryStatements(ReducerOps ops,\n"
     "                                             List<Statement> stmts,\n"
     "                                             List<Integer> stmtGroupIdx,\n"
     "                                             List<BlockGroup> groups,\n"
     "                                             List<TryCatchInfo> tryCatchAnns,\n"
     "                                             LinearIr ir) {", 1),
    ("                    out.set(firstStmt, buildTryCatch(tci, tryBody, ir));",
     "                    out.set(firstStmt, buildTryCatch(ops, tci, tryBody, ir));", 1),
])

c_isSyncHandler = transform(c_isSyncHandler, [
    ("    private boolean isSyncHandlerGroup(BlockGroup group, LinearIr ir) {",
     "    static boolean isSyncHandlerGroup(BlockGroup group, LinearIr ir) {", 1),
])

c_groupHasSync = transform(c_groupHasSync, [
    ("    private boolean groupHasSynchronizedAnnotation(BlockGroup group, LinearIr ir) {",
     "    static boolean groupHasSynchronizedAnnotation(BlockGroup group, LinearIr ir) {", 1),
])

c_isSync = transform(c_isSync, [
    ("    private boolean isSynchronizedHandler(TryCatchInfo info, LinearIr ir) {",
     "    static boolean isSynchronizedHandler(TryCatchInfo info, LinearIr ir) {", 1),
])

c_isMatch = transform(c_isMatch, [
    ("    private boolean isMatchExceptionHandler(TryCatchInfo info, LinearIr ir) {",
     "    static boolean isMatchExceptionHandler(TryCatchInfo info, LinearIr ir) {", 1),
])

c_extractMon = transform(c_extractMon, [
    ("    private String extractMonitorObject(TryCatchInfo info, LinearIr ir) {",
     "    static String extractMonitorObject(TryCatchInfo info, LinearIr ir) {", 1),
])

c_collectSync = transform(c_collectSync, [
    ("    private Statement collectSyncBody(BlockGroup group, List<BlockGroup> allGroups,\n"
     "                                      Set<BlockGroup> consumed, LinearIr ir,\n"
     "                                      ControlFlowGraph graph) {",
     "    static Statement collectSyncBody(ReducerOps ops, BlockGroup group, List<BlockGroup> allGroups,\n"
     "                                     Set<BlockGroup> consumed, LinearIr ir,\n"
     "                                     ControlFlowGraph graph) {", 1),
    ("            Statement bs = translateGroup(ng, ir);",
     "            Statement bs = ops.translateGroup(ng, ir);", 1),
])

c_wrapSync = transform(c_wrapSync, [
    ("    private SynchronizedStatement wrapSynchronized(Statement body,\n"
     "                                                   BlockGroup group, LinearIr ir) {",
     "    static SynchronizedStatement wrapSynchronized(Statement body,\n"
     "                                                  BlockGroup group, LinearIr ir) {", 1),
])

c_buildHandler = transform(c_buildHandler, [
    ("    private BlockGroup buildHandlerBlockGroup(TryCatchInfo info, LinearIr ir) {",
     "    static BlockGroup buildHandlerBlockGroup(TryCatchInfo info, LinearIr ir) {", 1),
])

c_collectHandler = transform(c_collectHandler, [
    ("    private List<IrInstruction> collectHandlerInstructions(TryCatchInfo info, LinearIr ir) {",
     "    static List<IrInstruction> collectHandlerInstructions(TryCatchInfo info, LinearIr ir) {", 1),
])

c_buildTryCatch = transform(c_buildTryCatch, [
    ("    private TryStatement buildTryCatch(TryCatchInfo info, Statement tryBody, LinearIr ir) {",
     "    static TryStatement buildTryCatch(ReducerOps ops, TryCatchInfo info, Statement tryBody, LinearIr ir) {", 1),
    ("            Statement finallyBody = translateHandlerWithoutThrow(info, ir, handlerInsns);",
     "            Statement finallyBody = ops.translateHandlerWithoutThrow(info, ir, handlerInsns);", 1),
    ("            handlerBody = translateGroup(handlerGroup, ir);",
     "            handlerBody = ops.translateGroup(handlerGroup, ir);", 1),
])

# orphan javadoc (buildTryCatch's) captured with buildHandlerBlockGroup: re-attach to buildTryCatch
assert c_buildHandler[0].strip() == "/**", c_buildHandler[0]
assert c_buildHandler[5].strip() == "*/", c_buildHandler[5]
assert c_buildHandler[6].strip().startswith("/** 构建一个覆盖所有处理器块的 BlockGroup"), c_buildHandler[6]
orphan_jd = c_buildHandler[0:6]
c_buildHandler = c_buildHandler[6:]
c_buildTryCatch = orphan_jd + c_buildTryCatch

# collectBranchTryAnns body from TCI chunk (rename field -> param, keep indentation)
tci_body = transform(tci_chunk, [
    ("            for (TryCatchInfo t : currentTryCatchAnns) {",
     "            for (TryCatchInfo t : tryCatchAnns) {", 1),
    ("                for (TryCatchInfo other : currentTryCatchAnns) {",
     "                for (TryCatchInfo other : tryCatchAnns) {", 1),
])
tci_body = [("    " + l[12:]) if l.strip() else l for l in tci_body]
c_branchTryAnns = [
    "    /**",
    "     * 收集分支体内应包装的 try 区域:仅处理 tryBlocks 完全包含在分支内",
    "     * 的区域——若 try 区域跨越整个 if/else(如 lock+try-finally 包裹分支体),",
    "     * 由顶层包装处理,此处包装会产生双重 finally(unlock 两次).",
    "     */",
    "    static List<TryCatchInfo> collectBranchTryAnns(Set<BasicBlock> branchBlocks,",
    "                                                   List<TryCatchInfo> tryCatchAnns,",
    "                                                   LinearIr ir) {",
    "        List<TryCatchInfo> branchTcis = new ArrayList<>();",
] + tci_body + [
    "        return branchTcis;",
    "    }",
]

# ---- 4. update remaining call sites in BlockReducer (line-based, exact) ----
replace_line("            else if (groupHasSynchronizedAnnotation(group, ir)) {",
             ["            else if (TryTranslator.groupHasSynchronizedAnnotation(group, ir)) {"])
replace_line("                Statement syncBody = collectSyncBody(group, groups, consumed, ir, graph);",
             ["                Statement syncBody = TryTranslator.collectSyncBody(this, group, groups, consumed, ir, graph);"])
replace_line("                s = wrapSynchronized(StatementUtils.blockOf(full), group, ir);",
             ["                s = TryTranslator.wrapSynchronized(StatementUtils.blockOf(full), group, ir);"])
replace_line("            else if (isSyncHandlerGroup(group, ir)) {",
             ["            else if (TryTranslator.isSyncHandlerGroup(group, ir)) {"])
replace_line("        root = wrapTryCatchBlocks(root, stmtGroupIdx, groups, tryCatchAnns, ir);",
             ["        root = TryTranslator.wrapTryCatchBlocks(this, root, stmtGroupIdx, groups, tryCatchAnns, ir);"])
replace_line("        if (groupHasSynchronizedAnnotation(group, ir)) {",
             ["        if (TryTranslator.groupHasSynchronizedAnnotation(group, ir)) {"])
replace_line("            Statement syncBody = collectSyncBody(group, allGroups, consumed, ir, graph);",
             ["            Statement syncBody = TryTranslator.collectSyncBody(this, group, allGroups, consumed, ir, graph);"])
replace_line("            return wrapSynchronized(StatementUtils.blockOf(full), group, ir);",
             ["            return TryTranslator.wrapSynchronized(StatementUtils.blockOf(full), group, ir);"])

# ---- 5. make two methods public @Override impls ----
replace_line("    private Expression extractConditionFromAllGroups(List<BlockGroup> groups, LinearIr ir) {",
             ["    @Override", "    public Expression extractConditionFromAllGroups(List<BlockGroup> groups, LinearIr ir) {"])
i_thw = find_line("    private Statement translateHandlerWithoutThrow(TryCatchInfo info, LinearIr ir,")
cont = lines[i_thw + 1]
assert cont.lstrip().startswith("List<IrInstruction> handlerInsns) {"), cont
lines[i_thw] = "    @Override"
lines.insert(i_thw + 1, "    public Statement translateHandlerWithoutThrow(TryCatchInfo info, LinearIr ir,")
lines[i_thw + 2] = cont[1:]  # remove one leading space ("public" is 1 char shorter than "private")

# ---- 6. add pushDeclaredScope / popDeclaredScope / tryCatchAnnotations impls ----
i_cbb = find_line("    public java.util.Set<Integer> currentBranchBlocks() {return currentBranchBlocks;}")
assert lines[i_cbb - 1] == "    @Override", lines[i_cbb - 1]
i_ov = i_cbb - 1
lines[i_ov:i_ov] = [
    "    @Override",
    "    public List<TryCatchInfo> tryCatchAnnotations() {return currentTryCatchAnns;}",
    "",
    "    @Override",
    "    public void pushDeclaredScope() {",
    "        declaredVarNameStack.push(new HashSet<>());",
    "    }",
    "",
    "    @Override",
    "    public void popDeclaredScope() {",
    "        declaredVarNameStack.pop();",
    "    }",
    "",
]

open(SRC, "w", encoding="utf-8", newline="").write("\n".join(lines))

# ---- 6b. ReducerOps: add 5 new callback methods ----
RO = "D:/bingbaihanji/fxdecomplie/bdec-agentB/src/main/java/com/bingbaihanji/bdec/structuring/ReducerOps.java"
ro = open(RO, encoding="utf-8").read()
assert "\n" in ro, "ReducerOps expected LF"
ro_lines = ro.split("\n")
if any("extractConditionFromAllGroups" in l for l in ro_lines):
    print("ReducerOps already updated - skipping interface edit")
    ro_lines = None

def ro_insert_after(old, new_lines):
    idxs = [i for i, l in enumerate(ro_lines) if l == old]
    assert len(idxs) == 1, f"ReducerOps anchor {old!r} found {len(idxs)} times"
    ro_lines[idxs[0] + 1:idxs[0] + 1] = new_lines

if ro_lines is not None:
    ro_insert_after(
        "    Expression extractConditionFromHeader(BasicBlock header, LinearIr ir);",
        ["",
         "    /** 扫描所有组与全部 IR 指令查找最靠前的 CONDITION 并翻译为表达式. */",
         "    Expression extractConditionFromAllGroups(List<BlockGroup> groups, LinearIr ir);"])
    ro_insert_after(
        "    boolean tryDeclareVar(String name);",
        ["",
         "    /** 压入新的变量声明作用域(分支体翻译前调用). */",
         "    void pushDeclaredScope();",
         "",
         "    /** 弹出变量声明作用域(分支体翻译后调用). */",
         "    void popDeclaredScope();",
         "",
         "    /** 当前 reduce() 调用的 try-catch 注解列表(供分支体翻译使用). */",
         "    List<TryCatchInfo> tryCatchAnnotations();"])
    ro_insert_after(
        "    Statement translateGroup(BlockGroup group, LinearIr ir);",
        ["",
         "    /** 将处理器指令(去除最后的 THROW)翻译为 Statement 体. */",
         "    Statement translateHandlerWithoutThrow(TryCatchInfo info, LinearIr ir,",
         "                                           List<IrInstruction> handlerInsns);"])

    open(RO, "w", encoding="utf-8", newline="").write("\n".join(ro_lines))

# ---- 7. assemble TryTranslator.java ----
header = '''package com.bingbaihanji.bdec.structuring;

import com.bingbaihanji.bdec.ast.expr.VarExpr;
import com.bingbaihanji.bdec.ast.stmt.BlockStatement;
import com.bingbaihanji.bdec.ast.stmt.ExpressionStatement;
import com.bingbaihanji.bdec.ast.stmt.Statement;
import com.bingbaihanji.bdec.ast.stmt.SynchronizedStatement;
import com.bingbaihanji.bdec.ast.stmt.ThrowStatement;
import com.bingbaihanji.bdec.ast.stmt.TryStatement;
import com.bingbaihanji.bdec.cfg.BasicBlock;
import com.bingbaihanji.bdec.cfg.ControlFlowGraph;
import com.bingbaihanji.bdec.cfg.EdgeKind;
import com.bingbaihanji.bdec.ir.InstructionRef;
import com.bingbaihanji.bdec.ir.IrInstruction;
import com.bingbaihanji.bdec.ir.IrOpcode;
import com.bingbaihanji.bdec.ir.LinearIr;
import com.bingbaihanji.bdec.ir.Value;
import com.bingbaihanji.bdec.ir.Variable;
import com.bingbaihanji.bdec.type.JavaType;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * try-catch 翻译器——从 {@link BlockReducer} 中按 Vineflower
 * "每模式一处理器"风格提取的 try/synchronized 专用翻译逻辑.
 *
 * <p>包含:try 区域包装({@link #wrapTryStatements} 按组区间映射语句区间,
 * 分支体内 try 区域收集 {@link #collectBranchTryAnns})、finally 合并
 * ({@link #buildTryCatch} 剥离重复 finally 体)、synchronized 块保护
 * ({@link #wrapSynchronized}/{@link #collectSyncBody} 与 {@link #isSynchronizedHandler}
 * 识别).依赖归约状态的能力(组翻译、处理器指令翻译)通过 {@link ReducerOps}
 * 回调 {@link BlockReducer},本类保持无状态.</p>
 */
public final class TryTranslator {

    private TryTranslator() {}'''

parts = ([header, ""]
         + c_wrapTryCatch + [""]
         + c_wrapTryStmts + [""]
         + c_branchTryAnns + [""]
         + c_isSyncHandler + [""]
         + c_groupHasSync + [""]
         + c_isSync + [""]
         + c_isMatch + [""]
         + c_extractMon + [""]
         + c_collectSync + [""]
         + c_wrapSync + [""]
         + c_buildHandler + [""]
         + c_collectHandler + [""]
         + c_buildTryCatch + [""]
         + ["}"])

open(DST, "w", encoding="utf-8", newline="").write("\n".join(parts))

print("BATCH A OK")
print(f"BlockReducer lines now: {len(lines)}")
print(f"TryTranslator lines: {len(parts)}")
