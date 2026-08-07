package com.bingbaihanji.bdec;

/**
 * Typed configuration for the bdec decompiler engine.
 * Construct via {@link #builder()}, not string keys.
 */
public final class BdecConfig {

    // === Output ===
    private final int indentSize;

    private final String lineSeparator;

    private final boolean showLineNumbers;

    private final boolean showBytecodeOffsets;

    // === Structuring switches ===
    private final boolean decodeEnums;

    private final boolean decodeLambdas;

    private final boolean decodeTernary;

    private final boolean decodeStringConcat;

    private final boolean decodeTryResource;

    private final boolean decodeForEach;

    private final boolean collapseImports;

    // === SSA ===
    private final int ssaThreshold;

    // === Debug ===
    private final boolean debugDumpCfg;

    private final boolean debugDumpAst;

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
        this.collapseImports = b.collapseImports;
        this.ssaThreshold = b.ssaThreshold;
        this.debugDumpCfg = b.debugDumpCfg;
        this.debugDumpAst = b.debugDumpAst;
    }

    public static Builder builder() {return new Builder();}

    public static BdecConfig defaults() {return builder().build();}

    public static BdecConfig debug() {
        return builder().debugDumpCfg(true).debugDumpAst(true).build();
    }

    public int indentSize() {return indentSize;}

    public String lineSeparator() {return lineSeparator;}

    public boolean showLineNumbers() {return showLineNumbers;}

    public boolean showBytecodeOffsets() {return showBytecodeOffsets;}

    public boolean decodeEnums() {return decodeEnums;}

    public boolean decodeLambdas() {return decodeLambdas;}

    public boolean decodeTernary() {return decodeTernary;}

    public boolean decodeStringConcat() {return decodeStringConcat;}

    public boolean decodeTryResource() {return decodeTryResource;}

    public boolean decodeForEach() {return decodeForEach;}

    public boolean collapseImports() {return collapseImports;}

    public int ssaThreshold() {return ssaThreshold;}

    public boolean debugDumpCfg() {return debugDumpCfg;}

    public boolean debugDumpAst() {return debugDumpAst;}

    public static final class Builder {

        private int indentSize = 4;

        private String lineSeparator = "\n";

        private boolean showLineNumbers = false;

        private boolean showBytecodeOffsets = false;

        private boolean decodeEnums = true;

        private boolean decodeLambdas = true;

        private boolean decodeTernary = true;

        private boolean decodeStringConcat = true;

        private boolean decodeTryResource = true;

        private boolean decodeForEach = true;

        private boolean collapseImports = true;

        private int ssaThreshold = 5;

        private boolean debugDumpCfg = false;

        private boolean debugDumpAst = false;

        public Builder indentSize(int n) {
            this.indentSize = n;
            return this;
        }

        public Builder lineSeparator(String s) {
            this.lineSeparator = s;
            return this;
        }

        public Builder showLineNumbers(boolean v) {
            this.showLineNumbers = v;
            return this;
        }

        public Builder showBytecodeOffsets(boolean v) {
            this.showBytecodeOffsets = v;
            return this;
        }

        public Builder decodeEnums(boolean v) {
            this.decodeEnums = v;
            return this;
        }

        public Builder decodeLambdas(boolean v) {
            this.decodeLambdas = v;
            return this;
        }

        public Builder decodeTernary(boolean v) {
            this.decodeTernary = v;
            return this;
        }

        public Builder decodeStringConcat(boolean v) {
            this.decodeStringConcat = v;
            return this;
        }

        public Builder decodeTryResource(boolean v) {
            this.decodeTryResource = v;
            return this;
        }

        public Builder decodeForEach(boolean v) {
            this.decodeForEach = v;
            return this;
        }

        public Builder collapseImports(boolean v) {
            this.collapseImports = v;
            return this;
        }

        public Builder ssaThreshold(int n) {
            this.ssaThreshold = n;
            return this;
        }

        public Builder debugDumpCfg(boolean v) {
            this.debugDumpCfg = v;
            return this;
        }

        public Builder debugDumpAst(boolean v) {
            this.debugDumpAst = v;
            return this;
        }

        public BdecConfig build() {return new BdecConfig(this);}
    }
}
