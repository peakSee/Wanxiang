/*
 * WanXiang 注入式 hook 运行时（阶段 1）。
 *
 * 约束：ES5 语法（无反引号 / 无模板字符串 / 无箭头函数 / 无 let-const），
 * 单 IIFE，幂等（window.__wanxiangHooks 守卫），仅顶层 frame。
 *
 * 桥协议（window.WanxiangBridge，addJavascriptInterface 注入）：
 *   - T.onEvent(json)：异步上报事件 envelope {v, seq, ts, kind, data}，绝不阻塞；
 *   - T.getRules()：同步返回规则 payload JSON（页内同步决策 block/mock 的关键）。
 *
 * 动作优先级：block > mock > redirect > modify_headers > log。
 * 无规则时所有 wrapper 走快速直通（零开销）。
 */
(function () {
  'use strict';

  if (window.__wanxiangHooks) { return; }
  var T = window.WanxiangBridge;
  if (!T) { return; }
  if (window.top !== window.self) { return; } // 仅顶层 frame

  var state = {
    seq: 0,
    nid: 0,
    rules: [],
    matchers: {},
    config: { maxBodyBytes: 131072 },
    scriptsDone: {},
    fnHooks: {}
  };

  // ===== 基础工具 =====

  function report(kind, data) {
    try {
      state.seq++;
      T.onEvent(JSON.stringify({ v: 1, seq: state.seq, ts: Date.now(), kind: kind, data: data }));
    } catch (e) { /* 桥不可用时静默：不影响页面 */ }
  }

  function reportError(stage, e) {
    report('hook_error', { stage: String(stage), message: String((e && e.message) || e) });
  }

  function nextId() {
    state.nid++;
    return 'n' + state.nid + '_' + Date.now().toString(36);
  }

  function truncStr(s, max) {
    s = String(s);
    return s.length > max ? s.slice(0, max) : s;
  }

  function captureStack() {
    try {
      var s = new Error().stack || '';
      return s.length > 2048 ? s.slice(0, 2048) : s;
    } catch (e) { return ''; }
  }

  var stringifySeen = null;
  function safeStringify(v, depth) {
    try {
      if (v === null || v === undefined) { return String(v); }
      var t = typeof v;
      if (t === 'string') { return truncStr(v, 256); }
      if (t === 'number' || t === 'boolean') { return String(v); }
      if (t === 'function') { return '[fn ' + (v.name || 'anonymous') + ']'; }
      if (depth <= 0) { return t === 'object' ? '[...]' : String(v); }
      if (stringifySeen && stringifySeen.indexOf(v) >= 0) { return '[circular]'; }
      if (!stringifySeen) { stringifySeen = []; }
      stringifySeen.push(v);
      try {
        if (Object.prototype.toString.call(v) === '[object Array]') {
          var arr = [];
          for (var i = 0; i < v.length && i < 16; i++) { arr.push(safeStringify(v[i], depth - 1)); }
          return '[' + arr.join(', ') + (v.length > 16 ? ', ...' : '') + ']';
        }
        var parts = [];
        for (var k in v) {
          if (Object.prototype.hasOwnProperty.call(v, k) && parts.length < 16) {
            parts.push(k + ': ' + safeStringify(v[k], depth - 1));
          }
        }
        return '{' + parts.join(', ') + '}';
      } finally {
        stringifySeen.pop();
      }
    } catch (e) { return '[unserializable]'; }
  }

  function argsSummary(args) {
    var parts = [];
    for (var i = 0; i < args.length && i < 8; i++) { parts.push(safeStringify(args[i], 2)); }
    return '(' + parts.join(', ') + ')';
  }

  // ===== URL glob 匹配（结果缓存） =====

  function escapeRe(c) {
    if ('.*+?^${}()|[]\\/'.indexOf(c) >= 0) { return '\\' + c; }
    return c;
  }

  function globToRegExp(g) {
    var r = '';
    for (var i = 0; i < g.length; i++) {
      var c = g.charAt(i);
      if (c === '*') { r += '.*'; }
      else if (c === '?') { r += '.'; }
      else { r += escapeRe(c); }
    }
    return new RegExp('^' + r + '$');
  }

  function matchUrl(url, rule) {
    var re = state.matchers[rule.id];
    if (!re) {
      re = globToRegExp(rule.target);
      state.matchers[rule.id] = re;
    }
    return re.test(url);
  }

  // ===== 规则决策 =====

  function actionsOf(rule) { return rule.actions || []; }

  function ruleMethodOk(rule, method) {
    var m = rule.method || '*';
    return m === '*' || String(m).toUpperCase() === String(method).toUpperCase();
  }

  /** 网络规则决策：返回 null 或 {rule, block, mock, redirect, modify, log, captureBody}。 */
  function netDecide(kind, url, method) {
    if (!state.rules.length) { return null; }
    var out = null;
    for (var i = 0; i < state.rules.length; i++) {
      var r = state.rules[i];
      if (r.type !== kind) { continue; }
      if (!ruleMethodOk(r, method)) { continue; }
      if (!matchUrl(url, r)) { continue; }
      if (!out) {
        out = { rule: r, block: null, mock: null, redirect: null, modify: null, log: false, captureBody: !!r.captureBody };
      }
      var acts = actionsOf(r);
      for (var j = 0; j < acts.length; j++) {
        var a = acts[j];
        if (!a || !a.type) { continue; }
        if (a.type === 'block') { out.block = a; }
        else if (a.type === 'mock') { out.mock = a; }
        else if (a.type === 'redirect') { out.redirect = a; }
        else if (a.type === 'modify_headers') { out.modify = a; }
        else if (a.type === 'log') {
          out.log = true;
          if (a.captureBody === true) { out.captureBody = true; }
          else if (a.captureBody === false) { out.captureBody = false; }
        }
      }
    }
    return out;
  }

  function wsDecide(url) {
    if (!state.rules.length) { return null; }
    for (var i = 0; i < state.rules.length; i++) {
      var r = state.rules[i];
      if (r.type !== 'WEBSOCKET') { continue; }
      if (matchUrl(url, r)) {
        var blocked = false;
        var acts = actionsOf(r);
        for (var j = 0; j < acts.length; j++) { if (acts[j] && acts[j].type === 'block') { blocked = true; } }
        return { rule: r, block: blocked };
      }
    }
    return null;
  }

  function typeRules(type) {
    var out = [];
    for (var i = 0; i < state.rules.length; i++) {
      if (state.rules[i].type === type) { out.push(state.rules[i]); }
    }
    return out;
  }

  function hasAction(rule, name) {
    var acts = actionsOf(rule);
    for (var i = 0; i < acts.length; i++) { if (acts[i] && acts[i].type === name) { return acts[i]; } }
    return null;
  }

  // ===== 上报 =====

  function reportHit(rule, phase, summary, detail) {
    var d = {
      hookId: rule.id,
      type: rule.type,
      target: rule.target,
      phase: String(phase || 'call'),
      summary: truncStr(summary || '', 1024)
    };
    var detailStr = '';
    if (detail !== undefined && detail !== null) {
      detailStr = typeof detail === 'string' ? truncStr(detail, 8192) : safeStringify(detail, 3);
    }
    if (rule.captureStack) {
      var stack = captureStack();
      if (stack) {
        detailStr = detailStr ? detailStr + '\n' + stack : stack;
      }
    }
    if (detailStr) { d.detail = detailStr; }
    report('hit', d);
  }

  function reportNetReq(id, initiator, url, method, headers, body) {
    var d = { id: id, initiator: initiator, url: url, method: method, headers: headers || {} };
    if (body !== null && body !== undefined) { d.body = truncStr(body, state.config.maxBodyBytes); }
    report('net_req', d);
  }

  function reportNetRes(id, url, status, statusText, headers, body, durationMs, ruleId, actionTaken) {
    var d = { id: id, url: url, status: status, statusText: statusText || '', headers: headers || {}, durationMs: durationMs || 0 };
    if (body !== null && body !== undefined) { d.body = truncStr(body, state.config.maxBodyBytes); }
    if (ruleId) { d.ruleId = ruleId; }
    if (actionTaken) { d.actionTaken = actionTaken; }
    report('net_res', d);
  }

  // ===== fetch wrapper =====

  var origFetch = typeof window.fetch === 'function' ? window.fetch : null;

  function headersToPlain(h) {
    var out = {};
    try {
      if (!h) { return out; }
      if (typeof h.forEach === 'function') { // Headers 实例
        h.forEach(function (v, k) { out[k] = truncStr(v, 4096); });
        return out;
      }
      for (var k in h) {
        if (Object.prototype.hasOwnProperty.call(h, k)) { out[k] = truncStr(String(h[k]), 4096); }
      }
    } catch (e) { /* 忽略头部解析失败 */ }
    return out;
  }

  function mergeHeaders(h, mods) {
    var out = headersToPlain(h);
    for (var k in mods) {
      if (!Object.prototype.hasOwnProperty.call(mods, k)) { continue; }
      var v = mods[k];
      if (v === '!') { delete out[k]; }
      else { out[k] = String(v); }
    }
    return out;
  }

  function respHeadersPlain(resp) {
    var out = {};
    try {
      if (resp && resp.headers && typeof resp.headers.forEach === 'function') {
        resp.headers.forEach(function (v, k) { out[k] = truncStr(v, 4096); });
      }
    } catch (e) { /* 忽略 */ }
    return out;
  }

  function isTextualContentType(ct) {
    if (!ct) { return true; } // 未知类型按文本尝试（读取失败会兜底 null）
    return /text|json|javascript|xml|form-urlencoded|plain/i.test(ct);
  }

  function captureFetchBody(resp) {
    try {
      var ct = '';
      try { ct = (resp.headers && resp.headers.get && resp.headers.get('content-type')) || ''; } catch (e) {}
      if (!isTextualContentType(ct)) { return Promise.resolve(null); }
      return resp.clone().text().then(
        function (t) { return t.length > state.config.maxBodyBytes ? t.slice(0, state.config.maxBodyBytes) : t; },
        function () { return null; }
      );
    } catch (e) { return Promise.resolve(null); }
  }

  function rejectedPromise(err) {
    return Promise.reject(err);
  }

  function installFetchWrapper() {
    if (!origFetch) { return; }
    window.fetch = function (input, init) {
      var args = arguments;
      var self = this;
      var url = '';
      try {
        url = typeof input === 'string' ? input : ((input && input.url) || String(input));
      } catch (e) { return origFetch.apply(self, args); }
      var method = 'GET';
      try {
        method = String((init && init.method) || (input && input.method) || 'GET').toUpperCase();
      } catch (e) {}
      var d = netDecide('FETCH', url, method);
      if (!d) { return origFetch.apply(self, args); }

      var id = nextId();
      var t0 = Date.now();
      var action = d.block ? 'block' : (d.mock ? 'mock' : (d.redirect ? 'redirect' : (d.modify ? 'modify_headers' : 'log')));
      reportHit(d.rule, 'request', '[' + method + '] ' + url);

      if (d.block) {
        reportNetRes(id, url, 0, '', {}, null, 0, d.rule.id, 'block');
        return rejectedPromise(new TypeError('Failed to fetch'));
      }

      if (d.mock) {
        var mStatus = d.mock.status || 200;
        reportNetRes(id, url, mStatus, '', d.mock.headers || {}, (d.captureBody ? (d.mock.body || '') : null), 0, d.rule.id, 'mock');
        try {
          return Promise.resolve(new Response(d.mock.body || '', { status: mStatus, headers: d.mock.headers || {} }));
        } catch (e) {
          reportError('fetch.mock', e);
          return origFetch.apply(self, args);
        }
      }

      // redirect / modify：改写参数后走原始 fetch
      var target = d.redirect ? d.redirect.url : url;
      var newInit = {};
      try {
        var src = init || {};
        for (var k in src) { if (Object.prototype.hasOwnProperty.call(src, k)) { newInit[k] = src[k]; } }
        if (input && typeof input === 'object') {
          if (!newInit.method && input.method) { newInit.method = input.method; }
          if (!newInit.headers && input.headers) { newInit.headers = input.headers; }
          if (newInit.body === undefined && input.body !== undefined) { newInit.body = input.body; }
        }
        if (d.modify) { newInit.headers = mergeHeaders(newInit.headers, d.modify.request || {}); }
      } catch (e) {
        reportError('fetch.rewrite', e);
        return origFetch.apply(self, args);
      }

      reportNetReq(id, 'fetch', target, method, headersToPlain(newInit.headers),
        (d.captureBody && typeof newInit.body === 'string') ? newInit.body : null);

      return origFetch.call(self, target, newInit).then(
        function (resp) {
          var ruleId = (d.redirect || d.modify) ? d.rule.id : '';
          var act = (d.redirect || d.modify) ? action : '';
          if (d.captureBody) {
            captureFetchBody(resp).then(function (txt) {
              reportNetRes(id, target, resp.status, resp.statusText || '', respHeadersPlain(resp), txt, Date.now() - t0, ruleId, act);
            });
          } else {
            reportNetRes(id, target, resp.status, resp.statusText || '', respHeadersPlain(resp), null, Date.now() - t0, ruleId, act);
          }
          if (d.log) { reportHit(d.rule, 'response', resp.status + ' ' + truncStr(target, 256)); }
          return resp;
        },
        function (err) {
          reportNetRes(id, target, 0, '', {}, null, Date.now() - t0, '', 'error');
          throw err;
        }
      );
    };
  }

  // ===== XHR wrapper =====

  var OrigXHR = window.XMLHttpRequest;

  function parseRespHeaders(xhr) {
    var out = {};
    try {
      var raw = xhr.getAllResponseHeaders() || '';
      var lines = raw.split('\r\n');
      for (var i = 0; i < lines.length; i++) {
        var idx = lines[i].indexOf(':');
        if (idx > 0) { out[lines[i].slice(0, idx).trim()] = truncStr(lines[i].slice(idx + 1).trim(), 4096); }
      }
    } catch (e) { /* 忽略 */ }
    return out;
  }

  /** XHR mock / block：实例级属性遮蔽 + 合成事件（fetch 优先，此路径最脆）。 */
  function mockXhrState(xhr, status, body, headers) {
    try {
      var props = {
        readyState: 4,
        status: status,
        statusText: status === 0 ? '' : 'OK',
        responseText: body,
        response: body,
        responseURL: xhr.__wanxiangUrl || ''
      };
      for (var k in props) {
        if (Object.prototype.hasOwnProperty.call(props, k)) {
          try {
            Object.defineProperty(xhr, k, { value: props[k], writable: false, configurable: true });
          } catch (e) { /* 某些实现禁止遮蔽则放弃 */ }
        }
      }
      var fire = function (type) {
        try {
          var ev;
          try { ev = new Event(type); }
          catch (e2) { ev = document.createEvent('Event'); ev.initEvent(type, false, false); }
          xhr.dispatchEvent(ev);
        } catch (e) { /* 页面未挂监听 */ }
      };
      fire('readystatechange');
      fire('load');
      fire('loadend');
    } catch (e) {
      reportError('xhr.mock', e);
    }
  }

  function installXhrWrapper() {
    if (!OrigXHR) { return; }
    function WanxiangXHR() {
      var xhr = new OrigXHR();
      var meta = { url: '', method: 'GET', id: '', t0: 0, reqHeaders: {} };

      var origOpen = xhr.open;
      xhr.open = function (m, u) {
        try {
          meta.method = String(m || 'GET').toUpperCase();
          meta.url = String(u || '');
          xhr.__wanxiangUrl = meta.url;
        } catch (e) { /* 保持原行为 */ }
        return origOpen.apply(xhr, arguments);
      };

      var origSetHeader = xhr.setRequestHeader;
      xhr.setRequestHeader = function (k, v) {
        try { meta.reqHeaders[String(k)] = truncStr(String(v), 4096); } catch (e) {}
        return origSetHeader.apply(xhr, arguments);
      };

      var origSend = xhr.send;
      xhr.send = function (body) {
        var d = netDecide('XHR', meta.url, meta.method);
        if (!d) { return origSend.apply(xhr, arguments); }

        var id = nextId();
        meta.id = id;
        meta.t0 = Date.now();
        reportHit(d.rule, 'request', '[' + meta.method + '] ' + meta.url);

        if (d.block) {
          reportNetRes(id, meta.url, 0, '', {}, null, 0, d.rule.id, 'block');
          mockXhrState(xhr, 0, '', {});
          return;
        }

        if (d.mock) {
          var mStatus = d.mock.status || 200;
          reportNetRes(id, meta.url, mStatus, '', d.mock.headers || {}, (d.captureBody ? (d.mock.body || '') : null), 0, d.rule.id, 'mock');
          mockXhrState(xhr, mStatus, d.mock.body || '', d.mock.headers || {});
          return;
        }

        var target = meta.url;
        if (d.redirect || d.modify) {
          try {
            target = d.redirect ? d.redirect.url : meta.url;
            origOpen.call(xhr, meta.method, target); // 重定向：以新 URL 重新 open（保持默认异步）
            xhr.__wanxiangUrl = target;
            if (d.modify) {
              var merged = mergeHeaders(meta.reqHeaders, d.modify.request || {});
              for (var k in merged) {
                if (Object.prototype.hasOwnProperty.call(merged, k)) {
                  try { xhr.setRequestHeader(k, merged[k]); } catch (e) {}
                }
              }
              meta.reqHeaders = merged;
            }
          } catch (e) {
            reportError('xhr.rewrite', e);
          }
        }

        reportNetReq(id, 'xhr', target, meta.method, meta.reqHeaders,
          (d.captureBody && typeof body === 'string') ? body : null);

        var ruleId = (d.redirect || d.modify) ? d.rule.id : '';
        var act = d.redirect ? 'redirect' : (d.modify ? 'modify_headers' : '');

        var onFinish = function () {
          var txt = null;
          if (d.captureBody) {
            try { txt = xhr.responseText; } catch (e) { txt = '[binary]'; }
          }
          reportNetRes(id, target, xhr.status, xhr.statusText || '', parseRespHeaders(xhr), txt, Date.now() - meta.t0, ruleId, act);
          if (d.log) { reportHit(d.rule, 'response', xhr.status + ' ' + truncStr(target, 256)); }
        };
        try { xhr.addEventListener('load', onFinish); } catch (e) {}
        try {
          xhr.addEventListener('error', function () {
            reportNetRes(id, target, 0, '', {}, null, Date.now() - meta.t0, ruleId, 'error');
          });
        } catch (e) {}

        return origSend.apply(xhr, arguments);
      };

      return xhr;
    }
    try { WanxiangXHR.prototype = OrigXHR.prototype; } catch (e) {}
    var consts = ['UNSENT', 'OPENED', 'HEADERS_RECEIVED', 'LOADING', 'DONE'];
    for (var i = 0; i < consts.length; i++) {
      try { WanxiangXHR[consts[i]] = OrigXHR[consts[i]]; } catch (e) {}
    }
    window.XMLHttpRequest = WanxiangXHR;
  }

  // ===== WebSocket wrapper =====

  var OrigWS = window.WebSocket;

  function wsSummary(data) {
    var s = safeStringify(data, 2);
    return truncStr(s, 512);
  }

  function installWebSocketWrapper() {
    if (!OrigWS) { return; }
    function WanxiangWS(url, protocols) {
      var u = String(url);
      var d = wsDecide(u);
      if (d && d.block) {
        reportHit(d.rule, 'connect', u);
        report('ws', { url: u, event: 'blocked', summary: '', detail: '' });
        throw new Error('WebSocket blocked by hook');
      }
      var ws = (arguments.length > 1) ? new OrigWS(url, protocols) : new OrigWS(url);
      if (d) {
        reportHit(d.rule, 'connect', u);
        report('ws', { url: u, event: 'open', summary: '', detail: '' });
        var origSend = ws.send;
        ws.send = function (data) {
          report('ws', { url: u, event: 'send', summary: wsSummary(data), detail: '' });
          return origSend.apply(ws, arguments);
        };
        try {
          ws.addEventListener('message', function (ev) {
            report('ws', { url: u, event: 'message', summary: wsSummary(ev && ev.data), detail: '' });
          });
          ws.addEventListener('close', function () {
            report('ws', { url: u, event: 'close', summary: '', detail: '' });
          });
        } catch (e) { /* 无监听 */ }
      }
      return ws;
    }
    try { WanxiangWS.prototype = OrigWS.prototype; } catch (e) {}
    var wsConsts = ['CONNECTING', 'OPEN', 'CLOSING', 'CLOSED'];
    for (var i = 0; i < wsConsts.length; i++) {
      try { WanxiangWS[wsConsts[i]] = OrigWS[wsConsts[i]]; } catch (e) {}
    }
    window.WebSocket = WanxiangWS;
  }

  // ===== Storage wrapper =====

  function installStorageWrapper() {
    var proto = (window.Storage && window.Storage.prototype) || null;
    if (!proto) { return; }
    var storageKind = function (self) {
      try {
        if (self === window.localStorage) { return 'local'; }
        if (self === window.sessionStorage) { return 'session'; }
      } catch (e) {}
      return '';
    };
    var storageRule = function (kind) {
      var rules = typeRules('STORAGE');
      for (var i = 0; i < rules.length; i++) {
        var t = rules[i].target || '*';
        if (t === '*' || t === kind) { return rules[i]; }
      }
      return null;
    };
    var wrap = function (name, reporter) {
      var orig = proto[name];
      if (typeof orig !== 'function') { return; }
      proto[name] = function () {
        var args = arguments;
        if (!state.rules.length) { return orig.apply(this, args); }
        var rule = storageRule(storageKind(this));
        if (!rule) { return orig.apply(this, args); }
        var blocked = !!hasAction(rule, 'block');
        reportHit(rule, name, argsSummary(args));
        if (blocked && (name === 'setItem' || name === 'removeItem' || name === 'clear')) {
          return undefined; // 阻断写入
        }
        return orig.apply(this, args);
      };
    };
    wrap('setItem', null);
    wrap('getItem', null);
    wrap('removeItem', null);
    wrap('clear', null);
  }

  // ===== Cookie wrapper =====

  function cookieRule(name, isSet) {
    var rules = typeRules('COOKIE');
    for (var i = 0; i < rules.length; i++) {
      var t = rules[i].target || '*';
      if (t === '*' || t === name) { return rules[i]; }
    }
    return null;
  }

  function installCookieWrapper() {
    var desc = null;
    try { desc = Object.getOwnPropertyDescriptor(Document.prototype, 'cookie'); } catch (e) {}
    if (!desc || !desc.get || !desc.set) {
      try { desc = Object.getOwnPropertyDescriptor(HTMLDocument.prototype, 'cookie'); } catch (e) {}
    }
    if (!desc || !desc.get || !desc.set) { return; }
    try {
      Object.defineProperty(document, 'cookie', {
        configurable: true,
        enumerable: true,
        get: function () {
          var v = desc.get.call(document);
          if (!state.rules.length) { return v; }
          var rule = cookieRule('*', false);
          if (rule) { reportHit(rule, 'get', truncStr(v, 256)); }
          return v;
        },
        set: function (v) {
          if (!state.rules.length) { desc.set.call(document, v); return; }
          var s = String(v);
          var eq = s.indexOf('=');
          var name = eq > 0 ? s.slice(0, eq).trim() : s;
          var rule = cookieRule(name, true);
          if (rule) {
            reportHit(rule, 'set', truncStr(s, 512));
            if (hasAction(rule, 'block')) { return; } // 阻断写入
          }
          desc.set.call(document, v);
        }
      });
    } catch (e) {
      reportError('cookie.install', e);
    }
  }

  // ===== Console wrapper =====

  function installConsoleWrapper() {
    var levels = ['log', 'warn', 'error', 'info', 'debug'];
    for (var i = 0; i < levels.length; i++) {
      (function (level) {
        var orig = console[level];
        if (typeof orig !== 'function') { return; }
        console[level] = function () {
          if (state.rules.length) {
            var rules = typeRules('CONSOLE');
            for (var j = 0; j < rules.length; j++) {
              var t = rules[j].target || '*';
              if (t === '*' || (',' + t + ',').indexOf(',' + level + ',') >= 0) {
                reportHit(rules[j], level, argsSummary(arguments));
                if (hasAction(rules[j], 'block')) { return; }
              }
            }
          }
          return orig.apply(console, arguments);
        };
      })(levels[i]);
    }
  }

  // ===== Timer wrapper =====

  function installTimerWrapper() {
    var wrapTimer = function (name) {
      var orig = window[name];
      if (typeof orig !== 'function') { return; }
      window[name] = function () {
        if (!state.rules.length) { return orig.apply(window, arguments); }
        var rules = typeRules('TIMER');
        for (var i = 0; i < rules.length; i++) {
          var t = rules[i].target || '*';
          if (t === '*' || t === name) {
            reportHit(rules[i], name, argsSummary(arguments));
            if (hasAction(rules[i], 'block')) { return 0; }
          }
        }
        return orig.apply(window, arguments);
      };
    };
    wrapTimer('setTimeout');
    wrapTimer('setInterval');
  }

  // ===== Crypto wrapper =====

  function installCryptoWrapper() {
    var cryptoRules = function (t) {
      var rules = typeRules('CRYPTO');
      var out = [];
      for (var i = 0; i < rules.length; i++) {
        var target = rules[i].target || '*';
        if (target === '*' || target === t) { out.push(rules[i]); }
      }
      return out;
    };
    var origRandom = Math.random;
    Math.random = function () {
      var v = origRandom();
      if (state.rules.length) {
        var rules = cryptoRules('random');
        for (var i = 0; i < rules.length; i++) {
          reportHit(rules[i], 'Math.random', String(v));
          if (hasAction(rules[i], 'block')) { return 0; }
        }
      }
      return v;
    };
    try {
      if (window.crypto && window.crypto.getRandomValues) {
        var origGetRandomValues = window.crypto.getRandomValues.bind(window.crypto);
        window.crypto.getRandomValues = function (arr) {
          var rules = cryptoRules('random');
          for (var i = 0; i < rules.length; i++) {
            reportHit(rules[i], 'crypto.getRandomValues', safeStringify(arr, 1));
          }
          return origGetRandomValues(arr);
        };
      }
      if (window.crypto && typeof window.crypto.randomUUID === 'function') {
        var origUuid = window.crypto.randomUUID.bind(window.crypto);
        window.crypto.randomUUID = function () {
          var v = origUuid();
          var rules = cryptoRules('uuid');
          for (var i = 0; i < rules.length; i++) { reportHit(rules[i], 'crypto.randomUUID', v); }
          return v;
        };
      }
    } catch (e) { /* crypto 属性只读则跳过 */ }
  }

  // ===== FUNCTION / METHOD / PROPERTY hook（applyRules 内动态绑定） =====

  function resolvePath(path) {
    var parts = String(path).split('.');
    var owner = window;
    for (var i = 0; i < parts.length - 1; i++) {
      if (!owner) { return null; }
      owner = owner[parts[i]];
    }
    if (!owner) { return null; }
    return { owner: owner, name: parts[parts.length - 1] };
  }

  function restoreFnHooks() {
    for (var path in state.fnHooks) {
      if (!Object.prototype.hasOwnProperty.call(state.fnHooks, path)) { continue; }
      var h = state.fnHooks[path];
      try {
        if (h.wrapped && h.owner[h.name] === h.wrapped) { h.owner[h.name] = h.orig; }
        else if (h.savedDesc) { Object.defineProperty(h.owner, h.name, h.savedDesc); }
        else if (h.hadOwnProp === false) { delete h.owner[h.name]; }
      } catch (e) { /* 恢复失败保持现状 */ }
    }
    state.fnHooks = {};
  }

  function hookFunction(rule) {
    var spot = resolvePath(rule.target);
    if (!spot) { return; }
    var orig = spot.owner[spot.name];
    if (typeof orig !== 'function') { return; }
    var wrapped = function () {
      var replaceAct = hasAction(rule, 'replace');
      var blocked = !!hasAction(rule, 'block');
      if (blocked) {
        reportHit(rule, 'call', argsSummary(arguments));
        throw new Error('blocked by hook ' + rule.id);
      }
      if (replaceAct) {
        reportHit(rule, 'call', argsSummary(arguments));
        try {
          var fn = (new Function('orig', 'return (' + replaceAct.code + ')'))(orig);
          if (typeof fn === 'function') { return fn.apply(this, arguments); }
        } catch (e) { reportError('replace:' + rule.id, e); }
        return orig.apply(this, arguments);
      }
      reportHit(rule, 'call', argsSummary(arguments));
      return orig.apply(this, arguments);
    };
    try {
      spot.owner[spot.name] = wrapped;
      state.fnHooks[rule.target] = { owner: spot.owner, name: spot.name, orig: orig, wrapped: wrapped };
    } catch (e) { /* 只读属性跳过 */ }
  }

  function hookProperty(rule) {
    var spot = resolvePath(rule.target);
    if (!spot) { return; }
    var owner = spot.owner;
    var name = spot.name;
    var desc = Object.getOwnPropertyDescriptor(owner, name);
    var hadOwnProp = !!desc;
    if (!desc) {
      try { desc = Object.getOwnPropertyDescriptor(Object.getPrototypeOf(owner), name); } catch (e) {}
    }
    if (!desc || !desc.get) { return; }
    var fake = hasAction(rule, 'fake_value');
    try {
      Object.defineProperty(owner, name, {
        configurable: true,
        enumerable: desc.enumerable,
        get: function () {
          var v = desc.get.call(owner);
          if (fake) {
            reportHit(rule, 'get', truncStr(fake.value, 256));
            return fake.value;
          }
          reportHit(rule, 'get', truncStr(v, 256));
          return v;
        },
        set: function (v) {
          reportHit(rule, 'set', truncStr(v, 256));
          if (desc.set) { desc.set.call(owner, v); }
        }
      });
      state.fnHooks[rule.target] = { owner: owner, name: name, savedDesc: desc, hadOwnProp: hadOwnProp };
    } catch (e) { /* 定义失败跳过 */ }
  }

  // ===== 持久脚本 =====

  function runScripts(scripts) {
    for (var i = 0; i < scripts.length; i++) {
      var s = scripts[i];
      if (!s || !s.id || state.scriptsDone[s.id]) { continue; }
      state.scriptsDone[s.id] = true;
      try { (new Function(s.code))(); }
      catch (e) { reportError('script:' + (s.id || s.name), e); }
    }
  }

  // ===== applyRules（外部唯一入口） =====

  function applyRules(payloadJson) {
    var p;
    try { p = JSON.parse(payloadJson); }
    catch (e) { reportError('applyRules', e); return; }
    if (!p || p.v !== 1) { return; }
    if (p.config && p.config.maxBodyBytes) { state.config.maxBodyBytes = p.config.maxBodyBytes; }
    state.rules = [];
    var i, r;
    for (i = 0; i < (p.rules || []).length; i++) {
      r = p.rules[i];
      if (r && r.id && r.type && (r.enabled === undefined || r.enabled)) { state.rules.push(r); }
    }
    state.matchers = {};
    restoreFnHooks();
    for (i = 0; i < state.rules.length; i++) {
      r = state.rules[i];
      if (r.type === 'FUNCTION' || r.type === 'METHOD') { hookFunction(r); }
      else if (r.type === 'PROPERTY') { hookProperty(r); }
    }
    runScripts(p.scripts || []);
  }

  // ===== 装配 =====

  installFetchWrapper();
  installXhrWrapper();
  installWebSocketWrapper();
  installStorageWrapper();
  installCookieWrapper();
  installConsoleWrapper();
  installTimerWrapper();
  installCryptoWrapper();

  try {
    Object.defineProperty(window, '__wanxiangHooks', { value: state, enumerable: false, configurable: false });
    Object.defineProperty(window, '__wanxiangApplyRules', { value: applyRules, enumerable: false, configurable: false });
  } catch (e) { /* 不可定义则退化为直接赋值 */ 
    window.__wanxiangHooks = state;
    window.__wanxiangApplyRules = applyRules;
  }

  try {
    applyRules(T.getRules());
  } catch (e) {
    reportError('init', e);
  }
  report('ready', { href: location.href });
})();
