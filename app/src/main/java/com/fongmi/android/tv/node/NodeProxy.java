package com.fongmi.android.tv.node;

/**
 * node 侧的 proxy 规则接入脚本。
 *
 * <p>node 猫源跑在独立进程里（nodejs-mobile / libnode v18），Java 的判定函数和
 * ProxySelector 都够不着它；而 Node 18 又不认 {@code NODE_USE_ENV_PROXY}（那是 Node 24 才有），
 * 所以这里直接对 node 的 http/https/fetch 打补丁，让它自己也按壳内规则分流。
 *
 * <p>补丁每次请求先读一次宿主写的状态文件（{@code proxy_state.json}，2 秒缓存）：
 * <ul>
 *   <li>开关没开 → 立刻走原始实现，行为与打补丁前完全一致（等于没有这段代码）</li>
 *   <li>开关开着 → 命中规则的域名走壳内规则出口（CONNECT 隧道 / 绝对 URI），其余直连</li>
 *   <li>同时刷新 HTTP_PROXY 等环境变量，照顾 axios 这类读 env 的库</li>
 * </ul>
 * 本机与局域网目标永远直连（本地服务、投屏、网盘回调都在这个网段里）。
 */
final class NodeProxy {

    private NodeProxy() {
    }

    static String source(String statePath) {
        return SOURCE.replace("__STATE_FILE__", statePath.replace("\\", "\\\\").replace("'", "\\'"));
    }

    private static final String SOURCE = """
            'use strict';
            // 壳内 proxy 规则的 node 侧接入（宿主生成，不要手改）
            (function () {
              var fs = require('fs');
              var http = require('http');
              var https = require('https');
              var tls = require('tls');
              var STATE = '__STATE_FILE__';
              var cached = null;
              var cachedAt = 0;
              var envSet = false;
              var noticed = false;

              function state() {
                var now = Date.now();
                if (now - cachedAt < 2000) return cached;
                cachedAt = now;
                try {
                  cached = JSON.parse(fs.readFileSync(STATE, 'utf8'));
                } catch (e) {
                  cached = null;
                }
                return cached;
              }

              function safePort() {
                try {
                  var snapshot = state();
                  return snapshot && snapshot.enabled && snapshot.port > 0 ? snapshot.port : 0;
                } catch (e) {
                  return 0;
                }
              }

              // 本机 / 局域网永远直连
              function isLocal(host) {
                if (!host) return true;
                var value = String(host).toLowerCase();
                if (value === 'localhost' || value === '127.0.0.1' || value === '0.0.0.0' || value === '::1') return true;
                if (value.indexOf('[') === 0) return true;
                var parts = value.split('.');
                if (parts.length !== 4) return value.indexOf(':') >= 0;
                for (var i = 0; i < 4; i++) {
                  if (!/^\\d{1,3}$/.test(parts[i])) return false;
                }
                var a = Number(parts[0]);
                var b = Number(parts[1]);
                if (a === 0 || a === 10 || a === 127) return true;
                if (a === 192 && b === 168) return true;
                if (a === 169 && b === 254) return true;
                return a === 172 && b >= 16 && b <= 31;
              }

              function fromUrl(value) {
                return {
                  protocol: value.protocol,
                  hostname: value.hostname,
                  port: value.port,
                  path: value.pathname + value.search,
                  auth: value.username ? value.username + ':' + value.password : undefined
                };
              }

              // http.request 支持的三种调用形式：string / URL / options，且
              // request(url, options, cb) 时 options 要盖在 URL 之上（跟 Node 行为一致）
              function normalize(args) {
                var base = null;
                var extra = null;
                var callback = null;
                for (var i = 0; i < args.length; i++) {
                  var item = args[i];
                  if (typeof item === 'function') { callback = item; break; }
                  if (item instanceof URL) { if (!base) base = fromUrl(item); continue; }
                  if (typeof item === 'string') { if (!base) base = fromUrl(new URL(item)); continue; }
                  if (item && typeof item === 'object') { extra = Object.assign({}, item); continue; }
                }
                return { options: extra ? Object.assign({}, base || {}, extra) : base, callback: callback };
              }

              function shape(options, secure) {
                var host = String(options.hostname || options.host || '');
                var port = options.port ? Number(options.port) : (secure ? 443 : 80);
                // host 里带端口（options 形式常见）要拆开，否则会被当成本机/异常主机而漏掉代理
                if (host.indexOf('[') === 0) {
                  var close = host.indexOf(']');
                  if (close > 0) {
                    if (!options.port && host.length > close + 2) port = Number(host.substring(close + 2)) || port;
                    host = host.substring(1, close);
                  }
                } else if (host.indexOf(':') >= 0) {
                  var split = host.split(':');
                  if (!options.port) port = Number(split[1]) || port;
                  host = split[0];
                }
                var path = options.path ? String(options.path) : '/';
                // 有些库（axios 走代理时）直接把完整 URL 塞在 path 里
                if (path.indexOf('://') > 0) {
                  try {
                    var built = new URL(path);
                    host = built.hostname;
                    port = built.port ? Number(built.port) : (secure ? 443 : 80);
                    path = built.pathname + built.search;
                  } catch (e) {
                  }
                }
                return { secure: secure, host: host, port: port, path: path };
              }

              function absoluteUrl(item) {
                var defaultPort = item.secure ? 443 : 80;
                return (item.secure ? 'https://' : 'http://') + item.host + (item.port === defaultPort ? '' : ':' + item.port) + item.path;
              }

              function headersOf(options, item) {
                var result = Object.assign({}, options.headers || {});
                var found = false;
                Object.keys(result).forEach(function (key) {
                  if (key.toLowerCase() === 'host') found = true;
                });
                if (!found) result.Host = item.host + ((item.secure && item.port === 443) || (!item.secure && item.port === 80) ? '' : ':' + item.port);
                return result;
              }

              function connectThrough(port, item) {
                return new Promise(function (resolve, reject) {
                  var request = http.request({
                    hostname: '127.0.0.1',
                    host: '127.0.0.1',
                    port: port,
                    method: 'CONNECT',
                    path: item.host + ':' + item.port,
                    headers: { Host: item.host + ':' + item.port },
                    agent: false
                  });
                  request.on('connect', function (response, socket, head) {
                    if (response.statusCode !== 200) {
                      socket.destroy();
                      reject(new Error('shell-proxy CONNECT ' + response.statusCode));
                      return;
                    }
                    if (head && head.length && socket.unshift) socket.unshift(head);
                    resolve(socket);
                  });
                  request.on('error', reject);
                  request.end();
                });
              }

              // https 只能先 CONNECT 打隧道再在隧道里做 TLS（端到端证书校验不受影响）
              function agentThrough(port) {
                var agent = new https.Agent({ keepAlive: false });
                agent.createConnection = function (options, callback) {
                  var item = { secure: true, host: options.host, port: Number(options.port || 443), path: '/' };
                  connectThrough(port, item).then(function (socket) {
                    var secured = tls.connect({
                      socket: socket,
                      servername: options.servername || options.host,
                      // 尊重进程级"关校验"开关（NODE_TLS_REJECT_UNAUTHORIZED=0），别把它盖回去
                      rejectUnauthorized: process.env.NODE_TLS_REJECT_UNAUTHORIZED === '0' ? false : options.rejectUnauthorized !== false,
                      ca: options.ca,
                      cert: options.cert,
                      key: options.key
                    });
                    secured.on('error', function (error) {
                      if (callback) callback(error);
                    });
                    if (callback) callback(null, secured);
                  }).catch(function (error) {
                    if (callback) callback(error);
                  });
                  return undefined;
                };
                return agent;
              }

              function notice(port) {
                if (noticed) return;
                noticed = true;
                console.log('shell-proxy: node channel routed through 127.0.0.1:' + port);
              }

              function patch(mod, secure) {
                var originalRequest = mod.request;
                var originalGet = mod.get;

                function request() {
                  var args = Array.prototype.slice.call(arguments);
                  var port = safePort();
                  if (!port) return originalRequest.apply(mod, args);
                  var parsed;
                  try {
                    parsed = normalize(args);
                  } catch (e) {
                    return originalRequest.apply(mod, args);
                  }
                  if (!parsed.options) return originalRequest.apply(mod, args);
                  var item;
                  try {
                    item = shape(parsed.options, secure);
                  } catch (e) {
                    return originalRequest.apply(mod, args);
                  }
                  if (!item.host || isLocal(item.host)) return originalRequest.apply(mod, args);
                  try {
                    notice(port);
                    if (!secure) {
                      var plain = Object.assign({}, parsed.options, {
                        protocol: 'http:',
                        hostname: '127.0.0.1',
                        host: '127.0.0.1',
                        port: port,
                        path: absoluteUrl(item),
                        headers: headersOf(parsed.options, item),
                        agent: false
                      });
                      delete plain.createConnection;
                      delete plain.socketPath;
                      return originalRequest.call(mod, plain, parsed.callback);
                    }
                    var proxied = Object.assign({}, parsed.options, {
                      protocol: 'https:',
                      hostname: item.host,
                      host: item.host,
                      port: item.port,
                      agent: agentThrough(port)
                    });
                    delete proxied.createConnection;
                    delete proxied.socketPath;
                    return originalRequest.call(mod, proxied, parsed.callback);
                  } catch (e) {
                    return originalRequest.apply(mod, args);
                  }
                }

                function get() {
                  var args = Array.prototype.slice.call(arguments);
                  var request2 = request.apply(mod, args);
                  try {
                    request2.end();
                  } catch (e) {
                  }
                  return request2;
                }

                mod.request = request;
                mod.get = get;
              }

              function wrapFetch() {
                var original = globalThis.fetch;
                if (typeof original !== 'function' || typeof Response !== 'function') return;
                globalThis.fetch = function (input, init) {
                  var port = safePort();
                  if (!port) return original.apply(globalThis, arguments);
                  var target = typeof input === 'string' ? input : (input && input.url) ? input.url : '';
                  var item;
                  try {
                    var resolved = new URL(target);
                    item = shape(fromUrl(resolved), String(resolved.protocol).toLowerCase() === 'https:');
                  } catch (e) {
                    return original.apply(globalThis, arguments);
                  }
                  if (!item.host || isLocal(item.host)) return original.apply(globalThis, arguments);
                  notice(port);
                  return fetchThrough(target, item, init);
                };
              }

              // undici（global fetch）不认 env、也拿不到 dispatcher，只能自己发一遍再拼 Response；
              // 只处理"命中规则"的域名，其余原样交给原生 fetch。
              function fetchThrough(target, item, init) {
                var method = (init && init.method) || 'GET';
                var body = init ? init.body : null;
                var source = (init && init.headers) || {};
                var headers = {};
                if (source && typeof source.forEach === 'function') source.forEach(function (value, key) { headers[key] = value; });
                else Object.assign(headers, source);
                // 自己拼 Response 时不能声明 gzip：原生 fetch 会解压，我们不会，留着就是乱码
                Object.keys(headers).forEach(function (key) {
                  if (key.toLowerCase() === 'accept-encoding') delete headers[key];
                });
                var hops = 0;

                function once(url) {
                  return new Promise(function (resolve, reject) {
                    var options = fromUrl(new URL(url));
                    var mod = String(options.protocol).toLowerCase() === 'https:' ? https : http;
                    options.method = method;
                    options.headers = Object.assign({}, headers);
                    var request = mod.request(options, function (response) {
                      var location = response.headers ? response.headers.location : null;
                      if (response.statusCode >= 300 && response.statusCode < 400 && location && hops < 5 && (!init || init.redirect !== 'manual')) {
                        hops++;
                        response.resume();
                        resolve(once(new URL(location, url).toString()));
                        return;
                      }
                      var chunks = [];
                      response.on('data', function (chunk) { chunks.push(chunk); });
                      response.on('end', function () {
                        var status = response.statusCode;
                        var payload = status === 204 || status === 205 || status === 304 ? null : Buffer.concat(chunks);
                        try {
                          resolve(new Response(payload, { status: status, statusText: response.statusMessage, headers: response.headers }));
                        } catch (e) {
                          reject(e);
                        }
                      });
                    });
                    request.on('error', reject);
                    if (body) {
                      if (typeof body === 'string' || Buffer.isBuffer(body)) request.end(body);
                      else if (body && typeof body.pipe === 'function') body.pipe(request);
                      else request.end(String(body));
                    } else {
                      request.end();
                    }
                  });
                }

                return once(target);
              }

              function refreshEnv() {
                var port = safePort();
                if (!port) {
                  if (!envSet) return;
                  ['HTTP_PROXY', 'http_proxy', 'HTTPS_PROXY', 'https_proxy', 'ALL_PROXY', 'all_proxy', 'NO_PROXY', 'no_proxy'].forEach(function (key) {
                    try { delete process.env[key]; } catch (e) { process.env[key] = ''; }
                  });
                  envSet = false;
                  return;
                }
                var value = 'http://127.0.0.1:' + port;
                process.env.HTTP_PROXY = value;
                process.env.http_proxy = value;
                process.env.HTTPS_PROXY = value;
                process.env.https_proxy = value;
                process.env.ALL_PROXY = value;
                process.env.all_proxy = value;
                process.env.NO_PROXY = 'localhost,127.0.0.1,::1';
                process.env.no_proxy = process.env.NO_PROXY;
                envSet = true;
              }

              try {
                patch(http, false);
                patch(https, true);
                wrapFetch();
              } catch (e) {
                console.error('shell-proxy patch failed: ' + (e && e.message));
              }
              try {
                refreshEnv();
                setInterval(refreshEnv, 5000);
              } catch (e) {
              }
            })();
            """;
}
