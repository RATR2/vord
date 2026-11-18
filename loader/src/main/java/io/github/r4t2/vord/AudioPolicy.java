package io.github.r4t2.vord;

import android.app.Application;
import android.content.Context;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executor;

/**
 * Keeps Android's default audio routing during voice calls.
 *
 * Discord pins a "communication device" for calls, and Android ties that device's speaker and
 * microphone together, so a Bluetooth or wired headset ends up on its low-quality call profile and
 * the phone microphone can no longer be used. This policy clears the pin whenever it lands on a
 * headset-type device, so Android's normal routing applies again: headphones for output, the
 * phone's own microphone for input. Picking the phone speaker or earpiece is left alone.
 *
 * Mode file {@code <externalFilesDir>/vord/audio.mode}: {@code media} (default), {@code off}, or
 * {@code test-all} (clears every pin, for testing the mechanism without a headset). Needs API 31+.
 */
final class AudioPolicy {
    private static final String TAG = "Vord";
    private static boolean started;

    private AudioPolicy() {}

    static synchronized void start(final Application app) {
        if (started) return;
        started = true;
        if (Build.VERSION.SDK_INT < 31) {
            Log.i(TAG, "audio policy: needs API 31+, inactive");
            return;
        }
        try {
            final AudioManager am = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
            final Handler main = new Handler(Looper.getMainLooper());
            am.addOnCommunicationDeviceChangedListener(new Executor() {
                @Override
                public void execute(Runnable r) {
                    main.post(r);
                }
            }, new AudioManager.OnCommunicationDeviceChangedListener() {
                @Override
                public void onCommunicationDeviceChanged(AudioDeviceInfo device) {
                    enforce(app, am, device);
                }
            });
            am.registerAudioDeviceCallback(new AudioDeviceCallback() {
                @Override
                public void onAudioDevicesAdded(AudioDeviceInfo[] added) {
                    enforce(app, am, am.getCommunicationDevice());
                }

                @Override
                public void onAudioDevicesRemoved(AudioDeviceInfo[] removed) {
                    enforce(app, am, am.getCommunicationDevice());
                }
            }, main);
            Log.i(TAG, "audio policy started, mode=" + mode(app));
        } catch (Throwable t) {
            Log.e(TAG, "audio policy failed to start", t);
        }
    }

    private static void enforce(Application app, AudioManager am, AudioDeviceInfo device) {
        try {
            String mode = mode(app);
            if (device == null || mode.equals("off")) return;
            if (mode.equals("media") && !isHeadset(device.getType())) return;
            Log.i(TAG, "audio policy: clearing communication device type=" + device.getType()
                    + " (" + device.getProductName() + "), mode=" + mode);
            am.clearCommunicationDevice();
        } catch (Throwable t) {
            Log.e(TAG, "audio policy enforce failed", t);
        }
    }

    private static boolean isHeadset(int type) {
        switch (type) {
            case AudioDeviceInfo.TYPE_BLUETOOTH_SCO:
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:
            case AudioDeviceInfo.TYPE_BLE_HEADSET:
            case AudioDeviceInfo.TYPE_BLE_BROADCAST:
            case AudioDeviceInfo.TYPE_HEARING_AID:
            case AudioDeviceInfo.TYPE_WIRED_HEADSET:
            case AudioDeviceInfo.TYPE_WIRED_HEADPHONES:
            case AudioDeviceInfo.TYPE_USB_HEADSET:
                return true;
            default:
                return false;
        }
    }

    private static String mode(Application app) {
        try {
            File dir = app.getExternalFilesDir(null);
            if (dir == null) return "media";
            File f = new File(new File(dir, "vord"), "audio.mode");
            if (!f.isFile()) return "media";
            byte[] b = new byte[32];
            try (FileInputStream in = new FileInputStream(f)) {
                int n = in.read(b);
                return n <= 0 ? "media" : new String(b, 0, n, StandardCharsets.UTF_8).trim();
            }
        } catch (IOException e) {
            return "media";
        }
    }
}
