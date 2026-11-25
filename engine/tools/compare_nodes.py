"""CPU 実行と NPU 実行の node-stats 出力を突き合わせ、値が最初に壊れたノード（発生源）を探す。

発生源 = 自分の出力はずれているが、入力元（src0/src1）はどれもずれていないノード。
使い方: python compare_nodes.py cpu.tsv htp.tsv [閾値(相対誤差, 既定 0.02)]
"""
import math
import sys
from collections import Counter

COLS = ["idx", "name", "op", "type", "buf", "shape", "sum", "sabs", "mabs", "l2", "nan", "inf", "n", "src0", "src1"]


def load(path):
    rows = []
    with open(path, encoding="utf-8", errors="replace") as f:
        for line in f:
            p = line.rstrip("\n").split("\t")
            if len(p) < len(COLS):
                continue
            r = dict(zip(COLS, p))
            for k in ("sum", "sabs", "mabs", "l2"):
                r[k] = float(r[k])
            for k in ("idx", "nan", "inf", "n"):
                r[k] = int(r[k])
            rows.append(r)
    return rows


def rel(a, b):
    d = abs(a - b)
    s = max(abs(a), abs(b))
    if s < 1e-12:
        return 0.0
    return d / s


def main():
    a, b = load(sys.argv[1]), load(sys.argv[2])
    thr = float(sys.argv[3]) if len(sys.argv) > 3 else 0.02
    n = min(len(a), len(b))
    print(f"nodes: cpu={len(a)} npu={len(b)} compared={n} threshold={thr}")

    bad = {}          # name -> True/False（最後に出てきた同名ノードの判定）
    sources = []
    mism_names = 0
    worst = []
    for i in range(n):
        x, y = a[i], b[i]
        if x["name"] != y["name"] or x["op"] != y["op"]:
            mism_names += 1
            if mism_names <= 3:
                print(f"  [warn] node {i} differs: {x['name']}/{x['op']} vs {y['name']}/{y['op']}")
            continue
        e_l2 = rel(x["l2"], y["l2"])
        e_sabs = rel(x["sabs"], y["sabs"])
        e_max = rel(x["mabs"], y["mabs"])
        naninf = (x["nan"] + x["inf"]) != (y["nan"] + y["inf"])
        err = max(e_l2, e_sabs, e_max)
        is_bad = err > thr or naninf
        srcs = [s for s in (x["src0"], x["src1"]) if s != "-"]
        src_bad = any(bad.get(s, False) for s in srcs)
        if is_bad and not src_bad:
            sources.append((i, x, y, e_l2, e_sabs, e_max, naninf))
        bad[x["name"]] = is_bad
        worst.append((err, i))

    print(f"name/op mismatches: {mism_names}")
    print(f"source nodes (output off, inputs fine): {len(sources)}")
    print(f"by op: {dict(Counter(s[1]['op'] for s in sources))}")
    print()
    for i, x, y, e_l2, e_sabs, e_max, naninf in sources[:25]:
        print(f"#{i} {x['name']} op={x['op']} type={x['type']} shape={x['shape']} npu_buf={y['buf']}")
        print(f"    src0={x['src0']} src1={x['src1']}")
        print(f"    rel L2={e_l2:.3g} sum|x|={e_sabs:.3g} max|x|={e_max:.3g} nan/inf cpu={x['nan']}/{x['inf']} npu={y['nan']}/{y['inf']}")
        print(f"    cpu: L2={x['l2']:.6g} max={x['mabs']:.6g}   npu: L2={y['l2']:.6g} max={y['mabs']:.6g}")


if __name__ == "__main__":
    main()
