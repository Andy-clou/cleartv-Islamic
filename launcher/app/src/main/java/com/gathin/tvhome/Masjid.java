package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Mosque photos listed in the built-in masjid.json manifest, downloaded to app storage with Indonesian captions and credits
 * for the Islamic screensaver. Home resolves the same manifest's URLs and holds images in memory.
 */
final class Masjid {
    static final String DIR = "masjid";
    private static final long RETRY_MS = 60L * 60 * 1000;
    private static final long REFRESH_MS = 30L * 24 * 60 * 60 * 1000;

    private Masjid() {
    }

    static File dir(Context ctx) {
        File base = Config.dir(ctx);
        return base != null ? new File(base, DIR) : null;
    }

    /** The photos, in file order; empty when the theme has none. */
    static List<File> list(Context ctx) {
        List<File> out = new ArrayList<>();
        File d = dir(ctx);
        File[] files = d != null ? d.listFiles() : null;
        if (files == null) return out;
        for (File f : files) {
            String n = f.getName().toLowerCase(java.util.Locale.ROOT);
            if (f.isFile() && f.length() > 0 && (n.endsWith(".jpg") || n.endsWith(".jpeg"))) out.add(f);
        }
        Collections.sort(out);
        return out;
    }

    /** captions.json from the photos' folder, or an empty object. */
    static JSONObject captions(File dir) {
        File f = dir != null ? new File(dir, "captions.json") : null;
        if (f == null || !f.isFile() || f.length() > 64 * 1024) return new JSONObject();
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[(int) f.length()];
            int n = 0, r;
            while (n < buf.length && (r = in.read(buf, n, buf.length - n)) > 0) n += r;
            return new JSONObject(new String(buf, 0, n, StandardCharsets.UTF_8));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    static boolean due(Context ctx) {
        File target = dir(ctx);
        int expected = 0;
        try (InputStream in = ctx.getResources().openRawResource(R.raw.masjid)) {
            byte[] bytes = readAll(in, 64 * 1024);
            expected = new JSONObject(new String(bytes, StandardCharsets.UTF_8)).optJSONArray("photos").length();
        } catch (Exception e) { return false; }
        SharedPreferences p = ctx.getSharedPreferences("masjid_photos", Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        if (now - p.getLong("tried", 0) < RETRY_MS && now - p.getLong("updated", 0) >= RETRY_MS) return false;
        return list(ctx).size() < expected || now - p.getLong("updated", 0) >= REFRESH_MS;
    }

    /** Downloads the bundled Wikimedia list into local app storage. Blocking: call from a worker thread. */
    static boolean refresh(Context ctx) {
        File target = dir(ctx);
        if (target == null) return false;
        SharedPreferences prefs = ctx.getSharedPreferences("masjid_photos", Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        if (!due(ctx)) return false;
        prefs.edit().putLong("tried", now).apply();
        File fresh = new File(target.getParentFile(), DIR + ".new");
        File old = new File(target.getParentFile(), DIR + ".old");
        deleteAll(fresh);
        if (!fresh.mkdirs()) return false;
        try {
            JSONObject list = new JSONObject(new String(readAsset(ctx), StandardCharsets.UTF_8));
            JSONArray photos = list.optJSONArray("photos");
            if (photos == null || photos.length() == 0 || photos.length() > 40) throw new IllegalStateException("invalid photo list");
            JSONObject credits = new JSONObject();
            int downloaded = 0;
            for (int i = 0; i < photos.length(); i++) {
                JSONObject photo = photos.optJSONObject(i);
                if (photo == null) continue;
                String commons = photo.optString("commons", "").trim();
                if (commons.isEmpty()) continue;
                String query = "https://commons.wikimedia.org/w/api.php?action=query&titles="
                        + URLEncoder.encode("File:" + commons, "UTF-8")
                        + "&prop=imageinfo&iiprop=url&iiurlwidth=1280&format=json";
                JSONObject response = new JSONObject(new String(get(query, 128 * 1024), StandardCharsets.UTF_8));
                JSONObject pages = response.optJSONObject("query") != null ? response.getJSONObject("query").optJSONObject("pages") : null;
                if (pages == null || pages.length() == 0) continue;
                JSONObject page = pages.getJSONObject(pages.keys().next());
                JSONArray info = page.optJSONArray("imageinfo");
                if (info == null || info.length() == 0) continue;
                String imageUrl = info.optJSONObject(0).optString("thumburl", "");
                if (imageUrl.isEmpty()) continue;
                byte[] image = get(imageUrl, 4 * 1024 * 1024);
                if (image.length < 2048) continue;
                String name = String.format(java.util.Locale.ROOT, "%02d.jpg", i);
                try (OutputStream out = new FileOutputStream(new File(fresh, name))) { out.write(image); }
                JSONObject credit = new JSONObject();
                credit.put("caption", photo.optString("caption", "Masjid"));
                credit.put("credit", "Foto: " + photo.optString("author", "Wikimedia Commons") + ", "
                        + photo.optString("license", "" ) + ", Wikimedia Commons");
                credits.put(name, credit);
                downloaded++;
                // Commons asks clients to pace automated requests; keep the work on this worker thread.
                if (i + 1 < photos.length()) Thread.sleep(1100L);
            }
            if (downloaded == 0) throw new IllegalStateException("no mosque photos downloaded");
            try (OutputStream out = new FileOutputStream(new File(fresh, "captions.json"))) {
                out.write(credits.toString(1).getBytes(StandardCharsets.UTF_8));
            }
            deleteAll(old);
            if (target.exists() && !target.renameTo(old)) throw new IllegalStateException("could not preserve old photos");
            if (!fresh.renameTo(target)) {
                if (old.exists()) old.renameTo(target);
                throw new IllegalStateException("could not activate downloaded photos");
            }
            deleteAll(old);
            prefs.edit().putLong("updated", System.currentTimeMillis()).apply();
            Log.i("TvHome.Masjid", "saved " + downloaded + " mosque photos locally");
            return true;
        } catch (Exception e) {
            Log.w("TvHome.Masjid", "download failed: " + e.getMessage());
            deleteAll(fresh);
            return false;
        }
    }

    private static byte[] readAsset(Context ctx) throws Exception {
        try (InputStream in = ctx.getResources().openRawResource(R.raw.masjid)) { return readAll(in, 64 * 1024); }
    }

    private static byte[] readAll(InputStream in, int maxBytes) throws Exception {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) > 0) {
                out.write(buffer, 0, n);
                if (out.size() > maxBytes) throw new IllegalStateException("response too large");
            }
            return out.toByteArray();
        }
    }

    private static byte[] get(String url, int maxBytes) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setRequestProperty("Accept", "application/json, image/jpeg");
        c.setRequestProperty("User-Agent", "ClearTVIslamic/1.0 (Android TV screensaver; https://github.com/Andy-clou/cleartv-Islamic)");
        try {
            if (c.getResponseCode() != 200) throw new IllegalStateException("HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream()) { return readAll(in, maxBytes); }
        } finally { c.disconnect(); }
    }

    private static void deleteAll(File dir) {
        File[] files = dir.listFiles();
        if (files != null) for (File file : files) {
            if (file.isDirectory()) deleteAll(file);
            else file.delete();
        }
        dir.delete();
    }

    /**
     * What Home's shelf says about a photo, in PlaceWallpapers.describe's shape: the mosque, the
     * photo's credit, the city. "Masjid Nabawi, Madinah" gives "Masjid Nabawi" and "Madinah".
     */
    static String[] describe(File photo, JSONObject captions) {
        if (photo == null) return null;
        JSONObject c = captions.optJSONObject(photo.getName());
        if (c == null) return null;
        String caption = c.optString("caption").trim();
        if (caption.isEmpty()) return null;
        int comma = caption.lastIndexOf(',');
        String name = comma > 0 ? caption.substring(0, comma).trim() : caption;
        String place = comma > 0 ? caption.substring(comma + 1).trim() : "";
        return new String[]{name, c.optString("credit").trim(), place};
    }
}
