package com.slay.tvlauncher;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.provider.Settings;

import java.io.ByteArrayOutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** App list + the iPhone-style icon renderer + small system helpers. */
final class Apps {
    static final int AMBER = 0xFFFFA45C;
    static final int ICON_PX = 160;

    static class Item {
        String label, pkg, tool;   // tool != null -> built-in utility tile
        Intent intent;
        Drawable icon;
        int color = AMBER;
    }

    // id, label, emoji, gradient top, gradient bottom
    private static final Object[][] TOOLS = {
            {"tool.wifi", "Wi-Fi", "\uD83D\uDCF6", 0xFF4F9DFF, 0xFF1D5FD8},
            {"tool.display", "Display", "\uD83D\uDDA5\uFE0F", 0xFFA78BFA, 0xFF6D40D8},
            {"tool.sound", "Sound", "\uD83D\uDD0A", 0xFFFF7AA8, 0xFFD8366F},
            {"tool.storage", "Storage", "\uD83D\uDCBE", 0xFF4FD6C2, 0xFF0F9B8A},
            {"tool.dev", "Developer", "\uD83D\uDEE0\uFE0F", 0xFFFFB347, 0xFFE0701A},
            {"tool.clean", "Clean RAM", "\uD83E\uDDF9", 0xFF7BE07B, 0xFF2FA84F},
            {"tool.saver", "Screensaver", "\uD83C\uDF0C", 0xFF6C7BFF, 0xFF3A3FC4},
            {"tool.remote", "Phone Remote", "\uD83D\uDCF1", 0xFFFF9A5C, 0xFFE0502A},
    };

    private static final Map<String, byte[]> PNG_CACHE = new HashMap<>();

    // ------------------------------------------------------------ app list

    static List<Item> queryApps(Context ctx, boolean withIcons) {
        PackageManager pm = ctx.getPackageManager();
        Map<String, Item> map = new LinkedHashMap<>();
        String[] cats = {Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER};
        for (String cat : cats) {
            Intent q = new Intent(Intent.ACTION_MAIN).addCategory(cat);
            for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
                String pkg = ri.activityInfo.packageName;
                if (pkg.equals(ctx.getPackageName()) || map.containsKey(pkg)) continue;
                Item it = new Item();
                it.pkg = pkg;
                it.label = String.valueOf(ri.loadLabel(pm));
                it.intent = new Intent(Intent.ACTION_MAIN)
                        .addCategory(cat)
                        .setComponent(new ComponentName(pkg, ri.activityInfo.name))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                if (withIcons) {
                    Bitmap b = render(ri.loadIcon(pm), ICON_PX);
                    it.icon = new BitmapDrawable(ctx.getResources(), b);
                    it.color = vivid(b);
                }
                map.put(pkg, it);
            }
        }
        if (!map.containsKey("com.android.settings")) {
            Item s = new Item();
            s.pkg = "builtin.settings";
            s.label = "Settings";
            s.intent = new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (withIcons) {
                s.icon = new BitmapDrawable(ctx.getResources(),
                        toolIcon("\u2699\uFE0F", 0xFF9AA0AE, 0xFF4A4F5E, ICON_PX));
                s.color = 0xFFB4BAC8;
            }
            map.put(s.pkg, s);
        }
        List<Item> list = new ArrayList<>(map.values());
        Collections.sort(list, new Comparator<Item>() {
            @Override public int compare(Item a, Item b) { return a.label.compareToIgnoreCase(b.label); }
        });
        return list;
    }

    static List<Item> tools(Context ctx) {
        List<Item> l = new ArrayList<>();
        for (Object[] t : TOOLS) {
            Item it = new Item();
            it.tool = (String) t[0];
            it.pkg = it.tool;
            it.label = (String) t[1];
            it.color = (Integer) t[3];
            it.icon = new BitmapDrawable(ctx.getResources(),
                    toolIcon((String) t[2], (Integer) t[3], (Integer) t[4], ICON_PX));
            l.add(it);
        }
        return l;
    }

    /** PNG of an installed app's iPhone-style icon (for the phone website). */
    static byte[] iconPng(Context ctx, String pkg) {
        synchronized (PNG_CACHE) {
            byte[] c = PNG_CACHE.get(pkg);
            if (c != null) return c;
        }
        try {
            Drawable d = ctx.getPackageManager().getApplicationIcon(pkg);
            Bitmap b = render(d, 128);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            b.compress(Bitmap.CompressFormat.PNG, 100, bo);
            b.recycle();
            byte[] out = bo.toByteArray();
            synchronized (PNG_CACHE) { PNG_CACHE.put(pkg, out); }
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------ iPhone-style icons

    private static Path squircle(float S, float inset) {
        Path p = new Path();
        float r = S / 2f - inset, c = S / 2f, e = 2f / 5f;
        int steps = 96;
        for (int i = 0; i <= steps; i++) {
            double t = i * 2 * Math.PI / steps;
            double ct = Math.cos(t), st = Math.sin(t);
            float x = (float) (c + r * Math.signum(ct) * Math.pow(Math.abs(ct), e));
            float y = (float) (c + r * Math.signum(st) * Math.pow(Math.abs(st), e));
            if (i == 0) p.moveTo(x, y); else p.lineTo(x, y);
        }
        p.close();
        return p;
    }

    private static void finish(Canvas c, int S, Path shape) {
        Paint g = new Paint(Paint.ANTI_ALIAS_FLAG);
        g.setShader(new LinearGradient(0, 0, 0, S * 0.58f, 0x3CFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, S, S * 0.58f, g);                       // top gloss
        Paint m = new Paint(Paint.ANTI_ALIAS_FLAG);
        m.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
        m.setColor(0xFF000000);
        c.drawPath(shape, m);                                     // soft squircle mask
        Paint s = new Paint(Paint.ANTI_ALIAS_FLAG);
        s.setStyle(Paint.Style.STROKE);
        s.setStrokeWidth(Math.max(1f, S / 90f));
        s.setColor(0x26FFFFFF);
        c.drawPath(shape, s);                                     // hairline edge
    }

    static Bitmap render(Drawable d, int S) {
        Bitmap out = Bitmap.createBitmap(S, S, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Path shape = squircle(S, 1f);
        if (Build.VERSION.SDK_INT >= 26 && d instanceof AdaptiveIconDrawable) {
            AdaptiveIconDrawable ad = (AdaptiveIconDrawable) d;
            int o = Math.round(S * 0.25f);
            Drawable bg = ad.getBackground(), fg = ad.getForeground();
            if (bg != null) { bg.setBounds(-o, -o, S + o, S + o); bg.draw(c); } else c.drawColor(0xFF2A2E3A);
            if (fg != null) { fg.setBounds(-o, -o, S + o, S + o); fg.draw(c); }
        } else {
            Bitmap t = Bitmap.createBitmap(S, S, Bitmap.Config.ARGB_8888);
            Canvas tc = new Canvas(t);
            d.setBounds(0, 0, S, S);
            d.draw(tc);
            boolean full = Color.alpha(t.getPixel(3, 3)) > 240 && Color.alpha(t.getPixel(S - 4, 3)) > 240
                    && Color.alpha(t.getPixel(3, S - 4)) > 240 && Color.alpha(t.getPixel(S - 4, S - 4)) > 240;
            if (full) {
                c.drawBitmap(t, 0, 0, null);
            } else {
                int top, bot;
                if (luminance(t) > 0.75f) { top = 0xFF4A4F5E; bot = 0xFF1E2129; }   // light logo -> dark tile
                else { top = 0xFFFFFFFF; bot = 0xFFDDE0E8; }                         // colourful logo -> white tile
                Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
                p.setShader(new LinearGradient(0, 0, 0, S, top, bot, Shader.TileMode.CLAMP));
                c.drawRect(0, 0, S, S, p);
                int in = Math.round(S * 0.16f);
                c.drawBitmap(t, null, new Rect(in, in, S - in, S - in), new Paint(Paint.FILTER_BITMAP_FLAG));
            }
            t.recycle();
        }
        finish(c, S, shape);
        return out;
    }

    static Bitmap toolIcon(String emoji, int c1, int c2, int S) {
        Bitmap out = Bitmap.createBitmap(S, S, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new LinearGradient(0, 0, S, S, c1, c2, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, S, S, p);
        Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
        tp.setTextSize(S * 0.52f);
        tp.setTextAlign(Paint.Align.CENTER);
        c.drawText(emoji, S / 2f, S / 2f - (tp.ascent() + tp.descent()) / 2f, tp);
        finish(c, S, squircle(S, 1f));
        return out;
    }

    private static float luminance(Bitmap src) {
        Bitmap b = Bitmap.createScaledBitmap(src, 16, 16, true);
        double sum = 0;
        int n = 0;
        for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) {
            int p = b.getPixel(x, y);
            if (Color.alpha(p) > 200) {
                sum += (0.299 * Color.red(p) + 0.587 * Color.green(p) + 0.114 * Color.blue(p)) / 255.0;
                n++;
            }
        }
        if (b != src) b.recycle();
        return n == 0 ? 0.5f : (float) (sum / n);
    }

    /** Vivid average colour of the icon: used for the focus halo and the LED strip. */
    static int vivid(Bitmap src) {
        try {
            Bitmap b = Bitmap.createScaledBitmap(src, 16, 16, true);
            long r = 0, g = 0, bl = 0, n = 0;
            for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) {
                int p = b.getPixel(x, y);
                if (Color.alpha(p) > 200) { r += Color.red(p); g += Color.green(p); bl += Color.blue(p); n++; }
            }
            if (b != src) b.recycle();
            if (n == 0) return AMBER;
            float[] hsv = new float[3];
            Color.RGBToHSV((int) (r / n), (int) (g / n), (int) (bl / n), hsv);
            if (hsv[1] < 0.12f) return 0xFFFFE2C4;       // neutral icons -> warm white
            hsv[1] = Math.max(hsv[1], 0.6f);
            hsv[2] = Math.max(hsv[2], 0.9f);
            return Color.HSVToColor(hsv);
        } catch (Throwable t) {
            return AMBER;
        }
    }

    // ------------------------------------------------------------ system helpers

    private static ActivityManager.MemoryInfo mem(Context ctx) {
        ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        return mi;
    }

    static long freeRamMb(Context ctx) { return mem(ctx).availMem >> 20; }

    static String ramLine(Context ctx) {
        ActivityManager.MemoryInfo mi = mem(ctx);
        return (mi.availMem >> 20) + " / " + (mi.totalMem >> 20) + " MB free";
    }

    /** Asks Android to drop background processes of launchable apps. Returns {before, after} MB. */
    static long[] cleanRam(Context ctx) {
        long before = freeRamMb(ctx);
        ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
        for (Item it : queryApps(ctx, false)) {
            if (it.pkg.startsWith("builtin")) continue;
            try { am.killBackgroundProcesses(it.pkg); } catch (Exception ignored) { }
        }
        try { Thread.sleep(800); } catch (InterruptedException ignored) { }
        return new long[]{before, freeRamMb(ctx)};
    }

    static String localIp() {
        try {
            for (Enumeration<NetworkInterface> e = NetworkInterface.getNetworkInterfaces(); e.hasMoreElements(); ) {
                NetworkInterface n = e.nextElement();
                if (!n.isUp() || n.isLoopback()) continue;
                for (Enumeration<InetAddress> a = n.getInetAddresses(); a.hasMoreElements(); ) {
                    InetAddress i = a.nextElement();
                    if (i instanceof Inet4Address && i.isSiteLocalAddress()) return i.getHostAddress();
                }
            }
        } catch (Exception ignored) { }
        return null;
    }
}
