#!/usr/bin/env python3
"""把压测产出的 JSON 汇总成一张 Markdown 表格，直接贴进性能报告。

读一个结果目录里所有 *.json，按场景分组、按并发升序排，
输出每个场景一条曲线，方便看"并发加到多少吞吐就不再涨、延迟开始飙"。
"""

import argparse
import json
from pathlib import Path


def load_results(directory):
    """把目录下所有场景结果读进来，忽略汇总文件自己。"""
    results = []
    for path in sorted(directory.glob("*.json")):
        if path.name.startswith("summary"):
            continue
        try:
            data = json.loads(path.read_text(encoding="utf-8"))
        except (json.JSONDecodeError, OSError):
            continue
        # id 清单那种纯文本文件不是 JSON，解析失败就跳过。
        if "rps" not in data:
            continue
        results.append(data)
    return results


def scenario_of(label):
    """label 形如 shop-cache-c100，去掉末尾的 -c<数字> 得到场景名。"""
    if "-c" in label:
        head, _, tail = label.rpartition("-c")
        if tail.isdigit():
            return head
    return label


def build_table(results):
    """按场景分组，组内按并发排序，每个场景输出一组行。"""
    grouped = {}
    for item in results:
        grouped.setdefault(scenario_of(item["label"]), []).append(item)

    lines = [
        "| 场景 | 并发 | 吞吐(req/s) | 成功 | 业务失败 | HTTP错误 | 传输异常 | avg | p50 | p95 | p99 | max |",
        "| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |",
    ]
    for scenario in sorted(grouped):
        rows = sorted(grouped[scenario], key=lambda x: x["concurrency"])
        for row in rows:
            lines.append(
                f"| {scenario} | {row['concurrency']} | {row['rps']} | {row['ok']} | "
                f"{row['businessFail']} | {row['httpError']} | {row.get('transportError', row.get('notSent', '未知'))} | {row['avgMs']} | {row['p50Ms']} | "
                f"{row['p95Ms']} | {row['p99Ms']} | {row['maxMs']} |"
            )
    return "\n".join(lines)


def find_knee(rows):
    """找吞吐拐点：按并发升序，当 rps 增幅不到 15% 时，说明再加并发只是排队。"""
    ordered = sorted(rows, key=lambda x: x["concurrency"])
    for prev, cur in zip(ordered, ordered[1:]):
        if prev["rps"] > 0 and cur["rps"] / prev["rps"] < 1.15:
            return prev, cur
    return None, ordered[-1] if ordered else None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path, nargs="?", default=Path("."))
    parser.add_argument("--out", type=Path, default=None)
    args = parser.parse_args()

    results = load_results(args.directory)
    if not results:
        raise SystemExit(f"{args.directory} 里没有可用的压测结果")

    table = build_table(results)
    print(table)

    grouped = {}
    for item in results:
        grouped.setdefault(scenario_of(item["label"]), []).append(item)
    print()
    print("## 拐点判断")
    for scenario in sorted(grouped):
        prev, best = find_knee(grouped[scenario])
        peak = max(grouped[scenario], key=lambda row: row["rps"])
        if prev and best:
            print(f"- {scenario}：并发 {prev['concurrency']} 之后吞吐基本不再涨"
                  f"（{prev['rps']} → {best['rps']} req/s），本轮最高吞吐 {peak['rps']} req/s（并发 {peak['concurrency']}），"
                  f"该档 p95={best['p95Ms']}ms")
        elif best:
            print(f"- {scenario}：没有检测到拐点；最高并发档吞吐 {best['rps']} req/s，p95={best['p95Ms']}ms")

    if args.out:
        args.out.write_text(table + "\n", encoding="utf-8")
        print(f"\n表格已写入 {args.out}")


if __name__ == "__main__":
    main()
