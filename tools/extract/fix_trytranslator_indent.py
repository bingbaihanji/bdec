# -*- coding: utf-8 -*-
"""Fix collectBranchTryAnns body indentation (+4 spaces) in TryTranslator.
Idempotent: skips if already indented."""
p = "D:/bingbaihanji/fxdecomplie/bdec-agentB/src/main/java/com/bingbaihanji/bdec/structuring/TryTranslator.java"
ls = open(p, encoding="utf-8").read().split("\n")
start = ls.index("    for (TryCatchInfo t : tryCatchAnns) {")
end = ls.index("        return branchTcis;")
if ls[start - 1].strip() == "List<TryCatchInfo> branchTcis = new ArrayList<>();" and ls[start].startswith("        "):
    print("already indented - skipping")
else:
    for i in range(start, end):
        if ls[i].strip():
            ls[i] = "    " + ls[i]
    open(p, "w", encoding="utf-8", newline="").write("\n".join(ls))
    print("indent fixed")
