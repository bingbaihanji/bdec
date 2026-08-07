package com.bingbaihanji.bdec;

import java.util.function.Function;

/**
 * Per-decompilation context — carries a class byte loader for resolving
 * dependent types, and the typed config.
 */
public class DecompileContext {

    private final BdecConfig config;

    private final Function<String, byte[]> classByteLoader;

    public DecompileContext(BdecConfig config, Function<String, byte[]> classByteLoader) {
        this.config = config;
        this.classByteLoader = classByteLoader;
    }

    /** Empty context for simple single-class decompilation */
    public static DecompileContext empty(BdecConfig config) {
        return new DecompileContext(config, null);
    }

    public BdecConfig config() {return config;}

    /** Load bytecode for a dependent class by internal name (e.g. "com/example/Foo$Bar") */
    public byte[] loadClassBytes(String internalName) {
        return classByteLoader != null ? classByteLoader.apply(internalName) : null;
    }
}
