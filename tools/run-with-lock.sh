#!/usr/bin/env bash
# 串行化 mvn 构建(并行子代理场景):获取锁 → 执行命令 → 释放锁.
# 用法: bash tools/run-with-lock.sh mvn -q test -Dtest=FooTest
# 锁为 .claude/build.lock 目录;陈旧锁(>10 分钟)自动回收.
LOCK_DIR=".claude/build.lock"
while ! mkdir "$LOCK_DIR" 2>/dev/null; do
    if [ -d "$LOCK_DIR" ]; then
        # 陈旧锁回收:锁目录修改时间超过 10 分钟视为残留
        if [ "$(find "$LOCK_DIR" -mmin +10 2>/dev/null)" ]; then
            rmdir "$LOCK_DIR" 2>/dev/null && continue
        fi
    fi
    sleep 2
done
trap 'rmdir "$LOCK_DIR" 2>/dev/null' EXIT
"$@"
