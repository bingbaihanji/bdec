#!/usr/bin/env python3
# -*- coding: utf-8 -*-

"""
将指定目录下(含子目录)所有 Java 源文件中的全角标点符号转换为半角标点符号
"""

import os
import sys
import argparse

# 全角 → 半角 映射表（标点符号）
FULLWIDTH_TO_HALFWIDTH = {
    # 中文常用标点
    '，': ',',   # 全角逗号
    '。': '.',   # 中文句号
    '！': '!',   # 全角感叹号
    '？': '?',   # 全角问号
    '；': ';',   # 全角分号
    '：': ':',   # 全角冒号
    '（': '(',   # 全角左括号
    '）': ')',   # 全角右括号
    '【': '[',   # 全角左方括号（中文常用）
    '】': ']',   # 全角右方括号
    '“': '"',   # 左双引号
    '”': '"',   # 右双引号
    '‘': "'",   # 左单引号
    '’': "'",   # 右单引号
    '＇': "'",   # 全角单引号（U+FF07）
    '＂': '"',   # 全角双引号（U+FF02）
    '　': ' ',   # 全角空格
    '、': ',',   # 顿号 → 逗号（酌情）
    '～': '~',   # 全角波浪号
    '…': '...',  # 省略号（酌情转为三个点）
}

# 构建 str.translate 用的映射字典（键为 Unicode 码点）
TRANS_TABLE = {ord(k): v for k, v in FULLWIDTH_TO_HALFWIDTH.items()}


def convert_content(text: str) -> str:
    """将文本中的全角标点转换为半角标点。"""
    return text.translate(TRANS_TABLE)


def process_file(filepath: str, dry_run: bool, backup: bool) -> bool:
    """
    处理单个文件：读取、转换、写回。
    返回值：是否实际修改了文件内容。
    """
    try:
        # 使用 utf-8-sig 自动处理 BOM，读取内容
        with open(filepath, 'r', encoding='utf-8-sig') as f:
            original = f.read()
    except UnicodeDecodeError:
        # 若 UTF-8 解码失败，尝试 GBK（常见于老项目）
        try:
            with open(filepath, 'r', encoding='gbk') as f:
                original = f.read()
        except Exception as e:
            print(f"⚠️  跳过无法解码的文件: {filepath} ({e})")
            return False
    except Exception as e:
        print(f"⚠️  读取失败: {filepath} ({e})")
        return False

    converted = convert_content(original)

    if converted == original:
        return False  # 无需修改

    if dry_run:
        print(f"🔍 [DRY RUN] 将会修改: {filepath}")
        return True

    # 实际修改：先备份（若需要）
    if backup:
        backup_path = filepath + '.bak'
        try:
            os.rename(filepath, backup_path)
            # 写入新文件，使用与读取时相同的编码（去掉 BOM）
            with open(filepath, 'w', encoding='utf-8') as f:
                f.write(converted)
            print(f"✅ 已备份并转换: {filepath} (备份: {backup_path})")
        except Exception as e:
            # 若备份失败，尝试直接写入（回退）
            print(f"⚠️  备份失败，尝试直接写入: {filepath} ({e})")
            try:
                with open(filepath, 'w', encoding='utf-8') as f:
                    f.write(converted)
                print(f"✅ 已转换: {filepath}")
            except Exception as e2:
                print(f"❌ 写入失败: {filepath} ({e2})")
                return False
    else:
        try:
            with open(filepath, 'w', encoding='utf-8') as f:
                f.write(converted)
            print(f"✅ 已转换: {filepath}")
        except Exception as e:
            print(f"❌ 写入失败: {filepath} ({e})")
            return False
    return True


def main():
    parser = argparse.ArgumentParser(
        description="将目录下所有 Java 文件中的全角标点转换为半角标点。"
    )
    parser.add_argument('directory', help='要处理的根目录路径')
    parser.add_argument('--backup', action='store_true',
                        help='修改前备份原文件（添加 .bak 后缀）')
    parser.add_argument('--dry-run', action='store_true',
                        help='只显示会修改的文件，不实际修改')
    args = parser.parse_args()

    root_dir = args.directory
    if not os.path.isdir(root_dir):
        print(f"❌ 错误: '{root_dir}' 不是有效的目录")
        sys.exit(1)

    modified_count = 0
    total_count = 0

    for dirpath, _, filenames in os.walk(root_dir):
        for filename in filenames:
            if filename.endswith('.java'):
                total_count += 1
                filepath = os.path.join(dirpath, filename)
                if process_file(filepath, args.dry_run, args.backup):
                    modified_count += 1

    print("\n处理完成:")
    print(f"  扫描 Java 文件总数: {total_count}")
    print(f"  修改文件数: {modified_count}")
    if args.dry_run:
        print("  (DRY RUN 模式，未实际写入)")


if __name__ == '__main__':
    main()