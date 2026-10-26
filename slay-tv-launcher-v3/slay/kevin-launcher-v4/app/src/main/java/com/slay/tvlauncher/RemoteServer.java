package com.slay.tvlauncher;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.BitmapFactory;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Tiny HTTP server so your phone can control the TV from a web page.
 * Open http://TV-IP:8080 on the same Wi-Fi and enter the PIN shown on the TV.
 */
final class RemoteServer {
    static final int PORT = 8080;
    private static final int MAX_BODY = 8 * 1024 * 1024;

    private final Context ctx;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService pool = Executors.newFixedThreadPool(4);
    private volatile boolean running;
    private ServerSocket ss;
    private int fails;
    private long lockUntil;

    RemoteServer(Context c) { ctx = c; }

    static String pin(Context c) {
        SharedPreferences p = c.getSharedPreferences("slay", Context.MODE_PRIVATE);
        String s = p.getString("pin", null);
        if (s == null) {
            s = String.valueOf(1000 + new SecureRandom().nextInt(9000));
            p.edit().putString("pin", s).apply();
        }
        return s;
    }

    private SharedPreferences prefs() { return ctx.getSharedPreferences("slay", Context.MODE_PRIVATE); }

    void start() {
        running = true;
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    ss = new ServerSocket();
                    ss.setReuseAddress(true);
                    ss.bind(new InetSocketAddress(PORT));
                    while (running) {
                        final Socket s = ss.accept();
                        pool.execute(new Runnable() {
                            @Override public void run() { handle(s); }
                        });
                    }
                } catch (Exception ignored) { }
            }
        }, "kevin-http").start();
    }

    void stop() {
        running = false;
        try { if (ss != null) ss.close(); } catch (Exception ignored) { }
        pool.shutdownNow();
    }

    // ------------------------------------------------------------ http plumbing

    private void handle(Socket s) {
        try {
            s.setSoTimeout(10000);
            InputStream in = new BufferedInputStream(s.getInputStream(), 8192);
            OutputStream out = s.getOutputStream();
            String line = readLine(in);
            if (line == null || line.isEmpty()) return;
            String[] parts = line.split(" ");
            if (parts.length < 2) return;
            String method = parts[0], target = parts[1];
            int cl = 0;
            String hd;
            while ((hd = readLine(in)) != null && !hd.isEmpty()) {
                if (hd.toLowerCase(Locale.ENGLISH).startsWith("content-length:")) {
                    try { cl = Integer.parseInt(hd.substring(15).trim()); } catch (Exception ignored) { }
                }
            }
            byte[] body = new byte[0];
            if (cl > MAX_BODY) { respond(out, 413, "application/json", "{\"ok\":false,\"err\":\"too big\"}"); return; }
            if (cl > 0) body = readN(in, cl);
            String path = target, qs = null;
            int qi = target.indexOf('?');
            if (qi >= 0) { path = target.substring(0, qi); qs = target.substring(qi + 1); }
            route(method, path, parseQuery(qs), body, out, s.getInetAddress().getHostAddress());
        } catch (Exception ignored) {
        } finally {
            try { s.close(); } catch (Exception ignored) { }
        }
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') sb.append((char) c);
            if (sb.length() > 8192) break;
        }
        if (c == -1 && sb.length() == 0) return null;
        return sb.toString();
    }

    private static byte[] readN(InputStream in, int n) throws IOException {
        byte[] b = new byte[n];
        int off = 0;
        while (off < n) {
            int r = in.read(b, off, n - off);
            if (r < 0) break;
            off += r;
        }
        return b;
    }

    private static Map<String, String> parseQuery(String q) {
        Map<String, String> m = new HashMap<>();
        if (q == null) return m;
        for (String kv : q.split("&")) {
            int i = kv.indexOf('=');
            try {
                String k = URLDecoder.decode(i < 0 ? kv : kv.substring(0, i), "UTF-8");
                String v = i < 0 ? "" : URLDecoder.decode(kv.substring(i + 1), "UTF-8");
                m.put(k, v);
            } catch (Exception ignored) { }
        }
        return m;
    }

    private void respond(OutputStream out, int code, String type, String body) throws IOException {
        respond(out, code, type, body.getBytes("UTF-8"), false);
    }

    private void respond(OutputStream out, int code, String type, byte[] body, boolean cache) throws IOException {
        String status = code == 200 ? "OK" : code == 401 ? "Unauthorized" : code == 404 ? "Not Found" : "Error";
        String head = "HTTP/1.1 " + code + " " + status + "\r\nContent-Type: " + type
                + "\r\nContent-Length: " + body.length
                + "\r\nCache-Control: " + (cache ? "max-age=3600" : "no-store")
                + "\r\nConnection: close\r\n\r\n";
        out.write(head.getBytes("UTF-8"));
        out.write(body);
        out.flush();
    }

    private static String js(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            if (c == '"' || c == '\\') b.append('\\').append(c);
            else if (c < 32) b.append(' ');
            else b.append(c);
        }
        return b.append('"').toString();
    }

    private synchronized boolean authOk(String k) {
        long now = System.currentTimeMillis();
        if (now < lockUntil) return false;
        if (k != null && k.equals(pin(ctx))) { fails = 0; return true; }
        if (++fails >= 5) { lockUntil = now + 60000; fails = 0; }
        return false;
    }

    // ------------------------------------------------------------ routes

    private void route(String method, String path, Map<String, String> q, byte[] body,
                       OutputStream out, String remote) throws IOException {
        if (path.equals("/") || path.equals("/index.html")) {
            InputStream in = ctx.getAssets().open("remote.html");
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            respond(out, 200, "text/html; charset=utf-8", bo.toByteArray(), false);
            return;
        }
        if (!path.startsWith("/api/")) { respond(out, 404, "text/plain", "not found"); return; }
        if (!authOk(q.get("k"))) { respond(out, 401, "application/json", "{\"ok\":false,\"err\":\"bad pin\"}"); return; }

        String json = "{\"ok\":true}";
        final MainActivity act = MainActivity.current;
        SharedPreferences.Editor ed;
        switch (path) {
            case "/api/ping":
                break;

            case "/api/status": {
                SimpleDateFormat f = new SimpleDateFormat("EEE d MMM  h:mm a", Locale.ENGLISH);
                f.setTimeZone(TimeZone.getTimeZone("Asia/Karachi"));
                SharedPreferences p = prefs();
                json = "{\"ok\":true,\"time\":" + js(f.format(System.currentTimeMillis() + MainActivity.TIME_OFFSET))
                        + ",\"weather\":" + js(p.getString("w_line", "--"))
                        + ",\"ram\":" + js(Apps.ramLine(ctx))
                        + ",\"led\":" + js(Ambient.ip(ctx))
                        + ",\"mode\":" + js(Ambient.mode(ctx))
                        + ",\"color\":" + js(String.format("%06X", Ambient.staticColor(ctx) & 0xFFFFFF))
                        + ",\"bri\":" + Ambient.bri(ctx)
                        + ",\"saver\":" + js(p.getString("saver_mode", "scenic"))
                        + ",\"onscreen\":" + (act != null && act.isOnScreen()) + "}";
                break;
            }

            case "/api/apps": {
                StringBuilder sb = new StringBuilder("{\"ok\":true,\"apps\":[");
                List<Apps.Item> list = Apps.queryApps(ctx, false);
                for (int i = 0; i < list.size(); i++) {
                    if (i > 0) sb.append(',');
                    sb.append("{\"p\":").append(js(list.get(i).pkg)).append(",\"l\":").append(js(list.get(i).label)).append('}');
                }
                json = sb.append("]}").toString();
                break;
            }

            case "/api/icon": {
                byte[] png = Apps.iconPng(ctx, q.get("p") == null ? "" : q.get("p"));
                if (png == null) { respond(out, 404, "text/plain", "no icon"); return; }
                respond(out, 200, "image/png", png, true);
                return;
            }

            case "/api/launch": {
                String pkg = q.get("p");
                Intent i = null;
                for (Apps.Item it : Apps.queryApps(ctx, false)) if (it.pkg.equals(pkg)) i = it.intent;
                if (i == null) { json = "{\"ok\":false,\"err\":\"app not found\"}"; break; }
                try { ctx.startActivity(i); } catch (Exception e) { json = "{\"ok\":false,\"err\":\"could not open\"}"; }
                break;
            }

            case "/api/home": {
                Intent i = new Intent(ctx, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                try { ctx.startActivity(i); } catch (Exception e) { json = "{\"ok\":false}"; }
                break;
            }

            case "/api/key": {
                if (act == null || !act.isOnScreen()) { json = "{\"ok\":false,\"err\":\"Kevin is not on screen\"}"; break; }
                act.remoteKey(q.get("d"));
                break;
            }

            case "/api/vol": {
                AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
                String d = q.get("d");
                if ("up".equals(d)) am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI);
                else if ("down".equals(d)) am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI);
                else am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, AudioManager.FLAG_SHOW_UI);
                break;
            }

            case "/api/media": {
                String d = q.get("d");
                int code = "next".equals(d) ? KeyEvent.KEYCODE_MEDIA_NEXT
                        : "prev".equals(d) ? KeyEvent.KEYCODE_MEDIA_PREVIOUS : KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE;
                AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
                am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, code));
                am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, code));
                break;
            }

            case "/api/ambient": {
                ed = prefs().edit();
                String m = q.get("mode"), c = q.get("c"), b = q.get("b");
                if (m != null && m.matches("sync|static|breathe|off")) ed.putString("amb_mode", m);
                try {
                    if (c != null && c.matches("[0-9A-Fa-f]{6}")) ed.putInt("amb_color", 0xFF000000 | Integer.parseInt(c, 16));
                    if (b != null) ed.putInt("amb_bri", Math.max(5, Math.min(255, Integer.parseInt(b))));
                } catch (Exception ignored) { }
                ed.apply();
                Ambient.applyMode(ctx);
                break;
            }

            case "/api/led": {
                String ip = q.get("ip") == null ? "" : q.get("ip").trim();
                if (!ip.isEmpty() && !ip.matches("[0-9A-Za-z.\\-:]{3,60}")) { json = "{\"ok\":false,\"err\":\"bad address\"}"; break; }
                prefs().edit().putString("led_ip", ip).apply();
                Ambient.applyMode(ctx);
                break;
            }

            case "/api/saver": {
                String m = q.get("mode");
                if (m != null && m.matches("clock|scenic|aurora")) prefs().edit().putString("saver_mode", m).apply();
                if ("1".equals(q.get("now"))) {
                    if (act == null || !act.isOnScreen()) { json = "{\"ok\":false,\"err\":\"Kevin is not on screen\"}"; break; }
                    act.remoteSaver();
                }
                break;
            }

            case "/api/clean": {
                long[] r = Apps.cleanRam(ctx);
                json = "{\"ok\":true,\"before\":" + r[0] + ",\"after\":" + r[1] + "}";
                break;
            }

            case "/api/wallpaper": {
                File f = new File(ctx.getFilesDir(), "wallpaper.jpg");
                if ("clear".equals(q.get("do"))) {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                } else {
                    android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
                    o.inJustDecodeBounds = true;
                    BitmapFactory.decodeByteArray(body, 0, body.length, o);
                    if (body.length < 100 || o.outWidth <= 0) { json = "{\"ok\":false,\"err\":\"not an image\"}"; break; }
                    FileOutputStream fo = new FileOutputStream(f);
                    fo.write(body);
                    fo.close();
                }
                if (act != null) act.remoteWallpaper();
                break;
            }

            default:
                respond(out, 404, "application/json", "{\"ok\":false,\"err\":\"unknown\"}");
                return;
        }
        respond(out, 200, "application/json", json);
    }
}
