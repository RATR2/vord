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

import android.os.FileObserver;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
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
    private static FileObserver observer; // kept so it is not garbage collected

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
                    writeState(app, am);
                }
            });
            am.registerAudioDeviceCallback(new AudioDeviceCallback() {
                @Override
                public void onAudioDevicesAdded(AudioDeviceInfo[] added) {
                    enforce(app, am, am.getCommunicationDevice());
                    writeState(app, am);
                }

                @Override
                public void onAudioDevicesRemoved(AudioDeviceInfo[] removed) {
                    enforce(app, am, am.getCommunicationDevice());
                    writeState(app, am);
                }
            }, main);
            writeState(app, am);
            watchCommands(app, am);
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

    private static File vordDir(Application app) {
        File d = new File(app.getFilesDir(), "vord");
        if (!d.isDirectory()) d.mkdirs();
        return d;
    }

    private static String mode(Application app) {
        try {
            File internal = new File(vordDir(app), "audio.mode");
            if (internal.isFile()) {
                byte[] b = new byte[32];
                try (FileInputStream in = new FileInputStream(internal)) {
                    int n = in.read(b);
                    if (n > 0) return new String(b, 0, n, StandardCharsets.UTF_8).trim();
                }
            }
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

    // ---- state for the JS chooser (audio.state.json) and commands from it (audio.cmd) ----------

    private static String kind(int t) {
        switch (t) {
            case AudioDeviceInfo.TYPE_BUILTIN_EARPIECE: return "earpiece";
            case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER: return "speaker";
            case AudioDeviceInfo.TYPE_BUILTIN_MIC: return "builtin_mic";
            case AudioDeviceInfo.TYPE_WIRED_HEADSET: return "wired_headset";
            case AudioDeviceInfo.TYPE_WIRED_HEADPHONES: return "wired_headphones";
            case AudioDeviceInfo.TYPE_BLUETOOTH_SCO: return "bt_sco";
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP: return "bt_a2dp";
            case AudioDeviceInfo.TYPE_USB_DEVICE: return "usb_device";
            case AudioDeviceInfo.TYPE_USB_HEADSET: return "usb_headset";
            case AudioDeviceInfo.TYPE_HEARING_AID: return "hearing_aid";
            case AudioDeviceInfo.TYPE_BLE_HEADSET: return "ble_headset";
            case AudioDeviceInfo.TYPE_BLE_SPEAKER: return "ble_speaker";
            case AudioDeviceInfo.TYPE_BLE_BROADCAST: return "ble_broadcast";
            default: return null; // internal/telephony/bus devices are not shown
        }
    }

    static void writeState(Application app, AudioManager am) {
        try {
            JSONObject root = new JSONObject();
            root.put("t", System.currentTimeMillis());
            root.put("mode", mode(app));
            AudioDeviceInfo comm = am.getCommunicationDevice();
            root.put("comm", comm == null ? JSONObject.NULL : new JSONObject()
                    .put("id", comm.getId()).put("kind", String.valueOf(kind(comm.getType()))));
            List<AudioDeviceInfo> commList = am.getAvailableCommunicationDevices();
            JSONArray devices = new JSONArray();
            for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_ALL)) {
                String k = kind(d.getType());
                if (k == null) continue;
                boolean isComm = false;
                for (AudioDeviceInfo c : commList) {
                    if (c.getId() == d.getId()) isComm = true;
                }
                devices.put(new JSONObject()
                        .put("id", d.getId())
                        .put("kind", k)
                        .put("name", String.valueOf(d.getProductName()))
                        .put("addr", d.getAddress())
                        .put("sink", d.isSink())
                        .put("source", d.isSource())
                        .put("comm", isComm));
            }
            root.put("devices", devices);
            File tmp = new File(vordDir(app), "audio.state.json.tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(root.toString().getBytes(StandardCharsets.UTF_8));
            }
            tmp.renameTo(new File(vordDir(app), "audio.state.json"));
        } catch (Throwable t) {
            Log.e(TAG, "audio state write failed", t);
        }
    }

    private static void watchCommands(final Application app, final AudioManager am) {
        final File dir = vordDir(app);
        observer = new FileObserver(dir, FileObserver.CLOSE_WRITE | FileObserver.MOVED_TO) {
            @Override
            public void onEvent(int event, String path) {
                if ("audio.cmd".equals(path)) runCommands(app, am, new File(dir, "audio.cmd"));
            }
        };
        observer.startWatching();
        File pending = new File(dir, "audio.cmd");
        if (pending.isFile()) runCommands(app, am, pending);
    }

    private static synchronized void runCommands(Application app, AudioManager am, File f) {
        try {
            byte[] b = new byte[2048];
            int n;
            try (FileInputStream in = new FileInputStream(f)) {
                n = in.read(b);
            }
            f.delete();
            if (n <= 0) return;
            for (String line : new String(b, 0, n, StandardCharsets.UTF_8).split("\n")) {
                String[] p = line.trim().split("\\s+");
                if (p.length == 0 || p[0].isEmpty()) continue;
                if (p[0].equals("mode") && p.length == 2 && p[1].matches("media|headset|off|test-all")) {
                    try (FileOutputStream out = new FileOutputStream(new File(vordDir(app), "audio.mode"))) {
                        out.write(p[1].getBytes(StandardCharsets.UTF_8));
                    }
                    Log.i(TAG, "audio cmd: mode " + p[1]);
                } else if (p[0].equals("clear")) {
                    am.clearCommunicationDevice();
                    Log.i(TAG, "audio cmd: clear");
                } else if (p[0].equals("pin") && p.length == 2) {
                    int id = Integer.parseInt(p[1]);
                    boolean ok = false;
                    for (AudioDeviceInfo d : am.getAvailableCommunicationDevices()) {
                        if (d.getId() == id) {
                            ok = am.setCommunicationDevice(d);
                        }
                    }
                    Log.i(TAG, "audio cmd: pin " + id + " -> " + ok);
                } else {
                    Log.w(TAG, "audio cmd: ignored '" + line + "'");
                }
            }
            writeState(app, am);
        } catch (Throwable t) {
            Log.e(TAG, "audio cmd failed", t);
        }
    }
}
