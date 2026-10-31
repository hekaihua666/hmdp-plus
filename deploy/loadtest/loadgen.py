#!/usr/bin/env python3
"""极简压测器：只用 Python 标准库，服务器上不用装任何依赖。

为什么用多进程：Python 有 GIL，单进程里开再多线程，真正能并行的只有 IO，
实测单进程到 180 req/s 左右就顶住了——那时压的是压测机自己，不是后端。
所以默认按 CPU 核数起多个进程，每个进程内再开若干线程，
压测机才不会成为瓶颈。

用法举例：
  # 缓存读，20 并发
  python3 loadgen.py --path '/shop/{id}' --ids-file shop-ids.txt --concurrency 20

  # 秒杀，每个请求换一个没抢过的用户
  python3 loadgen.py --path '/voucher-order/seckill/31' --method POST \
    --tokens tokens.txt --concurrency 20
"""

import argparse
import http.client
import json
import multiprocessing as mp
import random
import statistics
import threading
import time
import urllib.parse
from pathlib import Path


def parse_base(base):
    """把 http://127.0.0.1:8081 拆成 host 和 port，http.client 只认这两样。"""
    if not base.startswith("http://"):
        raise SystemExit("--base 必须以 http:// 开头")
    host_port = base[len("http://"):].rstrip("/")
    if ":" in host_port:
        host, port = host_port.split(":", 1)
        return host, int(port)
    return host_port, 80


def build_path(template, ids, id_min, id_max):
    """{id} 每次换一个 id。给了 id 清单就从清单里挑，避免随机到库里不存在的 id 把失败率抬高。

    路径里可能有中文（比如 keyword=海底捞）。http.client 只接受 ASCII 请求行，
    不转义会直接抛异常、请求根本没发出去，结果会显示成"每秒几百万"，全是假的。
    所以这里统一做一次 URL 转义。
    """
    if "{id}" in template:
        if ids:
            template = template.replace("{id}", str(random.choice(ids)))
        else:
            template = template.replace("{id}", str(random.randint(id_min, id_max)))
    return urllib.parse.quote(template, safe="/?&=:%,-._~+")


def run_worker(host, port, args, tokens, ids, deadline, bucket, progress):
    """一个线程的循环：自己持长连接、自己发请求，把耗时塞进本线程的桶里。

    每个线程用独立的桶，避免多线程抢同一把锁；汇总放到最后一起做。
    每发完一个请求把共享计数器 +1，父进程靠它每 2 秒报一次进度，
    不然 60 秒里屏幕一片安静，看着像卡死。
    """
    body = args.body.encode() if args.body else None
    conn = None
    while time.monotonic() < deadline:
        token = None
        if args.tokens:
            # unique：每个 token 只用一次，模拟不同用户各抢一次
            with args.token_lock:
                if args.cursor[0] >= len(tokens):
                    break
                token = tokens[args.cursor[0]]
                args.cursor[0] += 1
        headers = {"Content-Type": "application/json", "Connection": "keep-alive"}
        if token:
            headers["authorization"] = token
        path = build_path(args.path, ids, args.id_min, args.id_max)
        started = time.perf_counter()
        try:
            if conn is None:
                conn = http.client.HTTPConnection(host, port, timeout=args.timeout)
            conn.request(args.method, path, body=body, headers=headers)
            resp = conn.getresponse()
            text = resp.read()
            cost = (time.perf_counter() - started) * 1000
            business_ok = True
            if args.expect_success:
                # 后端业务失败也回 HTTP 200，只能从 JSON 的 success 字段区分。
                business_ok = b'"success":true' in text.replace(b" ", b"")
            # 完整响应与传输异常分别计数；异常可能发生在请求已经发出之后。
            bucket.append((cost, resp.status < 400, business_ok, True))
        except Exception:
            bucket.append(((time.perf_counter() - started) * 1000, False, False, False))
            if conn is not None:
                conn.close()
            conn = None
        with progress.get_lock():
            progress.value += 1
    if conn is not None:
        conn.close()


def process_main(index, host, port, args, tokens, ids, queue, progress):
    """一个压测进程：开 threads 个线程，各自跑到截止时间，再把结果汇总回父进程。"""
    deadline = time.monotonic() + args.duration
    args.token_lock = threading.Lock()
    args.cursor = [0]
    buckets = [[] for _ in range(args.threads)]
    threads = [threading.Thread(target=run_worker,
                                args=(host, port, args, tokens, ids, deadline, buckets[i], progress))
               for i in range(args.threads)]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join()
    merged = [sample for bucket in buckets for sample in bucket]
    queue.put((index, merged))


def percentile(sorted_samples, p):
    """取分位数。向上取整定位，样本很少时也不会取到第 0 个。"""
    if not sorted_samples:
        return 0.0
    index = min(len(sorted_samples) - 1, int(round(p / 100.0 * len(sorted_samples) + 0.5)) - 1)
    return round(sorted_samples[max(index, 0)], 2)


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--base", default="http://127.0.0.1:8081", help="后端地址，压测要直连本机/内网")
    parser.add_argument("--path", required=True, help="请求路径，可含 {id}")
    parser.add_argument("--method", default="GET", choices=["GET", "POST"])
    parser.add_argument("--body", default=None, help="POST 请求体")
    parser.add_argument("--concurrency", type=int, default=10, help="总并发（会平均分到各进程的线程上）")
    parser.add_argument("--duration", type=int, default=60, help="持续秒数")
    parser.add_argument("--processes", type=int, default=0, help="进程数，默认按 CPU 核数（最多 8）")
    parser.add_argument("--id-min", type=int, default=1)
    parser.add_argument("--id-max", type=int, default=214)
    parser.add_argument("--ids-file", type=Path, default=None, help="每行一个真实 id")
    parser.add_argument("--tokens", type=Path, default=None, help="每行一个 token 的文件")
    parser.add_argument("--expect-success", action="store_true", help="额外检查 JSON 的 success 字段")
    parser.add_argument("--timeout", type=float, default=15.0)
    parser.add_argument("--out", type=Path, default=None, help="把结果写成 JSON")
    parser.add_argument("--label", default="", help="结果里标注场景名")
    args = parser.parse_args()

    tokens = []
    if args.tokens:
        tokens = [line.strip() for line in args.tokens.read_text(encoding="utf-8").splitlines() if line.strip()]
        if not tokens:
            raise SystemExit("token 文件是空的")
    ids = []
    if args.ids_file:
        ids = [line.strip() for line in args.ids_file.read_text(encoding="utf-8").splitlines() if line.strip()]
        if not ids:
            raise SystemExit("id 文件是空的")

    processes = args.processes or min(mp.cpu_count(), 8)
    processes = max(1, min(processes, args.concurrency))
    threads = max(1, args.concurrency // processes)
    args.processes = processes
    args.threads = threads
    host, port = parse_base(args.base)

    # 令牌按进程切分：每个进程只拿自己那一份，全局每个用户只被抢一次，
    # 否则多个进程各自从第 0 个开始，同一批用户会被重复抢。
    token_shards = [[] for _ in range(processes)]
    for index, token in enumerate(tokens):
        token_shards[index % processes].append(token)

    print(f"[开始] {args.label or args.path}")
    print(f"       进程 {processes} × 线程 {threads} = 并发 {processes * threads}"
          f"（请求数模式下实际并发由令牌数决定） 时长 {args.duration}s", flush=True)

    queue = mp.Queue()
    progress = mp.Value("l", 0)
    started = time.monotonic()
    pool = [mp.Process(target=process_main,
                       args=(i, host, port, args, token_shards[i], ids, queue, progress))
            for i in range(processes)]
    for proc in pool:
        proc.start()

    samples = []
    done = 0
    last_report = started
    last_count = 0
    while done < processes:
        # 每 2 秒报一次进度：请求数 + 瞬时 req/s + 已跑秒数。
        # 不报的话 60 秒里屏幕一片安静，很容易以为卡死了。
        try:
            _, merged = queue.get(timeout=2.0)
            samples.extend(merged)
            done += 1
            print(f"  进程 {done}/{processes} 完成，累计 {len(samples)} 次", flush=True)
            continue
        except Exception:
            pass
        now = time.monotonic()
        count = progress.value
        if now - last_report >= 2.0:
            elapsed = now - started
            instant = (count - last_count) / (now - last_report) if now > last_report else 0
            print(f"  [进行中 {elapsed:5.1f}s / {args.duration}s] "
                  f"已完成 {count} 次，瞬时 {instant:.0f} req/s", flush=True)
            last_report, last_count = now, count
        if now - started > args.duration + 60:
            print("  [警告] 超时未收到全部进程结果，按已收到的统计", flush=True)
            break
    for proc in pool:
        proc.join(timeout=10)
        if proc.is_alive():
            proc.terminate()
    spent = time.monotonic() - started

    samples.sort()
    total = len(samples)
    # 已完整接收响应的吞吐和延迟单列；传输异常同样属于请求失败。
    delivered = [s for s in samples if s[3]]
    ok = sum(1 for _, http_ok, biz_ok, _ in delivered if http_ok and biz_ok)
    biz_fail = sum(1 for _, http_ok, biz_ok, _ in delivered if http_ok and not biz_ok)
    http_err = sum(1 for _, http_ok, _, _ in delivered if not http_ok)
    transport_error = total - len(delivered)
    costs = sorted(cost for cost, _, _, sent in samples if sent)

    result = {
        "label": args.label or args.path,
        "path": args.path,
        "concurrency": processes * threads,
        "elapsedSeconds": round(spent, 2),
        "requests": total,
        "rps": round(len(delivered) / spent, 2) if spent > 0 else 0.0,
        "ok": ok,
        "businessFail": biz_fail,
        "httpError": http_err,
        "transportError": transport_error,
        "avgMs": round(statistics.fmean(costs), 2) if costs else 0.0,
        "p50Ms": percentile(costs, 50),
        "p90Ms": percentile(costs, 90),
        "p95Ms": percentile(costs, 95),
        "p99Ms": percentile(costs, 99),
        "maxMs": round(max(costs), 2) if costs else 0.0,
    }

    print("-" * 72)
    print(f"场景        : {result['label']}")
    print(f"并发 / 耗时 : {result['concurrency']} / {result['elapsedSeconds']}s")
    print(f"总请求      : {result['requests']}    吞吐 {result['rps']} req/s")
    print(f"成功/业务失败/HTTP错误/传输异常 : {result['ok']} / {result['businessFail']} / "
          f"{result['httpError']} / {result['transportError']}")
    print(f"延迟(ms)    : avg {result['avgMs']}  p50 {result['p50Ms']}  p90 {result['p90Ms']}  "
          f"p95 {result['p95Ms']}  p99 {result['p99Ms']}  max {result['maxMs']}")
    if transport_error:
        print(f"[注意] {transport_error} 次请求未获得完整响应，可能已经发出；吞吐和延迟只统计完整响应")
    print("-" * 72)

    if args.out:
        args.out.parent.mkdir(parents=True, exist_ok=True)
        args.out.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
        print(f"结果已写入 {args.out}")


if __name__ == "__main__":
    main()
