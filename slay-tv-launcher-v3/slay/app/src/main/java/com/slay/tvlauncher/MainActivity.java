package com.slay.tvlauncher;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Slay Launcher: tiny, dependency-free, cozy glass TV launcher.
 *
 * Performance rules used everywhere:
 *  - animate only scale / alpha / translation (cheap, no re-layout)
 *  - big soft glows live on hardware layers, so drifting them is just a texture move
 *  - icons are downscaled to small bitmaps once, in the background
 *  - no blur, no video, no per-frame color math
 */
public class MainActivity extends Activity {

    private static final int AMBER = 0xFFFFA45C;
    private static final int ROSE = 0xFFE0707A;
    private static final int TEXT = 0xFFFFF3E6;
    private static final int TEXT_DIM = 0x99FFE2C4;

    private static final TimeZone TZ = TimeZone.getTimeZone("Asia/Karachi");
    // Mingora, Swat (34.7795 N, 72.3623 E), Open-Meteo: free, no API key
    private static final String WEATHER_URL =
            "https://api.open-meteo.com/v1/forecast?latitude=34.7795&longitude=72.3623"
                    + "&current=temperature_2m,weather_code,is_day"
                    + "&daily=temperature_2m_max,temperature_2m_min&forecast_days=1&timezone=Asia%2FKarachi";

    private static final long SYNC_EVERY_MS = 15 * 60 * 1000L;
    private static final long IDLE_MS = 3 * 60 * 1000L;   // ambient clock after 3 min idle

    private static final OvershootInterpolator OVERSHOOT = new OvershootInterpolator(1.8f);
    private static final DecelerateInterpolator DECEL = new DecelerateInterpolator();

    private static class Item {
        String label;
        String pkg;
        Intent intent;
        Drawable icon;
        int color = AMBER;
    }

    /** Centers the focused tile with a smooth scroll instead of jumping. */
    private static class CenterScroll extends HorizontalScrollView {
        CenterScroll(Context c) {
            super(c);
            setHorizontalScrollBarEnabled(false);
            setOverScrollMode(OVER_SCROLL_NEVER);
            setClipToPadding(false);
            setClipChildren(false);
        }

        @Override
        protected int computeScrollDeltaToGetChildRectOnScreen(Rect rect) {
            return 0;
        }
    }

    private final List<Item> all = new ArrayList<>();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ExecutorService net = Executors.newSingleThreadExecutor();

    private SharedPreferences prefs;
    private float unit;

    private FrameLayout root;
    private LinearLayout header;
    private View glowB;
    private FrameLayout glowAHolder;
    private final View[] glows = new View[2];
    private final GradientDrawable[] glowDraws = new GradientDrawable[2];
    private int glowFront = 0;
    private int glowTarget = AMBER;
    private int pendingGlow = AMBER;

    private TextView greet, clock, date, focusName;
    private TextView wEmoji, wTemp, wSub;
    private TextView ramText;
    private LinearLayout chipRow;

    // idle ambient-clock screensaver
    private FrameLayout saver;
    private LinearLayout saverBox;
    private TextView saverClock, saverDate;
    private ObjectAnimator[] saverDrift;
    private boolean saverOn = false;
    private final Runnable idleRunnable = new Runnable() {
        @Override public void run() { showSaver(); }
    };
    private CenterScroll favScroll, allScroll;
    private LinearLayout favRow, allRow;

    private ObjectAnimator[] drift;
    private boolean firstBuild = true;
    private String refocusPkg;

    private long timeOffset = 0;      // internet time minus device time
    private long lastSync = 0;        // elapsedRealtime of last good sync
    private SimpleDateFormat fmt24, fmt12, fmtDate;

    private final Runnable reloadRunnable = new Runnable() {
        @Override public void run() { reload(); }
    };
    private final Runnable glowRunnable = new Runnable() {
        @Override public void run() { crossfadeGlow(pendingGlow); }
    };
    private final Runnable tickRunnable = new Runnable() {
        @Override public void run() {
            updateClock();
            updateRam();
            long n = now();
            ui.postDelayed(this, 60000 - (n % 60000) + 50);
        }
    };
    private final Runnable syncRunnable = new Runnable() {
        @Override public void run() {
            syncNet();
            ui.postDelayed(this, SYNC_EVERY_MS);
        }
    };
    private final BroadcastReceiver pkgReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            ui.removeCallbacks(reloadRunnable);
            ui.postDelayed(reloadRunnable, 800);
        }
    };

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("slay", MODE_PRIVATE);
        unit = getResources().getDisplayMetrics().heightPixels / 720f;

        fmt24 = karachi("HH:mm");
        fmt12 = karachi("h:mm a");
        fmtDate = karachi("EEEE, d MMMM");

        buildShell();
        setContentView(root);

        if (prefs.contains("w_t")) {
            applyWeather(prefs.getFloat("w_t", 0f), prefs.getInt("w_c", 0), prefs.getBoolean("w_d", true),
                    prefs.getFloat("w_hi", Float.NaN), prefs.getFloat("w_lo", Float.NaN));
        }

        // Soft entrance for the header
        header.setAlpha(0f);
        header.setTranslationY(-u(22));
        header.animate().alpha(1f).translationY(0f).setDuration(600).setInterpolator(DECEL).start();

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addDataScheme("package");
        registerReceiver(pkgReceiver, f);

        reload();
    }

    @Override
    protected void onStart() {
        super.onStart();
        ui.removeCallbacks(tickRunnable);
        ui.post(tickRunnable);
        startDrift();
        resetIdle();

        ui.removeCallbacks(syncRunnable);
        long wait = lastSync == 0 ? 0
                : Math.max(0, SYNC_EVERY_MS - (SystemClock.elapsedRealtime() - lastSync));
        ui.postDelayed(syncRunnable, wait);
    }

    @Override
    protected void onStop() {
        super.onStop();
        ui.removeCallbacks(tickRunnable);
        ui.removeCallbacks(syncRunnable);
        ui.removeCallbacks(idleRunnable);
        hideSaver(true);
        if (drift != null) for (ObjectAnimator a : drift) a.pause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try { unregisterReceiver(pkgReceiver); } catch (Exception ignored) { }
        ui.removeCallbacksAndMessages(null);
        io.shutdownNow();
        net.shutdownNow();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        favScroll.smoothScrollTo(0, 0);
        allScroll.smoothScrollTo(0, 0);
        if (favRow.getChildCount() > 0) ((ViewGroup) favRow.getChildAt(0)).getChildAt(0).requestFocus();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (saverOn) {                       // first key just wakes the screen
            if (e.getAction() == KeyEvent.ACTION_DOWN) hideSaver(false);
            return true;
        }
        resetIdle();
        return super.dispatchKeyEvent(e);
    }

    @Override
    public void onBackPressed() { /* a launcher has nowhere to go back to */ }

    // ---------------------------------------------------------------- time + weather

    private SimpleDateFormat karachi(String pattern) {
        SimpleDateFormat f = new SimpleDateFormat(pattern, Locale.ENGLISH);
        f.setTimeZone(TZ);
        return f;
    }

    private long now() { return System.currentTimeMillis() + timeOffset; }

    private void updateClock() {
        long t = now();
        boolean h24 = DateFormat.is24HourFormat(this);
        clock.setText((h24 ? fmt24 : fmt12).format(t));
        date.setText(fmtDate.format(t));
        saverClock.setText((h24 ? fmt24 : fmt12).format(t));
        saverDate.setText(fmtDate.format(t));

        Calendar c = Calendar.getInstance(TZ);
        c.setTimeInMillis(t);
        int h = c.get(Calendar.HOUR_OF_DAY);
        greet.setText(h < 5 ? "Good night" : h < 12 ? "Good morning" : h < 17 ? "Good afternoon"
                : h < 21 ? "Good evening" : "Good night");
    }

    private void syncNet() {
        net.execute(new Runnable() {
            @Override public void run() {
                HttpURLConnection c = null;
                try {
                    c = (HttpURLConnection) new URL(WEATHER_URL).openConnection();
                    c.setConnectTimeout(8000);
                    c.setReadTimeout(8000);
                    c.setRequestProperty("User-Agent", "SlayLauncher/1.0");
                    InputStream in = c.getInputStream();
                    ByteArrayOutputStream bo = new ByteArrayOutputStream();
                    byte[] buf = new byte[2048];
                    int n;
                    while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                    in.close();
                    final long server = c.getDate();   // server clock = true time
                    JSONObject json = new JSONObject(bo.toString("UTF-8"));
                    JSONObject cur = json.getJSONObject("current");
                    final float t = (float) cur.getDouble("temperature_2m");
                    final int code = cur.getInt("weather_code");
                    final boolean day = cur.optInt("is_day", 1) == 1;
                    float h = Float.NaN, l = Float.NaN;
                    try {
                        JSONObject d = json.getJSONObject("daily");
                        h = (float) d.getJSONArray("temperature_2m_max").getDouble(0);
                        l = (float) d.getJSONArray("temperature_2m_min").getDouble(0);
                    } catch (Exception ignored) { }
                    final float hi = h, lo = l;
                    ui.post(new Runnable() {
                        @Override public void run() {
                            if (server > 0) timeOffset = server - System.currentTimeMillis();
                            lastSync = SystemClock.elapsedRealtime();
                            prefs.edit().putFloat("w_t", t).putInt("w_c", code).putBoolean("w_d", day)
                                    .putFloat("w_hi", hi).putFloat("w_lo", lo).apply();
                            applyWeather(t, code, day, hi, lo);
                            updateClock();
                        }
                    });
                } catch (Throwable ignored) {
                    // offline: keep showing the last known weather / device clock
                } finally {
                    if (c != null) c.disconnect();
                }
            }
        });
    }

    private void applyWeather(float t, int code, boolean day, float hi, float lo) {
        wEmoji.setText(emoji(code, day));
        wTemp.setText(Math.round(t) + "\u00B0");
        String sub = "Mingora, Swat \u00B7 " + describe(code);
        if (!Float.isNaN(hi) && !Float.isNaN(lo)) {
            sub += "\nH " + Math.round(hi) + "\u00B0  \u00B7  L " + Math.round(lo) + "\u00B0";
        }
        wSub.setText(sub);
    }

    private static String emoji(int c, boolean day) {
        if (c == 0) return day ? "\u2600\uFE0F" : "\uD83C\uDF19";
        if (c <= 2) return day ? "\u26C5" : "\u2601\uFE0F";
        if (c == 3) return "\u2601\uFE0F";
        if (c == 45 || c == 48) return "\uD83C\uDF2B\uFE0F";
        if (c >= 51 && c <= 57) return "\uD83C\uDF26\uFE0F";
        if (c >= 61 && c <= 67) return "\uD83C\uDF27\uFE0F";
        if ((c >= 71 && c <= 77) || c == 85 || c == 86) return "\u2744\uFE0F";
        if (c >= 80 && c <= 82) return "\uD83C\uDF26\uFE0F";
        if (c >= 95) return "\u26C8\uFE0F";
        return "\u2601\uFE0F";
    }

    private static String describe(int c) {
        if (c == 0) return "Clear";
        if (c == 1) return "Mostly clear";
        if (c == 2) return "Partly cloudy";
        if (c == 3) return "Overcast";
        if (c == 45 || c == 48) return "Foggy";
        if (c >= 51 && c <= 57) return "Drizzle";
        if (c >= 61 && c <= 67) return "Rain";
        if ((c >= 71 && c <= 77) || c == 85 || c == 86) return "Snow";
        if (c >= 80 && c <= 82) return "Showers";
        if (c >= 95) return "Thunderstorm";
        return "Cloudy";
    }

    // ---------------------------------------------------------------- layout

    private int u(float v) { return Math.round(v * unit); }

    private GradientDrawable glowDrawable(int color, int radiusPx) {
        GradientDrawable g = new GradientDrawable();
        g.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        g.setGradientRadius(radiusPx);
        setGlowColors(g, color);
        return g;
    }

    private static void setGlowColors(GradientDrawable g, int color) {
        int rgb = color & 0xFFFFFF;
        g.setColors(new int[]{0x5E000000 | rgb, rgb});
    }

    private void buildShell() {
        root = new FrameLayout(this);
        root.setClipChildren(false);
        // Translucent warm scrim: the TV wallpaper (if any) glows through.
        GradientDrawable scrim = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xB8221510, 0xD90E0809});
        root.setBackground(scrim);

        // Ambient glow A (two stacked, cross-faded when the focused app changes)
        glowAHolder = new FrameLayout(this);
        glowAHolder.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        for (int i = 0; i < 2; i++) {
            glowDraws[i] = glowDrawable(AMBER, u(430));
            glows[i] = new View(this);
            glows[i].setBackground(glowDraws[i]);
            glows[i].setAlpha(i == 0 ? 1f : 0f);
            glowAHolder.addView(glows[i], new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        FrameLayout.LayoutParams lpA = new FrameLayout.LayoutParams(u(860), u(860), Gravity.TOP | Gravity.END);
        lpA.topMargin = -u(330);
        lpA.rightMargin = -u(250);
        root.addView(glowAHolder, lpA);

        // Ambient glow B: soft rose, bottom-left
        glowB = new View(this);
        glowB.setBackground(glowDrawable(ROSE, u(400)));
        glowB.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        glowB.setAlpha(0.55f);
        FrameLayout.LayoutParams lpB = new FrameLayout.LayoutParams(u(800), u(800), Gravity.BOTTOM | Gravity.START);
        lpB.bottomMargin = -u(400);
        lpB.leftMargin = -u(260);
        root.addView(glowB, lpB);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setClipChildren(false);
        content.setPadding(0, u(44), 0, u(24));
        root.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // ---- header: greeting + clock + date | weather pill
        header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.TOP);
        header.setPadding(u(72), 0, u(72), 0);

        LinearLayout clockCol = new LinearLayout(this);
        clockCol.setOrientation(LinearLayout.VERTICAL);
        greet = text("", 21, 0xFFFFC48A);
        greet.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        clock = text("", 66, TEXT);
        clock.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        date = text("", 20, TEXT_DIM);
        clockCol.addView(greet);
        clockCol.addView(clock);
        clockCol.addView(date);
        header.addView(clockCol, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        header.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));

        LinearLayout pill = new LinearLayout(this);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setPadding(u(20), u(12), u(26), u(12));
        pill.setBackground(roundRect(0x22FFEBD6, u(26), Math.max(1, u(1)), 0x26FFFFFF));
        wEmoji = text("\u2600\uFE0F", 40, TEXT);
        pill.addView(wEmoji);
        LinearLayout wcol = new LinearLayout(this);
        wcol.setOrientation(LinearLayout.VERTICAL);
        wcol.setPadding(u(14), 0, 0, 0);
        wTemp = text("--\u00B0", 32, TEXT);
        wTemp.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        wSub = text("Mingora, Swat", 14, TEXT_DIM);
        wcol.addView(wTemp);
        wcol.addView(wSub);
        pill.addView(wcol);
        header.addView(pill, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        content.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ---- band: focused app name (left) + quick chips (right)
        LinearLayout band = new LinearLayout(this);
        band.setOrientation(LinearLayout.HORIZONTAL);
        band.setGravity(Gravity.BOTTOM);
        band.setClipChildren(false);
        band.setPadding(0, 0, u(72), u(14));
        focusName = text("", 34, TEXT);
        focusName.setGravity(Gravity.BOTTOM | Gravity.START);
        focusName.setSingleLine();
        focusName.setEllipsize(TextUtils.TruncateAt.END);
        focusName.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        focusName.setPadding(u(72), 0, u(20), 0);
        band.addView(focusName, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        chipRow = new LinearLayout(this);
        chipRow.setOrientation(LinearLayout.HORIZONTAL);
        chipRow.setClipChildren(false);
        buildChips();
        band.addView(chipRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        content.addView(band, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        content.addView(sectionTitle("FAVORITES"));
        favScroll = new CenterScroll(this);
        favRow = makeRow(favScroll);
        content.addView(favScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        content.addView(sectionTitle("ALL APPS"));
        allScroll = new CenterScroll(this);
        allRow = makeRow(allScroll);
        content.addView(allScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout foot = new LinearLayout(this);
        foot.setOrientation(LinearLayout.HORIZONTAL);
        foot.setPadding(u(72), u(4), u(72), 0);
        TextView brand = text("KEVIN", 14, 0xFFFFC48A);
        brand.setLetterSpacing(0.35f);
        brand.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        TextView hint = text("     \u00B7     OK to open     \u00B7     hold OK to pin / unpin", 15, 0x66FFE2C4);
        ramText = text("", 14, 0x88FFE2C4);
        foot.addView(brand);
        foot.addView(hint, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        foot.addView(ramText);
        content.addView(foot);

        // idle ambient clock overlay (hidden until the launcher sits untouched)
        saver = new FrameLayout(this);
        saver.setBackgroundColor(0xF2070506);
        saver.setVisibility(View.GONE);
        saverBox = new LinearLayout(this);
        saverBox.setOrientation(LinearLayout.VERTICAL);
        saverBox.setGravity(Gravity.CENTER_HORIZONTAL);
        saverClock = text("", 150, 0xCCFFF3E6);
        saverClock.setTypeface(Typeface.create("sans-serif-thin", Typeface.NORMAL));
        saverDate = text("", 28, 0x88FFE2C4);
        saverBox.addView(saverClock);
        saverBox.addView(saverDate);
        saver.addView(saverBox, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        root.addView(saver, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private LinearLayout makeRow(CenterScroll scroll) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setClipChildren(false);
        scroll.setPadding(u(72), u(20), u(72), u(20));
        scroll.addView(row, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return row;
    }

    private TextView text(String s, float sizeUnits, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, u(sizeUnits));
        t.setIncludeFontPadding(false);
        return t;
    }

    private TextView sectionTitle(String s) {
        TextView t = text(s, 14, 0x99FFD2A8);
        t.setLetterSpacing(0.2f);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setPadding(u(72), u(6), u(72), 0);
        return t;
    }

    private GradientDrawable roundRect(int fill, int radius, int strokeW, int strokeColor) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(radius);
        if (strokeW > 0) g.setStroke(strokeW, strokeColor);
        return g;
    }

    // ---------------------------------------------------------------- data

    private void reload() {
        io.execute(new Runnable() {
            @Override public void run() {
                final List<Item> list = queryApps();
                ui.post(new Runnable() {
                    @Override public void run() {
                        all.clear();
                        all.addAll(list);
                        ensureDefaultPins();
                        rebuildRows();
                    }
                });
            }
        });
    }

    private Drawable smallIcon(Drawable d, int size) {
        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        d.setBounds(0, 0, size, size);
        d.draw(c);
        return new BitmapDrawable(getResources(), bmp);
    }

    private List<Item> queryApps() {
        PackageManager pm = getPackageManager();
        Map<String, Item> map = new LinkedHashMap<>();
        String[] cats = {Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER};
        for (String cat : cats) {
            Intent q = new Intent(Intent.ACTION_MAIN).addCategory(cat);
            for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
                String pkg = ri.activityInfo.packageName;
                if (pkg.equals(getPackageName()) || map.containsKey(pkg)) continue;
                Item it = new Item();
                it.pkg = pkg;
                it.label = String.valueOf(ri.loadLabel(pm));
                it.intent = new Intent(Intent.ACTION_MAIN)
                        .addCategory(cat)
                        .setComponent(new ComponentName(pkg, ri.activityInfo.name))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                BitmapDrawable bd = (BitmapDrawable) smallIcon(ri.loadIcon(pm), 144);
                it.icon = bd;
                it.color = cozy(bd.getBitmap());
                map.put(pkg, it);
            }
        }
        if (!map.containsKey("com.android.settings")) {
            Item s = new Item();
            s.pkg = "builtin.settings";
            s.label = "Settings";
            s.intent = new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            BitmapDrawable bd = (BitmapDrawable) smallIcon(pm.getDefaultActivityIcon(), 144);
            s.icon = bd;
            s.color = cozy(bd.getBitmap());
            map.put(s.pkg, s);
        }
        List<Item> list = new ArrayList<>(map.values());
        Collections.sort(list, new Comparator<Item>() {
            @Override public int compare(Item a, Item b) { return a.label.compareToIgnoreCase(b.label); }
        });
        return list;
    }

    /** Average icon color, made vivid, then warmed toward amber so everything stays cozy. */
    private static int cozy(Bitmap src) {
        try {
            Bitmap b = Bitmap.createScaledBitmap(src, 16, 16, true);
            long r = 0, g = 0, bl = 0, n = 0;
            for (int y = 0; y < 16; y++) {
                for (int x = 0; x < 16; x++) {
                    int p = b.getPixel(x, y);
                    if (Color.alpha(p) > 200) {
                        r += Color.red(p);
                        g += Color.green(p);
                        bl += Color.blue(p);
                        n++;
                    }
                }
            }
            if (b != src) b.recycle();
            if (n == 0) return AMBER;
            float[] hsv = new float[3];
            Color.RGBToHSV((int) (r / n), (int) (g / n), (int) (bl / n), hsv);
            if (hsv[1] < 0.15f) return AMBER;
            hsv[1] = Math.max(hsv[1], 0.55f);
            hsv[2] = Math.max(hsv[2], 0.9f);
            int vivid = Color.HSVToColor(hsv);
            float k = 0.3f; // 30% amber
            int rr = (int) (Color.red(vivid) * (1 - k) + Color.red(AMBER) * k);
            int gg = (int) (Color.green(vivid) * (1 - k) + Color.green(AMBER) * k);
            int bb = (int) (Color.blue(vivid) * (1 - k) + Color.blue(AMBER) * k);
            return Color.rgb(rr, gg, bb);
        } catch (Throwable t) {
            return AMBER;
        }
    }

    private Item find(String pkg) {
        for (Item it : all) if (it.pkg.equals(pkg)) return it;
        return null;
    }

    // ---------------------------------------------------------------- pins

    private List<String> getPins() {
        String s = prefs.getString("pins", "");
        List<String> l = new ArrayList<>();
        if (!s.isEmpty()) l.addAll(Arrays.asList(s.split(",")));
        return l;
    }

    private void savePins(List<String> l) {
        prefs.edit().putString("pins", TextUtils.join(",", l)).apply();
    }

    private void ensureDefaultPins() {
        if (prefs.contains("pins")) return;
        String[] preferred = {
                "com.google.android.youtube.tv", "com.google.android.youtube",
                "org.videolan.vlc", "com.android.chrome", "com.android.browser",
                "com.android.settings", "builtin.settings"};
        List<String> pins = new ArrayList<>();
        for (String p : preferred) if (find(p) != null) pins.add(p);
        for (Item it : all) {
            if (pins.size() >= 6) break;
            if (!pins.contains(it.pkg)) pins.add(it.pkg);
        }
        savePins(pins);
    }

    private void togglePin(Item it) {
        List<String> pins = getPins();
        boolean removed = pins.remove(it.pkg);
        if (!removed) pins.add(it.pkg);
        savePins(pins);
        refocusPkg = it.pkg;
        rebuildRows();
        Toast.makeText(this, (removed ? "Unpinned " : "Pinned ") + it.label, Toast.LENGTH_SHORT).show();
    }

    // ---------------------------------------------------------------- tiles

    private void rebuildRows() {
        if (refocusPkg == null) {
            View f = root.findFocus();
            if (f != null && f.getTag() instanceof String) refocusPkg = (String) f.getTag();
        }
        favRow.removeAllViews();
        allRow.removeAllViews();

        int idx = 0;
        for (String p : getPins()) {
            Item it = find(p);
            if (it != null) favRow.addView(makeTile(it, true, idx++));
        }
        for (Item it : all) allRow.addView(makeTile(it, false, idx++));
        firstBuild = false;

        View target = findTile(allRow, refocusPkg);
        if (target == null) target = findTile(favRow, refocusPkg);
        if (target == null && favRow.getChildCount() > 0) target = ((ViewGroup) favRow.getChildAt(0)).getChildAt(0);
        if (target == null && allRow.getChildCount() > 0) target = ((ViewGroup) allRow.getChildAt(0)).getChildAt(0);
        refocusPkg = null;
        if (target != null) {
            final View t = target;
            root.post(new Runnable() {
                @Override public void run() { t.requestFocus(); }
            });
        }
    }

    private View findTile(LinearLayout row, String pkg) {
        if (pkg == null) return null;
        for (int i = 0; i < row.getChildCount(); i++) {
            View card = ((ViewGroup) row.getChildAt(i)).getChildAt(0);
            if (pkg.equals(card.getTag())) return card;
        }
        return null;
    }

    private View makeTile(final Item it, boolean big, int index) {
        int size = u(big ? 188 : 128);
        int radius = u(big ? 28 : 21);

        // slot: layout + entrance animation.  card: focus animation (scale/lift).
        FrameLayout slot = new FrameLayout(this);
        slot.setClipChildren(false);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(size, size);
        slp.setMargins(u(10), 0, u(10), 0);
        slot.setLayoutParams(slp);

        final FrameLayout card = new FrameLayout(this);
        card.setTag(it.pkg);
        card.setFocusable(true);
        card.setClickable(true);
        card.setLongClickable(true);
        // frosted-glass look: translucent warm fill + hairline edge
        card.setBackground(roundRect(0x22FFEBD6, radius, Math.max(1, u(1)), 0x26FFFFFF));

        // focus ring lives in its own view so we only animate its alpha (no drawable swaps)
        final View ring = new View(this);
        ring.setBackground(roundRect((it.color & 0xFFFFFF) | 0x4D000000, radius, u(3), 0xF2FFF3E6));
        ring.setAlpha(0f);
        card.addView(ring, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER);
        content.setPadding(u(10), u(10), u(10), u(10));

        ImageView iv = new ImageView(this);
        iv.setImageDrawable(it.icon);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        content.addView(iv, new LinearLayout.LayoutParams(u(big ? 92 : 58), u(big ? 92 : 58)));

        TextView tv = text(it.label, big ? 17 : 13, TEXT);
        tv.setSingleLine();
        tv.setEllipsize(TextUtils.TruncateAt.END);
        tv.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = u(big ? 12 : 8);
        content.addView(tv, tlp);
        card.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        card.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(final View v, boolean has) {
                float s = has ? 1.1f : 1f;
                v.animate().scaleX(s).scaleY(s).translationY(has ? -u(7) : 0)
                        .setDuration(has ? 260 : 170)
                        .setInterpolator(has ? OVERSHOOT : DECEL).start();
                ring.animate().alpha(has ? 1f : 0f).setDuration(has ? 190 : 140).start();
                if (has) {
                    onItemFocused(it);
                    v.post(new Runnable() {
                        @Override public void run() { centerOn(v); }
                    });
                }
            }
        });

        card.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(final View v) {
                v.animate().scaleX(1.03f).scaleY(1.03f).setDuration(90)
                        .withEndAction(new Runnable() {
                            @Override public void run() { launch(it); }
                        }).start();
            }
        });

        card.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) {
                togglePin(it);
                return true;
            }
        });

        slot.addView(card, new FrameLayout.LayoutParams(size, size));

        if (firstBuild) {
            slot.setAlpha(0f);
            slot.setTranslationY(u(44));
            slot.animate().alpha(1f).translationY(0f)
                    .setStartDelay(180 + index * 50L).setDuration(480)
                    .setInterpolator(new DecelerateInterpolator(1.7f)).start();
        }
        return slot;
    }

    private void centerOn(View card) {
        if (!(card.getParent() instanceof View)) return;
        View slot = (View) card.getParent();
        if (!(slot.getParent() instanceof View)) return;
        View row = (View) slot.getParent();
        if (!(row.getParent() instanceof CenterScroll)) return;
        CenterScroll s = (CenterScroll) row.getParent();
        int target = s.getPaddingLeft() + slot.getLeft() + slot.getWidth() / 2 - s.getWidth() / 2;
        s.smoothScrollTo(Math.max(0, target), 0);
    }

    private void onItemFocused(Item it) {
        focusName.animate().cancel();
        focusName.setAlpha(0f);
        focusName.setTranslationX(u(20));
        focusName.setText(it.label);
        focusName.animate().alpha(1f).translationX(0f).setDuration(260).setInterpolator(DECEL).start();

        // debounce: while zipping through apps, only the final color triggers a fade
        pendingGlow = it.color;
        ui.removeCallbacks(glowRunnable);
        ui.postDelayed(glowRunnable, 150);
    }

    /** Two pre-rendered glows swap by alpha: nothing is re-rasterised per frame. */
    private void crossfadeGlow(int color) {
        if (color == glowTarget) return;
        glowTarget = color;
        int back = 1 - glowFront;
        setGlowColors(glowDraws[back], color);
        glows[back].animate().alpha(1f).setDuration(650).start();
        glows[glowFront].animate().alpha(0f).setDuration(650).start();
        glowFront = back;
    }

    private void launch(Item it) {
        try {
            startActivity(it.intent);
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        } catch (Exception e) {
            Toast.makeText(this, "Can't open " + it.label, Toast.LENGTH_SHORT).show();
        }
    }

    // ---------------------------------------------------------------- quick chips + RAM

    private void buildChips() {
        chipRow.addView(makeChip("\uD83D\uDCF6", "Wi-Fi", new Runnable() {
            @Override public void run() { openSettings(Settings.ACTION_WIFI_SETTINGS); }
        }));
        chipRow.addView(makeChip("\uD83D\uDDA5\uFE0F", "Display", new Runnable() {
            @Override public void run() { openSettings(Settings.ACTION_DISPLAY_SETTINGS); }
        }));
        chipRow.addView(makeChip("\uD83D\uDD0A", "Sound", new Runnable() {
            @Override public void run() { openSettings(Settings.ACTION_SOUND_SETTINGS); }
        }));
        chipRow.addView(makeChip("\uD83D\uDCBE", "Storage", new Runnable() {
            @Override public void run() { openSettings(Settings.ACTION_INTERNAL_STORAGE_SETTINGS); }
        }));
        chipRow.addView(makeChip("\uD83D\uDEE0\uFE0F", "Developer", new Runnable() {
            @Override public void run() { openSettings(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS); }
        }));
        chipRow.addView(makeChip("\uD83E\uDDF9", "Clean RAM", new Runnable() {
            @Override public void run() { cleanRam(); }
        }));
    }

    private View makeChip(String emoji, final String label, final Runnable action) {
        final FrameLayout chip = new FrameLayout(this);
        chip.setFocusable(true);
        chip.setClickable(true);
        chip.setBackground(roundRect(0x22FFEBD6, u(20), Math.max(1, u(1)), 0x26FFFFFF));

        final View ring = new View(this);
        ring.setBackground(roundRect(0x55FFA45C, u(20), u(2), 0xF2FFF3E6));
        ring.setAlpha(0f);
        chip.addView(ring, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        TextView t = text(emoji + "  " + label, 15, TEXT);
        t.setPadding(u(16), u(9), u(18), u(9));
        chip.addView(t, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = u(10);
        chip.setLayoutParams(lp);

        chip.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean has) {
                float sc = has ? 1.08f : 1f;
                v.animate().scaleX(sc).scaleY(sc).setDuration(has ? 240 : 160)
                        .setInterpolator(has ? OVERSHOOT : DECEL).start();
                ring.animate().alpha(has ? 1f : 0f).setDuration(has ? 180 : 140).start();
                if (has) {
                    focusName.animate().cancel();
                    focusName.setAlpha(0f);
                    focusName.setTranslationX(u(20));
                    focusName.setText(label);
                    focusName.animate().alpha(1f).translationX(0f).setDuration(240).setInterpolator(DECEL).start();
                }
            }
        });
        chip.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { action.run(); }
        });
        return chip;
    }

    private void openSettings(String action) {
        try {
            startActivity(new Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            Toast.makeText(this, "Not available on this TV", Toast.LENGTH_SHORT).show();
        }
    }

    private long freeRamMb() {
        ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        return mi.availMem >> 20;
    }

    private void updateRam() {
        ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        ramText.setText("RAM " + (mi.availMem >> 20) + " / " + (mi.totalMem >> 20) + " MB free");
    }

    /** Asks Android to drop background processes of your apps. Safe: apps just restart when opened. */
    private void cleanRam() {
        final long before = freeRamMb();
        ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        for (Item it : all) {
            if (it.pkg.startsWith("builtin")) continue;
            try { am.killBackgroundProcesses(it.pkg); } catch (Exception ignored) { }
        }
        ui.postDelayed(new Runnable() {
            @Override public void run() {
                updateRam();
                Toast.makeText(MainActivity.this,
                        "RAM free: " + freeRamMb() + " MB (was " + before + " MB)", Toast.LENGTH_LONG).show();
            }
        }, 800);
    }

    // ---------------------------------------------------------------- idle ambient clock

    private void resetIdle() {
        ui.removeCallbacks(idleRunnable);
        ui.postDelayed(idleRunnable, IDLE_MS);
    }

    private void showSaver() {
        if (saverOn) return;
        saverOn = true;
        updateClock();
        saver.animate().cancel();
        saver.setAlpha(0f);
        saver.setVisibility(View.VISIBLE);
        saver.animate().alpha(1f).setDuration(900).start();
        if (saverDrift == null) {
            saverDrift = new ObjectAnimator[]{
                    loop(saverBox, "translationX", -u(170), u(170), 40000),
                    loop(saverBox, "translationY", -u(90), u(90), 52000)
            };
            for (ObjectAnimator a : saverDrift) a.start();
        } else {
            for (ObjectAnimator a : saverDrift) a.resume();
        }
        if (drift != null) for (ObjectAnimator a : drift) a.pause();   // save GPU while idle
    }

    private void hideSaver(boolean instant) {
        if (!saverOn) return;
        saverOn = false;
        saver.animate().cancel();
        if (instant) {
            saver.setVisibility(View.GONE);
        } else {
            saver.animate().alpha(0f).setDuration(300).withEndAction(new Runnable() {
                @Override public void run() { if (!saverOn) saver.setVisibility(View.GONE); }
            }).start();
            if (drift != null) for (ObjectAnimator a : drift) a.resume();
        }
        if (saverDrift != null) for (ObjectAnimator a : saverDrift) a.pause();
    }

    // ---------------------------------------------------------------- ambient drift

    private void startDrift() {
        if (drift != null) {
            for (ObjectAnimator a : drift) a.resume();
            return;
        }
        drift = new ObjectAnimator[]{
                loop(glowAHolder, "translationX", -u(150), u(150), 18000),
                loop(glowAHolder, "translationY", -u(60), u(90), 24000),
                loop(glowB, "translationX", -u(100), u(170), 21000)
        };
        for (ObjectAnimator a : drift) a.start();
    }

    private ObjectAnimator loop(View v, String prop, float from, float to, long ms) {
        ObjectAnimator a = ObjectAnimator.ofFloat(v, prop, from, to);
        a.setDuration(ms);
        a.setRepeatMode(ValueAnimator.REVERSE);
        a.setRepeatCount(ValueAnimator.INFINITE);
        a.setInterpolator(new AccelerateDecelerateInterpolator());
        return a;
    }
}
