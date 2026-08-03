#!/usr/bin/env python3
"""
按文件时间把一个文件夹里的文件分类到 年/月/日 文件夹中。

示例：
  python3 classify_files_by_date.py /path/to/files
  python3 classify_files_by_date.py /path/to/files --output /path/to/sorted
  python3 classify_files_by_date.py /path/to/files --layout flat
  python3 classify_files_by_date.py /path/to/files --recursive --mode copy
  python3 classify_files_by_date.py /path/to/files --dry-run
"""

from __future__ import annotations

import argparse
import shutil
from datetime import datetime
from pathlib import Path


def get_file_time(path: Path, date_field: str) -> datetime:
    stat = path.stat()

    if date_field == "created":
        # macOS 有 st_birthtime；其他系统没有时退回到 ctime。
        timestamp = getattr(stat, "st_birthtime", stat.st_ctime)
    elif date_field == "changed":
        timestamp = stat.st_ctime
    else:
        timestamp = stat.st_mtime

    return datetime.fromtimestamp(timestamp)


def unique_target_path(target: Path) -> Path:
    if not target.exists():
        return target

    stem = target.stem
    suffix = target.suffix
    parent = target.parent
    index = 1

    while True:
        candidate = parent / f"{stem}_{index}{suffix}"
        if not candidate.exists():
            return candidate
        index += 1


def iter_files(source: Path, recursive: bool, pattern: str):
    iterator = source.rglob(pattern) if recursive else source.glob(pattern)
    for path in iterator:
        if path.is_file():
            yield path


def classify_files(
    source: Path,
    output: Path,
    recursive: bool,
    pattern: str,
    mode: str,
    date_field: str,
    layout: str,
    dry_run: bool,
) -> tuple[int, int]:
    handled = 0
    skipped = 0

    for file_path in iter_files(source, recursive, pattern):
        file_time = get_file_time(file_path, date_field)
        if layout == "flat":
            target_dir = output / file_time.strftime("%Y-%m-%d")
        else:
            target_dir = output / file_time.strftime("%Y") / file_time.strftime("%m") / file_time.strftime("%d")
        target_path = unique_target_path(target_dir / file_path.name)

        if file_path.resolve() == target_path.resolve():
            skipped += 1
            continue

        print(f"{mode}: {file_path} -> {target_path}")

        if not dry_run:
            target_dir.mkdir(parents=True, exist_ok=True)
            if mode == "copy":
                shutil.copy2(file_path, target_path)
            else:
                shutil.move(str(file_path), str(target_path))

        handled += 1

    return handled, skipped


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="将指定文件夹里的文件按文件时间分类到 YYYY/MM/DD 文件夹下。"
    )
    parser.add_argument("source", help="要整理的源文件夹")
    parser.add_argument(
        "-o",
        "--output",
        help="输出文件夹；默认和源文件夹相同",
    )
    parser.add_argument(
        "--recursive",
        action="store_true",
        help="递归整理子文件夹中的文件",
    )
    parser.add_argument(
        "--pattern",
        default="*",
        help="文件匹配规则，默认 *，例如 '*.jpg'",
    )
    parser.add_argument(
        "--mode",
        choices=("move", "copy"),
        default="move",
        help="move 表示移动文件，copy 表示复制文件；默认 move",
    )
    parser.add_argument(
        "--date-field",
        choices=("modified", "created", "changed"),
        default="modified",
        help="按哪个时间分类：modified=修改时间，created=创建时间，changed=状态变更时间；默认 modified",
    )
    parser.add_argument(
        "--layout",
        choices=("nested", "flat"),
        default="nested",
        help="日期目录格式：nested=YYYY/MM/DD，flat=YYYY-MM-DD；默认 nested",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="只打印将要执行的操作，不实际移动或复制",
    )
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    source = Path(args.source).expanduser().resolve()
    output = Path(args.output).expanduser().resolve() if args.output else source

    if not source.exists():
        raise SystemExit(f"源文件夹不存在：{source}")
    if not source.is_dir():
        raise SystemExit(f"源路径不是文件夹：{source}")

    handled, skipped = classify_files(
        source=source,
        output=output,
        recursive=args.recursive,
        pattern=args.pattern,
        mode=args.mode,
        date_field=args.date_field,
        layout=args.layout,
        dry_run=args.dry_run,
    )

    action = "预览完成" if args.dry_run else "整理完成"
    print(f"{action}：处理 {handled} 个文件，跳过 {skipped} 个文件。")


if __name__ == "__main__":
    main()
