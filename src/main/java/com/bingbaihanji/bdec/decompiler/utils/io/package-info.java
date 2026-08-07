/**
 * 文件,字节,摘要和二进制格式辅助工具包
 *
 * <p>应用场景:文件读写与目录操作,字节数组与十六进制/二进制转换,原子性文件写入
 * (防止写入中断导致文件损坏),JAR 文件创建与解包,NIO 通道高效传输等日常 I/O
 * 操作场景 
 *
 * <ul>
 * <li>{@link com.bingbaihanji.common.framework.utils.io.FileUtils} —
 * 文件与目录的便捷操作</li>
 * <li>{@link com.bingbaihanji.common.framework.utils.io.AtomicFile} —
 * 原子性文件写入(先写临时文件再重命名)</li>
 * <li>{@link com.bingbaihanji.common.framework.utils.io.ByteUtils} —
 * 字节数组与十六进制字符串互转</li>
 * <li>{@link com.bingbaihanji.common.framework.utils.io.JarUtils} —
 * JAR 文件打包工具</li>
 * <li>{@link com.bingbaihanji.common.framework.utils.io.NIOUtils} —
 * NIO Channel/Buffer 操作工具</li>
 * </ul>
 *
 * @author 冰白寒祭
 * @since 2026-07-24
 */
package com.bingbaihanji.bdec.decompiler.utils.io;
