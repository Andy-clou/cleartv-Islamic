package com.gathin.tvhome;

import android.util.Log;
import android.content.Context;
import android.content.SharedPreferences;
import android.location.Location;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

/** Current conditions from Open-Meteo for the launcher's current Android location. */
final class Weather {
    private static final String TAG = "TvHome.Weather";

    static final class Conditions {
        final int temperature, code;
        Conditions(int temperature, int code) { this.temperature = temperature; this.code = code; }
        String text() { return temperature + "°C  " + description(code); }
    }

    private Weather() { }

    static Conditions cached(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences("weather", Context.MODE_PRIVATE);
        if (!p.contains("temperature")) return null;
        return new Conditions(p.getInt("temperature", 0), p.getInt("code", -1));
    }

    static boolean due(Context ctx, Location location) {
        SharedPreferences p = ctx.getSharedPreferences("weather", Context.MODE_PRIVATE);
        if (!p.contains("temperature") || System.currentTimeMillis() - p.getLong("updated", 0) >= 30L * 60 * 1000) return true;
        float[] distance = new float[1];
        Location.distanceBetween(location.getLatitude(), location.getLongitude(),
                Double.longBitsToDouble(p.getLong("latitude", Double.doubleToLongBits(location.getLatitude()))),
                Double.longBitsToDouble(p.getLong("longitude", Double.doubleToLongBits(location.getLongitude()))), distance);
        return distance[0] >= 10_000;
    }

    static void save(Context ctx, Location location, Conditions conditions) {
        if (conditions == null) return;
        ctx.getSharedPreferences("weather", Context.MODE_PRIVATE).edit()
                .putInt("temperature", conditions.temperature).putInt("code", conditions.code)
                .putLong("updated", System.currentTimeMillis())
                .putLong("latitude", Double.doubleToLongBits(location.getLatitude()))
                .putLong("longitude", Double.doubleToLongBits(location.getLongitude())).apply();
    }

    /** Blocking network request; call from a background thread. */
    static Conditions fetch(double latitude, double longitude) {
        HttpURLConnection connection = null;
        try {
            String url = String.format(Locale.ROOT,
                    "https://api.open-meteo.com/v1/forecast?latitude=%.2f&longitude=%.2f&current=temperature_2m,weather_code&timezone=auto",
                    latitude, longitude);
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(12000);
            connection.setReadTimeout(15000);
            connection.setRequestProperty("Accept", "application/json");
            if (connection.getResponseCode() != 200) throw new IllegalStateException("HTTP " + connection.getResponseCode());
            try (InputStream in = connection.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                int n;
                while ((n = in.read(buffer)) > 0) {
                    out.write(buffer, 0, n);
                    if (out.size() > 64 * 1024) throw new IllegalStateException("respons cuaca terlalu besar");
                }
                JSONObject current = new JSONObject(new String(out.toByteArray(), java.nio.charset.StandardCharsets.UTF_8))
                        .optJSONObject("current");
                if (current == null || !current.has("temperature_2m") || !current.has("weather_code")) {
                    throw new IllegalStateException("kondisi cuaca tidak ditemukan");
                }
                return new Conditions((int) Math.round(current.optDouble("temperature_2m")), current.optInt("weather_code", -1));
            }
        } catch (Exception e) {
            Log.w(TAG, "fetch failed: " + e.getMessage());
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    static String description(int code) {
        if (code == 0) return "Cerah";
        if (code == 1) return "Cerah berawan";
        if (code == 2) return "Berawan sebagian";
        if (code == 3) return "Berawan";
        if (code == 45 || code == 48) return "Berkabut";
        if (code == 51 || code == 53 || code == 55 || code == 56 || code == 57) return "Gerimis";
        if (code == 61 || code == 63 || code == 65 || code == 66 || code == 67 || code == 80 || code == 81 || code == 82) return "Hujan";
        if (code == 71 || code == 73 || code == 75 || code == 77 || code == 85 || code == 86) return "Bersalju";
        if (code >= 95) return "Badai petir";
        return "Cuaca terkini";
    }
}
