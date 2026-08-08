package com.bingbaihanji.bdec.semantic;

/**
 * Semantic annotation tags attached to IR instructions by the
 * {@link SemanticReconstructor} pipeline.
 *
 * Each tag represents a high-level semantic pattern that was recognized
 * in the raw bytecode IR, enabling downstream passes (BlockReducer,
 * StatementEmitter) to produce correct Java source.
 */
public enum SemanticTag {

    /** An invokespecial call to {@code <init>} — constructor delegation. */
    CONSTRUCTOR_DELEGATION,

    /** Constructor delegation to the same class: {@code this(...)}. */
    THIS_CONSTRUCTOR,

    /** Constructor delegation to the super class: {@code super(...)}. */
    SUPER_CONSTRUCTOR,

    /** An {@code Objects.requireNonNull} or {@code getClass()} null-check
     *  that has been removed. */
    NULL_CHECK_REMOVED,

    /** A block containing monitorenter → synchronized body → monitorexit. */
    SYNCHRONIZED_BLOCK,

    /** A return instruction in a boolean-typed method. */
    BOOLEAN_RETURN,

    /** A field initialization that should be extracted from the constructor
     *  into a field-level initializer. */
    FIELD_INIT,

    /** An expression whose result is consumed exactly once — candidate
     *  for inlining the definition into the use site. */
    SINGLE_USE_INLINE,

    /** The declaring class for a static method call. Allows emitting
     *  {@code Arrays.fill(...)} instead of just {@code fill(...)}. */
    DECLARING_CLASS,
}
