package com.bingbaihanji.bdec.decompiler;

import java.util.Collections;
import java.util.Map;
import java.util.function.Function;

/**
 * 反编译单次调用的上下文环境
 */
public class DecompileContext {

    public static final DecompileContext EMPTY = new Builder().build();

    private final Map<String, String> options;

    private final Function<String, byte[]> classByteLoader; // 用于关联加载内部类/依赖类字节码

    private DecompileContext(Builder builder) {
        this.options = Collections.unmodifiableMap(builder.options);
        this.classByteLoader = builder.classByteLoader;
    }

    public Map<String, String> getOptions() {
        return options;
    }

    public String getOption(String key, String defaultValue) {
        return options.getOrDefault(key, defaultValue);
    }

    /**
     * 根据内部类型名寻找依赖类的字节码
     *
     * @param internalName 如 "com/example/Foo$Bar"
     * @return 字节码数组，找不到返回 null
     */
    public byte[] loadClassBytes(String internalName) {
        return classByteLoader != null ? classByteLoader.apply(internalName) : null;
    }

    public static class Builder {

        private Map<String, String> options = Collections.emptyMap();

        private Function<String, byte[]> classByteLoader;

        public Builder setOptions(Map<String, String> options) {
            this.options = options != null ? options : Collections.emptyMap();
            return this;
        }

        public Builder setClassByteLoader(Function<String, byte[]> classByteLoader) {
            this.classByteLoader = classByteLoader;
            return this;
        }

        public DecompileContext build() {
            return new DecompileContext(this);
        }
    }
}