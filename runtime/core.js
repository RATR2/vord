/*
 * Vord core, M2 proof of concept. Evaluated by the Loader in Discord's Hermes runtime BEFORE
 * Discord's own bundle. It intercepts Metro's `__d` (module definition) so every module factory
 * is wrapped, then reports what it sees and applies one real patch to prove the pipeline.
 * Plain ES2015, no build step yet (the Vencord-derived core will replace this).
 */
(function (g) {
  'use strict';
  var nlh = g.nativeLoggingHook;
  function log(msg) {
    try { if (typeof nlh === 'function') nlh('[Vord] ' + msg, 1); } catch (e) {}
  }
  if (g.__vord) { log('core already loaded'); return; }

  var vord = g.__vord = {
    version: '0.0.1-m2',
    modules: Object.create(null), // id -> module.exports, filled as modules finish evaluating
    count: 0,
    listeners: [],
    onModule: function (fn) { vord.listeners.push(fn); }
  };

  function moduleDone(id, exports) {
    vord.modules[id] = exports;
    vord.count++;
    var c = vord.count;
    if (c === 1 || c === 100 || c === 1000 || c === 5000 || c === 10000 || c === 20000) {
      log('modules evaluated: ' + c + ' (last id ' + id + ')');
    }
    for (var i = 0; i < vord.listeners.length; i++) {
      try { vord.listeners[i](id, exports); } catch (e) { log('listener error: ' + e); }
    }
  }

  function wrapDefine(real) {
    return function define(factory, moduleId) {
      var args = arguments;
      if (typeof factory === 'function') {
        var original = factory;
        var wrapped = function () {
          var result = original.apply(this, arguments);
          try {
            var mod = arguments[4]; // Metro: (global, require, importDefault, importAll, module, exports, deps)
            if (mod && typeof mod === 'object') moduleDone(moduleId, mod.exports);
          } catch (e) { /* never break Discord */ }
          return result;
        };
        args = Array.prototype.slice.call(arguments);
        args[0] = wrapped;
      }
      return real.apply(this, args);
    };
  }

  // Metro's bundle does `global.__d = define`. Catch that assignment with an accessor so the
  // wrapper is in place before the first module is defined.
  var realDefine = g.__d;
  var wrapped = realDefine ? wrapDefine(realDefine) : undefined;
  try {
    Object.defineProperty(g, '__d', {
      configurable: true,
      enumerable: true,
      get: function () { return wrapped; },
      set: function (v) {
        realDefine = v;
        wrapped = typeof v === 'function' ? wrapDefine(v) : v;
        log('__d captured');
      }
    });
    log('core ' + vord.version + ' installed, waiting for Metro (__d ' + (realDefine ? 'already present' : 'not yet defined') + ')');
  } catch (e) {
    log('could not hook __d: ' + e);
    return;
  }

  // ---- discovery + first patch -------------------------------------------------------------
  // Find a store-like export with getCurrentUser (Discord's UserStore), report its shape, and
  // wrap getCurrentUser so we can see it being used.
  function keysOf(o) {
    var out = [];
    try {
      var p = o;
      for (var depth = 0; p && depth < 3 && out.length < 24; depth++, p = Object.getPrototypeOf(p)) {
        var names = Object.getOwnPropertyNames(p);
        for (var i = 0; i < names.length && out.length < 24; i++) {
          if (names[i] !== 'constructor') out.push(names[i]);
        }
      }
    } catch (e) {}
    return out.join(',');
  }
  var seen = 0;
  function wrapCurrentUser(label, s) {
    var origGet = s.getCurrentUser;
    var calls = 0;
    s.getCurrentUser = function () {
      var u = origGet.apply(this, arguments);
      calls++;
      if (calls === 1 || calls === 100 || calls === 10000) {
        log('getCurrentUser[' + label + '] call #' + calls + ' -> ' +
            (u ? 'user (id ' + (u.id ? 'set' : 'missing') + ', username ' + (typeof u.username) + ')' : String(u)));
      }
      return u;
    };
  }
  vord.onModule(function (id, exp) {
    if (!exp || seen >= 8) return;
    var cands = [exp, exp.default];
    for (var i = 0; i < cands.length; i++) {
      var s = cands[i];
      if (s && (typeof s === 'object' || typeof s === 'function') &&
          typeof s.getCurrentUser === 'function' && typeof s.getUser === 'function') {
        seen++;
        var label = id + (i ? '.default' : '');
        var proto = Object.getPrototypeOf(s);
        log('UserStore candidate ' + label + ' ctor=' + (s.constructor && s.constructor.name) +
            ' plain=' + (proto === Object.prototype) + ' keys=' + keysOf(s));
        try { wrapCurrentUser(label, s); } catch (e) { log('wrap failed for ' + label + ': ' + e); }
      }
    }
  });


  // Log calls (name, args, return, caller stack) of a function property so we can see who uses it.
  var traced = Object.create(null);
  function brief(v) {
    if (v === null || v === undefined) return String(v);
    var t = typeof v;
    if (t === 'string') return JSON.stringify(v.length > 30 ? v.slice(0, 30) + '..' : v);
    if (t === 'number' || t === 'boolean') return String(v);
    if (t === 'function') return 'fn';
    if (v && typeof v.then === 'function') return 'promise';
    return t === 'object' ? (Array.isArray(v) ? 'array(' + v.length + ')' : '{' + Object.keys(v).slice(0, 5).join(',') + '}') : t;
  }
  function trace(id, obj, name) {
    var key = id + '.' + name;
    if (traced[key]) return;
    traced[key] = 0;
    var orig = obj[name];
    obj[name] = function () {
      var n = ++traced[key];
      var args = [];
      for (var i = 0; i < arguments.length && i < 4; i++) args.push(brief(arguments[i]));
      var res = orig.apply(this, arguments);
      if (n <= 3) {
        var stack = '';
        try { stack = String(new Error().stack).split('\n').slice(2, 7).map(function (l) { return l.trim().replace(/\(.*\)/, ''); }).join(' < '); } catch (e) {}
        log('CALL ' + key + '(' + args.join(', ') + ') -> ' + brief(res) + '  stack: ' + stack);
      }
      return res;
    };
  }

  // ---- discovery: anything named like passkeys / WebAuthn ---------------------------------
  var WANT = /passkey|webauthn|fido|credentialmanager|publickeycredential/i;
  var found = 0;
  vord.onModule(function (id, exp) {
    if (!exp || found >= 80) return;
    var targets = [exp, exp.default];
    for (var t = 0; t < targets.length; t++) {
      var o = targets[t];
      if (!o || (typeof o !== 'object' && typeof o !== 'function')) continue;
      var names;
      try { names = Object.getOwnPropertyNames(o); } catch (e) { continue; }
      var hits = [];
      for (var i = 0; i < names.length && i < 400; i++) {
        if (WANT.test(names[i])) hits.push(names[i]);
      }
      try { if (typeof o === 'function' && o.name && WANT.test(o.name)) hits.push('fn:' + o.name); } catch (e) {}
      if (hits.length) {
        found++;
        log('PASSKEY? module ' + id + (t ? '.default' : '') + ': ' + hits.slice(0, 8).join(','));
        for (var th = 0; th < hits.length; th++) {
          if (hits[th].indexOf('fn:') === 0) continue;
          try { if (typeof o[hits[th]] === 'function') trace(id, o, hits[th]); } catch (e) {}
        }
        for (var h = 0; h < hits.length && h < 4; h++) {
          var member; try { member = o[hits[h]]; } catch (e) { continue; }
          if (member && (typeof member === 'object' || typeof member === 'function')) {
            log('   ' + hits[h] + ' is ' + typeof member + ' ctor=' + (member.constructor && member.constructor.name) +
                ' members=' + keysOf(member));
          }
        }
      }
    }
  });

  // ---- native module discovery: which TurboModules does Discord ask for? ------------------
  var asked = Object.create(null);
  function noteModule(name) {
    if (typeof name !== 'string' || asked[name]) return;
    asked[name] = true;
    log('turbo module requested: ' + name + (WANT.test(name) || /integrity|age/i.test(name) ? '  <== interesting' : ''));
  }
  // Wrap g[name] now if it exists, otherwise as soon as something assigns it.
  function trapGlobal(name, wrapFn) {
    var cur = g[name];
    if (typeof cur === 'function' && !cur.__vord) {
      g[name] = wrapFn(cur);
      log(name + ' hooked (already present)');
      return;
    }
    var val = cur;
    Object.defineProperty(g, name, {
      configurable: true, enumerable: true,
      get: function () { return val; },
      set: function (v) {
        val = (typeof v === 'function' && !v.__vord) ? wrapFn(v) : v;
        log(name + ' assigned and hooked: ' + typeof v);
      }
    });
    log(name + ' not present yet (' + typeof cur + '), trap installed');
  }
  trapGlobal('__turboModuleProxy', function (real) {
    var h = function (name) { noteModule(name); return real.apply(this, arguments); };
    h.__vord = true;
    return h;
  });
  log('globals now: nativeModuleProxy=' + typeof g.nativeModuleProxy + ' RN$Bridgeless=' +
      typeof g['RN$Bridgeless'] + ' __fbBatchedBridge=' + typeof g.__fbBatchedBridge);

  // RN's TurboModuleRegistry is a plain JS module: {get, getEnforcing}. Wrap both so every
  // native module lookup is visible (and later blockable). nativeModuleProxy itself is read-only.
  var registryDone = false;
  vord.onModule(function (id, exp) {
    if (registryDone || !exp) return;
    var cands = [exp, exp.default];
    for (var i = 0; i < cands.length; i++) {
      var r = cands[i];
      if (r && typeof r === 'object' && typeof r.get === 'function' &&
          typeof r.getEnforcing === 'function' && Object.keys(r).length <= 4) {
        registryDone = true;
        var oget = r.get, oenf = r.getEnforcing;
        try {
          r.get = function (name) { noteModule(name + ' [get]'); return oget.apply(this, arguments); };
          r.getEnforcing = function (name) { noteModule(name + ' [getEnforcing]'); return oenf.apply(this, arguments); };
          log('TurboModuleRegistry wrapped at module ' + id + (i ? '.default' : ''));
        } catch (e) { log('registry wrap failed: ' + e); }
        return;
      }
    }
  });

  // ---- plugin: noPasskeys ------------------------------------------------------------------
  // Passkeys / RP ID sign-in cannot work on the renamed package, so report "no passkey support".
  // Discord gates the option with hasAndroidPasskeySupport (module found by name, not by id).
  var noPasskeysCount = 0;
  vord.onModule(function (id, exp) {
    if (noPasskeysCount >= 60 || !exp) return;
    var m = [exp, exp.default];
    for (var i = 0; i < m.length; i++) {
      var o = m[i];
      if (o && typeof o === 'object' && typeof o.hasAndroidPasskeySupport === 'function') {
        if (o.hasAndroidPasskeySupport.__vordNoPasskeys) continue;
        noPasskeysCount++;
        var orig = o.hasAndroidPasskeySupport;
        var logged = 0;
        try {
          o.hasAndroidPasskeySupport = function () {
            var r = orig.apply(this, arguments);
            var isPromise = r && typeof r.then === 'function';
            if (logged++ < 5) log('noPasskeys: hasAndroidPasskeySupport() was ' + (isPromise ? 'a promise' : String(r)) + ' -> false');
            return isPromise ? r.then(function () { return false; }) : false;
          };
          o.hasAndroidPasskeySupport.__vordNoPasskeys = true;
          log('noPasskeys: patched module ' + id + (i ? '.default' : '') + ' plain=' + (Object.getPrototypeOf(o) === Object.prototype));
        } catch (e) { log('noPasskeys: patch failed: ' + e); }
      }
    }
  });

  // ---- React element watcher: find UI components by (surviving) function name ---------------
  var UI_WANT = /passkey|webauthn|securitykey|fido/i;
  var seenUi = Object.create(null);
  var elementCount = 0;
  function typeName(t) {
    try {
      if (!t) return '';
      if (typeof t === 'string') return '';
      var n = t.displayName || t.name;
      if (!n && t.type) n = t.type.displayName || t.type.name;     // React.memo
      if (!n && t.render) n = t.render.displayName || t.render.name; // forwardRef
      return n || '';
    } catch (e) { return ''; }
  }
  var TEXT_WANT = /passkey|security key|pass key|webauthn/i;
  var seenText = Object.create(null);
  function scanProps(n, props) {
    if (!props || typeof props !== 'object') return;
    var keys;
    try { keys = Object.keys(props); } catch (e) { return; }
    for (var i = 0; i < keys.length && i < 30; i++) {
      var v;
      try { v = props[keys[i]]; } catch (e) { continue; }
      var str = typeof v === 'string' ? v : (Array.isArray(v) && v.length === 1 && typeof v[0] === 'string' ? v[0] : null);
      if (str && str.length < 120 && TEXT_WANT.test(str)) {
        var k = n + '|' + keys[i] + '|' + str;
        if (!seenText[k]) { seenText[k] = true; log('UI text: <' + (n || '?') + ' ' + keys[i] + '="' + str + '">'); }
      }
    }
  }
  function onElement(type, props) {
    elementCount++;
    if (elementCount === 1 || elementCount === 500 || elementCount === 5000) log('elements created: ' + elementCount);
    var n = typeName(type);
    if (n && UI_WANT.test(n) && !seenUi[n]) {
      seenUi[n] = true;
      log('UI element: ' + n);
    }
    scanProps(n, props);
    return n;
  }
  // plugin noPasskeys, UI half: the sign-in option is a button whose text is "Log in with Passkey".
  // Elements carrying that text (as text/label/title/children) are not created at all.
  var HIDE_TEXT = /^\s*log in with (a )?passkey\s*$/i;
  var hidden = Object.create(null);
  function hideElement(type, props, args) {
    var hit = null;
    if (props && typeof props === 'object') {
      var fields = ['text', 'label', 'title', 'accessibilityLabel', 'children'];
      for (var i = 0; i < fields.length; i++) {
        var v = props[fields[i]];
        if (Array.isArray(v) && v.length === 1) v = v[0];
        if (typeof v === 'string' && HIDE_TEXT.test(v)) { hit = fields[i]; break; }
      }
    }
    if (!hit && args.length > 2 && typeof args[2] === 'string' && HIDE_TEXT.test(args[2])) hit = 'child';
    if (!hit) return false;
    var n = typeName(type) || '?';
    if (!hidden[n]) { hidden[n] = true; log('noPasskeys: hid <' + n + ' ' + hit + '>'); }
    return true;
  }
  function wrapElementFn(owner, key) {
    var orig = owner[key];
    if (typeof orig !== 'function' || orig.__vordEl) return false;
    var w = function (type, props) {
      onElement(type, props);
      if (hideElement(type, props, arguments)) return null;
      return orig.apply(this, arguments);
    };
    w.__vordEl = true;
    owner[key] = w;
    return true;
  }
  var jsxDone = false, createDone = false;
  vord.onModule(function (id, exp) {
    if (!exp || (jsxDone && createDone)) return;
    var cands = [exp, exp.default];
    for (var i = 0; i < cands.length; i++) {
      var o = cands[i];
      if (!o || typeof o !== 'object') continue;
      if (!jsxDone && typeof o.jsx === 'function' && typeof o.jsxs === 'function' && typeof o.Fragment !== 'undefined') {
        try { wrapElementFn(o, 'jsx'); wrapElementFn(o, 'jsxs'); jsxDone = true; log('jsx runtime hooked at module ' + id); } catch (e) { log('jsx hook failed: ' + e); }
      }
      if (!createDone && typeof o.createElement === 'function' && typeof o.useState === 'function') {
        try { wrapElementFn(o, 'createElement'); createDone = true; log('React.createElement hooked at module ' + id); } catch (e) { log('createElement hook failed: ' + e); }
      }
    }
  });
})(globalThis);
