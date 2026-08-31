#!/usr/bin/env python3
"""解码 MeaPet 导出日志（meapet-*.log）里的 ■ Native Crash 段。

LogExporter 把 debuggerd 的 tombstone protobuf（system/core/debuggerd/proto/tombstone.proto）
按原始字节写在「进程: …」行之后。protobuf 是二进制：文本编辑器里必然显示为乱码，而且用
UTF-8 文本方式读文件会把它再次损坏（≥0x80 的字节被替换成 U+FFFD）。解码必须按字节切段、
按二进制解析，本脚本就是标准姿势：

    python tools/decode_tombstone.py <meapet-20260830-xxxxxx.log>

输出每条 native 崩溃的 pid/tid/uid、信号、崩溃线程的寄存器与前若干帧堆栈。

历史背景：LogExporter 曾用 BufferedWriter（字符流）写出 tombstone，所有 ≥0x80 的字节被
替换成 U+FFFD（ef bf bd），protobuf 报废（解出的 pid/tid 全是垃圾值）。本脚本同时检查
该损坏特征是否存在，充当回归防线。

Schema 与 AOSP system/core/debuggerd/proto/tombstone.proto 保持一致。
"""
import re
import sys
from pathlib import Path

# Windows 控制台默认 GBK，把 stdout 固定为 UTF-8，避免输出中文乱码
if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

CRASH_MARK = "── 崩溃于".encode("utf-8")
PROC_MARK = "进程: ".encode("utf-8")
FFFD = b"\xef\xbf\xbd"

ARCH = {0: "ARM32", 1: "ARM64", 2: "X86", 3: "X86_64", 4: "RISCV64", 5: "NONE"}


def read_varint(buf, i):
    v = 0
    s = 0
    while True:
        b = buf[i]
        i += 1
        v |= (b & 0x7F) << s
        if not (b & 0x80):
            return v, i
        s += 7


def parse_fields(buf):
    """极简 wire format 解析：{field_number: [values]}（wiretype 2 的值为子 buf）。"""
    fields = {}
    i = 0
    while i < len(buf):
        tag, i = read_varint(buf, i)
        f, w = tag >> 3, tag & 7
        if w == 0:
            v, i = read_varint(buf, i)
        elif w == 1:
            v, i = buf[i:i + 8], i + 8
        elif w == 2:
            l, i = read_varint(buf, i)
            v, i = buf[i:i + l], i + l
        elif w == 5:
            v, i = buf[i:i + 4], i + 4
        else:
            raise ValueError(f"wire type {w} @ offset {i}")
        fields.setdefault(f, []).append(v)
    return fields


def s(fields, n, default=""):
    vals = fields.get(n)
    return vals[0].decode("utf-8", "replace") if vals else default


def parse_thread(buf):
    f = parse_fields(buf)
    registers = []
    for r in f.get(3, []):
        rf = parse_fields(r)
        registers.append((s(rf, 1), rf.get(2, [0])[0]))
    backtrace = []
    for b in f.get(4, []):
        bf = parse_fields(b)
        backtrace.append({
            "pc": bf.get(2, [0])[0],
            "sp": bf.get(3, [0])[0],
            "func": s(bf, 4) or "?",
            "offset": bf.get(5, [None])[0],
            "file": s(bf, 6),
        })
    return {
        "name": s(f, 2),
        "registers": registers,
        "backtrace": backtrace,
        "tagged_addr_ctrl": f.get(6, [None])[0],
    }


def decode_tombstone(proto):
    f = parse_fields(proto)
    out = {
        "arch": ARCH.get(f.get(1, [None])[0], "未知"),
        "build_fingerprint": s(f, 2),
        "revision": s(f, 3),
        "timestamp": s(f, 4),
        "pid": f.get(5, [None])[0],
        "tid": f.get(6, [None])[0],
        "uid": f.get(7, [None])[0],
        "selinux_label": s(f, 8).rstrip("\0"),
        "command_line": [v.decode("utf-8", "replace") for v in f.get(9, [])],
        "signal": None,
        "abort_message": s(f, 14),
        "causes": [s(c, 1) for c in f.get(15, [])],
        "threads": {},
        "memory_mappings": len(f.get(17, [])),
        "log_buffers": len(f.get(18, [])),
        "open_fds": len(f.get(19, [])),
        "process_uptime": f.get(20, [None])[0],
        "page_size": f.get(22, [None])[0],
    }
    if 10 in f:
        sig = parse_fields(f[10][0])
        out["signal"] = {
            "number": sig.get(1, [None])[0],
            "name": s(sig, 2),
            "code": sig.get(3, [0])[0],  # proto3 默认值 0 会缺省（如 SI_USER 的 code=0）
            "code_name": s(sig, 4),
            "fault_address": sig.get(9, [None])[0],
        }
    for entry in f.get(16, []):
        e = parse_fields(entry)  # map<uint32, Thread>：key=1, value=2
        tid = e.get(1, [None])[0]
        if 2 in e:
            out["threads"][tid] = parse_thread(e[2][0])
    return out


def extract_records(data):
    """按「── 崩溃于」标记切出每条崩溃的 proto 字节段。"""
    marks = []
    pos = 0
    while True:
        i = data.find(CRASH_MARK, pos)
        if i < 0:
            break
        marks.append(i)
        pos = i + 4

    records = []
    for idx, m in enumerate(marks):
        proc = data.find(PROC_MARK, m)
        if proc < 0:
            continue
        start = data.find(b"\n", proc) + 1
        # proto 之后跟一个换行，然后要么是下一条记录的标记，要么是段尾的 ═ 分隔线
        end = marks[idx + 1] - 1 if idx + 1 < len(marks) else data.find(b"\n\n\xe2\x95\x90", start)
        if end < start:
            continue
        header = data[m:start].decode("utf-8", "replace")
        records.append((header, data[start:end]))
    return records


def main():
    if len(sys.argv) != 2:
        print(__doc__)
        sys.exit(2)
    data = Path(sys.argv[1]).read_bytes()

    fffd = data.count(FFFD)
    print(f"文件 {len(data)} 字节，U+FFFD（ef bf bd）损坏特征出现 {fffd} 次"
          + ("——protobuf 未被字符流破坏" if fffd == 0 else "——注意：存在疑似损坏！"))

    records = extract_records(data)
    if not records:
        print("未找到 native 崩溃记录（可能「无 native 崩溃记录」）")
        sys.exit(1)

    print(f"共 {len(records)} 条 native 崩溃记录\n")
    for n, (header, proto) in enumerate(records, 1):
        m = re.search(r"── 崩溃于 (.+?) ──", header)
        pm = re.search(r"进程: (\S+)", header)
        print(f"===== 记录 {n}：{m.group(1) if m else '?'} · {pm.group(1) if pm else '?'} =====")
        print(f"      proto {len(proto)} 字节，损坏特征 {'无' if FFFD not in proto else '有！'}")
        try:
            t = decode_tombstone(proto)
        except Exception as e:
            print(f"      protobuf 解析失败: {e}")
            continue

        print(f"      pid {t['pid']}  tid {t['tid']}  uid {t['uid']}  "
              f"arch {t['arch']}  uptime {t['process_uptime']}s")
        print(f"      fingerprint: {t['build_fingerprint']}")
        print(f"      cmdline: {' '.join(t['command_line'])}")
        sig = t["signal"]
        if sig:
            extra = f"  fault_addr 0x{sig['fault_address']:x}" if sig["fault_address"] else ""
            print(f"      信号: {sig['number']} ({sig['name']})  code {sig['code']} "
                  f"({sig['code_name']}){extra}")
        if t["abort_message"]:
            print(f"      abort: {t['abort_message']!r}")
        for c in t["causes"]:
            print(f"      cause: {c}")
        print(f"      线程 {len(t['threads'])} 个 · 内存映射 {t['memory_mappings']} 段 · "
              f"fd {t['open_fds']} 个 · log 缓冲 {t['log_buffers']} 个")

        crashed = t["threads"].get(t["tid"])
        if crashed:
            print(f"      ── 崩溃线程 {t['tid']}（{crashed['name'] or '?'}）──")
            for name, v in crashed["registers"][:10]:
                print(f"        {name:>4} = 0x{v:016x}")
            for fr in crashed["backtrace"][:10]:
                off = f"+0x{fr['offset']:x}" if fr["offset"] is not None else ""
                print(f"        # pc 0x{fr['pc']:x}  {fr['func']}{off}  {fr['file']}")
        else:
            print("      （崩溃线程不在 threads map 中）")
        print()


if __name__ == "__main__":
    main()