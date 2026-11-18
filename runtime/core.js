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
  // Discovery/tracing tools are expensive (they run for every element and module); off by default.
  // Enable with a file <externalFilesDir>/vord/debug (checked by the Loader via the override path).
  var DEBUG = false;
  // Feature switches (for bisecting problems on a device).
  var FEATURES = { commOverride: true, chooser: true, noPasskeys: true, integrity: true, audioTrace: true };

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
    if (!DEBUG || !exp || found >= 80) return;
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

  // ---- native module wrapping ---------------------------------------------------------------
  // Modules returned by TurboModuleRegistry are JSI host objects. A Proxy in front of them can log
  // calls, replace chosen methods, or hide a module entirely. One wrapper per module name.
  var nativeSpecs = Object.create(null);   // name -> {trace: [method|'*'], override: {method: fn(args, orig, mod)}}
  var nativeWrapped = Object.create(null);
  var traceBudget = Object.create(null);
  function briefArg(v) {
    if (v === null || v === undefined) return String(v);
    var t = typeof v;
    if (t === 'string') return JSON.stringify(v.length > 40 ? v.slice(0, 40) + '..' : v);
    if (t === 'number' || t === 'boolean') return String(v);
    if (t === 'function') return 'fn';
    if (t === 'object') {
      try { var j = JSON.stringify(v); return j.length > 120 ? j.slice(0, 120) + '..' : j; } catch (e) { return '{..}'; }
    }
    return t;
  }
  function nativeWrap(name, mod) {
    var spec = nativeSpecs[name];
    if (!spec || !mod) return mod;
    if (nativeWrapped[name]) return nativeWrapped[name];
    if (typeof Proxy !== 'function') return mod;
    var tr = spec.trace || [];
    var all = tr.indexOf('*') >= 0;
    var prox = new Proxy(mod, {
      get: function (target, prop) {
        var v = target[prop];
        if (typeof prop !== 'string') return v;
        var ov = spec.override && spec.override[prop];
        if (ov) {
          return function () {
            var out = ov(arguments, v, target);
            if (all || tr.indexOf(prop) >= 0) log('native ' + name + '.' + prop + '(' + Array.prototype.map.call(arguments, briefArg).join(', ') + ') OVERRIDDEN -> ' + briefArg(out));
            return out;
          };
        }
        if (typeof v === 'function' && spec.cbTrace && spec.cbTrace.indexOf(prop) >= 0) {
          return function () {
            var key = name + '.' + prop;
            var args = Array.prototype.slice.call(arguments);
            for (var ci = 0; ci < args.length; ci++) {
              if (typeof args[ci] === 'function') {
                (function (cb) {
                  args[ci] = function () {
                    var out = Array.prototype.map.call(arguments, function (a) {
                      try { var j = JSON.stringify(a); return j.length > 900 ? j.slice(0, 900) + '..' : j; } catch (e) { return String(a); }
                    }).join(' | ');
                    log('  ' + key + ' callback: ' + out);
                    return cb.apply(this, arguments);
                  };
                })(args[ci]);
              }
            }
            log('native ' + key + '(...) called');
            return v.apply(target, args);
          };
        }
        if (typeof v === 'function' && (all || tr.indexOf(prop) >= 0)) {
          return function () {
            var key = name + '.' + prop;
            var n = traceBudget[key] = (traceBudget[key] || 0) + 1;
            var r = v.apply(target, arguments);
            if (n <= 12) {
              var isP = r && typeof r.then === 'function';
              log('native ' + key + '(' + Array.prototype.map.call(arguments, briefArg).join(', ') + ') -> ' + (isP ? 'promise' : briefArg(r)));
              if (isP) r.then(function (x) { log('  ' + key + ' resolved ' + briefArg(x)); }, function (e) { log('  ' + key + ' REJECTED ' + briefArg(e && e.message ? e.message : e)); });
            }
            return r;
          };
        }
        return typeof v === 'function' ? v.bind(target) : v;
      }
    });
    nativeWrapped[name] = prox;
    log('native module wrapped: ' + name);
    return prox;
  }

  // Phase 1 (observe): what do voice calls and age assurance do natively?
  nativeSpecs['NativeTelecomModule'] = { trace: ['*'] };
  nativeSpecs['NativeSystemTelecomModule'] = { trace: ['*'] };
  // Plugin voiceKeepsMedia: Discord switches Android into MODE_IN_COMMUNICATION for voice
  // (setCommunicationModeOn(true)), which makes Bluetooth headsets fall back to the low-quality call
  // profile and pins input/output to one communication device. Without it, voice output follows
  // normal media routing (A2DP headphones) and the capture source uses the phone's own microphone.
  nativeSpecs['NativeAudioManagerModule'] = {
    trace: ['*'],
    override: {
      setCommunicationModeOn: function (args, orig, target) { return FEATURES.commOverride ? undefined : orig.apply(target, args); }
    }
  };
  nativeSpecs['NativeAudioRouteEmitterModule'] = { trace: ['*'] };
  // Plugin noPlayIntegrity: the re-signed app can never get a valid Play Integrity verdict, and a
  // verdict is never forged. Both calls reject cleanly so Discord takes its normal failure path.
  function integrityUnavailable() {
    var e = new Error('Play Integrity is not available in this modded app');
    e.code = 'E_VORD_INTEGRITY_UNAVAILABLE';
    return Promise.reject(e);
  }
  nativeSpecs['NativePlayIntegrityModule'] = {
    trace: ['*'],
    override: {
      prepareIntegrityToken: integrityUnavailable,
      requestIntegrityToken: integrityUnavailable
    }
  };
  nativeSpecs['NativePlayAgeSignalsModule'] = { trace: ['*'] };
  // (NativeMediaEngineModule is deliberately NOT wrapped: it is called constantly during a call.)

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
          r.get = function (name) { noteModule(name + ' [get]'); return nativeWrap(name, oget.apply(this, arguments)); };
          r.getEnforcing = function (name) { noteModule(name + ' [getEnforcing]'); return nativeWrap(name, oenf.apply(this, arguments)); };
          vord.registry = { get: oget, getEnforcing: oenf, owner: r };
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
  // type -> name; runs per element. MUST be a WeakMap: Discord creates component types inline (a new
  // function every render), and a strong Map kept all of them, and everything they capture, alive.
  var nameCache = typeof WeakMap === 'function' ? new WeakMap() : null;
  function typeName(t) {
    if (!t) return '';
    var tt = typeof t;
    if (tt !== 'function' && tt !== 'object') return ''; // strings, symbols (Fragment) can't be WeakMap keys
    if (nameCache) { var hit = nameCache.get(t); if (hit !== undefined) return hit; }
    var n = '';
    try {
      n = t.displayName || t.name;
      if (!n && t.type) n = t.type.displayName || t.type.name;     // React.memo
      if (!n && t.render) n = t.render.displayName || t.render.name; // forwardRef
    } catch (e) { n = ''; }
    n = n || '';
    if (nameCache) nameCache.set(t, n);
    return n;
  }
  var dumpUntil = 0, dumpBudget = 0;
  var TEXT_WANT = /passkey|security key|pass key|webauthn|change audio|audio output|audio input/i;
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
        if (/select audio output|change audio output/i.test(str)) { dumpUntil = Date.now() + 1800; dumpBudget = 160; }
        if (!seenText[k]) { seenText[k] = true; log('UI text: <' + (n || '?') + ' ' + keys[i] + '="' + str + '">' + (/audio/i.test(str) ? '  props=' + Object.keys(props).join(',') + '  stack: ' + stackTop(12) : '')); }
      }
    }
  }
  function onElement(type, props) {
    elementCount++;
    if (elementCount === 1 || elementCount === 500 || elementCount === 5000) log('elements created: ' + elementCount);
    var n = typeName(type);
    if (n === 'TableRadioGroup' || n === 'TableRadioRow') captureUi(n, type);
    if (DEBUG && n && UI_WANT.test(n) && !seenUi[n]) {
      seenUi[n] = true;
      log('UI element: ' + n);
    }
    if (DEBUG) scanProps(n, props);
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
    if (!hidden[n]) { hidden[n] = true; log('noPasskeys: hid <' + n + ' ' + hit + '>  stack: ' + stackTop(14)); }
    return true;
  }
  function stackTop(n) {
    try {
      return String(new Error().stack).split('\n').slice(2, 2 + n).map(function (l) {
        return l.trim().replace(/^at\s+/, '').replace(/\s*\(.*\)\s*$/, '');
      }).join(' < ');
    } catch (e) { return ''; }
  }
  var OR_TEXT = /^\s*or\s*$/i;
  var orLogged = 0;
  function scanOr(type, props) {
    if (orLogged >= 12 || !props || typeof props !== 'object') return;
    var fields = ['text', 'label', 'title', 'children'];
    for (var i = 0; i < fields.length; i++) {
      var v = props[fields[i]];
      if (Array.isArray(v) && v.length === 1) v = v[0];
      if (typeof v === 'string' && OR_TEXT.test(v)) {
        orLogged++;
        log('OR-text <' + (typeName(type) || '?') + ' ' + fields[i] + '="' + v + '">  stack: ' + stackTop(14));
        return;
      }
    }
  }
  // Wrapper component for a divider that may have to disappear. It always runs the original
  // (so hook order never changes) and only discards the output; one stable type per original.
  var passkeyHiddenEver = false;
  function stackFrames(n) {
    try {
      return String(new Error().stack).split('\n').slice(2, 2 + n).map(function (l) {
        return l.trim().replace(/^at\s+/, '').replace(/\s*\(.*\)\s*$/, '');
      });
    } catch (e) { return []; }
  }
  function hideableFor(orig) {
    if (orig.__vordHideable) return orig.__vordHideable;
    var W = function VordHideable(props) {
      var out = props.__orig(props.__props);
      var hide = props.__ctl.creatorLogin && passkeyHiddenEver;
      if (hide && !props.__ctl.hidLogged) { props.__ctl.hidLogged = true; log('noPasskeys: hid the OrSeparator'); }
      return hide ? null : out;
    };
    try { orig.__vordHideable = W; } catch (e) {}
    return W;
  }
  var rawCreateElement = null; // React.createElement before our hook: the hook would swap the sheet section for the chooser again (endless recursion)
  function wrapElementFn(owner, key) {
    var orig = owner[key];
    if (key === 'createElement' && typeof orig === 'function' && !orig.__vordEl) rawCreateElement = orig;
    if (typeof orig !== 'function' || orig.__vordEl) return false;
    var w = function (type, props) {
      onElement(type, props);
      if (dumpBudget > 0 && Date.now() < dumpUntil) {
        dumpBudget--;
        var pk = props && typeof props === 'object' ? Object.keys(props).filter(function (k) { return k !== 'children' && k !== 'style' && k !== 'ref'; }).slice(0, 12).join(',') : '';
        var tv = '';
        if (props && typeof props === 'object') {
          var tf = ['label', 'title', 'text', 'subLabel', 'children', 'accessibilityLabel'];
          for (var q = 0; q < tf.length; q++) { var vv = props[tf[q]]; if (typeof vv === 'string') { tv += ' ' + tf[q] + '="' + vv.slice(0, 40) + '"'; } }
        }
        var fr = stackFrames(4);
        if (typeName(type) && fr[0] !== 'w') {}
        log('UI+ <' + (typeName(type) || (typeof type === 'string' ? type : '?')) + tv + '> props=' + pk + ' by ' + fr.slice(0, 3).join(' < '));
      }
      if (DEBUG) scanOr(type, props);
      var name = typeName(type);
      if (name === 'VoicePanelAudioPhoneOutputSection' && typeof type === 'function' && vord.React && !vord._chooserBroken) {
        var ca = Array.prototype.slice.call(arguments);
        ca[0] = chooserFor(type);
        return orig.apply(this, ca);
      }
      if (hideElement(type, props, arguments)) {
        passkeyHiddenEver = true;
        return null;
      }
      if (name === 'OrSeparator' && typeof type === 'function' && arguments.length <= 3) {
        // The login screen draws one "or" divider just for the passkey button. Created by Login and
        // rendered later (after Login's render already hid the button), so decide at render time.
        var frames = stackFrames(5);
        var li = frames.indexOf('Login');
        var ctl = { creatorLogin: li >= 0 && li <= 3 };
        if (!ctl.logged) { ctl.logged = true; log('OrSeparator created by ' + frames.slice(0, 4).join(' < ') + ' (creatorLogin=' + ctl.creatorLogin + ')'); }
        var a = Array.prototype.slice.call(arguments);
        a[0] = hideableFor(type);
        a[1] = { __orig: type, __props: props, __ctl: ctl };
        return orig.apply(this, a);
      }
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
        try { wrapElementFn(o, 'createElement'); createDone = true; vord.React = o; log('React.createElement hooked at module ' + id); } catch (e) { log('createElement hook failed: ' + e); }
      }
    }
  });

  // ---- native module access for plugins -------------------------------------------------------
  vord.native = function (name) {
    try { return nativeWrap(name, vord.registry.getEnforcing.call(vord.registry.owner, name)); }
    catch (e) { log('native(' + name + ') failed: ' + e); return null; }
  };
  // Safe call: never lets an exception (or a rejection) escape into Discord's error handler.
  vord.safe = function (fn, label) {
    try {
      var r = fn();
      if (r && typeof r.then === 'function') r.then(null, function (e) { log((label || 'call') + ' rejected: ' + String(e && e.message ? e.message : e).slice(0, 200)); });
      return r;
    } catch (e) { log((label || 'call') + ' threw: ' + String(e && e.message ? e.message : e).slice(0, 200)); return null; }
  };
  var fileProbeDone = false;
  vord.onModule(function () {
    if (fileProbeDone || vord.count < 300 || !vord.registry) return;
    fileProbeDone = true;
    var F = vord.native('NativeFileModule');
    if (!F) return;
    vord.safe(function () {
      return F.writeFile('documents', 'vord/probe.txt', 'probe1', 'utf8').then(function (p) {
        log('FILEPROBE writeFile documents -> ' + p);
        return F.readAsset('file://' + p, 'utf8').then(function (t) { log('FILEPROBE readAsset(file://path) -> ' + t); });
      });
    }, 'fileprobe');
  });

  // ---- plugin: audioChooser ------------------------------------------------------------------
  // Replaces the sheet's "This Phone" output list with an Output list and a Microphone list that
  // reflect what Android really routes. State comes from the Loader (audio.state.json); choices go
  // back as commands (audio.cmd). One pinned "communication device" ties a headset's speaker and mic
  // together (call quality); no pin = Android default routing = headphones out + phone mic.
  var FILES = null; // absolute dir of <filesDir>/vord, learnt from the first write
  var audioCache = null;
  function fileModule() { return vord.registry ? vord.native('NativeFileModule') : null; }
  function writeVordFile(name, text) {
    var F = fileModule();
    if (!F) return null;
    return vord.safe(function () {
      return F.writeFile('documents', 'vord/' + name, text, 'utf8').then(function (p) {
        FILES = String(p).replace(/\/[^\/]*$/, '');
        return p;
      });
    }, 'write ' + name);
  }
  function readAudioState() {
    var F = fileModule();
    if (!F || !FILES) return Promise.resolve(null);
    return vord.safe(function () {
      return F.readAsset('file://' + FILES + '/audio.state.json', 'utf8').then(function (t) {
        try { audioCache = JSON.parse(t); } catch (e) { log('audio state parse failed'); }
        return audioCache;
      });
    }, 'read audio state') || Promise.resolve(null);
  }
  function sendAudio(lines) {
    log('audioChooser: command ' + lines.join(' ; '));
    writeVordFile('audio.cmd', lines.join('\n') + '\n');
  }
  var UI_KINDS = { bt_a2dp: 1, wired_headphones: 1, wired_headset: 1, usb_headset: 1, usb_device: 1, ble_speaker: 1, ble_broadcast: 1 };
  var CALL_KINDS = { bt_sco: 1, ble_headset: 1, wired_headset: 1, usb_headset: 1, hearing_aid: 1 };
  function prettyKind(k) { return ({ bt_a2dp: 'Bluetooth', bt_sco: 'Bluetooth', ble_headset: 'Bluetooth LE', ble_speaker: 'Bluetooth LE', wired_headset: 'Wired headset', wired_headphones: 'Wired headphones', usb_headset: 'USB headset', usb_device: 'USB audio', hearing_aid: 'Hearing aid', ble_broadcast: 'Bluetooth LE' })[k] || k; }
  function buildModel(st) {
    var devs = st.devices || [];
    var comm = st.comm;
    var outs = [], mics = [];
    var commDevs = devs.filter(function (d) { return d.comm; });
    function find(kind) { return commDevs.filter(function (d) { return d.kind === kind; })[0]; }
    var ear = find('earpiece'), spk = find('speaker');
    if (ear) outs.push({ value: 'comm:' + ear.id, label: 'Phone earpiece', sub: 'Phone', cmd: ['mode media', 'pin ' + ear.id] });
    if (spk) outs.push({ value: 'comm:' + spk.id, label: 'Phone speaker', sub: 'Phone', cmd: ['mode media', 'pin ' + spk.id] });
    var seenMedia = {};
    var firstMedia = null;
    devs.forEach(function (d) {
      if (d.sink && UI_KINDS[d.kind] && !seenMedia[d.addr + d.name]) {
        seenMedia[d.addr + d.name] = true;
        var o = { value: 'media:' + d.id, label: d.name, sub: prettyKind(d.kind) + ', music quality', cmd: ['mode media', 'clear'] };
        if (!firstMedia) firstMedia = o;
        outs.push(o);
      }
    });
    commDevs.forEach(function (d) {
      if (CALL_KINDS[d.kind]) {
        outs.push({ value: 'comm:' + d.id, label: d.name, sub: prettyKind(d.kind) + ', call quality', cmd: ['mode headset', 'pin ' + d.id] });
      }
    });
    mics.push({ value: 'phone', label: 'Phone microphone', sub: 'Phone', cmd: null });
    commDevs.forEach(function (d) {
      if (CALL_KINDS[d.kind]) {
        mics.push({ value: 'comm:' + d.id, label: d.name + ' microphone', sub: prettyKind(d.kind) + ', call quality (also moves the output)', cmd: ['mode headset', 'pin ' + d.id] });
      }
    });
    // "pinned" = the device we (or Discord) explicitly set as communication device. The platform's
    // getCommunicationDevice() always returns something (the earpiece by default), so it is not used.
    var pinnedDev = null;
    if (st.pinned !== null && st.pinned !== undefined) {
      pinnedDev = devs.filter(function (d) { return d.id === st.pinned; })[0] || null;
    }
    comm = pinnedDev;
    var outSel, micSel;
    if (pinnedDev) {
      outSel = 'comm:' + pinnedDev.id;
      micSel = (pinnedDev.kind === 'earpiece' || pinnedDev.kind === 'speaker') ? 'phone' : 'comm:' + pinnedDev.id;
    } else {
      outSel = firstMedia ? firstMedia.value : (ear ? 'comm:' + ear.id : (outs[0] && outs[0].value));
      micSel = 'phone';
    }
    return { outs: outs, mics: mics, outSel: outSel, micSel: micSel, comm: comm, firstMedia: firstMedia };
  }
  // If our rows ever fail to render, show Discord's own list instead of crashing the app.
  var boundaryClass = null;
  function boundaryFor(R) {
    if (boundaryClass) return boundaryClass;
    function Boundary(props) { R.Component.call(this, props); this.state = { failed: false }; }
    Boundary.prototype = Object.create(R.Component.prototype);
    Boundary.prototype.constructor = Boundary;
    Boundary.getDerivedStateFromError = function () { return { failed: true }; };
    Boundary.prototype.componentDidCatch = function (err, info) {
      log('chooser render failed, falling back to Discord list: ' + String(err && err.message ? err.message : err).slice(0, 200));
      vord._chooserBroken = true;
    };
    Boundary.prototype.render = function () {
      if (this.state.failed) return (rawCreateElement || R.createElement)(this.props.orig, this.props.props);
      return this.props.children;
    };
    boundaryClass = Boundary;
    return Boundary;
  }
  var chooserPoll = null;
  var chooserReadyShown = false;
  var chooserTypes = [];
  function chooserFor(orig) {
    for (var i = 0; i < chooserTypes.length; i++) if (chooserTypes[i].orig === orig) return chooserTypes[i].comp;
    var comp = function VordAudioChooser(props) {
      var R = vord.React, ui = vord.ui;
      var useState = R.useState, useEffect = R.useEffect;
      var st = useState(audioCache);
      var state = st[0], setState = st[1];
      var pd = useState(null);
      var pending = pd[0], setPending = pd[1];
      var tk = useState(0);
      var setTick = tk[1];
      useEffect(function () {
        var alive = true;
        function poll() { readAudioState().then(function (s) { if (alive && s) setState(s); }); }
        chooserPoll = poll;
        if (!FILES) writeVordFile('.touch', '1');
        poll();
        var t1 = g.setTimeout(poll, 700);
        var n = 0;
        // Until the data and Discord's row components are there, check often and show Discord's own
        // list meanwhile; afterwards refresh every ~2.4 s.
        var id = g.setInterval(function () {
          if (!alive) return;
          n++;
          if (!audioCache) poll();
          if (!uiReady() || !audioCache) setTick(function (x) { return x + 1; });
          else if (n % 8 === 0) poll();
          else if (n === 1 || !chooserReadyShown) { chooserReadyShown = true; setTick(function (x) { return x + 1; }); }
        }, 300);
        return function () { alive = false; chooserPoll = null; g.clearInterval(id); g.clearTimeout(t1); };
      }, []);
      if (!state || !uiReady() || !state.devices) return (rawCreateElement || R.createElement)(orig, props);
      var m = buildModel(state);
      if (!vord._chooserDiag) {
        vord._chooserDiag = true;
        log('chooser diag: Fragment=' + typeof R.Fragment + ' Component=' + typeof R.Component + ' Group=' + typeof ui.TableRadioGroup + '/' + typeName(ui.TableRadioGroup) +
            ' Row=' + typeof ui.TableRadioRow + '/' + typeName(ui.TableRadioRow) + ' outs=' + m.outs.length + ' mics=' + m.mics.length +
            ' sel=' + m.outSel + ',' + m.micSel + ' outs=' + m.outs.map(function (x) { return x.value + ':' + x.label; }).join('|'));
      }
      function choose(list, v, isMic) {
        var item = list.filter(function (x) { return x.value === v; })[0];
        if (!item) return;
        // Show the choice at once; the real state (read back from Android) replaces it shortly after.
        var wantsHeadset = /^comm:/.test(v) && list.some(function (x) { return x.value === v && /call quality/.test(x.sub); });
        var optOut = m.outSel, optMic = m.micSel;
        if (isMic) {
          if (v === 'phone') { optMic = 'phone'; if (/^comm:/.test(m.outSel) && m.comm && m.comm.kind !== 'earpiece' && m.comm.kind !== 'speaker' && m.firstMedia) optOut = m.firstMedia.value; }
          else { optMic = v; optOut = v; }
        } else {
          optOut = v;
          optMic = wantsHeadset ? v : 'phone';
        }
        setPending({ out: optOut, mic: optMic });
        g.setTimeout(function () { if (chooserPoll) chooserPoll(); }, 150);
        g.setTimeout(function () { if (chooserPoll) chooserPoll(); }, 500);
        g.setTimeout(function () { if (chooserPoll) chooserPoll(); setPending(null); }, 1400);
        var cmd = item.cmd;
        if (isMic && v === 'phone') {
          // phone microphone: leave a headset's call profile and use default routing again
          if (m.comm && m.comm.kind !== 'earpiece' && m.comm.kind !== 'speaker') cmd = ['mode media', 'clear'];
          else return;
        }
        if (cmd) sendAudio(cmd);
      }
      function rows(list) {
        return list.map(function (x) { return R.createElement(ui.TableRadioRow, { key: x.value, value: x.value, label: x.label, subLabel: x.sub }); });
      }
      var shownOut = pending ? pending.out : m.outSel;
      var shownMic = pending ? pending.mic : m.micSel;
      return R.createElement(boundaryFor(R), { orig: orig, props: props }, R.createElement(R.Fragment, null,
        R.createElement(ui.TableRadioGroup, { title: 'Output', value: shownOut, onChange: function (v) { choose(m.outs, v, false); }, hasIcons: false }, rows(m.outs)),
        R.createElement(ui.TableRadioGroup, { title: 'Microphone', value: shownMic, onChange: function (v) { choose(m.mics, v, true); }, hasIcons: false }, rows(m.mics))));
    };
    chooserTypes.push({ orig: orig, comp: comp });
    return comp;
  }
  // Design-system components: only trust something whose function name really is the component's
  // (an i18n strings module also has keys called TableRadioGroup, holding message getters).
  vord.ui = {};
  function uiReady() { return !!(vord.ui && vord.ui.TableRadioGroup && vord.ui.TableRadioRow); }
  function captureUi(name, type) {
    if ((name === 'TableRadioGroup' || name === 'TableRadioRow') && (typeof type === 'function' || typeof type === 'object') && type && !vord.ui[name]) {
      vord.ui[name] = type;
      log('audioChooser: captured ' + name + ' from a rendered element');
    }
  }
  vord.onModule(function (id, exp) {
    if (!DEBUG || uiReady() || !exp) return;
    var cands = [exp, exp.default];
    for (var i = 0; i < cands.length; i++) {
      var o = cands[i];
      if (o && typeof o === 'object') {
        var a, b;
        try { a = o.TableRadioGroup; b = o.TableRadioRow; } catch (e) { continue; }
        if (a && b && typeName(a) === 'TableRadioGroup' && typeName(b) === 'TableRadioRow') {
          vord.ui.TableRadioGroup = a; vord.ui.TableRadioRow = b;
          log('audioChooser: design components found in module ' + id);
          return;
        }
      }
    }
  });
  // warm the state cache early so the first sheet open already has data
  vord.onModule(function () {
    if (vord._audioWarm || vord.count < 400 || !vord.registry) return;
    vord._audioWarm = true;
    writeVordFile('.touch', '1');
    var tries = 0;
    (function again() { readAudioState().then(function (s) { if (!s && tries++ < 5 && typeof g.setTimeout === 'function') g.setTimeout(again, 1000); else if (s) log('audioChooser: state loaded, ' + (s.devices || []).length + ' devices'); }); })();
  });

  // ---- memory heartbeat (cheap): lets us see JS heap growth in the log -----------------------------
  var hbStarted = false;
  vord.onModule(function () {
    if (hbStarted || vord.count < 600 || typeof g.setInterval !== 'function') return;
    hbStarted = true;
    g.setInterval(function () {
      try {
        var hi = g.HermesInternal && g.HermesInternal.getInstrumentedStats ? g.HermesInternal.getInstrumentedStats() : null;
        var heap = hi ? Math.round((hi.js_heapSize || 0) / 1048576) + 'MB heap, ' + Math.round((hi.js_allocatedBytes || 0) / 1048576) + 'MB alloc, ' + (hi.js_numGCs || 0) + ' gcs' : 'n/a';
        log('hb: ' + heap + ' | elements ' + elementCount + ' modules ' + vord.count);
      } catch (e) { log('hb failed: ' + e); }
    }, 5000);
  });
})(globalThis);
