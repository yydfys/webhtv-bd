#!/usr/bin/env python3
# -*- coding: utf-8 -*-
# [内置副本] 本文件由 webhtv 电视版随包释放（device=tv），源头 = NAS local-sites/ysp_live_watcher.py
"""
ysp_live_watcher.py —— 上游「ysp-live.py」更新监控下载器（手机 WebHTV 实验室版）

● 干什么
  定时检测上游脚本 ysp-live*.py（作者改文件名也能自动跟上）是否有更新，
  有更新就下载到本地目录（默认＝本脚本所在目录），原子写入 + 旧版备份。
  默认「自动发现」：抓作者 TG 频道预览页，取版本号最大的那个 ysp-live*.py；
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
import errno
import fnmatch
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
    # 上游脚本地址：留空("") = 自动发现最新版 ysp-live*.py（推荐）
    #   也可用 --url / YSP_URL 锁定一个固定地址
    "url": os.environ.get("YSP_URL", ""),
    # 自动发现页：上游作者的 TG 频道预览页（走同一个 Cloudflare 代理抓取）
    "discover": os.environ.get("YSP_DISCOVER", "https://t.me/s/garysclubchannel"),
    # 上游文件名匹配（glob，匹配发现页里的 .py 链接）
    "file_pattern": os.environ.get("YSP_FILE_PATTERN", "ysp-live*.py"),
    # 自动发现失败时的兜底地址（按顺序尝试；再不行退回 meta 里上次成功的地址）
    "fallback_urls": [
        "https://garysclub.sharewithyou.dpdns.org/others/ysp-live-v8.0.py",
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


def discover_upstream_url():
    """抓上游作者的 TG 频道预览页，自动发现最新的 ysp-live*.py 地址。

    返回 (url, 说明)；失败返回 (None, 失败原因)。
    """
    page = CONFIG.get("discover") or ""
    if not page:
        return None, "未配置发现页"
    wrapped = _proxy_wrap(page)
    if not wrapped:
        return None, "未配置 Cloudflare 代理"
    try:
        code, data = http_get(wrapped, CONFIG["timeout"])
    except Exception as e:
        return None, "抓取发现页异常: %s" % e
    if code != 200:
        return None, "抓取发现页 HTTP %s" % code
    html = data.decode("utf-8", "ignore")
    pattern = CONFIG.get("file_pattern") or "ysp-live*.py"
    found = []
    for i, raw in enumerate(re.findall(r'https?://[^\s"\'<>\\]+\.py', html)):
        fname = raw.rsplit("/", 1)[-1]
        if not fnmatch.fnmatch(fname.lower(), pattern.lower()):
            continue
        if "docker" in fname.lower():
            continue
        found.append((_ver_key(fname), i, raw, fname))
    if not found:
        return None, "发现页未找到匹配 %s 的链接" % pattern
    # 版本升序；同版本取靠后（更新）的那条
    found.sort(key=lambda t: (t[0], t[1]))
    _, _, url, fname = found[-1]
    return url, "自动发现 %s" % fname


def resolve_upstream_url(meta=None):
    """决定本轮要下载的上游地址。返回 (url, 说明)。

    优先级：--url/YSP_URL 指定 > 自动发现 > 上次成功地址 > 内置兜底
    """
    if CONFIG["url"]:
        return CONFIG["url"], "手动指定"
    url, why = discover_upstream_url()
    if url:
        return url, why
    log("  ⚠️ 自动发现失败（%s）→ 改用兜底地址" % why)
    last_ok = (meta or {}).get("resolved_url") or ""
    if last_ok:
        return last_ok, "上次成功地址"
    fb = CONFIG.get("fallback_urls") or []
    if fb:
        return fb[0], "内置兜底"
    return "", "无可用地址"


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

    upstream_url, how = resolve_upstream_url(old_meta)
    upstream_meta = {
        "url": upstream_url,
        "resolved_url": upstream_url,
        "upstream_name": os.path.basename(upstream_url) if upstream_url else "",
        "resolve_how": how,
    }
    log("开始检测上游更新…  →  %s（%s）" % (upstream_url or "（无）", how))
    log("本地文件: %s（%s）" % (target, ("存在 %.1fKB" % (before_size / 1024.0)) if before_exists else "不存在"))

    last_err = "无可用通道（未配置 Cloudflare 代理）"
    for label, u in build_candidates(upstream_url):
        try:
            code, data = http_get(u, CONFIG["timeout"])
        except Exception as e:
            last_err = "%s: %s" % (label, e)
            log("  [%s] 请求异常: %s" % (label, e))
            continue

        log("  [%s] HTTP %s，%d 字节" % (label, code, len(data)))
        if code != 200:
            last_err = "%s: HTTP %s" % (label, code)
            continue

        ok, why = looks_like_python(data)
        if not ok:
            last_err = "%s: %s" % (label, why)
            log("  [%s] 内容不可用 → %s" % (label, why))
            continue

        text = data.decode("utf-8", "ignore")
        new_sha = hashlib.sha256(data).hexdigest()
        new_ver = extract_version(text)

        if not force and before_exists and before_sha and new_sha == before_sha:
            log("  ✅ 已是最新（版本 %s，上游 %s，sha256 %s…），无需下载"
                % (new_ver, upstream_meta["upstream_name"] or "?", new_sha[:12]))
            save_meta(meta_path, **upstream_meta, sha256=new_sha, size=len(data),
                      version=new_ver, last_check=now_str(), last_result="up-to-date", last_error="")
            return "up-to-date"

        if dry_run:
            log("  🧪 dry-run：检测到可用版本 %s（sha256 %s…），不写文件" % (new_ver, new_sha[:12]))
            save_meta(meta_path, last_check=now_str(), last_result="dry-run-available")
            return "available"

        if before_exists:
            try:
                shutil.copy2(target, target + ".bak")
                log("  已备份旧版 → %s.bak" % os.path.basename(target))
            except Exception as e:
                log("  备份失败（忽略）: %s" % e)

        try:
            tmp = target + ".tmp"
            with open(tmp, "wb") as f:
                f.write(data)
            try:
                os.chmod(tmp, 0o755)
            except Exception:
                pass
            os.replace(tmp, target)
        except Exception as e:
            log("  ❌ 写入失败: %s" % e)
            return "failed"

        log("  ✅ 已更新到 %s（上游 %s，%d 字节，sha256 %s…）"
            % (new_ver, upstream_meta["upstream_name"] or "?", len(data), new_sha[:12]))
        save_meta(meta_path, **upstream_meta, sha256=new_sha, size=len(data),
                  version=new_ver, last_check=now_str(), last_result="updated",
                  updated_at=now_str(), last_error="", via=label)
        notify(new_ver, target)
        push_qq(new_ver, target, upstream_name=upstream_meta["upstream_name"])
        return "updated"

    log("  ❌ 所有通道均失败：%s" % (last_err or "未知错误"))
    save_meta(meta_path, last_check=now_str(), last_result="failed", last_error=last_err)
    return "failed"


# ---------------- 单实例锁 ----------------

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
        print(" 上游地址 : 自动发现 %s（留空 url 即自动）" % (CONFIG.get("file_pattern") or "ysp-live*.py"))
        print(" 发现页   : %s" % (CONFIG.get("discover") or "（未配置！）"))
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
    p.add_argument("--url", help="覆盖上游地址（指定后不再自动发现）")
    p.add_argument("--discover", help="覆盖自动发现页（TG 频道预览页）")
    p.add_argument("--pattern", help="覆盖上游文件名匹配（glob，如 ysp-live*.py）")
    p.add_argument("--proxy", help="覆盖代理前缀（唯一通道，清空则无可用通道）")
    p.add_argument("--save-dir", dest="save_dir", help="覆盖保存目录")
    p.add_argument("--interval", type=int, help="覆盖轮询间隔（秒）")
    return p.parse_args()


def main():
    global _LOG_PATH
    args = parse_args()
    if args.url:
        CONFIG["url"] = args.url
    if args.discover:
        CONFIG["discover"] = args.discover
    if args.pattern:
        CONFIG["file_pattern"] = args.pattern
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
