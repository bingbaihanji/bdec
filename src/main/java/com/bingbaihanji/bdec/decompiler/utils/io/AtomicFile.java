package com.bingbaihanji.bdec.decompiler.utils.io;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * 用于对文件执行原子写入操作的辅助类
 * 借鉴了 Android 原生 AtomicFile 的经典设计逻辑,能够较好地解决文件写入过程中由于突发断电,进程崩溃导致的数据损坏问题
 * <p>
 * 先写入临时文件 (.new),写入并强行刷盘 (sync) 成功后再将其原子重命名为目标文件.
 * 能够有效防止写文件过程中因断电,进程崩溃导致的数据损坏或文件内容为空.
 * <p>
 * <strong>线程安全注意:</strong>
 * 本类不提供任何文件锁机制.当文件可能被多个线程或进程并发修改时,
 * 调用者负责在外部进行适当的互斥同步.
 *
 * @author bingbaihanji
 * @since 1.0
 */
public final class AtomicFile {


    private final File baseFile;

    private final File newFile;

    private final File legacyBackupFile;

    /**
     * 为指定的基础文件路径创建一个新的 AtomicFile.
     *
     * @param baseName 基础文件对象
     */
    public AtomicFile(File baseName) {
        this.baseFile = Objects.requireNonNull(baseName, "baseName 不能为 null");
        this.newFile = new File(baseFile.getPath() + ".new");
        this.legacyBackupFile = new File(baseFile.getPath() + ".bak");
    }

    /**
     * @deprecated commitTag 参数未被使用,请使用 {@link #AtomicFile(File)}
     */
    @Deprecated
    public AtomicFile(File baseName, String commitTag) {
        this(baseName);
    }

    private static void renameInternal(File source, File target) throws IOException {
        if (target.isDirectory()) {
            deleteIfExists(target);
        }
        try {
            Files.move(source.toPath(), target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            // 如果操作系统/文件系统不支持 ATOMIC_MOVE,退而求其次使用普通移动
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void syncParentDir(File file) {
        File parent = file.getParentFile();
        if (parent == null) {
            return;
        }
        Path parentPath = parent.toPath();
        try (FileChannel fc = FileChannel.open(parentPath, StandardOpenOption.READ)) {
            fc.force(true);
        } catch (IOException ignored) {
            // 部分操作系统(如 Windows)不支持对目录 open/force,忽略此异常
        }
    }

    private static void trySetParentPermissions(File parent) {
        try {
            parent.setExecutable(true, false);
            parent.setReadable(true, false);
            parent.setWritable(true, false);
        } catch (SecurityException ignored) {
            // 忽略权限设置失败
        }
    }

    private static void deleteIfExists(File file) {
        try {
            Files.deleteIfExists(file.toPath());
        } catch (IOException e) {
//            log.warn("无法删除文件 {}", file, e);
        }
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (Exception ignored) {
                // 静默忽略
            }
        }
    }

    /**
     * 返回基础文件对象.
     *
     * @return 基础文件
     */
    public File getBaseFile() {
        return baseFile;
    }

    /**
     * 检查原始文件或旧版备份文件是否存在.
     *
     * @return 如果原始文件或备份文件存在则返回 true
     */
    public boolean exists() {
        return baseFile.exists() || legacyBackupFile.exists();
    }

    /**
     * 获取原子文件的最后修改时间.
     *
     * @return 毫秒级时间戳,如果文件不存在或发生 I/O 错误则返回 0L
     */
    public long getLastModifiedTime() {
        if (legacyBackupFile.exists()) {
            return legacyBackupFile.lastModified();
        }
        return baseFile.lastModified();
    }

    /**
     * 删除与此原子文件关联的所有相关文件(包含基础文件,临时文件和备份文件).
     */
    public void delete() {
        deleteIfExists(baseFile);
        deleteIfExists(newFile);
        deleteIfExists(legacyBackupFile);
    }

    /**
     * 开始对文件进行新的写入操作.
     * <p>
     * 返回一个 {@link FileOutputStream} 用于写入新数据.
     * 写入完成后<b>必须</b>调用 {@link #finishWrite(FileOutputStream)} 提交,
     * 或在发生异常时调用 {@link #failWrite(FileOutputStream)} 放弃写入.
     *
     * @return 用于写入新数据的文件输出流
     * @throws IOException 如果无法创建父目录或无法打开新文件流
     */
    public FileOutputStream startWrite() throws IOException {
        // 恢复旧版备份文件(如果存在)
        if (legacyBackupFile.exists()) {
            renameInternal(legacyBackupFile, baseFile);
        }

        try {
            return new FileOutputStream(newFile);
        } catch (FileNotFoundException e) {
            File parent = newFile.getParentFile();
            if (parent != null) {
                // 确保父目录存在
                Files.createDirectories(parent.toPath());
                // 尝试配置权限(POSIX 系统有效)
                trySetParentPermissions(parent);
            }
            try {
                return new FileOutputStream(newFile);
            } catch (FileNotFoundException e2) {
                throw new IOException("无法创建临时写入文件: " + newFile, e2);
            }
        }
    }

    /**
     * 完成写入并提交数据.
     * <p>
     * 此方法会关闭流,将数据物理刷入磁盘,并将临时文件重命名为基础文件.
     * 如果刷盘或重命名失败,将抛出 {@link IOException}.
     *
     * @param str 要提交的输出流
     * @throws IOException 如果刷盘或文件重命名失败
     */
    public void finishWrite(FileOutputStream str) throws IOException {
        if (str == null) {
            return;
        }

        boolean success = false;
        try {
            // 1. 强制数据落盘
            str.getFD().sync();
            // 2. 关闭输出流
            str.close();
            success = true;
        } catch (IOException e) {
            throw new IOException("原子写入失败:无法同步或关闭输出流 " + newFile, e);
        } finally {
            if (!success) {
                closeQuietly(str);
                deleteIfExists(newFile);
            }
        }

        // 3. 将临时文件原子替换至目标文件
        renameInternal(newFile, baseFile);

        // 4. 对父目录执行 sync,保证目录元数据落盘 (POSIX 兼容性)
        syncParentDir(baseFile);
    }

    /**
     * 放弃写入操作.关闭写入流并清理创建的临时文件.
     *
     * @param str 要放弃的输出流(可为 null)
     */
    public void failWrite(FileOutputStream str) {
        if (str == null) {
            return;
        }
        closeQuietly(str);
        deleteIfExists(newFile);
    }

    // ==================== 私有辅助方法 ====================

    /**
     * 打开原子文件以进行读取.
     *
     * @return 用于读取的文件输入流
     * @throws FileNotFoundException 如果基础文件不存在
     */
    public FileInputStream openRead() throws FileNotFoundException {
        if (legacyBackupFile.exists()) {
            try {
                renameInternal(legacyBackupFile, baseFile);
            } catch (IOException e) {
                // log.warn("恢复旧备份文件失败: {}", legacyBackupFile, e);
            }
        }

        // 如果存在上次写入中断留下的 .new 垃圾文件,将其清理
        if (newFile.exists()) {
            deleteIfExists(newFile);
        }

        return new FileInputStream(baseFile);
    }

    /**
     * 便捷读取方法,读取文件全部内容至字节数组.
     *
     * @return 文件全部内容的字节数组
     * @throws IOException 如果读取过程发生错误
     */
    public byte[] readFully() throws IOException {
        try (FileInputStream stream = openRead()) {
            return stream.readAllBytes();
        }
    }

    /**
     * 便捷写入方法,使用给定的写入逻辑自动处理起点,提交与失败回滚.
     *
     * @param writeContent 接收 FileOutputStream 执行写入逻辑的 Consumer
     * @throws UncheckedIOException 如果发生 I/O 错误
     */
    public void write(Consumer<FileOutputStream> writeContent) {
        Objects.requireNonNull(writeContent, "writeContent 不能为 null");
        FileOutputStream out = null;
        try {
            out = startWrite();
            writeContent.accept(out);
            finishWrite(out);
            out = null; // 标记成功,防止 finally 中重复 failWrite
        } catch (IOException e) {
            failWrite(out);
            throw new UncheckedIOException("原子写入文件失败: " + baseFile, e);
        } catch (RuntimeException | Error e) {
            failWrite(out);
            throw e;
        }
    }

    @Deprecated
    public void truncate() throws IOException {
        try (FileOutputStream fos = new FileOutputStream(baseFile)) {
            fos.getFD().sync();
        } catch (FileNotFoundException e) {
            throw new IOException("无法清空文件: " + baseFile, e);
        }
    }

    @Deprecated
    public FileOutputStream openAppend() throws IOException {
        try {
            return new FileOutputStream(baseFile, true);
        } catch (FileNotFoundException e) {
            throw new IOException("无法以追加模式打开文件: " + baseFile, e);
        }
    }

    @Override
    public String toString() {
        return "AtomicFile[" + baseFile + "]";
    }
}