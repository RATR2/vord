package io.github.r4t2.vord;

import android.app.Application;
import android.util.Log;

import com.facebook.react.bridge.JSBundleLoader;
import com.facebook.react.bridge.JSBundleLoaderDelegate;

import java.io.File;

/**
 * Replaces Discord's {@link JSBundleLoader}. Evaluates Vord's core.js in the same Hermes runtime
 * first, then hands over to the original loader (which may be an OTA bundle, so the real bundle is
 * never touched). Any failure in Vord's part is logged and ignored so Discord still starts.
 */
final class VordBundleLoader extends JSBundleLoader {
    private static final String TAG = "Vord";
    /** Dev override: adb push core.js here to iterate without re-patching the APK. */
    private static final String OVERRIDE = "vord/core.js";
    private static final String ASSET = "assets://vord/core.js";

    private final JSBundleLoader original;
    private final Application app;

    VordBundleLoader(JSBundleLoader original, Application app) {
        this.original = original;
        this.app = app;
    }

    @Override
    public String loadScript(JSBundleLoaderDelegate delegate) {
        try {
            File dir = app.getExternalFilesDir(null);
            File override = dir == null ? null : new File(dir, OVERRIDE);
            if (override != null && override.isFile()) {
                Log.i(TAG, "evaluating core.js from " + override);
                delegate.loadScriptFromFile(override.getAbsolutePath(), "vord-core.js", false);
            } else {
                Log.i(TAG, "evaluating core.js from APK assets");
                delegate.loadScriptFromAssets(app.getAssets(), ASSET, false);
            }
        } catch (Throwable t) {
            Log.e(TAG, "core.js failed, continuing with Discord's own bundle", t);
        }
        return original.loadScript(delegate);
    }
}
