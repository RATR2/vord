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
  // Find a store-like export with getCurrentUser (Discord's UserStore) and report its shape.
  var patched = false;
  vord.onModule(function (id, exp) {
    if (patched || !exp) return;
    var cands = [exp, exp.default];
    for (var i = 0; i < cands.length; i++) {
      var s = cands[i];
      if (s && typeof s === 'object' && typeof s.getCurrentUser === 'function' &&
          typeof s.getUser === 'function') {
        log('UserStore candidate at module ' + id + (i ? ' (default export)' : '') +
            ' name=' + (typeof s.getName === 'function' ? s.getName() : '?'));
        var origGet = s.getCurrentUser;
        s.getCurrentUser = function () {
          var u = origGet.apply(this, arguments);
          if (u && typeof u === 'object') {
            if (!vord.userLogged) {
              vord.userLogged = true;
              log('getCurrentUser patched OK, user id present: ' + (u.id ? 'yes' : 'no'));
            }
          }
          return u;
        };
        patched = true;
        return;
      }
    }
  });
})(globalThis);
