#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""已知冲突自动解析器（供 auto-release-*.yml 的 merge 步骤调用）—— v3

用法：
    python3 .github/scripts/resolve_conflicts.py <冲突文件1> [<冲突文件2> ...]

原理（真·三方合并，不是拿冲突标记瞎拼）：
  对每个冲突文件，从 git index 取三个版本：
      :1: 共同祖先(base)   :2: 我方(ours)   :3: 上游(theirs)
  用 difflib 分别算 base→ours / base→theirs 的行级改动块，
  把两边的改动块按 base 区间聚类（重叠/同点插入的算一簇），然后逐簇定夺：
      * 只有一边改了            → 直接用改的那边
      * 双方都在**同一处纯插入** → 两块都留（上游在前，我方在后）
      * 双方真冲突（改了同一段） → 按该文件的策略（见下），默认保留我方
  这样"双方都动过的同一段代码"不会被拼成垃圾，且上游没冲突的改动照常流入。

策略表（按路径命中，未命中的走默认策略）：
  app/build.gradle            gradle：版本号两行必须用我方的变量式
                              （versionCode appVersionCode / versionName appVersionName，
                              上游每次发版都改自己的字面量 560→561，那两行丢弃），
                              其余行：我方的 + 上游新增的（我方结构不动）
  gradle.properties           keyed：k=v 按 key 合并，同 key 用我方值（保住 CI 的 jvmargs 等）
  app/src/main/AndroidManifest.xml   union：双方都是插入元素（我方 LabVpnActivity 等，
                              上游 PlaybackRecoveryActivity 等），两块都留
  **/res/values*/**.xml       union + 同名资源条目去重（同 tag+name 保留我方那份）
  *.md / *.txt / *.pro        union
  其它                        union（纯插入都留、真冲突留我方）；RESOLVE_UNKNOWN=fail 时改为失败退出

校验（任一文件不过 → 退出码 1，一个字节都不写回、绝不 push）：
  冲突标记残留 / XML 不可解析 / build.gradle 版本行数不为 1 或变量式定义丢失
"""

import difflib
import os
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

MARK_RE = re.compile(r'^(<{7}|={7}|>{7}|\|{7})(\s|$)')
VERSION_LINE = re.compile(r'^\s*version(?:Code|Name)\b')
VERSION_CODE = re.compile(r'^\s*versionCode\b')
VERSION_NAME = re.compile(r'^\s*versionName\b')
NAMED_TAG = re.compile(r'^(\s{0,4})<([A-Za-z][\w.:-]*)\b[^>]*\bname="([^"]+)"')

# ---------------------------------------------------------------- git 取三版

def git_show(path, stage):
    """stage: 1=base 2=ours 3=theirs；该 stage 不存在返回 None。"""
    r = subprocess.run(['git', 'show', ':%d:%s' % (stage, path)], capture_output=True)
    if r.returncode != 0:
        return None
    return r.stdout.decode('utf-8', 'surrogateescape')


def load_sides(path):
    base = git_show(path, 1) or ''
    ours = git_show(path, 2)
    theirs = git_show(path, 3)
    return base, ours, theirs


# ---------------------------------------------------------------- 行级改动块

def change_events(base_lines, side_lines):
    """[(base_start, base_end, side_lines)]，按 base_start 升序。"""
    ev = []
    sm = difflib.SequenceMatcher(None, base_lines, side_lines, autojunk=False)
    for tag, i1, i2, j1, j2 in sm.get_opcodes():
        if tag != 'equal':
            ev.append((i1, i2, side_lines[j1:j2]))
    return ev


def cluster(our_ev, their_ev):
    """把两边改动块按 base 区间聚类。返回 [{'gs','ge','o':[],'t':[]}]。"""
    items = [('o', e) for e in our_ev] + [('t', e) for e in their_ev]
    items.sort(key=lambda kv: (kv[1][0], kv[1][1]))
    out = []
    for kind, e in items:
        c = out[-1] if out else None
        joins = False
        if c is not None:
            if e[0] < c['ge']:
                joins = True                                  # 与上一簇 base 区间重叠
            elif e[0] == e[1] and c['gs'] == c['ge'] == e[0]:
                joins = True                                  # 同一位置的纯插入 → 同簇
        if joins:
            c['o' if kind == 'o' else 't'].append(e)
            c['gs'] = min(c['gs'], e[0])
            c['ge'] = max(c['ge'], e[1])
        else:
            out.append({'gs': e[0], 'ge': e[1], 'o': [e] if kind == 'o' else [],
                        't': [e] if kind == 't' else []})
    return out


def side_chunk(events, base_slice, gs):
    """把该侧在簇内的改动块套到 base 片段上 → 该侧在该区间的实际内容。"""
    out, pos = [], gs
    for i1, i2, lines in sorted(events):
        a, b = max(i1, gs), min(i2, gs + len(base_slice))
        if a > pos:
            out += base_slice[pos - gs:a - gs]
        out += lines
        pos = max(pos, b)
    if pos < gs + len(base_slice):
        out += base_slice[pos - gs:]
    return out


# ---------------------------------------------------------------- 策略

def kv_key(line):
    m = re.match(r'^\s*([^#=\s:]+)\s*[=:]\s*(.*)$', line)
    return m.group(1).strip() if m else None


def pol_auto(base_c, our_c, their_c, warn):
    if not base_c and our_c and their_c:
        return list(their_c) + list(our_c)          # 同一处纯插入 → 两块都留（上游在前）
    warn.append('非纯插入冲突 → 保留我方')
    return list(our_c)


def pol_gradle(base_c, our_c, their_c, warn):
    touched = any(VERSION_LINE.match(l) for l in our_c + their_c + base_c)
    if not touched:
        return pol_auto(base_c, our_c, their_c, warn)
    # 版本号两行以我方的变量式为准：丢上游的两行、丢 base 的旧值，其余按默认策略合
    b = [l for l in base_c if not VERSION_LINE.match(l)]
    o = [l for l in our_c if not VERSION_LINE.match(l)]
    t = [l for l in their_c if not VERSION_LINE.match(l)]
    rest = pol_auto(b, o, t, warn)
    vlines = [l for l in our_c if VERSION_LINE.match(l)]
    return vlines + rest                            # 我方变量式版本行放簇首（原位置紧随其后）


def pol_keyed(base_c, our_c, their_c, warn):
    our_keys = {kv_key(l) for l in our_c if kv_key(l)}
    out = list(our_c)
    for l in their_c:
        k = kv_key(l)
        if k and k in our_keys:                     # 同 key → 我方值
            continue
        if l in out:
            continue
        out.append(l)                               # 上游新增的 key / 注释行留下
    return out


POLICIES = {
    'auto': pol_auto,
    'gradle': pol_gradle,
    'keyed': pol_keyed,
}

RESOURCE_XML = re.compile(r'(^|/)res/values(?:-[\w-]+)?/[\w.-]+\.xml$')
RULES = [
    (re.compile(r'(^|/)app/build\.gradle$'), 'gradle'),
    (re.compile(r'(^|/)gradle\.properties$'), 'keyed'),
    (re.compile(r'(^|/)AndroidManifest\.xml$'), 'auto'),
    (RESOURCE_XML, 'auto'),
    (re.compile(r'\.(md|txt|pro)$'), 'auto'),
]


def policy_for(path, strict):
    p = path.replace('\\', '/')
    for pat, name in RULES:
        if pat.search(p):
            return name, True
    if strict:
        return None, False
    return 'auto', False


# ---------------------------------------------------------------- 同名条目去重（values XML）

def dedupe_named_entries(text):
    """同 (tag, name) 重复时保留**最后一个**（union 后我方在后 → 即我方那份）。"""
    lines = text.split('\n')
    groups, i = {}, 0
    while i < len(lines):
        m = NAMED_TAG.match(lines[i])
        if not m:
            i += 1
            continue
        tag = m.group(2)
        start = i
        if not re.search(r'/>|</%s>' % re.escape(tag), lines[i]):
            close = re.compile(r'^\s*</%s>\s*$' % re.escape(tag))
            j = i + 1
            while j < len(lines) and not close.match(lines[j]):
                j += 1
            i = j if j < len(lines) else len(lines) - 1
        groups.setdefault((tag, m.group(3)), []).append((start, i))
        i += 1
    drop = set()
    for spans in groups.values():
        if len(spans) > 1:
            for s, e in spans[:-1]:
                drop.update(range(s, e + 1))
    if not drop:
        return text
    return '\n'.join(l for n, l in enumerate(lines) if n not in drop)


# ---------------------------------------------------------------- 校验

def _xml_ok(text):
    try:
        ET.fromstring(text)
        return True
    except Exception:
        return False


def validate(path, text):
    problems = []
    for n, line in enumerate(text.split('\n'), 1):
        if MARK_RE.match(line):
            problems.append('第 %d 行仍有冲突标记' % n)
    if path.endswith('.xml') and not _xml_ok(text):
        problems.append('XML 解析失败')
    if path.endswith('build.gradle'):
        nvc = len([l for l in text.split('\n') if VERSION_CODE.match(l)])
        nvn = len([l for l in text.split('\n') if VERSION_NAME.match(l)])
        if nvc != 1:
            problems.append('versionCode 行数 = %d（应为 1）' % nvc)
        if nvn != 1:
            problems.append('versionName 行数 = %d（应为 1）' % nvn)
        if 'appVersionCode' not in text or 'appVersionName' not in text:
            problems.append('变量式版本号定义丢失（appVersionCode/appVersionName）')
    return problems


# ---------------------------------------------------------------- 主流程

def resolve_file(path, strict):
    """返回 (action, payload, problems, how, hunks)；action ∈ write/delete/None。"""
    pol_name, known = policy_for(path, strict)
    if pol_name is None:
        return None, None, ['无专用规则且处于严格模式（RESOLVE_UNKNOWN=fail）'], None, 0
    base, ours, theirs = load_sides(path)
    if ours is None and theirs is None:
        return None, None, ['git index 里取不到 :2:/:3: 版本（不在合并状态？）'], None, 0
    if ours is None:                                 # 我方删除、上游改动 → 我方删除生效
        return 'delete', None, [], '%s(我方删除)' % pol_name, 0
    if theirs is None:                               # 上游删除、我方改动 → 保留我方
        return 'write', ours, [], '%s(保留我方)' % pol_name, 0

    bl = base.split('\n')
    ol = ours.split('\n')
    tl = theirs.split('\n')
    warn, out, pos, hunks = [], [], 0, 0
    for c in cluster(change_events(bl, ol), change_events(bl, tl)):
        if c['gs'] > pos:
            out += bl[pos:c['gs']]
        base_c = bl[c['gs']:c['ge']]
        our_c = side_chunk(c['o'], base_c, c['gs'])
        their_c = side_chunk(c['t'], base_c, c['gs'])
        if our_c == base_c:
            out += their_c
        elif their_c == base_c:
            out += our_c
        else:
            w = []
            out += POLICIES[pol_name](base_c, our_c, their_c, w)
            hunks += 1
            if w:
                warn.append('第 %d~%d 行附近的冲突按我方保留（上游改动被丢弃）' % (c['gs'] + 1, c['ge']))
        pos = max(pos, c['ge'])
    if pos < len(bl):
        out += bl[pos:]
    new_text = '\n'.join(out)
    if pol_name == 'auto' and RESOURCE_XML.search(path.replace('\\', '/')):
        new_text = dedupe_named_entries(new_text)
    problems = validate(path, new_text)
    if problems:
        return None, None, problems, None, 0
    return 'write', new_text, [], pol_name, hunks


def main(argv):
    if len(argv) < 2:
        print('用法: resolve_conflicts.py <冲突文件...>')
        return 1
    strict = os.environ.get('RESOLVE_UNKNOWN', 'ours').lower() in ('fail', '1', 'true', 'strict')
    print('模式: RESOLVE_UNKNOWN=%s' % ('strict(fail)' if strict else 'ours'))
    failures, plans, total = [], [], 0
    for path in argv[1:]:
        if not os.path.isfile(path) and not git_show(path, 2) and not git_show(path, 3):
            print('  %s: 已不在工作区且 index 无版本 → 跳过' % path)
            continue
        action, payload, problems, how, hunks = resolve_file(path, strict)
        if problems:
            failures.append((path, problems))
            continue
        total += hunks or 0
        plans.append((path, action, payload, how))
        print('  %s: %s [%s]' % (path, ('冲突 %d 处' % hunks) if hunks else '无真冲突', how or '-'))
    print()
    if failures:
        print('❌ 自动解冲突失败，未写回任何内容：')
        for path, probs in failures:
            for pr in probs:
                print('   %s -> %s' % (path, pr))
        return 1
    for path, action, payload, _how in plans:
        if action == 'delete':
            if os.path.isfile(path):
                os.remove(path)
            print('  rm %s（我方删除生效）' % path)
        else:
            with open(path, 'w', encoding='utf-8', newline='') as f:
                f.write(payload)
    print('✅ 全部冲突已解并通过校验（%d 处真冲突按策略定夺，%d 个文件落盘）' % (total, len(plans)))
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv))
