package com.slay.tvlauncher;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Room lighting: drives a Wi-Fi LED strip running WLED (the free, popular
 * ESP32/ESP8266 firmware) over its simple JSON API. Modes:
 *   sync    - strip follows the colour of the focused app
 *   static  - one colour you pick on the phone website
 *   breathe - the picked colour, slowly pulsing (WLED "Breathe" effect)
 *   off
 */
final class Ambient {
    private static final ExecutorService EX = Executors.newSingleThreadExecutor();
    private static final AtomicReference<String[]> PENDING = new AtomicReference<>();

    private static SharedPreferences p(Context c) {
        return c.getSharedPreferences("slay", Context.MODE_PRIVATE);
    }

    static String ip(Context c) { return p(c).getString("led_ip", ""); }
    static String mode(Context c) { return p(c).getString("amb_mode", "sync"); }
    static int staticColor(Context c) { return p(c).getInt("amb_color", 0xFF3B82F6); }
    static int bri(Context c) { return p(c).getInt("amb_bri", 200); }

    /** Focus changed in the launcher. */
    static void onFocusColor(Context c, int color) {
        if ("sync".equals(mode(c))) send(c, color, 0);
    }

    /** Aurora screensaver colour cycling. */
    static void cycle(Context c, int color) {
        if (!"off".equals(mode(c))) send(c, color, 0);
    }

    /** Re-apply whatever the current mode says. */
    static void applyMode(Context c) {
        String m = mode(c);
        if ("static".equals(m)) send(c, staticColor(c), 0);
        else if ("breathe".equals(m)) send(c, staticColor(c), 2);
        else if ("sync".equals(m)) send(c, 0xFFFFA45C, 0);
        else sendRaw(c, "{\"on\":false}");
    }

    private static void send(Context c, int color, int fx) {
        int r = (color >> 16) & 0xFF, g = (color >> 8) & 0xFF, b = color & 0xFF;
        sendRaw(c, String.format(Locale.US,
                "{\"on\":true,\"bri\":%d,\"transition\":8,\"seg\":[{\"fx\":%d,\"col\":[[%d,%d,%d]]}]}",
                bri(c), fx, r, g, b));
    }

    private static void sendRaw(Context c, String json) {
        final String ip = ip(c);
        if (ip.isEmpty()) return;
        PENDING.set(new String[]{ip, json});
        EX.execute(new Runnable() {
            @Override public void run() {
                String[] job = PENDING.getAndSet(null);   // only the newest command is sent
                if (job == null) return;
                HttpURLConnection h = null;
                try {
                    h = (HttpURLConnection) new URL("http://" + job[0] + "/json/state").openConnection();
                    h.setConnectTimeout(1500);
                    h.setReadTimeout(1500);
                    h.setRequestMethod("POST");
                    h.setDoOutput(true);
                    h.setRequestProperty("Content-Type", "application/json");
                    OutputStream o = h.getOutputStream();
                    o.write(job[1].getBytes("UTF-8"));
                    o.close();
                    h.getResponseCode();
                } catch (Throwable ignored) {
                } finally {
                    if (h != null) h.disconnect();
                }
            }
        });
    }
}
