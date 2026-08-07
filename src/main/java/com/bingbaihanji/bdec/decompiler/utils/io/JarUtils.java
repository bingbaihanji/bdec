package com.bingbaihanji.bdec.decompiler.utils.io;


import com.bingbaihanji.bdec.decompiler.utils.collection.CollectionUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Jar 文件工具类
 *
 * <p>
 * 提供 Jar 路径分割,类名与文件路径转换,自动模块名推导等常用方法
 */
public final class JarUtils {

    /** 检查路径开头是否具有 URL 方案 */
    public static final Pattern URL_SCHEME_PATTERN = Pattern.compile("[a-zA-Z][a-zA-Z0-9+-.]+:.*");

    private static final Pattern DASH_VERSION = Pattern.compile("-(\\d+(\\.|$))");

    private static final Pattern NON_ALPHANUM = Pattern.compile("[^A-Za-z0-9]");

    private static final Pattern REPEATING_DOTS = Pattern.compile("(\\.)(\\1)+");

    private static final Pattern LEADING_DOTS = Pattern.compile("^\\.");

    private static final Pattern TRAILING_DOTS = Pattern.compile("\\.$");

    private static final Pattern DOUBLE_BACKSHLASH_WITH_COLON = Pattern.compile("\\\\:");

    /** 临时文件叶子分隔符,对应 {@code NestedJarHandler.TEMP_FILENAME_LEAF_SEPARATOR} */
    private static final String TEMP_FILENAME_LEAF_SEPARATOR = "!";  // 默认值,与实际使用保持一致

    private static final String[] UNIX_NON_PATH_SEPARATORS = {"jar:", "file:", "http://", "https://", "\\:"};

    private static final int[] UNIX_NON_PATH_SEPARATOR_COLON_POSITIONS;

    static {
        UNIX_NON_PATH_SEPARATOR_COLON_POSITIONS = new int[UNIX_NON_PATH_SEPARATORS.length];
        for (int i = 0; i < UNIX_NON_PATH_SEPARATORS.length; i++) {
            UNIX_NON_PATH_SEPARATOR_COLON_POSITIONS[i] = UNIX_NON_PATH_SEPARATORS[i].indexOf(':');
            if (UNIX_NON_PATH_SEPARATOR_COLON_POSITIONS[i] < 0) {
                throw new RuntimeException("Could not find ':' in \"" + UNIX_NON_PATH_SEPARATORS[i] + "\"");
            }
        }
    }

    private JarUtils() {
        throw new AssertionError("工具类不允许实例化");
    }

    // ==================== 扫描规格 ====================

    /**
     * 按 File.pathSeparator 拆分路径,同时允许 URL 协议(如
     * "http://domain/jar1.jar:http://domain/jar2.jar")
     */
    public static String[] smartPathSplit(final String pathStr, final ScanSpec scanSpec) {
        return smartPathSplit(pathStr, File.pathSeparatorChar, scanSpec);
    }

    // ==================== 路径分割 ====================

    /**
     * 按指定分隔符拆分路径 当分隔符为 ':' 时,会避开 URL 协议中的冒号
     */
    public static String[] smartPathSplit(final String pathStr, final char separatorChar, final ScanSpec scanSpec) {
        if (pathStr == null || pathStr.isEmpty()) {
            return new String[0];
        }
        if (separatorChar != ':') {
            // Windows 使用 '; ' 等,直接按分隔符切分
            final List<String> partsFiltered = new ArrayList<>();
            for (final String part : pathStr.split(String.valueOf(separatorChar))) {
                final String partFiltered = part.trim();
                if (!partFiltered.isEmpty()) {
                    partsFiltered.add(partFiltered);
                }
            }
            return partsFiltered.toArray(new String[0]);
        } else {
            // ':' 作为分隔符,需避开 URL 协议边界
            final Set<Integer> splitPoints = new HashSet<>();
            for (int i = -1; ; ) {
                boolean foundNonPathSeparator = false;
                for (int j = 0; j < UNIX_NON_PATH_SEPARATORS.length; j++) {
                    final int startIdx = i - UNIX_NON_PATH_SEPARATOR_COLON_POSITIONS[j];
                    if (pathStr.regionMatches(true, startIdx, UNIX_NON_PATH_SEPARATORS[j], 0,
                            UNIX_NON_PATH_SEPARATORS[j].length())
                            && (startIdx == 0 || pathStr.charAt(startIdx - 1) == ':')) {
                        foundNonPathSeparator = true;
                        break;
                    }
                }
                if (!foundNonPathSeparator && scanSpec != null && scanSpec.allowedURLSchemes != null
                        && !scanSpec.allowedURLSchemes.isEmpty()) {
                    for (final String scheme : scanSpec.allowedURLSchemes) {
                        if (!"http".equals(scheme) && !"https".equals(scheme) && !"jar".equals(scheme)
                                && !"file".equals(scheme)) {
                            final int schemeLen = scheme.length();
                            final int startIdx = i - schemeLen;
                            if (pathStr.regionMatches(true, startIdx, scheme, 0, schemeLen)
                                    && (startIdx == 0 || pathStr.charAt(startIdx - 1) == ':')) {
                                foundNonPathSeparator = true;
                                break;
                            }
                        }
                    }
                }
                if (!foundNonPathSeparator) {
                    splitPoints.add(i);
                }
                i = pathStr.indexOf(':', i + 1);
                if (i < 0) {
                    splitPoints.add(pathStr.length());
                    break;
                }
            }
            final List<Integer> splitPointsSorted = new ArrayList<>(splitPoints);
            CollectionUtils.sortIfNotEmpty(splitPointsSorted);
            final List<String> parts = new ArrayList<>();
            for (int i = 1; i < splitPointsSorted.size(); i++) {
                final int idx0 = splitPointsSorted.get(i - 1);
                final int idx1 = splitPointsSorted.get(i);
                String part = pathStr.substring(idx0 + 1, idx1).trim();
                part = DOUBLE_BACKSHLASH_WITH_COLON.matcher(part).replaceAll(":");
                if (!part.isEmpty()) {
                    parts.add(part);
                }
            }
            return parts.toArray(new String[0]);
        }
    }

    private static void appendPathElt(final Object pathElt, final StringBuilder buf) {
        if (!buf.isEmpty()) {
            buf.append(File.pathSeparatorChar);
        }
        final String path = File.separatorChar == '\\' ? pathElt.toString()
                : pathElt.toString().replaceAll(File.pathSeparator, "\\" + File.pathSeparator);
        buf.append(path);
    }

    // ==================== 路径拼接 ====================

    public static String pathElementsToPathStr(final Object... pathElts) {
        final StringBuilder buf = new StringBuilder();
        if (pathElts != null) {
            for (final Object pathElt : pathElts) {
                appendPathElt(pathElt, buf);
            }
        }
        return buf.toString();
    }

    public static String pathElementsToPathStr(final Iterable<?> pathElts) {
        final StringBuilder buf = new StringBuilder();
        if (pathElts != null) {
            for (final Object pathElt : pathElts) {
                appendPathElt(pathElt, buf);
            }
        }
        return buf.toString();
    }

    /**
     * 返回路径的叶子名称,会剥离 '!' 之后的内容以及临时文件前缀
     */
    public static String leafName(final String path) {
        if (path == null) {
            return "";
        }
        final int bangIdx = path.indexOf('!');
        final int endIdx = bangIdx >= 0 ? bangIdx : path.length();
        int leafStartIdx = 1 + (File.separatorChar == '/' ? path.lastIndexOf('/', endIdx)
                : Math.max(path.lastIndexOf('/', endIdx), path.lastIndexOf(File.separatorChar, endIdx)));
        // 移除临时文件名前缀(参考 NestedJarHandler.unzipToTempFile())
        int sepIdx = path.indexOf(TEMP_FILENAME_LEAF_SEPARATOR);
        if (sepIdx >= 0) {
            sepIdx += TEMP_FILENAME_LEAF_SEPARATOR.length();
        }
        leafStartIdx = Math.max(leafStartIdx, sepIdx);
        leafStartIdx = Math.min(leafStartIdx, endIdx);
        return path.substring(leafStartIdx, endIdx);
    }

    // ==================== 名称提取 ====================

    public static String classfilePathToClassName(final String classfilePath) {
        if (classfilePath == null || !classfilePath.endsWith(".class")) {
            throw new IllegalArgumentException("Classfile path does not end with \".class\": " + classfilePath);
        }
        return classfilePath.substring(0, classfilePath.length() - 6).replace('/', '.');
    }

    // ==================== 类名/路径互转 ====================

    public static String classNameToClassfilePath(final String className) {
        if (className == null) {
            throw new IllegalArgumentException("类名不能为空");
        }
        return className.replace('.', '/') + ".class";
    }

    /**
     * 根据 JAR 名称推导自动模块名称,遵循 {@code ModuleFinder} 算法
     */
    public static String derivedAutomaticModuleName(final String jarPath) {
        if (jarPath == null) {
            return "";
        }
        int endIdx = jarPath.length();
        final int lastPlingIdx = jarPath.lastIndexOf('!');
        if (lastPlingIdx > 0 && jarPath.lastIndexOf('.') <= Math.max(lastPlingIdx, jarPath.lastIndexOf('/'))) {
            endIdx = lastPlingIdx;
        }
        final int secondToLastPlingIdx = endIdx == 0 ? -1 : jarPath.lastIndexOf("!", endIdx - 1);
        final int startIdx = Math.max(secondToLastPlingIdx, jarPath.lastIndexOf('/', endIdx - 1)) + 1;
        final int lastDotBeforeLastPlingIdx = jarPath.lastIndexOf('.', endIdx - 1);
        if (lastDotBeforeLastPlingIdx > startIdx) {
            endIdx = lastDotBeforeLastPlingIdx;
        }

        String moduleName = jarPath.substring(startIdx, endIdx);

        final Matcher matcher = DASH_VERSION.matcher(moduleName);
        if (matcher.find()) {
            moduleName = moduleName.substring(0, matcher.start());
        }

        moduleName = NON_ALPHANUM.matcher(moduleName).replaceAll(".");
        moduleName = REPEATING_DOTS.matcher(moduleName).replaceAll(".");
        if (!moduleName.isEmpty() && moduleName.charAt(0) == '.') {
            moduleName = LEADING_DOTS.matcher(moduleName).replaceAll("");
        }
        final int len = moduleName.length();
        if (len > 0 && moduleName.charAt(len - 1) == '.') {
            moduleName = TRAILING_DOTS.matcher(moduleName).replaceAll("");
        }
        return moduleName;
    }

    // ==================== 自动模块名推导 ====================

    /**
     * 扫描规格,用于 {@link #smartPathSplit(String, ScanSpec)} 控制自定义 URL 方案
     */
    public static class ScanSpec {

        /** 允许的额外 URL 方案集合,可为空 */
        public Set<String> allowedURLSchemes;

        public ScanSpec() {
        }

        public ScanSpec(Set<String> allowedURLSchemes) {
            this.allowedURLSchemes = allowedURLSchemes;
        }

    }

}