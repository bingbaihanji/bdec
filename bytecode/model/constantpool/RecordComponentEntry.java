package com.bingbaihanji.bdec.bytecode.model.constantpool;

/** Represents one component in a Record attribute (Java 16+). */
public record RecordComponentEntry(
        String name,       // component name (from CP utf8)
        String descriptor  // component type descriptor
) {}
