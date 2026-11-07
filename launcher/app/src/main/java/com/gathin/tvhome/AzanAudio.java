package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Downloads the theme's default azan recordings to app-local storage when they are missing. */
final class AzanAudio {
    private static final String TAG = "TvHome.AzanAudio";
    private static final String BASE = "https://raw.githubusercontent.com/Andy-clou/cleartv-Islamic/main/themes/islamik/";
    private static final long RETRY_MS = 60L * 60 * 1000;
    private static final int MAX_BYTES = 16 * 1024 * 1024;

    private AzanAudio() { }

    /** Blocking network operation. Call on a worker thread; existing/custom files are never replaced. */
    static boolean ensureDownloaded(Context context) {
        File dir = Config.dir(context);
        if (dir == null || (!missing(dir, "azan.mp3") && !missing(dir, "azan-subuh.mp3"))) return true;
        SharedPreferences prefs = context.getSharedPreferences("azan_audio", Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        if (now - prefs.getLong("last_attempt", 0) < RETRY_MS) return false;
        prefs.edit().putLong("last_attempt", now).apply();
        if (!dir.isDirectory() && !dir.mkdirs()) return false;

        boolean normal = downloadIfMissing(dir, "azan.mp3");
        boolean fajr = downloadIfMissing(dir, "azan-subuh.mp3");
        boolean complete = normal && fajr;
        if (complete) Log.i(TAG, "default azan recordings saved locally");
        return complete;
    }

    private static boolean missing(File dir, String name) {
        File file = new File(dir, name);
        return !file.isFile() || file.length() == 0;
    }

    private static boolean downloadIfMissing(File dir, String name) {
        File target = new File(dir, name);
        if (target.isFile() && target.length() > 0) return true;
        File temp = new File(dir, name + ".download");
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(BASE + name).openConnection();
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(30000);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("Accept", "audio/mpeg, application/octet-stream");
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                throw new IllegalStateException("HTTP " + connection.getResponseCode());
            }
            int declaredLength = connection.getContentLength();
            if (declaredLength > MAX_BYTES) throw new IllegalStateException("audio file too large");
            byte[] header = new byte[3];
            int headerLength = 0;
            try (InputStream in = new BufferedInputStream(connection.getInputStream());
                 FileOutputStream out = new FileOutputStream(temp)) {
                while (headerLength < header.length) {
                    int read = in.read(header, headerLength, header.length - headerLength);
                    if (read < 0) break;
                    headerLength += read;
                }
                if (!validHeader(header, headerLength)) throw new IllegalStateException("response is not an MP3 file");
                out.write(header, 0, headerLength);
                byte[] buffer = new byte[16 * 1024];
                int count;
                int total = headerLength;
                while ((count = in.read(buffer)) != -1) {
                    total += count;
                    if (total > MAX_BYTES) throw new IllegalStateException("audio file too large");
                    out.write(buffer, 0, count);
                }
                out.getFD().sync();
                if (total < 1024) throw new IllegalStateException("audio file is incomplete");
            }
            if (target.exists() && !target.delete()) throw new IllegalStateException("cannot replace incomplete audio");
            if (!temp.renameTo(target)) throw new IllegalStateException("cannot save audio locally");
            Log.i(TAG, "downloaded " + name + " (" + target.length() + " bytes)");
            return true;
        } catch (Exception e) {
            Log.w(TAG, "download failed for " + name + ": " + e.getMessage());
            // Keep a previously present file untouched if a refresh ever fails.
            if (temp.exists()) temp.delete();
            return false;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static boolean validHeader(byte[] header, int length) {
        if (length >= 3 && header[0] == 'I' && header[1] == 'D' && header[2] == '3') return true;
        return length >= 2 && (header[0] & 0xff) == 0xff && (header[1] & 0xe0) == 0xe0;
    }
}
