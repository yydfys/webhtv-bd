#!/usr/bin/env node
/*
 * node_server.js — 零依赖 Node.js 静态文件服务器
 * 用法: node node_server.js --port 8903 --root /storage/emulated/0/VodPlus/wwwroot
 * 设计给 lab.json 的 node_server 命令段使用（Ubuntu Rootfs / 任意 Node 环境）
 * 特点:
 *   - 只用 Node 内置 http/fs/path，零 npm 依赖
 *   - 自动 MIME 类型（html/js/css/json/图片/文本等）
 *   - 路径穿越防护（防止 ../ 逃逸根目录）
 *   - 中文路径/URL 编码自动处理
 *   - 启动时打印实际监听地址
 */
'use strict';

const http = require('http');
const fs = require('fs');
const path = require('path');

// ---------- 解析命令行参数 ----------
function parseArgs(argv) {
    const args = { port: 8903, root: process.cwd() };
    for (let i = 0; i < argv.length; i++) {
        if (argv[i] === '--port' && argv[i + 1]) {
            args.port = parseInt(argv[i + 1], 10) || args.port;
            i++;
        } else if (argv[i] === '--root' && argv[i + 1]) {
            args.root = path.resolve(argv[i + 1]);
            i++;
        }
    }
    return args;
}

// ---------- MIME 类型映射 ----------
const MIME = {
    'html': 'text/html; charset=utf-8',
    'htm': 'text/html; charset=utf-8',
    'js': 'text/javascript; charset=utf-8',
    'mjs': 'text/javascript; charset=utf-8',
    'css': 'text/css; charset=utf-8',
    'json': 'application/json; charset=utf-8',
    'txt': 'text/plain; charset=utf-8',
    'md': 'text/markdown; charset=utf-8',
    'xml': 'application/xml; charset=utf-8',
    'png': 'image/png',
    'jpg': 'image/jpeg',
    'jpeg': 'image/jpeg',
    'gif': 'image/gif',
    'webp': 'image/webp',
    'svg': 'image/svg+xml',
    'ico': 'image/x-icon',
    'bmp': 'image/bmp',
    'woff': 'font/woff',
    'woff2': 'font/woff2',
    'ttf': 'font/ttf',
    'otf': 'font/otf',
    'mp3': 'audio/mpeg',
    'mp4': 'video/mp4',
    'm3u8': 'application/vnd.apple.mpegurl',
    'ts': 'video/mp2t',
    'pdf': 'application/pdf',
    'zip': 'application/zip',
    'apk': 'application/vnd.android.package-archive'
};

function mimeFor(filePath) {
    const ext = path.extname(filePath).slice(1).toLowerCase();
    return MIME[ext] || 'application/octet-stream';
}

// ---------- 创建服务器 ----------
function createServer(root) {
    return http.createServer((req, res) => {
        try {
            let urlPath;
            try {
                urlPath = decodeURIComponent(req.url.split('?')[0]);
            } catch (e) {
                urlPath = req.url.split('?')[0];
            }
            if (urlPath === '/') urlPath = '/index.html';

            // 防路径穿越
            const filePath = path.join(root, urlPath);
            if (!filePath.startsWith(path.resolve(root) + path.sep) && filePath !== path.resolve(root)) {
                res.writeHead(403, { 'Content-Type': 'text/plain; charset=utf-8' });
                return res.end('403 Forbidden');
            }

            fs.stat(filePath, (err, stat) => {
                if (err || !stat.isFile()) {
                    res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
                    return res.end('404 Not Found');
                }
                res.writeHead(200, {
                    'Content-Type': mimeFor(filePath),
                    'Content-Length': stat.size,
                    'Cache-Control': 'no-cache'
                });
                fs.createReadStream(filePath).pipe(res);
            });
        } catch (e) {
            res.writeHead(500, { 'Content-Type': 'text/plain; charset=utf-8' });
            res.end('500 Internal Server Error');
        }
    });
}

// ---------- 主入口 ----------
const args = parseArgs(process.argv.slice(2));
if (!fs.existsSync(args.root) || !fs.statSync(args.root).isDirectory()) {
    console.error('[node_server] ROOT NOT FOUND: ' + args.root);
    process.exit(1);
}

const server = createServer(args.root);
server.listen(args.port, '0.0.0.0', () => {
    console.log('[node_server] listening on http://0.0.0.0:' + args.port);
    console.log('[node_server] root: ' + args.root);
});
