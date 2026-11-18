package io.github.r4t2.vord;

import android.app.Application;
import android.util.Log;

import com.facebook.react.bridge.JSBundleLoader;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * Gets Vord's JS evaluated before Discord's bundle without touching Discord's obfuscated code.
 *
 * MainApplication holds a kotlin.Lazy that builds the ReactHostImpl (on first use, in onCreate's
 * init thread). Right after the Application is instantiated we swap that lazy's initializer
 * (a public non-final Function0 field) for a proxy that runs the original, then replaces the
 * JSBundleLoader inside the host's DefaultReactHostDelegate with a {@link VordBundleLoader}. Only
 * com.facebook.react.* names (kept by R8) and kotlin.Lazy / Function0 are used, matched by type.
 */
final class ReactHook {
    private static final String TAG = "Vord";

    private ReactHook() {}

    static void install(final ClassLoader cl, final Application app) {
        try {
            final Class<?> lazyType = Class.forName("kotlin.Lazy", false, cl);
            final Class<?> fn0 = Class.forName("kotlin.jvm.functions.Function0", false, cl);
            boolean hooked = false;
            for (Field f : app.getClass().getDeclaredFields()) {
                f.setAccessible(true);
                final Object lazy = f.get(app);
                if (lazy == null || !lazyType.isInstance(lazy)) continue;
                for (Field g : lazy.getClass().getDeclaredFields()) {
                    if (g.getType() != fn0) continue;
                    g.setAccessible(true);
                    final Object init = g.get(lazy);
                    if (init == null) continue;
                    Object proxy = Proxy.newProxyInstance(cl, new Class<?>[] {fn0}, new InvocationHandler() {
                        @Override
                        public Object invoke(Object p, Method m, Object[] a) throws Throwable {
                            if (m.getDeclaringClass() == Object.class) {
                                return m.invoke(init, a);
                            }
                            Object host;
                            try {
                                host = m.invoke(init, a);
                            } catch (java.lang.reflect.InvocationTargetException e) {
                                throw e.getCause();
                            }
                            swapLoader(host, app);
                            return host;
                        }
                    });
                    g.set(lazy, proxy);
                    Log.i(TAG, "hooked lazy " + f.getName() + " (" + lazy.getClass().getName() + ")");
                    hooked = true;
                }
            }
            if (!hooked) Log.w(TAG, "no kotlin.Lazy host initializer found, JS hook inactive");
        } catch (Throwable t) {
            Log.e(TAG, "ReactHook.install failed, JS hook inactive", t);
        }
    }

    private static void swapLoader(Object host, Application app) {
        try {
            if (host == null) return;
            Log.i(TAG, "host created: " + host.getClass().getName());
            Object delegate = null;
            for (Field f : host.getClass().getDeclaredFields()) {
                if (f.getType().getName().equals("com.facebook.react.defaults.DefaultReactHostDelegate")) {
                    f.setAccessible(true);
                    delegate = f.get(host);
                    break;
                }
            }
            if (delegate == null) {
                Log.w(TAG, "no DefaultReactHostDelegate in host");
                return;
            }
            for (Field f : delegate.getClass().getDeclaredFields()) {
                if (f.getType() == JSBundleLoader.class) {
                    f.setAccessible(true);
                    JSBundleLoader original = (JSBundleLoader) f.get(delegate);
                    f.set(delegate, new VordBundleLoader(original, app));
                    Log.i(TAG, "JSBundleLoader replaced (was " + (original == null ? "null" : original.getClass().getName()) + ")");
                    return;
                }
            }
            Log.w(TAG, "no JSBundleLoader field in delegate");
        } catch (Throwable t) {
            Log.e(TAG, "swapLoader failed", t);
        }
    }
}
