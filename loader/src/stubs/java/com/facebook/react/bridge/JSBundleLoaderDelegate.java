package com.facebook.react.bridge;

import android.content.res.AssetManager;

/** Compile-time stub only (never packaged): the real interface lives in Discord's own dex. */
public interface JSBundleLoaderDelegate {
    void loadScriptFromAssets(AssetManager assetManager, String assetURL, boolean loadSynchronously);

    void loadScriptFromFile(String fileName, String sourceURL, boolean loadSynchronously);

    void loadSplitBundleFromFile(String fileName, String sourceURL);

    void setSourceURLs(String deviceURL, String remoteURL);
}
