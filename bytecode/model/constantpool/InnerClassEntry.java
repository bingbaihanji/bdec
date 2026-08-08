package com.bingbaihanji.bdec.bytecode.model.constantpool;

/**
 * Represents one entry in the InnerClasses attribute.
 *
 * @param innerClass   internal name of inner class (e.g. {@code pkg/Outer$Inner})
 * @param outerClass   internal name of outer class, or null if not nested
 * @param simpleName   simple name of inner class (e.g. {@code "Inner"}), or null if anonymous
 * @param accessFlags  access flags of inner class
 */
public record InnerClassEntry(
        String innerClass,
        String outerClass,
        String simpleName,
        int accessFlags
) {}
