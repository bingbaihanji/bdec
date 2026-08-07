package com.bingbaihanji.bdec.decompiler.utils.io;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;

/**
 * 字节,十六进制,摘要和简单文件魔数工具
 *
 * @author bingbaihanji
 * @date 2025-12-08
 */
public final class ByteUtils {

    private static final String[] UNITS = {"B", "KB", "MB", "GB", "TB", "PB"};

    private static final int UNIT = 1024;

    private static final char[] HEX_CHARS = "0123456789abcdef".toCharArray();

    private ByteUtils() {
        throw new AssertionError("工具类不允许实例化");
    }

    /**
     * 格式化字节大小为易读字符串
     * @param byteSize 字节数
     * @return 格式化后的字符串,如 "1.23 MB"
     */
    public static String formatByteSize(long byteSize) {

        if (byteSize < 0) {
            return String.valueOf(byteSize);
        }

        double size = (double) byteSize;
        int unitIndex = 0;

        while (unitIndex < UNITS.length - 1 && size >= UNIT) {
            size /= UNIT;
            unitIndex++;
        }

        return format(size, UNITS[unitIndex]);
    }

    /**
     * 字节数组转十六进制字符串
     * @param bytes 字节数组,不能为 null
     * @return 十六进制字符串(小写)
     */
    public static String bytesToHex(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes 不能为 null");
        char[] hexChars = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            hexChars[i * 2] = HEX_CHARS[v >>> 4];
            hexChars[i * 2 + 1] = HEX_CHARS[v & 0x0F];
        }
        return new String(hexChars);
    }

    /**
     * 十六进制字符串转字节数组
     * @param hex 十六进制字符串,不能为 null,长度必须为偶数
     * @return 字节数组
     * @throws IllegalArgumentException 如果 hex 为空或长度非偶数
     */
    public static byte[] hexToBytes(String hex) {
        Objects.requireNonNull(hex, "hex 不能为 null");
        if (hex.length() % 2 != 0) {
            throw new IllegalArgumentException("十六进制字符串长度必须为偶数: " + hex.length());
        }
        int len = hex.length();
        byte[] bytes = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            int high = Character.digit(hex.charAt(i), 16);
            int low = Character.digit(hex.charAt(i + 1), 16);
            if (high < 0 || low < 0) {
                throw new IllegalArgumentException("非法十六进制字符,位置: " + i);
            }
            bytes[i / 2] = (byte) ((high << 4) | low);
        }
        return bytes;
    }

    /**
     * 字节数组转 int(大端序,4 字节)
     * @param bytes 字节数组,长度至少 4
     * @return int 值
     * @throws IllegalArgumentException 如果 bytes 长度不足 4
     */
    public static int toInt(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes 不能为 null");
        if (bytes.length < 4) {
            throw new IllegalArgumentException("字节数组长度不足 4: " + bytes.length);
        }
        return (bytes[0] & 0xFF) << 24 | (bytes[1] & 0xFF) << 16 | (bytes[2] & 0xFF) << 8 | (bytes[3] & 0xFF);
    }

    /**
     * 字节数组转 long(大端序,8 字节)
     * @param bytes 字节数组,长度至少 8
     * @return long 值
     * @throws IllegalArgumentException 如果 bytes 长度不足 8
     */
    public static long toLong(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes 不能为 null");
        if (bytes.length < 8) {
            throw new IllegalArgumentException("字节数组长度不足 8: " + bytes.length);
        }
        return (long) (bytes[0] & 0xFF) << 56 | (long) (bytes[1] & 0xFF) << 48 | (long) (bytes[2] & 0xFF) << 40
                | (long) (bytes[3] & 0xFF) << 32 | (long) (bytes[4] & 0xFF) << 24 | (long) (bytes[5] & 0xFF) << 16
                | (long) (bytes[6] & 0xFF) << 8 | (long) (bytes[7] & 0xFF);
    }

    /**
     * 将单个字节值转换为补零的两位十六进制字符串
     * @param value 字节值 (仅取低 8 位)
     * @return 两位十六进制字符串
     */
    public static String byteToHex(int value) {
        int v = value & 0xFF;
        char[] hexChars = {HEX_CHARS[v >>> 4], HEX_CHARS[v & 0x0F]};
        return new String(hexChars);
    }

    /**
     * 将 int 值转换为补零的 8 位十六进制字符串
     * @param value 整数值
     * @return 8 位十六进制字符串
     */
    public static String intToHex(int value) {
        char[] hexChars = new char[8];
        int v = value;
        for (int i = 7; i >= 0; i--) {
            hexChars[i] = HEX_CHARS[v & 0x0F];
            v >>>= 4;
        }
        return new String(hexChars);
    }

    /**
     * 计算字节数组的 MD5 哈希值
     * @param data 输入字节数组
     * @return 十六进制表示的 MD5 值
     */
    public static String md5Sum(byte[] data) {
        Objects.requireNonNull(data, "data 不能为 null");
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            md.update(data);
            return bytesToHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("MD5 algorithm not available", e);
        }
    }

    /**
     * 计算字符串 (UTF-8 编码)的 MD5 哈希值
     * @param str 输入字符串
     * @return 十六进制表示的 MD5 值
     */
    public static String md5Sum(String str) {
        Objects.requireNonNull(str, "str 不能为 null");
        return md5Sum(str.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 通过文件魔数 (PK\03\04)判断文件是否为 ZIP 格式
     * @param file 待检测的文件
     * @return 是 ZIP 文件返回 true
     */
    public static boolean isZipFile(File file) {
        if (file == null || !file.isFile()) {
            return false;
        }
        byte[] magic = {0x50, 0x4B, 0x03, 0x04};
        try (InputStream is = new FileInputStream(file)) {
            byte[] headers = new byte[magic.length];
            int read = is.read(headers);
            return read == magic.length && Arrays.equals(headers, magic);
        } catch (Exception e) {
            return false;
        }
    }

    private static String format(double size, String type) {
        int precision = 0;

        if (size * 100 % 10 > 0) {
            precision = 2;
        } else if (size * 10 % 10 > 0) {
            precision = 1;
        }

        String formatStr = "%." + precision + "f";
        return String.format(Locale.ROOT, formatStr, size) + " " + type;
    }

}
