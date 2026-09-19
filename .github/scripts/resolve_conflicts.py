#!/usr/bin/env python3
"""已知冲突自动解析器（供 auto-release-*.yml 的 merge 步骤调用）。

用法：
    python3 .github/scripts/resolve_conflicts.py <冲突文件1> [<冲突文件2> ...]

设计原则（只做"加法"，绝不猜）：
  * 只有**规则表里写明**的文件才自动解；
  * 规则之外的任何冲突 → 整体失败退出（退出码 1），不瞎猜、也不会 push；
  * 解完做校验（冲突标记清零 / XML 可解析 / gradle 关键行唯一且变量式定义还在），校验不过 → 失败退出。

规则（对应历史真实冲突）：
  app/src/main/AndroidManifest.xml —— 双方都是**插入元素**（我方 LabVpnActivity / SystemVpnService /
      UbuntuDocumentsProvider，上游方 PlaybackRecoveryActivity 等）→ 保留双方（union），元素顺序不影响语义。
  app/build.gradle —— 版本号两行**必须用我方的变量式**（versionCode appVersionCode /
      versionName appVersionName，由 CI 用 WEBHTV_VERSION_CODE/NAME 注入）；上游每次发版都改自己的字面量
      （560→561…）→ 丢弃上游的 versionCode/versionName 行，其余行（如新增 testInstrumentationRunner）保留。
"""

import os
import re
import sys
import xml.etree.ElementTree as ET

MARK_START = re.compile(r'^<{7}(?:\s|$)')
MARK_MID = re.compile(r'^={7}(?:\s|$)')
MARK_END = re.compile(r'^>{7}(?:\s|$)')
VERSION_LINE = re.compile(r'^\s*version(?:Code|Name)\b')
VERSION_CODE = re.compile(r'^\s*versionCode\b')
VERSION_NAME = re.compile(r'^\s*versionName\b')


def parse(text):
    """拆成 [('line', s) | ('hunk', ours, theirs)]。"""
    lines = text.split('\n')
    out, i = [], 0
    while i < len(lines):
        if MARK_START.match(lines[i]):
            i += 1
            ours = []
            while i < len(lines) and not MARK_MID.match(lines[i]):
                ours.append(lines[i])
                i += 1
            i += 1  # 跳过 =======
            theirs = []
            while i < len(lines) and not MARK_END.match(lines[i]):
                theirs.append(lines[i])
                i += 1
            i += 1  # 跳过 >>>>>>>
            out.append(('hunk', ours, theirs))
        else:
            out.append(('line', lines[i]))
            i += 1
    return out


def merge_both(ours, theirs):
    """union：上游行在前，我方独有行补在后。"""
    return theirs + [l for l in ours if l not in theirs]


def merge_gradle(ours, theirs):
    """丢弃上游的 versionCode/versionName 行，其余 union。"""
    theirs_keep = [l for l in theirs if not VERSION_LINE.match(l)]
    return theirs_keep + [l for l in ours if l not in theirs_keep]


RULES = [
    ('AndroidManifest.xml', merge_both),
    ('build.gradle', merge_gradle),
]


def rule_for(path):
    for suffix, fn in RULES:
        if path.endswith(suffix):
            return fn
    return None


def validate(path, text):
    problems = []
    for n, line in enumerate(text.split('\n'), 1):
        if MARK_START.match(line) or MARK_MID.match(line) or MARK_END.match(line):
            problems.append('第 %d 行仍有冲突标记' % n)
    if path.endswith('AndroidManifest.xml'):
        try:
            ET.fromstring(text)
        except Exception as e:
            problems.append('XML 解析失败: %s' % e)
    if path.endswith('build.gradle'):
        lines = text.split('\n')
        nvc = len([l for l in lines if VERSION_CODE.match(l)])
        nvn = len([l for l in lines if VERSION_NAME.match(l)])
        if nvc != 1:
            problems.append('versionCode 行数 = %d（应为 1）' % nvc)
        if nvn != 1:
            problems.append('versionName 行数 = %d（应为 1）' % nvn)
        if 'appVersionCode' not in text or 'appVersionName' not in text:
            problems.append('变量式版本号定义丢失（appVersionCode/appVersionName）')
    return problems


def resolve_file(path):
    """只解析+校验，不写盘（两阶段：全部通过才统一落盘）。"""
    fn = rule_for(path)
    if fn is None:
        return None, None, ['没有为该文件定义自动解冲突规则（拒绝猜测）']
    with open(path, encoding='utf-8') as f:
        text = f.read()
    parts = parse(text)
    hunks = sum(1 for p in parts if p[0] == 'hunk')
    if hunks == 0:
        return 0, None, []
    buf = ['\n'.join(fn(p[1], p[2])) if p[0] == 'hunk' else p[1] for p in parts]
    new_text = '\n'.join(buf)
    problems = validate(path, new_text)
    if problems:
        return None, None, problems
    return hunks, new_text, []


def main(argv):
    if len(argv) < 2:
        print('用法: resolve_conflicts.py <冲突文件...>')
        return 1
    failures, results, total = [], [], 0
    for path in argv[1:]:
        if not os.path.isfile(path):
            failures.append((path, ['文件不存在']))
            continue
        hunks, new_text, problems = resolve_file(path)
        if problems:
            failures.append((path, problems))
        else:
            total += hunks or 0
            if new_text is not None:
                results.append((path, new_text))
            print('  %s: %s' % (path, '解掉 %d 个冲突块 ✅' % hunks if hunks else '无冲突块，跳过'))
    print()
    if not failures:
        for path, new_text in results:
            with open(path, 'w', encoding='utf-8') as f:
                f.write(new_text)
    if failures:
        print('❌ 自动解冲突失败，未写回任何未经校验的内容：')
        for path, probs in failures:
            for pr in probs:
                print('   %s -> %s' % (path, pr))
        return 1
    print('✅ 全部冲突已按已知规则解掉并通过校验（共 %d 个冲突块）' % total)
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv))
