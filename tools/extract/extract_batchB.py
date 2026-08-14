# -*- coding: utf-8 -*-
"""Batch B: extract if-header detection cluster from BlockReducer into IfTranslator.
Deterministic: exact anchors, bracket-pair scanning, exact replacement counts."""
import os

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

# ---- 1. cut the five pure if-cluster methods ----
c_detectIf = cut("    private IfInfo detectIfHeader(BlockGroup group, ControlFlowGraph graph, LinearIr ir,")
c_hasExt = cut("    private boolean hasExternalPred(BasicBlock target, BasicBlock header,")
c_isTerm = cut("    private boolean isTerminalBranch(BasicBlock start, BasicBlock otherTarget,")
c_joins = cut("    private boolean branchJoins(BasicBlock start, BasicBlock joinTarget,")
c_reach = cut("    private Set<BasicBlock> collectReachableBlocks(BasicBlock start, BasicBlock stop,")

# ---- 2. transform signatures ----
c_detectIf = transform(c_detectIf, [
    ("    private IfInfo detectIfHeader(BlockGroup group, ControlFlowGraph graph, LinearIr ir,\n"
     "                                  PostDominatorTree postDom) {",
     "    static IfInfo detectIfHeader(BlockGroup group, ControlFlowGraph graph, LinearIr ir,\n"
     "                                 PostDominatorTree postDom) {", 1),
])
c_hasExt = transform(c_hasExt, [
    ("    private boolean hasExternalPred(BasicBlock target, BasicBlock header,\n"
     "                                    ControlFlowGraph graph) {",
     "    static boolean hasExternalPred(BasicBlock target, BasicBlock header,\n"
     "                                   ControlFlowGraph graph) {", 1),
])
c_isTerm = transform(c_isTerm, [
    ("    private boolean isTerminalBranch(BasicBlock start, BasicBlock otherTarget,\n"
     "                                     BasicBlock header, ControlFlowGraph graph) {",
     "    static boolean isTerminalBranch(BasicBlock start, BasicBlock otherTarget,\n"
     "                                    BasicBlock header, ControlFlowGraph graph) {", 1),
])
c_joins = transform(c_joins, [
    ("    private boolean branchJoins(BasicBlock start, BasicBlock joinTarget,\n"
     "                                BasicBlock stop, ControlFlowGraph graph) {",
     "    static boolean branchJoins(BasicBlock start, BasicBlock joinTarget,\n"
     "                               BasicBlock stop, ControlFlowGraph graph) {", 1),
])
c_reach = transform(c_reach, [
    ("    private Set<BasicBlock> collectReachableBlocks(BasicBlock start, BasicBlock stop,\n"
     "                                                   ControlFlowGraph graph) {",
     "    static Set<BasicBlock> collectReachableBlocks(BasicBlock start, BasicBlock stop,\n"
     "                                                  ControlFlowGraph graph) {", 1),
])

# ---- 3. update call sites in BlockReducer ----
replace_line("                ifInfo = detectIfHeader(group, graph, ir, postDom);",
             ["                ifInfo = IfTranslator.detectIfHeader(group, graph, ir, postDom);"])
replace_line("        IfInfo nestedIf = detectIfHeader(group, graph, ir, postDom);",
             ["        IfInfo nestedIf = IfTranslator.detectIfHeader(group, graph, ir, postDom);"])

open(SRC, "w", encoding="utf-8", newline="").write("\n".join(lines))

# ---- 4. create IfTranslator.java ----
header = '''package com.bingbaihanji.bdec.structuring;

import com.bingbaihanji.bdec.ast.expr.Expression;
import com.bingbaihanji.bdec.ast.expr.LitExpr;
import com.bingbaihanji.bdec.ast.expr.UnExpr;
import com.bingbaihanji.bdec.ast.expr.UnaryOperator;
import com.bingbaihanji.bdec.ast.stmt.BlockStatement;
import com.bingbaihanji.bdec.ast.stmt.IfStatement;
import com.bingbaihanji.bdec.ast.stmt.ReturnStatement;
import com.bingbaihanji.bdec.ast.stmt.Statement;
import com.bingbaihanji.bdec.cfg.BasicBlock;
import com.bingbaihanji.bdec.cfg.ControlFlowGraph;
import com.bingbaihanji.bdec.cfg.EdgeKind;
import com.bingbaihanji.bdec.cfg.PostDominatorTree;
import com.bingbaihanji.bdec.ir.IrInstruction;
import com.bingbaihanji.bdec.ir.IrOpcode;
import com.bingbaihanji.bdec.ir.LinearIr;
import com.bingbaihanji.bdec.type.JavaType;
import com.bingbaihanji.bdec.type.TypeKind;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * if 翻译器——从 {@link BlockReducer} 中按 Vineflower "每模式一处理器"
 * 风格提取的 if 专用翻译逻辑.
 *
 * <p>包含:if-header 检测({@link #detectIfHeader} 按 CFG 结构与后支配树
 * 计算合并点)、分支体翻译({@link #translateBranchBody} 递归结构化嵌套
 * if/loop/switch)、布尔 return 折叠与 short-branch-first 规范化
 * ({@link #translateIf}).依赖归约状态的能力(表达式翻译、作用域追踪、
 * PHI 分支上下文)通过 {@link ReducerOps} 回调 {@link BlockReducer},
 * 本类保持无状态.</p>
 */
public final class IfTranslator {

    private IfTranslator() {}'''

parts = ([header, ""]
         + c_detectIf + [""]
         + c_hasExt + [""]
         + c_isTerm + [""]
         + c_joins + [""]
         + c_reach + [""]
         + ["}"])

open(DST, "w", encoding="utf-8", newline="").write("\n".join(parts))

print("BATCH B OK")
print(f"BlockReducer lines now: {len(lines)}")
print(f"IfTranslator lines: {len(parts)}")
