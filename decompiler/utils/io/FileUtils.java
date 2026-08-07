package com.bingbaihanji.bdec.decompiler.utils.io;


import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 文件工具类
 */
public final class FileUtils {

    /**
     * 文件缓冲区数组的最大大小比 {@link Integer#MAX_VALUE} 小 8 字节, 因为某些虚拟机在数组中保留头字
     */
    public static final int MAX_BUFFER_SIZE = Integer.MAX_VALUE - 8;


    /** 类的静态字段是否已初始化 */
    private static final AtomicBoolean initialized = new AtomicBoolean();

    // /** jdk.incubator.foreign.MemorySegment 类(JDK 14+) */
    // private static Class<?> memorySegmentClass; 
    //
    // /** jdk.incubator.foreign.MemorySegment.ofByteBuffer 方法(JDK 14+) */
    // private static Method memorySegmentOfByteBufferMethod; 
    //
    // /** jdk.incubator.foreign.MemorySegment.ofByteBuffer 方法(JDK 14+) */
    // private static Method memorySegmentCloseMethod; 

    /** DirectByteBuffer.cleaner() 方法 */
    private static Method directByteBufferCleanerMethod;

    /** Cleaner.clean() 方法 */
    private static Method cleanerCleanMethod;

    /** attachment() 方法 */
    private static Method attachmentMethod;

    /** Unsafe 对象 */
    private static Object theUnsafe;

    /**
     * 当前目录路径(仅在首次访问此字段时读取当前目录一次, 因此不会反映当前目录的后续更改)
     */
    private static String currDirPath;

    // -------------------------------------------------------------------------------------------------------------

    /**
     * 构造方法
     */
    private FileUtils() {
        // 不可构造
    }

    // -------------------------------------------------------------------------------------------------------------


    // -------------------------------------------------------------------------------------------------------------

    /**
     * 检查路径是否以 ".class" 扩展名结尾,忽略大小写
     * @param path 文件路径
     * @return 如果路径具有 ".class" 扩展名(忽略大小写)则返回 true
     */
    public static boolean isClassfile(final String path) {
        final int len = path.length();
        return len > 6 && path.regionMatches(true, len - 6, ".class", 0, 6);
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * 检查 {@link File} 是否存在且可读
     * @param file 一个 {@link File}
     * @return 如果文件存在且可读则返回 true
     */
    public static boolean canRead(final File file) {
        try {
            return file.canRead();
        } catch (final SecurityException e) {
            return false;
        }
    }

    /**
     * 检查 {@link Path} 是否存在且可读
     * @param path 一个 {@link Path}
     * @return 如果文件存在且可读则返回 true
     */
    public static boolean canRead(final Path path) {
        try {
            return canRead(path.toFile());
        } catch (final UnsupportedOperationException ignored) {
        }
        try {
            return Files.isReadable(path);
        } catch (final SecurityException e) {
            return false;
        }
    }

    /**
     * 检查 {@link File} 是否存在,是普通文件且可读
     * @param file 一个 {@link File}
     * @return 如果文件存在,是普通文件且可读则返回 true
     */
    public static boolean canReadAndIsFile(final File file) {
        try {
            if (!file.canRead()) {
                return false;
            }
        } catch (final SecurityException e) {
            return false;
        }
        return file.isFile();
    }

    /**
     * 检查 {@link Path} 是否存在,是普通文件且可读
     * @param path 一个 {@link Path}
     * @return 如果文件存在,是普通文件且可读则返回 true
     */
    public static boolean canReadAndIsFile(final Path path) {
        try {
            return canReadAndIsFile(path.toFile());
        } catch (final UnsupportedOperationException ignored) {
        }
        try {
            if (!Files.isReadable(path)) {
                return false;
            }
        } catch (final SecurityException e) {
            return false;
        }
        return Files.isRegularFile(path);
    }

    public static boolean isFile(final Path path) {
        try {
            return path.toFile().isFile();
        } catch (final UnsupportedOperationException e) {
            return Files.isRegularFile(path);
        } catch (final SecurityException e) {
            return false;
        }
    }

    /**
     * 检查 {@link File} 是否可读:如果不存在,不是普通文件或无法读取, 则抛出 IOException
     * @param file 一个 {@link File}
     * @throws IOException 如果文件不存在,不是普通文件或无法读取
     */
    public static void checkCanReadAndIsFile(final File file) throws IOException {
        try {
            if (!file.canRead()) {
                throw new FileNotFoundException("File does not exist or cannot be read: " + file);
            }
        } catch (final SecurityException e) {
            throw new FileNotFoundException("File " + file + " cannot be accessed: " + e);
        }
        if (!file.isFile()) {
            throw new IOException("Not a regular file: " + file);
        }
    }

    /**
     * 检查 {@link Path} 是否可读:如果不存在,不是普通文件或无法读取, 则抛出 IOException
     * @param path 一个 {@link Path}
     * @throws IOException 如果路径不存在,不是普通文件或无法读取
     */
    public static void checkCanReadAndIsFile(final Path path) throws IOException {
        try {
            checkCanReadAndIsFile(path.toFile());
            return;
        } catch (final UnsupportedOperationException ignored) {
        }
        try {
            if (!Files.isReadable(path)) {
                throw new FileNotFoundException("Path does not exist or cannot be read: " + path);
            }
        } catch (final SecurityException e) {
            throw new FileNotFoundException("Path " + path + " cannot be accessed: " + e);
        }
        if (!Files.isRegularFile(path)) {
            throw new IOException("Not a regular file: " + path);
        }
    }

    /**
     * 检查 {@link File} 是否存在,是目录且可读
     * @param file 一个 {@link File}
     * @return 如果文件存在,是目录且可读则返回 true
     */
    public static boolean canReadAndIsDir(final File file) {
        try {
            if (!file.canRead()) {
                return false;
            }
        } catch (final SecurityException e) {
            return false;
        }
        return file.isDirectory();
    }

    /**
     * 检查 {@link Path} 是否存在,是目录且可读
     * @param path 一个 {@link Path}
     * @return 如果文件存在,是目录且可读则返回 true
     */
    public static boolean canReadAndIsDir(final Path path) {
        try {
            return canReadAndIsDir(path.toFile());
        } catch (final UnsupportedOperationException ignored) {
        }
        try {
            if (!Files.isReadable(path)) {
                return false;
            }
        } catch (final SecurityException e) {
            return false;
        }
        return Files.isDirectory(path);
    }

    public static boolean isDir(final Path path) {
        try {
            return path.toFile().isDirectory();
        } catch (final UnsupportedOperationException e) {
            return Files.isDirectory(path);
        } catch (final SecurityException e) {
            return false;
        }
    }

    /**
     * 检查 {@link File} 是否可读:如果不存在,不是目录或无法读取, 则抛出 IOException
     * @param file 一个 {@link File}
     * @throws IOException 如果文件不存在,不是目录或无法读取
     */
    public static void checkCanReadAndIsDir(final File file) throws IOException {
        try {
            if (!file.canRead()) {
                throw new FileNotFoundException("Directory does not exist or cannot be read: " + file);
            }
        } catch (final SecurityException e) {
            throw new FileNotFoundException("File " + file + " cannot be accessed: " + e);
        }
        if (!file.isDirectory()) {
            throw new IOException("Not a directory: " + file);
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * 获取父目录路径
     * @param path 路径
     * @param separator 分隔符
     * @return 父目录路径
     */
    public static String getParentDirPath(final String path, final char separator) {
        final int lastSlashIdx = path.lastIndexOf(separator);
        if (lastSlashIdx <= 0) {
            return "";
        }
        return path.substring(0, lastSlashIdx);
    }

    /**
     * 获取父目录路径
     * @param path 路径
     * @return 父目录路径
     */
    public static String getParentDirPath(final String path) {
        return getParentDirPath(path, '/');
    }

    public static AtomicBoolean getInitialized() {
        return initialized;
    }

}
