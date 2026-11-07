package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/** Prayer times from Kementerian Agama Indonesia through myQuran API v3. */
final class PrayerTimes {
    private static final String TAG = "TvHome.Solat";
    private static final String API = "https://api.myquran.com/v3";
    private static final long RETRY_MS = 60L * 60 * 1000;
    private static final String[] KEYS = {"subuh", "dzuhur", "ashar", "maghrib", "isya"};
    static final String[] NAMES = {"Subuh", "Zuhur", "Asar", "Magrib", "Isya"};
    private static final String[] HIJRI_MONTHS = {"Muharam", "Safar", "Rabiulawal", "Rabiulakhir", "Jumadilawal",
            "Jumadilakhir", "Rajab", "Syakban", "Ramadan", "Syawal", "Zulkaidah", "Zulhijah"};

    static final class City {
        final String id, name, province;
        City(String id, String name, String province) {
            this.id = id;
            this.name = name;
            this.province = province;
        }
        String label() { return province.isEmpty() ? title(name) : title(name) + ", " + title(province); }
    }

    static final class Day {
        final int[] minutes;
        final String hijri;
        Day(int[] minutes, String hijri) { this.minutes = minutes; this.hijri = hijri; }
    }

    private PrayerTimes() { }

    static City currentCity(Context ctx) {
        SharedPreferences p = prefs(ctx);
        String id = p.getString("city_id", ""), name = p.getString("city_name", ""), province = p.getString("province", "");
        return id.isEmpty() || name.isEmpty() ? null : new City(id, name, province);
    }

    /** Reverse-geocodes the device location, then resolves the kabupaten/kota in myQuran. Run off the UI thread. */
    static City resolveCity(Context ctx, Location location) {
        if (location == null || !Geocoder.isPresent()) return null;
        try {
            Geocoder geocoder = new Geocoder(ctx, new Locale("id", "ID"));
            List<Address> addresses = geocoder.getFromLocation(location.getLatitude(), location.getLongitude(), 5);
            List<String> queries = new ArrayList<>();
            if (addresses != null) for (Address a : addresses) {
                addQuery(queries, a.getSubAdminArea());
                addQuery(queries, a.getLocality());
                addQuery(queries, a.getSubLocality());
                if (queries.size() >= 5) break;
            }
            for (String q : queries) {
                String encoded = URLEncoder.encode(q, "UTF-8").replace("+", "%20");
                JSONObject response = new JSONObject(new String(get(API + "/sholat/kabkota/cari/" + encoded,
                        128 * 1024, 10_000, 12_000), StandardCharsets.UTF_8));
                JSONArray data = response.optJSONArray("data");
                if (!response.optBoolean("status") || data == null) continue;
                City match = null;
                for (int i = 0; i < data.length(); i++) {
                    JSONObject item = data.optJSONObject(i);
                    if (item == null) continue;
                    String id = item.optString("id", "").trim();
                    String name = item.optString("lokasi", "").trim();
                    if (id.isEmpty() || name.isEmpty()) continue;
                    City found = new City(id, name, "");
                    if (normalize(name).equals(normalize(q)) || normalize(name).endsWith(" " + normalize(q))) {
                        match = found;
                        break;
                    }
                }
                if (match != null) {
                    SharedPreferences p = prefs(ctx);
                    String old = p.getString("city_id", "");
                    p.edit().putString("city_id", match.id).putString("city_name", match.name)
                            .putString("province", "").apply();
                    if (!old.equals(match.id)) Log.i(TAG, "location resolved to " + match.name);
                    return match;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "could not resolve prayer location: " + e.getMessage());
        }
        return null;
    }

    private static void addQuery(List<String> out, String value) {
        if (value != null && !value.trim().isEmpty() && !out.contains(value.trim())) out.add(value.trim());
    }

    /** A short title-case location label for the weather line. Run on a worker thread. */
    static String displayLocation(Context ctx, Location location) {
        if (location != null && Geocoder.isPresent()) try {
            Geocoder geocoder = new Geocoder(ctx, new Locale("id", "ID"));
            List<Address> addresses = geocoder.getFromLocation(location.getLatitude(), location.getLongitude(), 1);
            if (addresses != null && !addresses.isEmpty()) {
                Address a = addresses.get(0);
                String city = a.getSubAdminArea();
                if (city == null || city.trim().isEmpty()) city = a.getLocality();
                if (city == null || city.trim().isEmpty()) city = a.getSubLocality();
                String province = a.getAdminArea();
                if (city != null && !city.trim().isEmpty()) {
                    String label = title(city);
                    if (province != null && !province.trim().isEmpty() && !province.equalsIgnoreCase(city)) label += ", " + title(province);
                    return label;
                }
            }
        } catch (Exception ignored) { }
        City city = currentCity(ctx);
        return city == null ? "" : city.label();
    }

    private static String title(String text) {
        String[] words = text.trim().toLowerCase(new Locale("id", "ID")).split("\\s+");
        StringBuilder out = new StringBuilder();
        for (String word : words) {
            if (out.length() > 0) out.append(' ');
            if (!word.isEmpty()) out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        if (out.toString().equals("Dki Jakarta")) return "DKI Jakarta";
        if (out.toString().equals("Di Yogyakarta")) return "DI Yogyakarta";
        return out.toString();
    }

    private static String normalize(String text) {
        String s = Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").toUpperCase(Locale.ROOT).trim();
        s = s.replaceFirst("^(KOTA ADMINISTRASI|KABUPATEN ADMINISTRASI|KOTA ADM\\.?|KOTA|KABUPATEN|KAB\\.?)\\s+", "");
        return s.replaceAll("\\s+", " ");
    }

    private static File file(Context ctx, String cityId, String date) {
        File base = Config.dir(ctx);
        return base != null ? new File(base, "sholat-" + cityId + "-" + date + ".json") : null;
    }

    static Day day(Context ctx, String cityId, String date) {
        JSONObject saved = read(file(ctx, cityId, date));
        if (saved == null) return null;
        int[] minutes = new int[KEYS.length];
        for (int i = 0; i < KEYS.length; i++) {
            minutes[i] = minutes(saved.optString(KEYS[i], ""));
            if (minutes[i] < 0) return null;
        }
        return new Day(minutes, saved.optString("hijri", ""));
    }

    static boolean due(Context ctx, String cityId, String date) {
        return day(ctx, cityId, date) == null
                && System.currentTimeMillis() - prefs(ctx).getLong("tried_" + cityId + "_" + date, 0) >= RETRY_MS;
    }

    /** Fetches today's prayer times and Hijri date. Blocking: call from a background thread. */
    static boolean refresh(Context ctx, String cityId, String date) {
        prefs(ctx).edit().putLong("tried_" + cityId + "_" + date, System.currentTimeMillis()).apply();
        File out = file(ctx, cityId, date);
        if (out == null) return false;
        try {
            String tz = URLEncoder.encode(TimeZone.getDefault().getID(), "UTF-8");
            JSONObject response = new JSONObject(new String(get(API + "/sholat/jadwal/" + cityId + "/" + date + "?tz=" + tz, 128 * 1024), StandardCharsets.UTF_8));
            JSONObject data = response.optJSONObject("data");
            JSONObject schedules = data != null ? data.optJSONObject("jadwal") : null;
            JSONObject row = schedules != null ? schedules.optJSONObject(date) : null;
            if (!response.optBoolean("status") || row == null) throw new IllegalStateException("jadwal tidak ditemukan");
            JSONObject saved = new JSONObject();
            for (String key : KEYS) {
                String value = row.optString(key, "");
                if (minutes(value) < 0) throw new IllegalStateException("waktu " + key + " tidak valid");
                saved.put(key, value);
            }
            String hijri = fetchHijri(date, tz);
            saved.put("hijri", hijri);
            File tmp = new File(out.getPath() + ".new");
            try (OutputStream os = new FileOutputStream(tmp)) { os.write(saved.toString().getBytes(StandardCharsets.UTF_8)); }
            if (!tmp.renameTo(out)) throw new IllegalStateException("cache jadwal gagal disimpan");
            if (data != null) {
                SharedPreferences p = prefs(ctx);
                p.edit().putString("city_name", data.optString("kabko", p.getString("city_name", "")))
                        .putString("province", data.optString("prov", "")).apply();
            }
            Log.i(TAG, "saved prayer times for " + cityId + " " + date);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "fetch failed for " + cityId + ": " + e.getMessage());
            return false;
        }
    }

    private static String fetchHijri(String date, String tz) {
        try {
            JSONObject response = new JSONObject(new String(get(API + "/cal/hijr/" + date + "?utc=" + tz, 32 * 1024), StandardCharsets.UTF_8));
            JSONObject data = response.optJSONObject("data");
            JSONObject hijriDate = data != null ? data.optJSONObject("hijr") : null;
            if (hijriDate == null) return "";
            String month = hijriDate.optString("monthName", "");
            String idMonth = hijriMonth(month);
            return hijriDate.optInt("day", 0) + " " + idMonth + " " + hijriDate.optInt("year", 0);
        } catch (Exception e) {
            return "";
        }
    }

    private static String hijriMonth(String name) {
        String n = normalize(name);
        String[] en = {"MUHARRAM", "SAFAR", "RABI AL-AWWAL", "RABI AL-THANI", "JUMADA AL-AWWAL", "JUMADA AL-THANI",
                "RAJAB", "SHA'BAN", "RAMADAN", "SHAWWAL", "DHU AL-QI'DAH", "DHU AL-HIJJAH"};
        for (int i = 0; i < en.length; i++) if (n.equals(en[i]) || n.equals(HIJRI_MONTHS[i].toUpperCase(Locale.ROOT))) return HIJRI_MONTHS[i];
        return name;
    }

    static String key(Calendar c) {
        return String.format(Locale.ROOT, "%04d-%02d-%02d", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH));
    }

    /** Accepts an ISO calendar date from the myQuran schedule response. */
    static String dateKey(String date) {
        String s = date == null ? "" : date.trim();
        return s.matches("\\d{4}-\\d{2}-\\d{2}") ? s : "";
    }

    static int minutes(String hm) {
        String[] p = hm == null ? new String[0] : hm.trim().split(":");
        if (p.length < 2) return -1;
        try {
            int h = Integer.parseInt(p[0]), m = Integer.parseInt(p[1]);
            return h < 0 || h > 23 || m < 0 || m > 59 ? -1 : h * 60 + m;
        } catch (NumberFormatException e) { return -1; }
    }

    static int next(int[] minutes, int now) {
        for (int i = 0; i < minutes.length; i++) if (minutes[i] > now) return i;
        return -1;
    }

    static String countdown(int left) {
        int h = left / 60, m = left % 60;
        if (h == 0) return "dalam " + Math.max(1, m) + " mnt";
        return m == 0 ? "dalam " + h + " jam" : "dalam " + h + " jam " + m + " mnt";
    }

    static String format(int mins, boolean is24) {
        int h = mins / 60, m = mins % 60;
        if (is24) return String.format(Locale.ROOT, "%02d:%02d", h, m);
        return String.format(Locale.ROOT, "%d:%02d", h % 12 == 0 ? 12 : h % 12, m);
    }

    private static JSONObject read(File file) {
        if (file == null || !file.isFile() || file.length() > 16 * 1024) return null;
        try (InputStream in = new FileInputStream(file)) {
            byte[] bytes = new byte[(int) file.length()];
            int n = 0, r;
            while (n < bytes.length && (r = in.read(bytes, n, bytes.length - n)) > 0) n += r;
            return new JSONObject(new String(bytes, 0, n, StandardCharsets.UTF_8));
        } catch (Exception e) { return null; }
    }

    private static byte[] get(String url, int maxBytes) throws Exception {
        return get(url, maxBytes, 15000, 20000);
    }

    private static byte[] get(String url, int maxBytes, int connectTimeout, int readTimeout) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(connectTimeout);
        c.setReadTimeout(readTimeout);
        c.setRequestProperty("Accept", "application/json");
        try {
            if (c.getResponseCode() != 200) throw new IllegalStateException("HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[8192];
                int r;
                while ((r = in.read(buf)) > 0) {
                    out.write(buf, 0, r);
                    if (out.size() > maxBytes) throw new IllegalStateException("response too large");
                }
                return out.toByteArray();
            }
        } finally { c.disconnect(); }
    }

    private static SharedPreferences prefs(Context ctx) { return ctx.getSharedPreferences("prayer_times", Context.MODE_PRIVATE); }
}
