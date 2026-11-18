package io.github.r4t2.vord;

import android.content.pm.ApplicationInfo;
import android.os.Environment;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Dev tool: swap the app's own data (login session included) in and out, so a fresh login screen
 * can be inspected without losing the user's session. Runs inside Discord's process at start-up,
 * before any database is opened, so no other app's data is touched and no root is needed.
 *
 * A command is a one-line file {@code <externalFilesDir>/vord/cmd} (adb push), executed once at
 * the next launch of the main process:
 * <pre>
 *   swap-out NAME   copy live data to vord_profiles/NAME, verify, then clear live data (fresh start)
 *   swap-in  NAME   replace live data with vord_profiles/NAME (the profile is kept)
 * </pre>
 * The outcome is written to {@code vord/cmd.result} next to it. A profile is never overwritten.
 */
final class Profiles {
    private static final String TAG = "Vord";
    private static final String[] FIXED = {"files", "databases", "shared_prefs", "no_backup"};
    /** Large and re-downloadable (Discord's OTA JS bundles), not part of the login. */
    private static final String KEEP_IN_FILES = "otas";

    private Profiles() {}

    static void runPending(ApplicationInfo ai) {
        File result = null;
        try {
            if (ai == null || ai.dataDir == null || !isMainProcess(ai.packageName)) return;
            File dir = new File(Environment.getExternalStorageDirectory(),
                    "Android/data/" + ai.packageName + "/files/vord");
            File cmd = new File(dir, "cmd");
            if (!cmd.isFile()) return;
            result = new File(dir, "cmd.result");
            String line = new String(readAll(cmd), StandardCharsets.UTF_8).trim();
            // Consume first: a command that cannot be deleted must never repeat on every launch.
            if (!cmd.delete() && cmd.exists()) {
                Log.e(TAG, "profile cmd cannot be consumed, ignoring: " + line);
                return;
            }
            String[] parts = line.split("\\s+");
            if (parts.length != 2 || !parts[1].matches("[A-Za-z0-9_.-]{1,40}")) {
                write(result, "ERROR bad command: " + line);
                return;
            }
            File data = new File(ai.dataDir);
            File profile = new File(new File(data, "vord_profiles"), parts[1]);
            String msg;
            if (parts[0].equals("swap-out")) msg = swapOut(data, profile);
            else if (parts[0].equals("swap-in")) msg = swapIn(data, profile);
            else msg = "ERROR unknown command: " + parts[0];
            Log.i(TAG, "profile " + line + " -> " + msg);
            write(result, msg);
        } catch (Throwable t) {
            Log.e(TAG, "profile command failed", t);
            if (result != null) write(result, "ERROR " + t);
        }
    }

    private static String swapOut(File data, File profile) throws IOException {
        if (profile.exists()) return "ERROR profile exists, refusing to overwrite: " + profile.getName();
        File tmp = new File(profile.getParentFile(), profile.getName() + ".tmp");
        deleteTree(tmp, null);
        long copied = 0;
        long source = 0;
        for (File item : items(data)) {
            source += size(item, item.getName().equals("files") ? KEEP_IN_FILES : null);
            copied += copyTree(item, new File(tmp, item.getName()), item.getName().equals("files") ? KEEP_IN_FILES : null);
        }
        long verify = size(tmp, null);
        if (verify != source) {
            deleteTree(tmp, null);
            return "ERROR verify failed (source " + source + " B, copy " + verify + " B), live data untouched";
        }
        if (!tmp.renameTo(profile)) return "ERROR cannot finalise profile, live data untouched";
        for (File item : items(data)) {
            deleteTree(item, item.getName().equals("files") ? KEEP_IN_FILES : null);
        }
        return "OK saved " + source + " B to profile '" + profile.getName() + "' and cleared live data";
    }

    private static String swapIn(File data, File profile) throws IOException {
        if (!profile.isDirectory()) return "ERROR no such profile: " + profile.getName();
        for (File item : items(data)) {
            deleteTree(item, item.getName().equals("files") ? KEEP_IN_FILES : null);
        }
        long n = 0;
        File[] saved = profile.listFiles();
        if (saved != null) {
            for (File item : saved) {
                n += copyTree(item, new File(data, item.getName()), null);
            }
        }
        return "OK restored " + n + " B from profile '" + profile.getName() + "'";
    }

    /** Data directories that make up the app state (never the profiles or caches). */
    private static List<File> items(File data) {
        List<File> out = new ArrayList<>();
        for (String name : FIXED) {
            File f = new File(data, name);
            if (f.exists()) out.add(f);
        }
        File[] all = data.listFiles();
        if (all != null) {
            for (File f : all) {
                if (f.isDirectory() && f.getName().startsWith("app_")) out.add(f);
            }
        }
        return out;
    }

    private static long size(File f, String skipTop) {
        if (f.isFile()) return f.length();
        long n = 0;
        File[] kids = f.listFiles();
        if (kids != null) {
            for (File k : kids) {
                if (skipTop != null && k.getName().equals(skipTop)) continue;
                n += size(k, null);
            }
        }
        return n;
    }

    private static long copyTree(File src, File dst, String skipTop) throws IOException {
        if (src.isDirectory()) {
            if (!dst.isDirectory() && !dst.mkdirs()) throw new IOException("mkdir " + dst);
            long n = 0;
            File[] kids = src.listFiles();
            if (kids != null) {
                for (File k : kids) {
                    if (skipTop != null && k.getName().equals(skipTop)) continue;
                    n += copyTree(k, new File(dst, k.getName()), null);
                }
            }
            return n;
        }
        File parent = dst.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("mkdir " + parent);
        try (InputStream in = new FileInputStream(src); OutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[128 * 1024];
            int r;
            while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
        }
        return src.length();
    }

    private static void deleteTree(File f, String keepTop) {
        if (!f.exists()) return;
        File[] kids = f.isDirectory() ? f.listFiles() : null;
        if (kids != null) {
            for (File k : kids) {
                if (keepTop != null && k.getName().equals(keepTop)) continue;
                deleteTree(k, null);
            }
        }
        if (keepTop == null) f.delete(); // keep the directory itself when something inside is kept
    }

    private static boolean isMainProcess(String pkg) {
        try {
            return new String(readAll(new File("/proc/self/cmdline")), StandardCharsets.UTF_8)
                    .trim().replace("\0", "").equals(pkg);
        } catch (IOException e) {
            return false;
        }
    }

    private static byte[] readAll(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int r;
            while ((r = in.read(buf)) > 0) b.write(buf, 0, r);
            return b.toByteArray();
        }
    }

    private static void write(File f, String text) {
        try (OutputStream o = new FileOutputStream(f)) {
            o.write(text.getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            // result is informational only
        }
    }
}
