package io.github.r4t2.vord;

import android.app.Activity;
import android.app.AppComponentFactory;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.ContentProvider;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.app.Service;
import android.util.Log;

/**
 * Vord's entry point inside a patched Discord. The Manager points the manifest's
 * {@code appComponentFactory} at this class instead of Discord's own factory, because
 * {@code MainApplication} is final and Discord hard-casts {@code getApplication()} to it, so the
 * Application class itself must stay untouched.
 *
 * Everything is forwarded to Discord's original factory. If that cannot be created, the plain
 * {@link AppComponentFactory} behaviour is used so Discord still starts.
 */
public final class Loader extends AppComponentFactory {
    private static final String TAG = "Vord";
    private static final String ORIGINAL_FACTORY = "com.discord.TTIComponentFactory";

    private final AppComponentFactory delegate;

    public Loader() {
        AppComponentFactory original = null;
        try {
            original = (AppComponentFactory) Class.forName(ORIGINAL_FACTORY)
                    .getDeclaredConstructor().newInstance();
        } catch (Throwable t) {
            Log.e(TAG, "could not create " + ORIGINAL_FACTORY + ", using default factory", t);
        }
        this.delegate = original;
        Log.i(TAG, "Loader created, delegate=" + (original != null));
    }

    @Override
    public ClassLoader instantiateClassLoader(ClassLoader cl, ApplicationInfo aInfo) {
        return delegate != null ? delegate.instantiateClassLoader(cl, aInfo)
                : super.instantiateClassLoader(cl, aInfo);
    }

    @Override
    public Application instantiateApplication(ClassLoader cl, String className)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        Application app = delegate != null ? delegate.instantiateApplication(cl, className)
                : super.instantiateApplication(cl, className);
        Log.i(TAG, "application instantiated: " + className);
        ReactHook.install(cl, app);
        return app;
    }

    @Override
    public Activity instantiateActivity(ClassLoader cl, String className, Intent intent)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        return delegate != null ? delegate.instantiateActivity(cl, className, intent)
                : super.instantiateActivity(cl, className, intent);
    }

    @Override
    public BroadcastReceiver instantiateReceiver(ClassLoader cl, String className, Intent intent)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        return delegate != null ? delegate.instantiateReceiver(cl, className, intent)
                : super.instantiateReceiver(cl, className, intent);
    }

    @Override
    public Service instantiateService(ClassLoader cl, String className, Intent intent)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        return delegate != null ? delegate.instantiateService(cl, className, intent)
                : super.instantiateService(cl, className, intent);
    }

    @Override
    public ContentProvider instantiateProvider(ClassLoader cl, String className)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        return delegate != null ? delegate.instantiateProvider(cl, className)
                : super.instantiateProvider(cl, className);
    }
}
