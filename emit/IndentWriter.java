package com.bingbaihanji.bdec.emit;

public class IndentWriter {

    private final StringBuilder sb = new StringBuilder();

    private final int indentSize;

    private int indentLevel = 0;

    private boolean atLineStart = true;

    private int currentLine = 1;

    public IndentWriter(int indentSize) {this.indentSize = indentSize;}

    public IndentWriter() {this(4);}

    public IndentWriter indent() {
        indentLevel++;
        return this;
    }

    public IndentWriter dedent() {
        indentLevel = Math.max(0, indentLevel - 1);
        return this;
    }

    public IndentWriter write(String text) {
        if (atLineStart && !text.isEmpty()) {
            writeIndent();
            atLineStart = false;
        }
        sb.append(text);
        return this;
    }

    public IndentWriter write(char c) {return write(String.valueOf(c));}

    public IndentWriter token(String keyword) {return write(keyword);}

    public IndentWriter space() {return write(' ');}

    public IndentWriter newLine() {
        sb.append("\n");
        currentLine++;
        atLineStart = true;
        return this;
    }

    public int currentLine() {return currentLine;}

    /** Associate the current position with a bytecode offset for line mapping. */
    public int currentPosition() {return sb.length();}

    private void writeIndent() {sb.append(" ".repeat(indentLevel * indentSize));}

    @Override
    public String toString() {return sb.toString();}
}
