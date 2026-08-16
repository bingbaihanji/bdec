/**
 * 缓存工具包
 *
 * <p>应用场景:热点数据本地缓存,计算结果记忆化(避免重复计算),会话/Session 缓存,
 * 配置缓存,临时数据带过期时间的自动淘汰等需要减少数据库或远程调用,提升响应速度的场景 
 *
 * <ul>
 * <li>{@link com.bingbaihanji.common.framework.utils.cache.Cache} — 缓存统一接口</li>
 * <li>{@link com.bingbaihanji.common.framework.utils.cache.LruCache} — LRU 缓存(基于
 * LinkedHashMap)</li>
 * <li>{@link com.bingbaihanji.common.framework.utils.cache.LfuCache} — LFU
 * 缓存(基于频率淘汰)</li>
 * <li>{@link com.bingbaihanji.common.framework.utils.cache.TtlCache} — 带过期时间的缓存</li>
 * <li>{@link com.bingbaihanji.common.framework.utils.cache.TtlLruCache} — 支持 TTL/TTI 的
 * LRU 缓存</li>
 * <li>{@link com.bingbaihanji.common.framework.utils.cache.MemoizedFunctions} —
 * 函数记忆化工具</li>
 * <li>{@link com.bingbaihanji.common.framework.utils.cache.LocalCacheUtils} — 本地缓存工具</li>
 * </ul>
 *
 * @author 冰白寒祭
 * @since 2026-07-24
 */
package com.bingbaihanji.test.cache;
