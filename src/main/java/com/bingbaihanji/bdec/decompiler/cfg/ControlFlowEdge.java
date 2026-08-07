package com.bingbaihanji.bdec.decompiler.cfg;

import java.util.OptionalInt;

public interface ControlFlowEdge {

    BasicBlock source();

    BasicBlock target();

    EdgeKind kind();

    OptionalInt switchKey();

    String catchTypeInternalName();
}
