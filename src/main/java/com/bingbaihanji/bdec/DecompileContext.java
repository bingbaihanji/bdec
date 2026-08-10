package com.bingbaihanji.bdec;

import com.bingbaihanji.bdec.bytecode.model.ClassFileModel;
import com.bingbaihanji.bdec.bytecode.model.constantpool.BootstrapMethodEntry;

import java.util.Collections;
import java.util.List;
import java.util.function.Function;

/**
 * Per-decompilation context — carries a class byte loader for resolving
 * dependent types, the typed config, bootstrap methods data needed
 * by RewriteRules (LambdaRewriter, MethodRefRewriter), and the parsed
 * class file model for rewriters that need bytecode-level access
 * (EnumRewriter).
 */
public class DecompileContext {

    private final BdecConfig config;

    private final Function<String, byte[]> classByteLoader;

    private final List<BootstrapMethodEntry> bootstrapMethods;

    private final ClassFileModel classFile;

    public DecompileContext(BdecConfig config, Function<String, byte[]> classByteLoader) {
        this(config, classByteLoader, Collections.emptyList(), null);
    }

    public DecompileContext(BdecConfig config, Function<String, byte[]> classByteLoader,
                            List<BootstrapMethodEntry> bootstrapMethods) {
        this(config, classByteLoader, bootstrapMethods, null);
    }

    public DecompileContext(BdecConfig config, Function<String, byte[]> classByteLoader,
                            List<BootstrapMethodEntry> bootstrapMethods,
                            ClassFileModel classFile) {
        this.config = config;
        this.classByteLoader = classByteLoader;
        this.bootstrapMethods = Collections.unmodifiableList(bootstrapMethods);
        this.classFile = classFile;
    }

    /** Empty context for simple single-class decompilation */
    public static DecompileContext empty(BdecConfig config) {
        return new DecompileContext(config, null, Collections.emptyList(), null);
    }

    public BdecConfig config() {return config;}

    /** Load bytecode for a dependent class by internal name (e.g. "com/example/Foo$Bar") */
    public byte[] loadClassBytes(String internalName) {
        return classByteLoader != null ? classByteLoader.apply(internalName) : null;
    }

    /** Bootstrap methods from the class file (needed for lambda/method ref resolution). */
    public List<BootstrapMethodEntry> bootstrapMethods() {return bootstrapMethods;}

    /** The parsed class file model (needed for bytecode-level analysis by rewriters). */
    public ClassFileModel classFile() {return classFile;}
}
