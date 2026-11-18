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
})(globalThis);
