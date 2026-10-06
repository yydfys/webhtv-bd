#!/usr/bin/env python3
# -*- coding: utf-8 -*-
# [内置副本] 本文件由 webhtv 电视版随包释放（device=tv），源头 = NAS local-sites/ysp_live_watcher.py
"""
ysp_live_watcher.py —— 上游「ysp-live.py」更新监控下载器（手机 WebHTV 实验室版）

● 干什么
  定时检测上游脚本 ysp-live*.py（作者改文件名也能自动跟上）是否有更新，
  有更新就下载到本地目录（默认＝本脚本所在目录），原子写入 + 旧版备份。
  默认「自动发现」优先双方案：①先扫 TG 频道（t.me/s/<ch>）里贴出的下载地址，
  能发现就直接用；②频道发现不了 → 按版本号网格探测（vM.m / vM.m.p）取最新；
  拿到版本后优先下整包 zip（内含 .py + 伴生引擎 ysp-engine.js），确保文件完整。
  也可用 --url / YSP_URL 锁定一个固定地址。
  只走我们自己的 Cloudflare 代理（永久忽略实验室注入的环境代理；不做直连兜底）。

● 特点
  · 纯 Python 标准库（urllib / ssl），无 requests 等第三方依赖 → 手机实验室可直接跑
  · 代理地址、下载目录、轮询间隔全部可配置（见下方 CONFIG / 环境变量 / 命令行）
  · 校验内容是合法 Python 源码，绝不把 403 拦截页当成脚本写进去
  · 单实例锁，避免重复启动跑出多个进程
  · 有更新可执行自定义通知命令（可选）
  · 国内/代理均不可达时不会破坏本地已有文件（只在拿到合法内容后才写）

● 配置优先级：命令行参数 > 环境变量 > 文件顶部 CONFIG

● 用法
  python3 ysp_live_watcher.py                 # 常驻后台，按 interval 轮询
  python3 ysp_live_watcher.py --once          # 只检测一次
  python3 ysp_live_watcher.py --status        # 查看本地版本 / 上次检测结果
  python3 ysp_live_watcher.py --force         # 强制重新下载
  python3 ysp_live_watcher.py --dry-run       # 只检测不写文件

● 手机实验室里后台跑
  nohup python3 ysp_live_watcher.py >/dev/null 2>&1 &
  或直接把本脚本配成实验室的一条常驻命令。
"""

import argparse
import concurrent.futures as futures
import errno
import hashlib
import json
import os
import re
import shutil
import signal
import ssl
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime

HERE = os.path.dirname(os.path.abspath(__file__))

# ==================== 配置（可直接改这里） ====================
CONFIG = {
    # 上游脚本地址：留空("") = 自动探测最新版 ysp-live*.py（推荐）
    #   也可用 --url / YSP_URL 锁定一个固定地址
    "url": os.environ.get("YSP_URL", ""),
    # 上游目录（发布方目录，版本号会拼到它后面）
    "base_dir": os.environ.get("YSP_BASE_DIR",
                               "https://garysclub.sharewithyou.dpdns.org/others/"),
    # 上游文件名模板：{v} = 版本号（8.0 → ysp-live-v8.0.py）
    "name_tpl": os.environ.get("YSP_NAME_TPL", "ysp-live-v{v}.py"),
    # 不带版本号的文件名（一并探测）
    "plain_name": os.environ.get("YSP_PLAIN_NAME", "ysp-live.py"),
    # 版本号网格：主版本范围 [min, max]
    "ver_major": (int(os.environ.get("YSP_VER_MIN", "6") or "6"),
                  int(os.environ.get("YSP_VER_MAX", "15") or "15")),
    # 网格探测并发数
    "probe_workers": int(os.environ.get("YSP_PROBE_WORKERS", "6") or "6"),
    # 自动发现失败时的兜底地址（按顺序尝试；再不行退回 meta 里上次成功的地址）
    "fallback_urls": [
        "https://garysclub.sharewithyou.dpdns.org/others/ysp-live-v8.1.py",
        "https://garysclub.sharewithyou.dpdns.org/others/ysp-live.py",
    ],
    # 代理前缀：真实地址会被拼到 ?url= 后面。必须填写（唯一通道，不走直连）
    "proxy": os.environ.get("YSP_PROXY", "https://proxy.yydf2.de5.net/?url="),
    # 下载保存目录：留空("") = 本脚本所在目录
    #   **建议把本脚本与 ysp-live.py 放在同一个目录，即可零配置**
    "save_dir": os.environ.get("YSP_SAVE_DIR", ""),
    # 保存文件名
    "filename": os.environ.get("YSP_FILENAME", "ysp-live.py"),
    # 轮询间隔（秒），默认 1800 = 30 分钟
    "interval": int(os.environ.get("YSP_INTERVAL", "1800") or "1800"),
    # 单次请求超时（秒）
    "timeout": int(os.environ.get("YSP_TIMEOUT", "25") or "25"),
    # 日志文件：留空("") = 保存目录下的 ysp_live_watcher.log
    "log_file": os.environ.get("YSP_LOG", ""),
    # 有新版本时执行的通知命令（可选，留空=不通知）
    #   支持占位符 {version} {path}，例如：
    #   YSP_NOTIFY_CMD='curl -s "http://127.0.0.1:8888/notify?t=ysp-live{version}"'
    "notify_cmd": os.environ.get("YSP_NOTIFY_CMD", ""),
    # 证书校验失败时是否降级为不校验证书（1=允许 0=不允许）
    "insecure_fallback": int(os.environ.get("YSP_INSECURE_TLS", "1") or "1"),
    # 请求 UA（TVBox 生态标配）
    "ua": os.environ.get("YSP_UA", "okhttp/4.12.0"),
    # ── 双方案①：TG 频道发现（能发现就直接用，发现不了才退网格探测） ──
    #   频道预览页 https://t.me/s/<ch> 里贴出的下载地址；多个用逗号分隔
    "channels": [c.strip() for c in (os.environ.get("YSP_CHANNELS", "garysclubchannel,rocCHL") or "").split(",") if c.strip()],
    "tg_tpl": os.environ.get("YSP_TG_TPL", "https://t.me/s/{ch}"),
    #   只认该域名下的 ysp-live*.py / *.zip（防止抓到别人贴的无关文件）
    "link_host": os.environ.get("YSP_LINK_HOST", "garysclub.sharewithyou.dpdns.org"),
    # ── 双方案②：整包 zip 优先（内含 .py + 伴生引擎 ysp-engine.js + README） ──
    "zip_tpl": os.environ.get("YSP_ZIP_TPL", "ysp-live-v{v}.zip"),
    #   伴生 JS 引擎文件名（缺它 → 上游第 4 层 Web WASM 兜底失效，1080P 受限）
    "engine_name": os.environ.get("YSP_ENGINE_NAME", "ysp-engine.js"),
    # ※ 永久忽略 http_proxy / https_proxy / all_proxy 环境变量：
    #   手机实验室会注入本地代理变量，把「连接 Cloudflare 代理」这一步也劫持走（返回 400），
    #   因此本脚本一律只走上面配置的 Cloudflare 代理，不提供任何开关能改回环境代理。
}
# ============================================================

_LOG_PATH = None
_LOCK_PATH = None


def now_str():
    return datetime.now().strftime("%Y-%m-%d %H:%M:%S")


def log(msg):
    line = "[%s] %s" % (now_str(), msg)
    print(line, flush=True)
    try:
        if _LOG_PATH:
            with open(_LOG_PATH, "a", encoding="utf-8") as f:
                f.write(line + "\n")
    except Exception:
        pass


def resolve_paths():
    """补全 save_dir / log_file 默认值，返回 (save_dir, target, meta_path, log_path)"""
    save_dir = os.path.expanduser(CONFIG["save_dir"] or HERE)
    try:
        if not os.path.isdir(save_dir):
            os.makedirs(save_dir, exist_ok=True)
    except Exception as e:
        log("⚠️ 无法创建保存目录 %s（%s），回退到脚本目录" % (save_dir, e))
        save_dir = HERE
    target = os.path.join(save_dir, CONFIG["filename"])
    meta_path = target + ".meta.json"
    log_path = os.path.expanduser(CONFIG["log_file"] or os.path.join(save_dir, "ysp_live_watcher.log"))
    return save_dir, target, meta_path, log_path


# ---------------- meta / 工具 ----------------

def load_meta(meta_path):
    try:
        with open(meta_path, "r", encoding="utf-8") as f:
            return json.load(f)
    except Exception:
        return {}


def save_meta(meta_path, **kw):
    m = load_meta(meta_path)
    m.update(kw)
    try:
        tmp = meta_path + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(m, f, ensure_ascii=False, indent=2)
        os.replace(tmp, meta_path)
    except Exception as e:
        log("  写入 meta 失败: %s" % e)


def extract_version(text):
    for pat in (r'__version__\s*=\s*["\']([^"\']+)["\']',
                r'\bVERSION\s*=\s*["\']([^"\']+)["\']',
                r'\bversion\s*=\s*["\']([0-9]+\.[0-9]+(?:\.[0-9]+)?)["\']'):
        m = re.search(pat, text)
        if m:
            return m.group(1)
    return "?"


BLOCK_HINTS = ("just a moment", "attention required", "cf-error", "access denied",
               "<html", "<!doctype", "403 forbidden", "cloudflare")


def looks_like_python(data):
    """判断字节内容是否像一份 Python 源码；返回 (bool, 原因)"""
    if not data or len(data) < 500:
        return False, "内容过短"
    head = data[:6000].decode("utf-8", "ignore").lower()
    for h in BLOCK_HINTS:
        if h in head:
            return False, "命中拦截特征: %s" % h
    text = data.decode("utf-8", "ignore")
    signals = sum(1 for k in ("import ", "def ", "class ", "print(", "if __name__") if k in text)
    if signals < 2:
        return False, "不像 Python 源码（关键特征不足）"
    return True, "ok"


# ---------------- 网络 ----------------

def _open(url, timeout, insecure):
    ctx = ssl.create_default_context()
    if insecure:
        ctx.check_hostname = False
        ctx.verify_mode = ssl.CERT_NONE
    req = urllib.request.Request(url, headers={
        "User-Agent": CONFIG["ua"],
        "Accept": "*/*",
        "Accept-Language": "zh-CN,zh;q=0.9",
        "Connection": "close",
    })
    # 永久忽略环境里的 http_proxy/https_proxy/all_proxy：
    # 手机实验室会注入本地代理变量，把「连接 Cloudflare 代理」这一步也劫持走（返回 400）
    opener = urllib.request.build_opener(
        urllib.request.ProxyHandler({}),
        urllib.request.HTTPSHandler(context=ctx),
    )
    return opener.open(req, timeout=timeout)


def env_proxy_info():
    """返回当前环境里被设置了的代理变量（诊断用）"""
    keys = ("http_proxy", "https_proxy", "all_proxy",
            "HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY")
    return {k: os.environ[k] for k in keys if os.environ.get(k)}


def http_get(url, timeout):
    """返回 (status, data)。先按校验证书请求，SSL 失败且允许时降级不校验。"""
    try:
        with _open(url, timeout, insecure=False) as r:
            return getattr(r, "status", 200), r.read()
    except ssl.SSLError as e:
        if not CONFIG["insecure_fallback"]:
            raise
        log("  TLS 校验失败（%s）→ 降级为不校验证书重试" % e)
        with _open(url, timeout, insecure=True) as r:
            return getattr(r, "status", 200), r.read()
    except urllib.error.HTTPError as e:
        return e.code, e.read()


def _proxy_wrap(url):
    """把真实地址套进 Cloudflare 代理前缀；未配置代理返回 "" """
    proxy = CONFIG["proxy"] or ""
    if not proxy or not url:
        return ""
    enc = urllib.parse.quote(url, safe="")
    if "url=" in proxy:
        return proxy + enc
    sep = "&" if "?" in proxy else "?"
    return proxy + sep + "url=" + enc


def _ver_key(name):
    """从文件名里抠出版本号元组（越大越新）；抠不到返回 ()"""
    m = re.search(r"(\d+(?:[._]\d+)*)", name, re.I)
    if not m:
        return ()
    return tuple(int(x) for x in re.split(r"[._]", m.group(1)))


_PRELOAD = {}


def _parse_ver(text):
    """从脚本内容里读 __version__；读不到返回 ()"""
    v = extract_version(text)
    return _ver_key(v) if v and v != "?" else ()


def _base_dir():
    b = CONFIG.get("base_dir") or ""
    if b and not b.endswith("/"):
        b += "/"
    return b


def _name_for(ver):
    """按模板生成候选文件名：ver=(8, 0) → ysp-live-v8.1.py"""
    return (CONFIG.get("name_tpl") or "ysp-live-v{v}.py").replace(
        "{v}", ".".join(str(x) for x in ver))


def _candidate_url(name):
    return _base_dir() + urllib.parse.quote(name)


def _probe_one(name):
    """GET 探测一个候选文件名。返回 (name, url, data|None, 说明)"""
    url = _candidate_url(name)
    wrapped = _proxy_wrap(url)
    if not wrapped:
        return name, url, None, "未配置 Cloudflare 代理"
    try:
        code, data = http_get(wrapped, CONFIG["timeout"])
    except Exception as e:
        return name, url, None, "请求异常: %s" % e
    if code != 200:
        return name, url, None, "HTTP %s" % code
    ok, why = looks_like_python(data)
    if not ok:
        return name, url, None, why
    return name, url, data, "命中"


def _probe_many(names):
    """并发探测一批文件名，返回 {name: (url, data)}（只保留通过校验的）"""
    names = [n for n in dict.fromkeys(names) if n]
    if not names:
        return {}
    workers = max(1, min(int(CONFIG.get("probe_workers") or 6), len(names)))
    got = {}
    with futures.ThreadPoolExecutor(max_workers=workers) as ex:
        for name, url, data, why in ex.map(_probe_one, names):
            if data is not None:
                got[name] = (url, data)
                log("    · 探测命中 %s（%d 字节）" % (name, len(data)))
            elif why != "HTTP 404":
                log("    · 探测 %s → %s" % (name, why))
    return got


# ---------------- 上游链接工具（双方案共用） ----------------

def upstream_dir():
    return (CONFIG.get("base_dir") or "").rstrip("/")


def guess_version_text(name):
    """从文件名 / URL 里抽版本号：ysp-live-v8.1.py → '8.1'"""
    m = re.search(r"[-_]v(\d+(?:\.\d+){1,3})", name or "", re.I)
    if not m:
        m = re.search(r"(\d+(?:\.\d+){1,3})", name or "")
    return m.group(1) if m else ""


def zip_url_for(version_text):
    """按版本号推整包 zip 地址（整包内含 .py + 伴生引擎 ysp-engine.js）"""
    if not version_text:
        return ""
    return "%s/%s" % (upstream_dir(), CONFIG["zip_tpl"].format(v=version_text))


def engine_url():
    return "%s/%s" % (upstream_dir(), CONFIG["engine_name"])


def is_zip_blob(data):
    return bool(data) and data[:2] == b"PK"


def zip_extract(data):
    """解整包：返回 (dict, err)；dict 含 py / py_name / engine / engine_name / readme"""
    import io
    import zipfile
    out = {"py": None, "py_name": "", "engine": None, "engine_name": "", "readme": None}
    try:
        zf = zipfile.ZipFile(io.BytesIO(data))
    except Exception as exc:
        return None, "无法解压：%s" % exc
    for info in zf.infolist():
        if info.is_dir():
            continue
        base = os.path.basename(info.filename)
        low = base.lower()
        try:
            blob = zf.read(info)
        except Exception:
            continue
        if low.endswith(".py"):
            if out["py"] is None or len(blob) > len(out["py"]):
                out["py"] = blob
                out["py_name"] = base
        elif low.endswith(".js"):
            if out["engine"] is None or len(blob) > len(out["engine"]):
                out["engine"] = blob
                out["engine_name"] = base
        elif low.endswith((".md", ".txt")):
            if out["readme"] is None:
                out["readme"] = blob
    if not out["py"]:
        return None, "整包里没有 .py 文件"
    return out, ""


def zip_candidates(zip_url, version_text):
    """整包候选：频道给的地址 + 按版本号推的地址"""
    out = []
    for u in (zip_url, zip_url_for(version_text)):
        if u and u not in out:
            out.append(u)
    return out


def write_blob(path, blob, mode=None):
    """原子写入二进制内容"""
    tmp = path + ".tmp"
    with open(tmp, "wb") as fh:
        fh.write(blob)
    if mode is not None:
        try:
            os.chmod(tmp, mode)
        except Exception:
            pass
    os.replace(tmp, path)


# ---------------- 方案①：TG 频道发现 ----------------

def parse_channel_links(html):
    """从 TG 频道预览页 HTML 找上游 ysp-live*.py / *.zip 链接。
    返回 (pys, zips)，元素 (ver_key, ver_text, url)，按版本从高到低。
    先收绝对链接；一个都没有时，再退回「正文文件名 + 上游目录」拼地址。"""
    host = (CONFIG.get("link_host") or "").lower()
    pys, zips, seen = [], [], set()
    for m in re.finditer(r"https?://[^\s\"'<>)]+", html or ""):
        url = m.group(0).rstrip(".,;:!")
        try:
            hostname = url.split("://", 1)[1].split("/", 1)[0].lower()
        except Exception:
            continue
        if host and host not in hostname:
            continue
        fname = url.rsplit("/", 1)[-1].split("?")[0]
        low = fname.lower()
        if not low.startswith("ysp-live") or url in seen:
            continue
        vt = guess_version_text(fname)
        if low.endswith(".py"):
            seen.add(url)
            pys.append((_ver_key(vt) if vt else (), vt, url))
        elif low.endswith(".zip") and "docker" not in low:
            seen.add(url)
            zips.append((_ver_key(vt) if vt else (), vt, url))
    if not pys and not zips:
        for m in re.finditer(r"(ysp-live[a-z0-9_.\-]*?\.(?:py|zip))", html or "", re.I):
            fname = m.group(1)
            low = fname.lower()
            if "docker" in low or fname in seen:
                continue
            url = "%s/%s" % (upstream_dir(), fname)
            seen.add(fname)
            vt = guess_version_text(fname)
            if low.endswith(".py"):
                pys.append((_ver_key(vt) if vt else (), vt, url))
            else:
                zips.append((_ver_key(vt) if vt else (), vt, url))
    pys.sort(key=lambda x: x[0], reverse=True)
    zips.sort(key=lambda x: x[0], reverse=True)
    return pys, zips


def discover_from_channel():
    """方案①：扫 TG 频道预览页找上游下载地址。
    返回 dict(py_url, zip_url, version_text, channel) 或 None"""
    tg_tpl = CONFIG.get("tg_tpl") or "https://t.me/s/{ch}"
    for ch in CONFIG.get("channels") or []:
        page = tg_tpl.format(ch=ch)
        wrapped = _proxy_wrap(page) or page
        try:
            status, data = http_get(wrapped, CONFIG["timeout"])
        except Exception as exc:
            log("  · 频道 %s 拉取异常：%s" % (ch, exc))
            continue
        if status != 200 or not data:
            log("  · 频道 %s 返回 %s，跳过" % (ch, status))
            continue
        pys, zips = parse_channel_links(data.decode("utf-8", "ignore"))
        if not pys and not zips:
            log("  · 频道 %s 未发现 ysp-live*.py / *.zip 链接" % ch)
            continue
        ver_text = pys[0][1] if pys else zips[0][1]
        py_url = pys[0][2] if pys else ""
        if not py_url:
            py_url = "%s/%s" % (upstream_dir(), CONFIG["name_tpl"].format(v=ver_text))
        zip_url = ""
        for vk, vt, u in zips:
            if vt == ver_text:
                zip_url = u
                break
        log("  ✅ 频道发现：%s → v%s（%s）"
            % (ch, ver_text, os.path.basename(zip_url or py_url)))
        return {"py_url": py_url, "zip_url": zip_url,
                "version_text": ver_text, "channel": ch}
    return None


def fetch_engine_blob():
    """单文件线路：单独把伴生引擎 ysp-engine.js 取回来"""
    url = engine_url()
    wrapped = _proxy_wrap(url) or url
    try:
        status, data = http_get(wrapped, CONFIG["timeout"])
    except Exception as exc:
        log("  · 伴生引擎下载异常：%s" % exc)
        return None, ""
    if status != 200 or not data or len(data) < 10240:
        log("  · 伴生引擎下载失败：HTTP %s，%d 字节" % (status, len(data or b"")))
        return None, ""
    return data, os.path.basename(url)


def discover_upstream_url(meta=None):
    """版本号网格探测：在上游目录下找最新的 ysp-live*.py。

    返回 (url, 说明)；失败返回 (None, 失败原因)。
    """
    base = _base_dir()
    if not base:
        return None, "未配置上游目录 base_dir"
    if not (CONFIG.get("proxy") or ""):
        return None, "未配置 Cloudflare 代理"

    hits = {}
    tried = set()

    def run(names):
        todo = [n for n in dict.fromkeys(names) if n and n not in tried]
        tried.update(todo)
        hits.update(_probe_many(todo))

    lo, hi = CONFIG.get("ver_major") or (6, 15)
    lo, hi = int(lo), int(hi)

    # 0) 上次成功的文件名 + 不带版本号的名字
    last_name = os.path.basename((meta or {}).get("resolved_url") or "")
    run([last_name, CONFIG.get("plain_name") or "ysp-live.py"])

    # 1a) 锚点快扫：从上次成功的版本起向上探（上游版本只递增，几次探测即命中）
    mv = re.search(r"v(\d+)\.(\d+)", last_name or "")
    if mv:
        am, ami = int(mv.group(1)), int(mv.group(2))
        run([_name_for((am, x)) for x in range(ami + 1, 10)]
            + [_name_for((am + 1, x)) for x in range(0, 10)])

    # 1b) 二维网格：v{M}.{m}.py（M: lo..hi，m: 0..9）
    #     上游只保留最新一个文件、且可能只递增次版本号（v8.0 -> v8.1），
    #     只探 v{M}.0 会漏掉 v8.1 这类名字；全空再退一步试三段式 v{M}.0.0.py
    if not hits:
        run([_name_for((m, mi)) for m in range(lo, hi + 1) for mi in range(0, 10)])
    if not hits:
        run([_name_for((m, 0, 0)) for m in range(lo, hi + 1)])
    if not hits:
        return None, "网格未找到任何 ysp-live-VERSION.py（目录 %s）" % base

    # 2) 次版本细化：命中的最高主版本
    majors = [v[0] for v in (_ver_key(n) for n in hits) if v]
    top = max(majors)
    run([_name_for((top, mi)) for mi in range(0, 10)])

    # 3) 补丁位细化：最高次版本的三段式 + 后续次版本的零补丁三段式
    minors = [v[1] for v in (_ver_key(n) for n in hits)
              if len(v) >= 2 and v[0] == top]
    mis = max(minors) if minors else 0
    run([_name_for((top, mis, p)) for p in range(1, 10)])
    run([_name_for((top, mi, 0)) for mi in range(mis + 1, 10)])

    # 命中里挑最新的：优先内容里的 __version__，其次文件名版本号
    best = None
    for name, (url, data) in hits.items():
        text = data.decode("utf-8", "ignore")
        key = (_parse_ver(text) or _ver_key(name), _ver_key(name), name)
        if best is None or key > best[0]:
            best = (key, name, url, data)
    if best is None:
        return None, "网格未找到可用脚本"
    _, name, url, data = best
    _PRELOAD[url] = data
    return url, "网格探测 %s（候选 %d 个，命中 %d 个）" % (name, len(tried), len(hits))


def resolve_upstream(meta=None):
    """决定本次抓哪个上游（双方案）。
    优先级：--url / YSP_URL 指定 → ①TG 频道发现 → ②版本号网格探测
            → meta 上次成功 → fallback_urls 兜底
    返回 (py_url, zip_url, version_text, how, source)"""
    meta = meta or {}
    if CONFIG.get("url"):
        u = CONFIG["url"]
        return u, "", guess_version_text(u), "指定地址", "fixed"
    try:
        found = discover_from_channel()
    except Exception as exc:
        log("  · 频道发现异常：%s" % exc)
        found = None
    if found:
        return (found["py_url"], found["zip_url"], found["version_text"],
                "频道发现（%s）" % found["channel"], "channel")
    log("  · 频道未发现可用链接 → 改用版本号网格探测")
    url, how = discover_upstream_url(meta)
    if url:
        return url, "", guess_version_text(url), how, "grid"
    last = meta.get("resolved_url") or ""
    if last:
        return last, "", guess_version_text(last), "meta 上次成功", "meta"
    for u in CONFIG.get("fallback_urls") or []:
        return u, "", guess_version_text(u), "兜底地址", "fallback"
    return "", "", "", "无可用地址", "none"


def build_candidates(url):
    """返回 [(标签, 最终URL), ...]：只走我们的 Cloudflare 代理，不做直连兜底"""
    wrapped = _proxy_wrap(url)
    return [("代理", wrapped)] if wrapped else []


# ---------------- QQ 推送（纯 urllib，显式禁用一切代理） ----------------

PUSH = {
    "enable": True,
    "app_id": "1904171517",
    "secret": "WWWXYadgkoty4AHOWfoy8JUgs5IWl0GW",
    "openid": "E29F25B0F605D41933CAC62FDFEF10C4",
    "device": "tv",   # mobile=手机版 / tv=电视版（TV 内置释放的副本固定为 tv）
}


def push_qq(version, path, upstream_name=""):
    """检测到上游新版本时直推 QQ（推送助手通道）。

    纯 urllib 实现：显式 ProxyHandler({})，忽略实验室注入的 http(s)_proxy，
    避免手机实验室代理干扰导致推送失败。
    """
    if not PUSH.get("enable"):
        return False
    label = "电视版" if str(PUSH.get("device", "")).lower() == "tv" else "手机版"
    text = (
        "🎬 ysp-live 上游已更新\n"
        "━━━━━━━━━━━━━━━━━\n"
        "🖥 webhtv %s · 已同步最新版\n"
        "📦 新版本：%s\n"
        "📄 保存位置：%s" % (label, version, path)
    )
    if upstream_name:
        text += "\n📡 上游文件：%s" % upstream_name
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    try:
        body = json.dumps({"appId": PUSH["app_id"], "clientSecret": PUSH["secret"]}).encode()
        req = urllib.request.Request(
            "https://bots.qq.com/app/getAppAccessToken", data=body,
            headers={"Content-Type": "application/json"})
        with opener.open(req, timeout=20) as resp:
            token = (json.loads(resp.read().decode()) or {}).get("access_token")
        if not token:
            log("  ⚠️ QQ 推送：未取到 access_token")
            return False
        seq = int(hashlib.md5(("%s|%s" % (version, path)).encode("utf-8")).hexdigest()[:8], 16) % 100000
        payload = {"content": text, "msg_type": 0, "msg_seq": seq}
        req2 = urllib.request.Request(
            "https://api.sgroup.qq.com/v2/users/%s/messages" % PUSH["openid"],
            data=json.dumps(payload).encode(),
            headers={"Content-Type": "application/json",
                     "Authorization": "QQBot %s" % token,
                     "X-Union-Appid": PUSH["app_id"]})
        with opener.open(req2, timeout=20) as resp:
            ok = getattr(resp, "status", 200) in (200, 201)
        log("  ✅ 已推送 QQ 通知" if ok else "  ⚠️ QQ 推送返回异常")
        return ok
    except Exception as e:
        log("  ⚠️ QQ 推送失败：%s" % e)
        return False


def notify(version, path):
    cmd = CONFIG["notify_cmd"]
    if not cmd:
        return
    try:
        c = cmd.replace("{version}", str(version)).replace("{path}", path)
        subprocess.run(c, shell=True, timeout=20)
        log("  通知命令已执行")
    except Exception as e:
        log("  通知命令失败（忽略）: %s" % e)


# ---------------- 核心检测 ----------------

def check_once(force=False, dry_run=False):
    save_dir, target, meta_path, _ = resolve_paths()
    old_meta = load_meta(meta_path)
    before_exists = os.path.exists(target)
    before_sha = old_meta.get("sha256", "")
    before_size = os.path.getsize(target) if before_exists else 0
    engine_path = os.path.join(save_dir, CONFIG["engine_name"])
    engine_exists = os.path.exists(engine_path)

    upstream_url, up_zip, up_ver, how, up_kind = resolve_upstream(old_meta)
    upstream_meta = {
        "url": upstream_url,
        "resolved_url": upstream_url,
        "upstream_name": os.path.basename(upstream_url) if upstream_url else "",
        "resolve_how": how,
        "source": up_kind,
    }
    log("开始检测上游更新…  →  %s（%s）" % (upstream_url or "（无）", how))
    log("本地文件: %s（%s）" % (target, ("存在 %.1fKB" % (before_size / 1024.0)) if before_exists else "不存在"))
    if engine_exists:
        log("伴生引擎: %s（%.1fKB）" % (CONFIG["engine_name"], os.path.getsize(engine_path) / 1024.0))
    else:
        log("伴生引擎: %s（缺失，本次尝试补齐）" % CONFIG["engine_name"])

    last_err = "无可用通道（未配置 Cloudflare 代理）"
    # 候选顺序：① 整包 zip（含 .py + 引擎）→ ② 单文件 .py → ③ 网格预载内容
    cands = []
    for zu in zip_candidates(up_zip, up_ver):
        cands.append(("整包", zu))
    pre = _PRELOAD.get(upstream_url)
    if pre is not None:
        cands.append(("网格", None))
    cands += build_candidates(upstream_url)

    data = None
    label = "?"
    pkg = "py"
    for label, u in cands:
        if u is None:
            code, data = 200, pre
            log("  [%s] 复用探测阶段已取到的内容，%d 字节" % (label, len(data)))
        else:
            try:
                code, data = http_get(u, CONFIG["timeout"])
            except Exception as e:
                last_err = "%s: %s" % (label, e)
                log("  [%s] 请求异常: %s" % (label, e))
                continue
            if code != 200:
                last_err = "%s: HTTP %s" % (label, code)
                log("  [%s] 淘汰（HTTP %s）" % (label, code))
                continue
            log("  [%s] 成功（HTTP %s，%d 字节）" % (label, code, len(data)))
        if is_zip_blob(data):
            pkg = "zip"
            break
        pkg = "py"
        ok, why = looks_like_python(data)
        if not ok:
            last_err = "%s: %s" % (label, why)
            log("  [%s] 淘汰（%s）" % (label, why))
            data = None
            continue
        break

    if data is None:
        log("  ❌ 所有通道均失败：%s" % (last_err or "未知错误"))
        save_meta(meta_path, last_check=now_str(), last_result="failed", last_error=last_err)
        return "failed"

    # ---- 解包 / 取内容 ----
    engine_blob = None
    if pkg == "zip":
        files, perr = zip_extract(data)
        if perr:
            log("  ❌ 整包解压失败：%s" % perr)
            save_meta(meta_path, last_check=now_str(), last_result="failed",
                      last_error=perr)
            return "failed"
        py_blob = files["py"]
        engine_blob = files["engine"]
        src_name = files["py_name"]
        log("  📦 整包：%s（%d 字节）%s"
            % (src_name, len(py_blob),
               (" + 伴生 %s（%d 字节）" % (files["engine_name"], len(engine_blob)))
               if engine_blob else "（包内无伴生 JS）"))
    else:
        py_blob = data
        src_name = upstream_meta["upstream_name"]

    text = py_blob.decode("utf-8", "ignore")
    new_ver = extract_version(text)
    if not new_ver or new_ver == "?":
        new_ver = up_ver or "?"
    new_sha = hashlib.sha256(py_blob).hexdigest()

    # 伴生引擎状态
    eng_state = ""
    if engine_blob:
        old_e = os.path.getsize(engine_path) if engine_exists else -1
        eng_state = "upd" if len(engine_blob) != old_e else "same"
    elif not engine_exists:
        eng_state = "missing"

    if not force and before_exists and before_sha and new_sha == before_sha:
        log("  ✅ 已是最新（v%s，%s，sha256 %s…）" % (new_ver, src_name or "?", new_sha[:12]))
        if eng_state == "upd":
            write_blob(engine_path, engine_blob)
            log("  🧩 伴生引擎已同步 → %s（%.1fKB）" % (CONFIG["engine_name"], len(engine_blob) / 1024.0))
            eng_state = "synced"
        elif eng_state == "missing":
            blob, esrc = fetch_engine_blob()
            if blob:
                write_blob(engine_path, blob)
                log("  🧩 伴生引擎已补齐 → %s（%.1fKB）" % (CONFIG["engine_name"], len(blob) / 1024.0))
                eng_state = "fetched"
            else:
                log("  ⚠️ 伴生引擎缺失且本次未取到（源内第 4 层 WASM 兜底不可用）")
                eng_state = "unavailable"
        save_meta(meta_path, **upstream_meta, sha256=new_sha, size=len(py_blob),
                  version=new_ver, last_check=now_str(), last_result="up-to-date",
                  last_error="", engine=eng_state or "same")
        return "up-to-date"

    if dry_run:
        log("  🧪 dry-run：可用版本 v%s（sha256 %s…），不写文件" % (new_ver, new_sha[:12]))
        save_meta(meta_path, last_check=now_str(), last_result="dry-run-available")
        return "available"

    if before_exists:
        try:
            shutil.copy2(target, target + ".bak")
            log("  已备份旧版 → %s.bak" % os.path.basename(target))
        except Exception as e:
            log("  备份失败（忽略）: %s" % e)

    try:
        write_blob(target, py_blob, 0o755)
    except Exception as e:
        log("  ❌ 写入失败: %s" % e)
        save_meta(meta_path, last_check=now_str(), last_result="failed", last_error="写入失败: %s" % e)
        return "failed"

    # 伴生引擎：整包里带了就直接写；单文件线路则单独补一次
    if engine_blob:
        try:
            if engine_exists:
                shutil.copy2(engine_path, engine_path + ".bak")
            write_blob(engine_path, engine_blob)
            log("  🧩 伴生引擎已写入 → %s（%.1fKB）" % (CONFIG["engine_name"], len(engine_blob) / 1024.0))
            eng_state = "written"
        except Exception as e:
            log("  ⚠️ 伴生引擎写入失败: %s" % e)
            eng_state = "write-failed"
    elif eng_state == "missing":
        blob, esrc = fetch_engine_blob()
        if blob:
            try:
                write_blob(engine_path, blob)
                log("  🧩 伴生引擎已补齐 → %s（%.1fKB）" % (CONFIG["engine_name"], len(blob) / 1024.0))
                eng_state = "fetched"
            except Exception as e:
                log("  ⚠️ 伴生引擎写入失败: %s" % e)
                eng_state = "write-failed"
        else:
            log("  ⚠️ 伴生引擎缺失（本次走的是单文件线路）→ 源内第 4 层 WASM 兜底不可用")
            eng_state = "unavailable"

    log("  ✅ 已更新到 %s（上游 %s，%d 字节，sha256 %s…）"
        % (new_ver, src_name or "?", len(py_blob), new_sha[:12]))
    save_meta(meta_path, **upstream_meta, sha256=new_sha, size=len(py_blob),
              version=new_ver, last_check=now_str(), last_result="updated",
              updated_at=now_str(), last_error="", via=label, engine=eng_state)
    notify(new_ver, target)
    push_qq(new_ver, target, upstream_name=src_name or upstream_meta["upstream_name"])
    return "updated"


def pid_alive(pid):
    if pid <= 0:
        return False
    try:
        os.kill(pid, 0)
    except OSError as e:
        return e.errno == errno.EPERM
    return True


def acquire_lock(save_dir):
    global _LOCK_PATH
    lp = os.path.join(save_dir, ".ysp_live_watcher.lock")
    _LOCK_PATH = lp
    if os.path.exists(lp):
        try:
            with open(lp, "r", encoding="utf-8") as f:
                old = int(f.read().strip() or "0")
        except Exception:
            old = 0
        if old and old != os.getpid() and pid_alive(old):
            log("⚠️ 已有实例在运行（PID %d），本次退出以免重复下载。" % old)
            return False
    try:
        with open(lp, "w", encoding="utf-8") as f:
            f.write(str(os.getpid()))
    except Exception as e:
        log("⚠️ 写锁文件失败（忽略）: %s" % e)
    return True


def release_lock():
    if _LOCK_PATH and os.path.exists(_LOCK_PATH):
        try:
            with open(_LOCK_PATH, "r", encoding="utf-8") as f:
                cur = f.read().strip()
            if cur == str(os.getpid()):
                os.remove(_LOCK_PATH)
        except Exception:
            pass


def _sig(signum, frame):
    log("收到信号 %s，退出。" % signum)
    release_lock()
    sys.exit(0)


# ---------------- 状态 ----------------

def show_status():
    save_dir, target, meta_path, log_path = resolve_paths()
    meta = load_meta(meta_path)
    print("=" * 54)
    print(" ysp-live 更新监控 · 状态")
    print("=" * 54)
    if CONFIG["url"]:
        print(" 上游地址 : %s（手动指定）" % CONFIG["url"])
    else:
        print(" 上游地址 : 自动探测 %s%s" % (_base_dir(), CONFIG.get("name_tpl") or "ysp-live-v{v}.py"))
        print(" 探测范围 : 主版本 %d~%d + 次/补丁位 0~9 + %s"
              % (CONFIG["ver_major"][0], CONFIG["ver_major"][1],
                 CONFIG.get("plain_name") or "ysp-live.py"))
    print(" 解析地址 : %s" % (meta.get("resolved_url") or "—"))
    print(" 上游文件 : %s" % (meta.get("upstream_name") or "—"))
    print(" 代理前缀 : %s" % (CONFIG["proxy"] or "（未配置！）"))
    print(" 环境代理 : %s" % (env_proxy_info() or "（无）"))
    print(" 代理策略 : 仅走 Cloudflare 代理（永久忽略实验室环境代理，无直连兜底）")
    print(" 保存目录 : %s" % save_dir)
    print(" 目标文件 : %s" % target)
    if os.path.exists(target):
        st = os.stat(target)
        with open(target, "rb") as f:
            raw = f.read()
        sha = hashlib.sha256(raw).hexdigest()
        print(" 本地版本 : %s" % extract_version(raw.decode("utf-8", "ignore")))
        print(" 文件大小 : %d 字节" % st.st_size)
        print(" 修改时间 : %s" % datetime.fromtimestamp(st.st_mtime).strftime("%Y-%m-%d %H:%M:%S"))
        print(" sha256   : %s" % sha)
    else:
        print(" 本地文件 : （不存在）")
    print(" 上次检测 : %s" % meta.get("last_check", "—"))
    print(" 上次结果 : %s" % meta.get("last_result", "—"))
    if meta.get("last_error"):
        print(" 上次错误 : %s" % meta["last_error"])
    print(" 轮询间隔 : %d 秒" % CONFIG["interval"])
    print(" 日志文件 : %s" % log_path)
    print("=" * 54)


# ---------------- 入口 ----------------

def _log_proxy_policy():
    got = env_proxy_info()
    policy = "仅走 Cloudflare 代理（已忽略实验室环境代理，无直连兜底）"
    if got:
        log("环境代理变量: %s → %s" % (got, policy))
    else:
        log("环境代理变量: 无 → %s" % policy)


def run_daemon():
    save_dir, _, _, _ = resolve_paths()
    if not acquire_lock(save_dir):
        return 2
    signal.signal(signal.SIGTERM, _sig)
    signal.signal(signal.SIGINT, _sig)
    _log_proxy_policy()
    log("▶ 进入常驻模式：每 %d 秒检测一次（PID %d，目录 %s）" % (CONFIG["interval"], os.getpid(), save_dir))
    try:
        while True:
            try:
                check_once(force=False)
            except Exception as e:
                log("❌ 本轮检测异常: %s" % e)
            time.sleep(CONFIG["interval"])
    finally:
        release_lock()


def parse_args():
    p = argparse.ArgumentParser(description="ysp-live.py 上游更新监控下载器（手机实验室版）")
    p.add_argument("--once", action="store_true", help="只检测一次")
    p.add_argument("--status", action="store_true", help="打印当前状态")
    p.add_argument("--force", action="store_true", help="强制重新下载")
    p.add_argument("--dry-run", action="store_true", help="只检测不写文件")
    p.add_argument("--url", help="覆盖上游地址（指定后不再自动探测）")
    p.add_argument("--base-dir", dest="base_dir", help="覆盖上游目录（自动探测用）")
    p.add_argument("--name-tpl", dest="name_tpl",
                   help="覆盖上游文件名模板（{v}=版本号，如 ysp-live-v{v}.py）")
    p.add_argument("--proxy", help="覆盖代理前缀（唯一通道，清空则无可用通道）")
    p.add_argument("--save-dir", dest="save_dir", help="覆盖保存目录")
    p.add_argument("--interval", type=int, help="覆盖轮询间隔（秒）")
    return p.parse_args()


def main():
    global _LOG_PATH
    args = parse_args()
    if args.url:
        CONFIG["url"] = args.url
    if args.base_dir:
        CONFIG["base_dir"] = args.base_dir
    if args.name_tpl:
        CONFIG["name_tpl"] = args.name_tpl
    if args.proxy is not None:
        CONFIG["proxy"] = args.proxy
    if args.save_dir:
        CONFIG["save_dir"] = args.save_dir
    if args.interval:
        CONFIG["interval"] = args.interval
    _, _, _, log_path = resolve_paths()
    _LOG_PATH = log_path

    if args.status:
        show_status()
        return 0

    if args.dry_run:
        check_once(force=args.force, dry_run=True)
        return 0

    if args.once or args.force:
        r = check_once(force=args.force, dry_run=False)
        return 0 if r in ("updated", "up-to-date") else 1

    return run_daemon()


if __name__ == "__main__":
    sys.exit(main())
