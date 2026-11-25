package com.bingbaihanji.bdec.emit;

/**
 * 缩进写入器,提供带有缩进控制的流式源代码输出能力.
 * 支持缩进级别的递增/递减,行首自动缩进,行号追踪等功能,
 * 采用链式调用的流畅 API 风格.
 */
public class IndentWriter {

    /** 内部字符串构建器,累积输出的源代码文本 */
    private final StringBuilder sb = new StringBuilder();

    /** 每级缩进的空格数 */
    private final int indentSize;

    /** 换行符,默认为 "\n" */
    private final String lineSeparator;

    /** 当前缩进级别 */
    private int indentLevel = 0;

    /** 标记当前是否处于行首位置(需要写缩进) */
    private boolean atLineStart = true;

    /** 当前行号(从 1 开始) */
    private int currentLine = 1;

    /**
     * 构造指定缩进大小(换行符为 "\n")的写入器.
     *
     * @param indentSize 每级缩进的空格数
     */
    public IndentWriter(int indentSize) {this(indentSize, "\n");}

    /**
     * 构造默认缩进大小(4 空格,换行符为 "\n")的写入器.
     */
    public IndentWriter() {this(4, "\n");}

    /**
     * 构造指定缩进大小与换行符的写入器.
     *
     * @param indentSize    每级缩进的空格数
     * @param lineSeparator 换行符字符串,为 null 时回退为 "\n"
     */
    public IndentWriter(int indentSize, String lineSeparator) {
        this.indentSize = indentSize;
        this.lineSeparator = lineSeparator == null ? "\n" : lineSeparator;
    }

    /**
     * 增加一级缩进.
     *
     * @return 当前写入器实例,支持链式调用
     */
    public IndentWriter indent() {
        indentLevel++;
        return this;
    }

    /**
     * 减少一级缩进,最低为 0.
     *
     * @return 当前写入器实例,支持链式调用
     */
    public IndentWriter dedent() {
        indentLevel = Math.max(0, indentLevel - 1);
        return this;
    }

    /**
     * 写入字符串文本.若当前处于行首,先自动写入缩进.
     * <p>文本中内嵌的换行符会同步推进行号与行首状态,因此写入一段
     * 预渲染的多行文本(如整个类型体)后,{@link #currentLine()} 仍然准确.
     * 统计的是 {@code \n} 的数量:自定义换行符 {@code \r\n} 同样只含一个
     * {@code \n},按 {@code endsWith("\n")} 判定行首对两者均成立.
     *
     * @param text 要写入的文本
     * @return 当前写入器实例,支持链式调用
     */
    public IndentWriter write(String text) {
        if (atLineStart && !text.isEmpty()) {
            writeIndent();
            atLineStart = false;
        }
        sb.append(text);
        // 内嵌换行符同样推进当前行号(不能只由 newLine() 维护)
        int embeddedNewlines = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                embeddedNewlines++;
            }
        }
        currentLine += embeddedNewlines;
        if (!text.isEmpty()) {
            atLineStart = text.endsWith("\n");
        }
        return this;
    }

    /**
     * 写入单个字符.
     *
     * @param c 要写入的字符
     * @return 当前写入器实例,支持链式调用
     */
    public IndentWriter write(char c) {return write(String.valueOf(c));}

    /**
     * 写入关键字标记(等价于 {@link #write(String)}).
     *
     * @param keyword 关键字字符串
     * @return 当前写入器实例,支持链式调用
     */
    public IndentWriter token(String keyword) {return write(keyword);}

    /**
     * 写入一个空格.
     *
     * @return 当前写入器实例,支持链式调用
     */
    public IndentWriter space() {return write(' ');}

    /**
     * 写入配置的换行符并更新行号,同时将行首标记置为 true.
     *
     * @return 当前写入器实例,支持链式调用
     */
    public IndentWriter newLine() {
        sb.append(lineSeparator);
        currentLine++;
        atLineStart = true;
        return this;
    }

    /**
     * 获取当前行号.
     *
     * @return 当前行号(从 1 开始)
     */
    public int currentLine() {return currentLine;}

    /**
     * 获取当前输出缓冲区的位置(字符偏移量),用于行映射.
     *
     * @return 当前缓冲区长度(字符偏移量)
     */
    public int currentPosition() {return sb.length();}

    /** 写入当前缩进级别对应的缩进空白 */
    private void writeIndent() {sb.append(" ".repeat(indentLevel * indentSize));}

    @Override
    public String toString() {return sb.toString();}
}
