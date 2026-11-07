package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Curated Commons photo metadata for Home; image bytes are downloaded only into memory. */
final class HomeWallpapers {
    private static final String TAG = "TvHome.HomeWallpapers";
    private static final String LEGACY_DIR = "home-wallpapers";
    private static final long REFRESH_MS = 7L * 24 * 60 * 60 * 1000;
    private static final long RETRY_MS = 5L * 60 * 1000;
    private static final int MAX_PHOTOS = 40;
    private static final int MAX_IMAGE_BYTES = 4 * 1024 * 1024;
    private static volatile long lastAttemptElapsedRealtime;

    static final class Photo {
        final String title;
        final String imageUrl;
        final String artist;
        final String license;
        final String licenseUrl;
        final String description;

        Photo(String title, String imageUrl, String artist, String license,
              String licenseUrl, String description) {
            this.title = title;
            this.imageUrl = imageUrl;
            this.artist = artist;
            this.license = license;
            this.licenseUrl = licenseUrl;
            this.description = description;
        }

        String displayTitle() { return title; }
    }

    private HomeWallpapers() { }

    /** Clear the old disk cache from earlier versions. New Internet photos are never written to disk. */
    static void clearLegacyCache(Context ctx) {
        java.io.File base = Config.dir(ctx);
        if (base != null) FileCompat.deleteTree(new java.io.File(base, LEGACY_DIR));
    }

    static boolean due(Context ctx, int availablePhotos) {
        SharedPreferences p = prefs(ctx);
        long now = System.currentTimeMillis();
        long elapsed = android.os.SystemClock.elapsedRealtime();
        if (availablePhotos > 0) {
            return now - p.getLong("updated", 0) >= REFRESH_MS
                    && (lastAttemptElapsedRealtime == 0 || elapsed - lastAttemptElapsedRealtime >= RETRY_MS);
        }
        return lastAttemptElapsedRealtime == 0 || elapsed - lastAttemptElapsedRealtime >= RETRY_MS;
    }

    /** Resolves the bundled masjid.json list to Commons image URLs; no photo bytes are written to disk. */
    static List<Photo> refresh(Context ctx) {
        List<Photo> photos = new ArrayList<>();
        SharedPreferences p = prefs(ctx);
        lastAttemptElapsedRealtime = android.os.SystemClock.elapsedRealtime();
        try {
            JSONArray manifest = readManifest(ctx);
            if (manifest.length() == 0 || manifest.length() > MAX_PHOTOS)
                throw new IllegalStateException("invalid masjid.json photo list");

            List<JSONObject> items = new ArrayList<>();
            List<String> titles = new ArrayList<>();
            for (int i = 0; i < manifest.length(); i++) {
                JSONObject item = manifest.optJSONObject(i);
                String file = item != null ? item.optString("commons", "").trim() : "";
                if (file.isEmpty()) continue;
                items.add(item);
                titles.add("File:" + file);
            }
            if (titles.isEmpty()) throw new IllegalStateException("masjid.json has no Commons filenames");

            String endpoint = "https://commons.wikimedia.org/w/api.php?action=query&titles="
                    + URLEncoder.encode(android.text.TextUtils.join("|", titles), "UTF-8")
                    + "&prop=imageinfo&iiprop=url%7Cmime%7Csize%7Cextmetadata&iiurlwidth=1920"
                    + "&iiextmetadatalanguage=en&format=json";
            JSONObject response = new JSONObject(new String(get(endpoint, 2 * 1024 * 1024), StandardCharsets.UTF_8));
            JSONObject query = response.optJSONObject("query");
            JSONObject pages = query != null ? query.optJSONObject("pages") : null;
            if (pages == null) throw new IllegalStateException("Commons returned no masjid.json images");

            java.util.Map<String, JSONObject> byTitle = new java.util.HashMap<>();
            java.util.Iterator<String> keys = pages.keys();
            while (keys.hasNext()) {
                JSONObject page = pages.optJSONObject(keys.next());
                if (page != null) byTitle.put(normalizeTitle(page.optString("title", "")), page);
            }
            for (int i = 0; i < items.size(); i++) {
                JSONObject item = items.get(i);
                JSONObject page = byTitle.get(normalizeTitle(titles.get(i)));
                JSONArray infos = page != null ? page.optJSONArray("imageinfo") : null;
                JSONObject info = infos != null ? infos.optJSONObject(0) : null;
                if (info == null) continue;
                String mime = info.optString("mime", "");
                if (!"image/jpeg".equals(mime) && !"image/png".equals(mime)) continue;
                String imageUrl = info.optString("thumburl", "");
                if (imageUrl.isEmpty()) continue;
                JSONObject ext = info.optJSONObject("extmetadata");
                photos.add(new Photo(item.optString("caption", "Masjid"), imageUrl,
                        item.optString("author", metadataValue(ext, "Artist")),
                        item.optString("license", metadataValue(ext, "LicenseShortName")),
                        metadataValue(ext, "LicenseUrl"), metadataValue(ext, "ImageDescription")));
            }
            if (photos.isEmpty()) throw new IllegalStateException("no usable photos from masjid.json");
            p.edit().putLong("updated", System.currentTimeMillis()).apply();
            Log.i(TAG, "resolved " + photos.size() + " wallpaper URLs from masjid.json");
        } catch (Exception e) {
            Log.w(TAG, "masjid.json wallpaper refresh failed: " + e.getMessage());
            photos.clear();
        }
        return photos;
    }

    /** Downloads one selected photo into a byte array only; callers decode and discard it in memory. */
    static byte[] download(Photo photo) throws Exception {
        return get(photo.imageUrl, MAX_IMAGE_BYTES);
    }

    private static JSONArray readManifest(Context ctx) throws Exception {
        try (InputStream in = ctx.getResources().openRawResource(R.raw.masjid)) {
            JSONObject json = new JSONObject(new String(readAll(in, 64 * 1024), StandardCharsets.UTF_8));
            JSONArray photos = json.optJSONArray("photos");
            if (photos == null) throw new IllegalStateException("masjid.json is missing photos");
            return photos;
        }
    }

    private static String normalizeTitle(String title) {
        return title.replace(' ', '_').toLowerCase(java.util.Locale.ROOT);
    }

    private static byte[] readAll(InputStream in, int maxBytes) throws Exception {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) > 0) {
                out.write(buffer, 0, n);
                if (out.size() > maxBytes) throw new IllegalStateException("masjid.json is too large");
            }
            return out.toByteArray();
        }
    }

    private static String metadataValue(JSONObject ext, String key) {
        JSONObject value = ext != null ? ext.optJSONObject(key) : null;
        return value != null ? value.optString("value", "") : "";
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences("home_wallpapers", Context.MODE_PRIVATE);
    }

    private static byte[] get(String url, int maxBytes) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(12000);
        c.setReadTimeout(20000);
        c.setRequestProperty("Accept", "application/json, image/jpeg, image/png");
        c.setRequestProperty("User-Agent", "ClearTVIslamic/1.0 (Android TV Home wallpaper; https://github.com/Andy-clou/cleartv-Islamic)");
        try {
            if (c.getResponseCode() != 200) throw new IllegalStateException("HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int n;
                while ((n = in.read(buffer)) > 0) {
                    out.write(buffer, 0, n);
                    if (out.size() > maxBytes) throw new IllegalStateException("Commons response too large");
                }
                return out.toByteArray();
            }
        } finally {
            c.disconnect();
        }
    }

    /** Tiny private helper so legacy image files can be removed without introducing another utility dependency. */
    private static final class FileCompat {
        static void deleteTree(java.io.File file) {
            if (file == null) return;
            java.io.File[] children = file.listFiles();
            if (children != null) for (java.io.File child : children) deleteTree(child);
            if (file.exists()) file.delete();
        }
    }
}
