#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""本地补丁校验：同步上游后确认我们的修复没被覆盖。

用法:
    python3 tools/check_local_patches.py          # 人读输出，全绿 exit 0，有缺失 exit 1
    python3 tools/check_local_patches.py --json    # 机器可读

判定方式: 对清单里每条 {file, markers:[{text,min}]}，统计该文件（git show HEAD:file，
缺失则退回工作区文件）中 text 出现次数，少于 min 即判缺失。
"""
import json
import os
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
MANIFEST = os.path.join(HERE, "local_patches.json")


def read_file(path, ref="HEAD"):
    try:
        out = subprocess.run(["git", "show", "{}:{}".format(ref, path)],
                             capture_output=True, text=True, check=True)
        return out.stdout
    except Exception:
        pass
    try:
        with open(path, encoding="utf-8", errors="replace") as fh:
            return fh.read()
    except Exception:
        return None


def main():
    as_json = "--json" in sys.argv
    with open(MANIFEST, encoding="utf-8") as fh:
        entries = json.load(fh)["entries"]

    failures = []
    total = 0
    for entry in entries:
        content = read_file(entry["file"])
        for marker in entry.get("markers", []):
            total += 1
            text = marker["text"]
            need = int(marker.get("min", 1))
            if content is None:
                failures.append({"file": entry["file"], "version": entry.get("version", ""),
                                 "desc": entry.get("desc", ""), "marker": text, "need": need,
                                 "found": 0, "reason": "文件不存在"})
                continue
            found = content.count(text)
            if found < need:
                failures.append({"file": entry["file"], "version": entry.get("version", ""),
                                 "desc": entry.get("desc", ""), "marker": text, "need": need,
                                 "found": found, "reason": "标记数量不足"})

    if as_json:
        print(json.dumps({"ok": not failures, "checked": total, "failures": failures},
                         ensure_ascii=False, indent=2))
    else:
        for item in failures:
            print("\u274c 丢失 [{}{}] {} :: `{}` 期望\u2265{} 实际{} ({})".format(
                item["version"], "" if not item["desc"] else " " + item["desc"],
                item["file"], item["marker"], item["need"], item["found"], item["reason"]))
        if failures:
            print("\n\u274c {}/{} 个标记缺失 —— 上游同步覆盖了本地修复，禁止发版。".format(len(failures), total))
            print("恢复方式: git checkout local-base-vXXX -- <文件>  （再跑一遍本脚本确认全绿）")
        else:
            print("\u2705 本地补丁全在: {}/{} 个标记通过".format(total, total))
    return 0 if not failures else 1


if __name__ == "__main__":
    sys.exit(main())
