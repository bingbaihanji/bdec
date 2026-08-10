package com.bingbaihanji.bdec;

/**
 * BDEC 反编译引擎的类型化配置类.
 *
 * <p>通过 {@link #builder()} 构建配置实例,避免使用字符串键值对的方式设置参数.</p>
 */
public final class BdecConfig {

    // ======================== 输出相关配置 ========================

    /** 缩进空格数 */
    private final int indentSize;

    /** 换行符 */
    private final String lineSeparator;

    /** 是否显示行号 */
    private final boolean showLineNumbers;

    /** 是否显示字节码偏移量 */
    private final boolean showBytecodeOffsets;

    // ======================== 结构化反编译开关 ========================

    /** 是否解码枚举类型 */
    private final boolean decodeEnums;

    /** 是否解码 Lambda 表达式 */
    private final boolean decodeLambdas;

    /** 是否解码三元运算符 */
    private final boolean decodeTernary;

    /** 是否解码字符串拼接 */
    private final boolean decodeStringConcat;

    /** 是否解码 try-with-resources */
    private final boolean decodeTryResource;

    /** 是否解码增强 for-each 循环 */
    private final boolean decodeForEach;

    /** 是否解码字符串 switch */
    private final boolean decodeStringSwitch;

    /** 是否合并导入语句 */
    private final boolean collapseImports;

    // ======================== SSA 优化配置 ========================

    /** SSA(静态单赋值)优化的指令数阈值,低于此阈值不启用 SSA */
    private final int ssaThreshold;

    // ======================== 调试配置 ========================

    /** 是否输出 CFG(控制流图)调试信息 */
    private final boolean debugDumpCfg;

    /** 是否输出 AST(抽象语法树)调试信息 */
    private final boolean debugDumpAst;

    /**
     * 私有构造函数,通过 {@link Builder} 构造实例.
     *
     * @param b 构建器实例
     */
    private BdecConfig(Builder b) {
        this.indentSize = b.indentSize;
        this.lineSeparator = b.lineSeparator;
        this.showLineNumbers = b.showLineNumbers;
        this.showBytecodeOffsets = b.showBytecodeOffsets;
        this.decodeEnums = b.decodeEnums;
        this.decodeLambdas = b.decodeLambdas;
        this.decodeTernary = b.decodeTernary;
        this.decodeStringConcat = b.decodeStringConcat;
        this.decodeTryResource = b.decodeTryResource;
        this.decodeForEach = b.decodeForEach;
        this.decodeStringSwitch = b.decodeStringSwitch;
        this.collapseImports = b.collapseImports;
        this.ssaThreshold = b.ssaThreshold;
        this.debugDumpCfg = b.debugDumpCfg;
        this.debugDumpAst = b.debugDumpAst;
    }

    /** 创建配置构建器实例 */
    public static Builder builder() {return new Builder();}

    /** 获取默认配置实例 */
    public static BdecConfig defaults() {return builder().build();}

    /** 获取开启调试输出的配置实例 */
    public static BdecConfig debug() {
        return builder().debugDumpCfg(true).debugDumpAst(true).build();
    }

    /** 获取缩进空格数 */
    public int indentSize() {return indentSize;}

    /** 获取换行符 */
    public String lineSeparator() {return lineSeparator;}

    /** 是否显示行号 */
    public boolean showLineNumbers() {return showLineNumbers;}

    /** 是否显示字节码偏移量 */
    public boolean showBytecodeOffsets() {return showBytecodeOffsets;}

    /** 是否解码枚举类型 */
    public boolean decodeEnums() {return decodeEnums;}

    /** 是否解码 Lambda 表达式 */
    public boolean decodeLambdas() {return decodeLambdas;}

    /** 是否解码三元运算符 */
    public boolean decodeTernary() {return decodeTernary;}

    /** 是否解码字符串拼接 */
    public boolean decodeStringConcat() {return decodeStringConcat;}

    /** 是否解码 try-with-resources */
    public boolean decodeTryResource() {return decodeTryResource;}

    /** 是否解码增强 for-each 循环 */
    public boolean decodeForEach() {return decodeForEach;}

    /** 是否解码字符串 switch */
    public boolean decodeStringSwitch() {return decodeStringSwitch;}

    /** 是否合并导入语句 */
    public boolean collapseImports() {return collapseImports;}

    /** 获取 SSA 优化指令数阈值 */
    public int ssaThreshold() {return ssaThreshold;}

    /** 是否输出 CFG 调试信息 */
    public boolean debugDumpCfg() {return debugDumpCfg;}

    /** 是否输出 AST 调试信息 */
    public boolean debugDumpAst() {return debugDumpAst;}

    /**
     * BdecConfig 的构建器类,采用链式调用风格设置各项配置.
     */
    public static final class Builder {

        /** 缩进空格数,默认 4 */
        private int indentSize = 4;

        /** 换行符,默认为 "\n" */
        private String lineSeparator = "\n";

        /** 是否显示行号,默认关闭 */
        private boolean showLineNumbers = false;

        /** 是否显示字节码偏移量,默认关闭 */
        private boolean showBytecodeOffsets = false;

        /** 是否解码枚举类型,默认开启 */
        private boolean decodeEnums = true;

        /** 是否解码 Lambda 表达式,默认开启 */
        private boolean decodeLambdas = true;

        /** 是否解码三元运算符,默认开启 */
        private boolean decodeTernary = true;

        /** 是否解码字符串拼接,默认开启 */
        private boolean decodeStringConcat = true;

        /** 是否解码 try-with-resources,默认开启 */
        private boolean decodeTryResource = true;

        /** 是否解码增强 for-each 循环,默认开启 */
        private boolean decodeForEach = true;

        /** 是否解码字符串 switch,默认开启 */
        private boolean decodeStringSwitch = true;

        /** 是否合并导入语句,默认开启 */
        private boolean collapseImports = true;

        /** SSA 优化指令数阈值,默认 5 */
        private int ssaThreshold = 5;

        /** 是否输出 CFG 调试信息,默认关闭 */
        private boolean debugDumpCfg = false;

        /** 是否输出 AST 调试信息,默认关闭 */
        private boolean debugDumpAst = false;

        /**
         * 设置缩进空格数.
         *
         * @param n 缩进空格数
         * @return 当前构建器实例
         */
        public Builder indentSize(int n) {
            this.indentSize = n;
            return this;
        }

        /**
         * 设置换行符.
         *
         * @param s 换行符字符串
         * @return 当前构建器实例
         */
        public Builder lineSeparator(String s) {
            this.lineSeparator = s;
            return this;
        }

        /**
         * 设置是否显示行号.
         *
         * @param v true 表示显示行号
         * @return 当前构建器实例
         */
        public Builder showLineNumbers(boolean v) {
            this.showLineNumbers = v;
            return this;
        }

        /**
         * 设置是否显示字节码偏移量.
         *
         * @param v true 表示显示字节码偏移量
         * @return 当前构建器实例
         */
        public Builder showBytecodeOffsets(boolean v) {
            this.showBytecodeOffsets = v;
            return this;
        }

        /**
         * 设置是否解码枚举类型.
         *
         * @param v true 表示开启枚举解码
         * @return 当前构建器实例
         */
        public Builder decodeEnums(boolean v) {
            this.decodeEnums = v;
            return this;
        }

        /**
         * 设置是否解码 Lambda 表达式.
         *
         * @param v true 表示开启 Lambda 解码
         * @return 当前构建器实例
         */
        public Builder decodeLambdas(boolean v) {
            this.decodeLambdas = v;
            return this;
        }

        /**
         * 设置是否解码三元运算符.
         *
         * @param v true 表示开启三元运算符解码
         * @return 当前构建器实例
         */
        public Builder decodeTernary(boolean v) {
            this.decodeTernary = v;
            return this;
        }

        /**
         * 设置是否解码字符串拼接.
         *
         * @param v true 表示开启字符串拼接解码
         * @return 当前构建器实例
         */
        public Builder decodeStringConcat(boolean v) {
            this.decodeStringConcat = v;
            return this;
        }

        /**
         * 设置是否解码 try-with-resources.
         *
         * @param v true 表示开启 try-with-resources 解码
         * @return 当前构建器实例
         */
        public Builder decodeTryResource(boolean v) {
            this.decodeTryResource = v;
            return this;
        }

        /**
         * 设置是否解码增强 for-each 循环.
         *
         * @param v true 表示开启 for-each 解码
         * @return 当前构建器实例
         */
        public Builder decodeForEach(boolean v) {
            this.decodeForEach = v;
            return this;
        }

        /**
         * 设置是否解码字符串 switch.
         *
         * @param v true 表示开启字符串 switch 解码
         * @return 当前构建器实例
         */
        public Builder decodeStringSwitch(boolean v) {
            this.decodeStringSwitch = v;
            return this;
        }

        /**
         * 设置是否合并导入语句.
         *
         * @param v true 表示开启导入合并
         * @return 当前构建器实例
         */
        public Builder collapseImports(boolean v) {
            this.collapseImports = v;
            return this;
        }

        /**
         * 设置 SSA 优化指令数阈值.
         *
         * @param n 指令数阈值,低于此值不启用 SSA 优化
         * @return 当前构建器实例
         */
        public Builder ssaThreshold(int n) {
            this.ssaThreshold = n;
            return this;
        }

        /**
         * 设置是否输出 CFG 调试信息.
         *
         * @param v true 表示输出 CFG 调试信息
         * @return 当前构建器实例
         */
        public Builder debugDumpCfg(boolean v) {
            this.debugDumpCfg = v;
            return this;
        }

        /**
         * 设置是否输出 AST 调试信息.
         *
         * @param v true 表示输出 AST 调试信息
         * @return 当前构建器实例
         */
        public Builder debugDumpAst(boolean v) {
            this.debugDumpAst = v;
            return this;
        }

        /**
         * 构建 {@link BdecConfig} 实例.
         *
         * @return 新的配置实例
         */
        public BdecConfig build() {return new BdecConfig(this);}
    }
}
