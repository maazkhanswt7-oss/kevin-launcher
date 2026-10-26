package com.slay.tvlauncher;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
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
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Kevin: iPhone-style TV home screen.
 * Painted scenic wallpaper, squircle icons in a 2-row grid, glass dock,
 * Karachi clock + Mingora weather, LED-strip sync, phone remote, screensavers.
 *
 * Speed rules: only scale/alpha/translation animations, one smooth scroller,
 * pre-rendered bitmaps, hardware layers only while the aurora is visible.
 */
public class MainActivity extends Activity {

    static volatile MainActivity current;
    static volatile long TIME_OFFSET = 0;         // internet time minus device time

    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    private static final int TEXT = 0xFFFFF6EC;
    private static final int TEXT_DIM = 0xB3FFE9D2;

    private static final TimeZone TZ = TimeZone.getTimeZone("Asia/Karachi");
    // Mingora, Swat (34.7795 N, 72.3623 E), Open-Meteo: free, no key
    private static final String WEATHER_URL =
            "https://api.open-meteo.com/v1/forecast?latitude=34.7795&longitude=72.3623"
                    + "&current=temperature_2m,weather_code,is_day"
                    + "&daily=temperature_2m_max,temperature_2m_min&forecast_days=1&timezone=Asia%2FKarachi";

    private static final long SYNC_EVERY_MS = 15 * 60 * 1000L;
    private static final long IDLE_MS = 3 * 60 * 1000L;
    private static final int MAX_DOCK = 6;

    private static final OvershootInterpolator OVERSHOOT = new OvershootInterpolator(1.7f);
    private static final DecelerateInterpolator DECEL = new DecelerateInterpolator();

    /** Smooth-centering scroller (no jumpy default scrolling). */
    private static class CenterScroll extends HorizontalScrollView {
        CenterScroll(Context c) {
            super(c);
            setHorizontalScrollBarEnabled(false);
            setOverScrollMode(OVER_SCROLL_NEVER);
            setClipToPadding(false);
            setClipChildren(false);
        }

        @Override
        protected int computeScrollDeltaToGetChildRectOnScreen(Rect rect) { return 0; }
    }

    private final List<Apps.Item> all = new ArrayList<>();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ExecutorService net = Executors.newSingleThreadExecutor();

    private SharedPreferences prefs;
    private float unit;
    private volatile boolean resumed = false;

    // layers
    private FrameLayout root, wallHolder;
    private final ImageView[] wall = new ImageView[2];
    private final Bitmap[] wallBmp = new Bitmap[2];
    private int wallFront = 0;
    private String wallKey = "";
    private LinearLayout content, header;
    private ObjectAnimator[] drift;

    // header + weather
    private TextView greet, clock, date, wEmoji, wTemp, wSub;
    // grid + dock + footer
    private CenterScroll gridScroll;
    private LinearLayout gridRow, dockRow;
    private TextView ramText, remoteText;

    // info card
    private FrameLayout info;
    private TextView infoTitle, infoBody;
    private boolean infoOn = false;

    // screensaver
    private FrameLayout saver, aurora;
    private LinearLayout saverBox;
    private TextView saverClock, saverDate;
    private final View[] blobs = new View[4];
    private ObjectAnimator[] saverAnims, auroraAnims;
    private boolean saverOn = false;
    private int cycleIdx = 0;

    private boolean firstBuild = true;
    private String refocusPkg;
    private long lastSync = 0;
    private int pendingColor = Apps.AMBER;
    private SimpleDateFormat fmt24, fmt12, fmtDate;

    private final Runnable reloadRunnable = new Runnable() {
        @Override public void run() { reload(); }
    };
    private final Runnable ambientRunnable = new Runnable() {
        @Override public void run() { Ambient.onFocusColor(MainActivity.this, pendingColor); }
    };
    private final Runnable idleRunnable = new Runnable() {
        @Override public void run() { showSaver(); }
    };
    private final Runnable cycleRunnable = new Runnable() {
        @Override public void run() {
            int[] pal = {0xFF3B82F6, 0xFF8B5CF6, 0xFF14B8A6, 0xFFF472B6};
            Ambient.cycle(MainActivity.this, pal[cycleIdx++ % pal.length]);
            ui.postDelayed(this, 9000);
        }
    };
    private final Runnable tickRunnable = new Runnable() {
        @Override public void run() {
            updateClock();
            updateFooter();
            refreshWallpaper(false);
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

    // ================================================================ lifecycle

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        current = this;
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

        header.setAlpha(0f);
        header.setTranslationY(-u(22));
        header.animate().alpha(1f).translationY(0f).setDuration(650).setInterpolator(DECEL).start();

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addDataScheme("package");
        registerReceiver(pkgReceiver, f);

        Intent svc = new Intent(this, RemoteService.class);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(svc); else startService(svc);

        refreshWallpaper(true);
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

    @Override protected void onResume() { super.onResume(); resumed = true; }
    @Override protected void onPause() { resumed = false; super.onPause(); }

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
        if (current == this) current = null;
        try { unregisterReceiver(pkgReceiver); } catch (Exception ignored) { }
        ui.removeCallbacksAndMessages(null);
        io.shutdownNow();
        net.shutdownNow();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        gridScroll.smoothScrollTo(0, 0);
        View first = firstCard();
        if (first != null) first.requestFocus();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (infoOn) {
            if (e.getAction() == KeyEvent.ACTION_DOWN) hideInfo();
            return true;
        }
        if (saverOn) {                       // first key just wakes the screen
            if (e.getAction() == KeyEvent.ACTION_DOWN) hideSaver(false);
            return true;
        }
        resetIdle();
        return super.dispatchKeyEvent(e);
    }

    @Override
    public void onBackPressed() { /* a launcher has nowhere to go back to */ }

    // ---- called by the phone remote (any thread)
    boolean isOnScreen() { return resumed; }

    void remoteKey(String d) {
        if (d == null) return;
        final int code = "up".equals(d) ? KeyEvent.KEYCODE_DPAD_UP
                : "down".equals(d) ? KeyEvent.KEYCODE_DPAD_DOWN
                : "left".equals(d) ? KeyEvent.KEYCODE_DPAD_LEFT
                : "right".equals(d) ? KeyEvent.KEYCODE_DPAD_RIGHT
                : "ok".equals(d) ? KeyEvent.KEYCODE_DPAD_CENTER : -1;
        if (code < 0) return;
        runOnUiThread(new Runnable() {
            @Override public void run() {
                View dv = getWindow().getDecorView();
                dv.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, code));
                dv.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, code));
            }
        });
    }

    void remoteSaver() {
        runOnUiThread(new Runnable() {
            @Override public void run() { hideSaver(true); showSaver(); }
        });
    }

    void remoteWallpaper() {
        runOnUiThread(new Runnable() {
            @Override public void run() { refreshWallpaper(true); }
        });
    }

    // ================================================================ time + weather

    private SimpleDateFormat karachi(String pattern) {
        SimpleDateFormat f = new SimpleDateFormat(pattern, Locale.ENGLISH);
        f.setTimeZone(TZ);
        return f;
    }

    private long now() { return System.currentTimeMillis() + TIME_OFFSET; }

    private int phaseNow() {
        Calendar c = Calendar.getInstance(TZ);
        c.setTimeInMillis(now());
        return Scenery.phaseForHour(c.get(Calendar.HOUR_OF_DAY));
    }

    private void updateClock() {
        long t = now();
        boolean h24 = DateFormat.is24HourFormat(this);
        String hm = (h24 ? fmt24 : fmt12).format(t);
        clock.setText(hm);
        date.setText(fmtDate.format(t));
        saverClock.setText(hm);
        saverDate.setText(fmtDate.format(t));
        Calendar c = Calendar.getInstance(TZ);
        c.setTimeInMillis(t);
        int h = c.get(Calendar.HOUR_OF_DAY);
        greet.setText(h < 5 ? "Good night" : h < 12 ? "Good morning" : h < 17 ? "Good afternoon"
                : h < 21 ? "Good evening" : "Good night");
    }

    private void updateFooter() {
        ramText.setText("RAM " + Apps.ramLine(this));
        String ip = Apps.localIp();
        remoteText.setText(ip == null ? "Phone remote offline \u00B7 connect the TV to Wi-Fi"
                : "Phone remote   http://" + ip + ":" + RemoteServer.PORT + "   PIN " + RemoteServer.pin(this));
    }

    private void syncNet() {
        net.execute(new Runnable() {
            @Override public void run() {
                HttpURLConnection c = null;
                try {
                    c = (HttpURLConnection) new URL(WEATHER_URL).openConnection();
                    c.setConnectTimeout(8000);
                    c.setReadTimeout(8000);
                    c.setRequestProperty("User-Agent", "Kevin/1.0");
                    InputStream in = c.getInputStream();
                    ByteArrayOutputStream bo = new ByteArrayOutputStream();
                    byte[] buf = new byte[2048];
                    int n;
                    while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                    in.close();
                    final long server = c.getDate();       // server clock = true time
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
                            if (server > 0) TIME_OFFSET = server - System.currentTimeMillis();
                            lastSync = SystemClock.elapsedRealtime();
                            prefs.edit().putFloat("w_t", t).putInt("w_c", code).putBoolean("w_d", day)
                                    .putFloat("w_hi", hi).putFloat("w_lo", lo).apply();
                            applyWeather(t, code, day, hi, lo);
                            updateClock();
                        }
                    });
                } catch (Throwable ignored) {
                    // offline: keep last weather / device clock
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
        prefs.edit().putString("w_line", Math.round(t) + "\u00B0 \u00B7 " + describe(code)).apply();
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

    // ================================================================ layout

    private int u(float v) { return Math.round(v * unit); }

    private TextView text(String s, float sizeUnits, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, u(sizeUnits));
        t.setIncludeFontPadding(false);
        return t;
    }

    private GradientDrawable roundRect(int fill, int radius, int strokeW, int strokeColor) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(radius);
        if (strokeW > 0) g.setStroke(strokeW, strokeColor);
        return g;
    }

    private void buildShell() {
        root = new FrameLayout(this);
        root.setClipChildren(false);
        root.setBackgroundColor(0xFF0A0B10);

        // ---- scenic wallpaper (two views so themes cross-fade) with a slow Ken Burns drift
        wallHolder = new FrameLayout(this);
        wallHolder.setScaleX(1.1f);
        wallHolder.setScaleY(1.1f);
        for (int i = 0; i < 2; i++) {
            wall[i] = new ImageView(this);
            wall[i].setScaleType(ImageView.ScaleType.CENTER_CROP);
            wall[i].setAlpha(i == 0 ? 1f : 0f);
            wallHolder.addView(wall[i], new FrameLayout.LayoutParams(MATCH, MATCH));
        }
        root.addView(wallHolder, new FrameLayout.LayoutParams(MATCH, MATCH));

        // readability scrim: darker at the top (clock) and bottom (dock)
        View scrim = new View(this);
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x6E000000, 0x14000000, 0x0F000000, 0x9E000000}));
        root.addView(scrim, new FrameLayout.LayoutParams(MATCH, MATCH));

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setClipChildren(false);
        content.setPadding(0, u(38), 0, u(20));
        root.addView(content, new FrameLayout.LayoutParams(MATCH, MATCH));

        // ---- header: greeting/clock/date | weather pill
        header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.TOP);
        header.setPadding(u(72), 0, u(72), 0);

        LinearLayout clockCol = new LinearLayout(this);
        clockCol.setOrientation(LinearLayout.VERTICAL);
        greet = text("", 21, 0xFFFFD1A0);
        greet.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        clock = text("", 78, TEXT);
        clock.setTypeface(Typeface.create("sans-serif-thin", Typeface.NORMAL));
        clock.setShadowLayer(u(10), 0, u(3), 0x66000000);
        date = text("", 21, TEXT_DIM);
        clockCol.addView(greet);
        clockCol.addView(clock);
        clockCol.addView(date);
        header.addView(clockCol, new LinearLayout.LayoutParams(WRAP, WRAP));
        header.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));

        LinearLayout pill = new LinearLayout(this);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setPadding(u(20), u(12), u(26), u(12));
        pill.setBackground(roundRect(0x38FFFFFF, u(26), Math.max(1, u(1)), 0x30FFFFFF));
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
        header.addView(pill, new LinearLayout.LayoutParams(WRAP, WRAP));
        content.addView(header, new LinearLayout.LayoutParams(MATCH, WRAP));

        content.addView(new View(this), new LinearLayout.LayoutParams(MATCH, 0, 1f));

        // ---- app grid (2 rows, scrolls sideways like iPhone pages)
        gridScroll = new CenterScroll(this);
        gridScroll.setPadding(u(72), u(14), u(72), u(10));
        gridRow = new LinearLayout(this);
        gridRow.setOrientation(LinearLayout.HORIZONTAL);
        gridRow.setClipChildren(false);
        gridScroll.addView(gridRow, new FrameLayout.LayoutParams(WRAP, WRAP));
        content.addView(gridScroll, new LinearLayout.LayoutParams(MATCH, WRAP));

        content.addView(new View(this), new LinearLayout.LayoutParams(MATCH, 0, 1f));

        // ---- glass dock
        LinearLayout dockWrap = new LinearLayout(this);
        dockWrap.setGravity(Gravity.CENTER);
        dockWrap.setClipChildren(false);
        dockRow = new LinearLayout(this);
        dockRow.setOrientation(LinearLayout.HORIZONTAL);
        dockRow.setGravity(Gravity.CENTER_VERTICAL);
        dockRow.setClipChildren(false);
        dockRow.setPadding(u(18), u(14), u(18), u(8));
        dockRow.setBackground(roundRect(0x38FFFFFF, u(38), Math.max(1, u(1)), 0x36FFFFFF));
        dockWrap.addView(dockRow, new LinearLayout.LayoutParams(WRAP, WRAP));
        content.addView(dockWrap, new LinearLayout.LayoutParams(MATCH, WRAP));

        // ---- footer
        LinearLayout foot = new LinearLayout(this);
        foot.setOrientation(LinearLayout.HORIZONTAL);
        foot.setPadding(u(72), u(12), u(72), 0);
        TextView brand = text("KEVIN", 14, 0xFFFFD1A0);
        brand.setLetterSpacing(0.35f);
        brand.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        remoteText = text("", 14, 0x88FFE9D2);
        remoteText.setPadding(u(24), 0, 0, 0);
        remoteText.setSingleLine();
        remoteText.setEllipsize(TextUtils.TruncateAt.END);
        ramText = text("", 14, 0x88FFE9D2);
        foot.addView(brand);
        foot.addView(remoteText, new LinearLayout.LayoutParams(0, WRAP, 1f));
        foot.addView(ramText);
        content.addView(foot);

        buildSaver();
        buildInfo();
    }

    private void buildSaver() {
        saver = new FrameLayout(this);
        saver.setVisibility(View.GONE);

        aurora = new FrameLayout(this);
        aurora.setVisibility(View.GONE);
        aurora.setClipChildren(true);
        int[] cols = {0xFF3B82F6, 0xFF8B5CF6, 0xFF14B8A6, 0xFFF472B6};
        int[] gx = {Gravity.TOP | Gravity.START, Gravity.TOP | Gravity.END,
                Gravity.BOTTOM | Gravity.START, Gravity.BOTTOM | Gravity.END};
        for (int i = 0; i < 4; i++) {
            GradientDrawable g = new GradientDrawable();
            g.setGradientType(GradientDrawable.RADIAL_GRADIENT);
            g.setGradientRadius(u(290));
            int rgb = cols[i] & 0xFFFFFF;
            g.setColors(new int[]{0xAA000000 | rgb, rgb});
            blobs[i] = new View(this);
            blobs[i].setBackground(g);
            blobs[i].setAlpha(0.5f);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(u(580), u(580), gx[i]);
            lp.setMargins(-u(140), -u(140), -u(140), -u(140));
            aurora.addView(blobs[i], lp);
        }
        saver.addView(aurora, new FrameLayout.LayoutParams(MATCH, MATCH));

        saverBox = new LinearLayout(this);
        saverBox.setOrientation(LinearLayout.VERTICAL);
        saverBox.setGravity(Gravity.CENTER_HORIZONTAL);
        saverClock = text("", 150, 0xE6FFF3E6);
        saverClock.setTypeface(Typeface.create("sans-serif-thin", Typeface.NORMAL));
        saverClock.setShadowLayer(u(14), 0, u(4), 0x99000000);
        saverDate = text("", 28, 0xAAFFE2C4);
        saverDate.setShadowLayer(u(8), 0, u(2), 0x99000000);
        saverBox.addView(saverClock);
        saverBox.addView(saverDate);
        saver.addView(saverBox, new FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER));
        root.addView(saver, new FrameLayout.LayoutParams(MATCH, MATCH));
    }

    private void buildInfo() {
        info = new FrameLayout(this);
        info.setBackgroundColor(0xB8000000);
        info.setVisibility(View.GONE);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(u(48), u(36), u(48), u(40));
        card.setBackground(roundRect(0x55201626, u(34), Math.max(1, u(1)), 0x44FFFFFF));
        infoTitle = text("", 34, TEXT);
        infoTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        infoBody = text("", 24, TEXT_DIM);
        infoBody.setLineSpacing(0, 1.35f);
        infoBody.setPadding(0, u(18), 0, 0);
        card.addView(infoTitle);
        card.addView(infoBody);
        info.addView(card, new FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER));
        root.addView(info, new FrameLayout.LayoutParams(MATCH, MATCH));
    }

    // ================================================================ wallpaper

    private void refreshWallpaper(boolean force) {
        final File custom = new File(getFilesDir(), "wallpaper.jpg");
        final boolean hasCustom = custom.exists();
        final int phase = phaseNow();
        String key = hasCustom ? "c" + custom.lastModified() : "p" + phase;
        if (!force && key.equals(wallKey)) return;
        wallKey = key;
        io.execute(new Runnable() {
            @Override public void run() {
                Bitmap b = null;
                if (hasCustom) {
                    try {
                        BitmapFactory.Options o = new BitmapFactory.Options();
                        o.inJustDecodeBounds = true;
                        BitmapFactory.decodeFile(custom.getPath(), o);
                        int s = 1;
                        while (o.outWidth / s > 1280) s *= 2;
                        o.inJustDecodeBounds = false;
                        o.inSampleSize = s;
                        b = BitmapFactory.decodeFile(custom.getPath(), o);
                    } catch (Throwable ignored) { }
                }
                if (b == null) b = Scenery.render(1280, 720, phase);
                final Bitmap fb = b;
                ui.post(new Runnable() {
                    @Override public void run() { applyWallpaper(fb); }
                });
            }
        });
    }

    private void applyWallpaper(Bitmap b) {
        final int back = 1 - wallFront, front = wallFront;
        boolean first = wallBmp[0] == null && wallBmp[1] == null;
        wallBmp[back] = b;
        wall[back].setImageBitmap(b);
        if (first) {
            wall[back].setAlpha(1f);
            wall[front].setAlpha(0f);
            wallFront = back;
            return;
        }
        wall[back].setAlpha(0f);
        wall[back].animate().alpha(1f).setDuration(1800).start();
        wall[front].animate().alpha(0f).setDuration(1800).withEndAction(new Runnable() {
            @Override public void run() {
                wall[front].setImageDrawable(null);
                if (wallBmp[front] != null) { wallBmp[front].recycle(); wallBmp[front] = null; }
            }
        }).start();
        wallFront = back;
    }

    // ================================================================ data

    private void reload() {
        io.execute(new Runnable() {
            @Override public void run() {
                final List<Apps.Item> list = Apps.queryApps(MainActivity.this, true);
                list.addAll(Apps.tools(MainActivity.this));
                ui.post(new Runnable() {
                    @Override public void run() {
                        all.clear();
                        all.addAll(list);
                        ensureDefaultPins();
                        rebuild();
                    }
                });
            }
        });
    }

    private Apps.Item find(String pkg) {
        for (Apps.Item it : all) if (it.pkg.equals(pkg)) return it;
        return null;
    }

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
        for (Apps.Item it : all) {
            if (pins.size() >= 5) break;
            if (it.tool == null && !pins.contains(it.pkg)) pins.add(it.pkg);
        }
        savePins(pins);
    }

    private void togglePin(Apps.Item it) {
        List<String> pins = getPins();
        boolean removed = pins.remove(it.pkg);
        if (!removed) {
            if (pins.size() >= MAX_DOCK) {
                Toast.makeText(this, "Dock is full (max " + MAX_DOCK + ")", Toast.LENGTH_SHORT).show();
                return;
            }
            pins.add(it.pkg);
        }
        savePins(pins);
        refocusPkg = it.pkg;
        rebuild();
        Toast.makeText(this, (removed ? "Moved to grid: " : "Added to dock: ") + it.label, Toast.LENGTH_SHORT).show();
    }

    // ================================================================ icons

    private void rebuild() {
        if (refocusPkg == null) {
            View f = root.findFocus();
            if (f != null && f.getTag() instanceof String) refocusPkg = (String) f.getTag();
        }
        gridRow.removeAllViews();
        dockRow.removeAllViews();

        List<String> pins = getPins();
        List<String> shown = new ArrayList<>();
        int idx = 0;
        for (String p : pins) {
            Apps.Item it = find(p);
            if (it == null) continue;
            shown.add(p);
            dockRow.addView(makeIcon(it, 80, true, idx++));
        }
        dockRow.setVisibility(shown.isEmpty() ? View.INVISIBLE : View.VISIBLE);

        LinearLayout col = null;
        int n = 0;
        for (Apps.Item it : all) {
            if (shown.contains(it.pkg)) continue;
            if (n % 2 == 0) {
                col = new LinearLayout(this);
                col.setOrientation(LinearLayout.VERTICAL);
                col.setClipChildren(false);
                gridRow.addView(col, new LinearLayout.LayoutParams(WRAP, WRAP));
            }
            col.addView(makeIcon(it, 92, false, idx + n / 2 * 2 + n % 2));
            n++;
        }
        firstBuild = false;

        View target = findCard(refocusPkg);
        if (target == null) target = firstCard();
        refocusPkg = null;
        if (target != null) {
            final View t = target;
            root.post(new Runnable() {
                @Override public void run() { t.requestFocus(); }
            });
        }
    }

    private View firstCard() {
        if (gridRow.getChildCount() > 0) {
            ViewGroup col = (ViewGroup) gridRow.getChildAt(0);
            if (col.getChildCount() > 0) return ((ViewGroup) col.getChildAt(0)).getChildAt(0);
        }
        if (dockRow.getChildCount() > 0) return ((ViewGroup) dockRow.getChildAt(0)).getChildAt(0);
        return null;
    }

    private View findCard(String pkg) {
        if (pkg == null) return null;
        for (int i = 0; i < dockRow.getChildCount(); i++) {
            View c = ((ViewGroup) dockRow.getChildAt(i)).getChildAt(0);
            if (pkg.equals(c.getTag())) return c;
        }
        for (int i = 0; i < gridRow.getChildCount(); i++) {
            ViewGroup col = (ViewGroup) gridRow.getChildAt(i);
            for (int j = 0; j < col.getChildCount(); j++) {
                View c = ((ViewGroup) col.getChildAt(j)).getChildAt(0);
                if (pkg.equals(c.getTag())) return c;
            }
        }
        return null;
    }

    private View makeIcon(final Apps.Item it, int iconU, final boolean dock, int index) {
        int icon = u(iconU);
        int w = u(iconU + 30), hgt = u(iconU + (dock ? 22 : 36));

        // slot = layout + entrance animation;  card = focus animation
        FrameLayout slot = new FrameLayout(this);
        slot.setClipChildren(false);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(w, hgt);
        slp.setMargins(u(dock ? 6 : 8), 0, u(dock ? 6 : 8), u(dock ? 0 : 6));
        slot.setLayoutParams(slp);

        final LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setClipChildren(false);
        card.setTag(it.pkg);
        card.setFocusable(true);
        card.setClickable(true);
        card.setLongClickable(true);

        FrameLayout box = new FrameLayout(this);
        box.setClipChildren(false);
        final View halo = new View(this);
        halo.setBackground(roundRect((it.color & 0xFFFFFF) | 0x73000000, Math.round(icon * 0.32f), 0, 0));
        halo.setScaleX(1.2f);
        halo.setScaleY(1.2f);
        halo.setAlpha(0f);
        box.addView(halo, new FrameLayout.LayoutParams(MATCH, MATCH));
        ImageView iv = new ImageView(this);
        iv.setImageDrawable(it.icon);
        iv.setScaleType(ImageView.ScaleType.FIT_XY);
        box.addView(iv, new FrameLayout.LayoutParams(MATCH, MATCH));
        card.addView(box, new LinearLayout.LayoutParams(icon, icon));

        final TextView label = text(it.label, 15, 0xFFFFFFFF);
        label.setShadowLayer(u(3), 0, u(1), 0xB0000000);
        label.setSingleLine();
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setGravity(Gravity.CENTER);
        if (dock) label.setAlpha(0f);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(w, WRAP);
        tlp.topMargin = u(8);
        card.addView(label, tlp);
        slot.addView(card, new FrameLayout.LayoutParams(w, hgt));

        card.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(final View v, boolean has) {
                float s = has ? 1.14f : 1f;
                v.animate().scaleX(s).scaleY(s).translationY(has ? -u(8) : 0)
                        .setDuration(has ? 260 : 170)
                        .setInterpolator(has ? OVERSHOOT : DECEL).start();
                halo.animate().alpha(has ? 1f : 0f).setDuration(has ? 200 : 150).start();
                if (dock) label.animate().alpha(has ? 1f : 0f).setDuration(150).start();
                if (has) {
                    pendingColor = it.color;                  // LED strip follows focus (debounced)
                    ui.removeCallbacks(ambientRunnable);
                    ui.postDelayed(ambientRunnable, 220);
                    if (!dock) v.post(new Runnable() {
                        @Override public void run() { centerOn(v); }
                    });
                }
            }
        });
        card.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(final View v) {
                v.animate().scaleX(1.05f).scaleY(1.05f).setDuration(90)
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

        if (firstBuild) {
            slot.setAlpha(0f);
            slot.setTranslationY(u(46));
            slot.animate().alpha(1f).translationY(0f)
                    .setStartDelay(160 + index * 38L).setDuration(500)
                    .setInterpolator(new DecelerateInterpolator(1.7f)).start();
        }
        return slot;
    }

    private void centerOn(View card) {
        int x = 0;
        View v = card;
        while (v.getParent() instanceof View && !(v.getParent() instanceof CenterScroll)) {
            x += v.getLeft();
            v = (View) v.getParent();
        }
        if (!(v.getParent() instanceof CenterScroll)) return;
        CenterScroll s = (CenterScroll) v.getParent();
        int target = s.getPaddingLeft() + x + card.getWidth() / 2 - s.getWidth() / 2;
        s.smoothScrollTo(Math.max(0, target), 0);
    }

    // ================================================================ actions

    private void launch(Apps.Item it) {
        if (it.tool != null) { runTool(it.tool); return; }
        try {
            startActivity(it.intent);
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        } catch (Exception e) {
            Toast.makeText(this, "Can't open " + it.label, Toast.LENGTH_SHORT).show();
        }
    }

    private void runTool(String id) {
        switch (id) {
            case "tool.wifi": openSettings(Settings.ACTION_WIFI_SETTINGS); break;
            case "tool.display": openSettings(Settings.ACTION_DISPLAY_SETTINGS); break;
            case "tool.sound": openSettings(Settings.ACTION_SOUND_SETTINGS); break;
            case "tool.storage": openSettings(Settings.ACTION_INTERNAL_STORAGE_SETTINGS); break;
            case "tool.dev": openSettings(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS); break;
            case "tool.clean": cleanRam(); break;
            case "tool.saver": showSaver(); break;
            case "tool.remote": showRemoteInfo(); break;
            default: break;
        }
    }

    private void openSettings(String action) {
        try {
            startActivity(new Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            Toast.makeText(this, "Not available on this TV", Toast.LENGTH_SHORT).show();
        }
    }

    private void cleanRam() {
        Toast.makeText(this, "Cleaning\u2026", Toast.LENGTH_SHORT).show();
        io.execute(new Runnable() {
            @Override public void run() {
                final long[] r = Apps.cleanRam(MainActivity.this);
                ui.post(new Runnable() {
                    @Override public void run() {
                        updateFooter();
                        Toast.makeText(MainActivity.this, "RAM free: " + r[1] + " MB (was " + r[0] + " MB)",
                                Toast.LENGTH_LONG).show();
                    }
                });
            }
        });
    }

    private void showRemoteInfo() {
        String ip = Apps.localIp();
        showInfo("Control your TV from your phone",
                ip == null ? "The TV is not connected to Wi-Fi."
                        : "1.  Put your phone on the same Wi-Fi\n2.  Open  http://" + ip + ":" + RemoteServer.PORT
                        + "\n3.  Enter the PIN  " + RemoteServer.pin(this)
                        + "\n\nPress any button to close");
    }

    private void showInfo(String title, String body) {
        infoTitle.setText(title);
        infoBody.setText(body);
        infoOn = true;
        info.setAlpha(0f);
        info.setVisibility(View.VISIBLE);
        info.animate().alpha(1f).setDuration(260).start();
    }

    private void hideInfo() {
        infoOn = false;
        info.animate().alpha(0f).setDuration(200).withEndAction(new Runnable() {
            @Override public void run() { if (!infoOn) info.setVisibility(View.GONE); }
        }).start();
    }

    // ================================================================ ambient motion

    private void startDrift() {
        if (drift != null) {
            if (!saverOn) for (ObjectAnimator a : drift) a.resume();
            return;
        }
        drift = new ObjectAnimator[]{
                loop(wallHolder, "translationX", -u(38), u(38), 70000),
                loop(wallHolder, "translationY", -u(16), u(16), 53000)
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

    // ================================================================ screensaver

    private void resetIdle() {
        ui.removeCallbacks(idleRunnable);
        ui.postDelayed(idleRunnable, IDLE_MS);
    }

    private void showSaver() {
        if (saverOn || infoOn) return;
        saverOn = true;
        String mode = prefs.getString("saver_mode", "scenic");
        boolean aur = "aurora".equals(mode), scenic = "scenic".equals(mode);
        updateClock();

        saver.setBackgroundColor(aur ? 0xF4050810 : scenic ? 0x00000000 : 0xF4070506);
        aurora.setVisibility(aur ? View.VISIBLE : View.GONE);
        saver.animate().cancel();
        saver.setAlpha(0f);
        saver.setVisibility(View.VISIBLE);
        saver.animate().alpha(1f).setDuration(1000).start();
        content.animate().alpha(0f).setDuration(900).start();

        if (saverAnims == null) {
            saverAnims = new ObjectAnimator[]{
                    loop(saverBox, "translationX", -u(170), u(170), 40000),
                    loop(saverBox, "translationY", -u(90), u(90), 52000)
            };
            for (ObjectAnimator a : saverAnims) a.start();
        } else {
            for (ObjectAnimator a : saverAnims) a.resume();
        }

        if (!scenic && drift != null) for (ObjectAnimator a : drift) a.pause();

        if (aur) {
            for (View v : blobs) v.setLayerType(View.LAYER_TYPE_HARDWARE, null);   // cheap drift, freed on exit
            if (auroraAnims == null) {
                List<ObjectAnimator> l = new ArrayList<>();
                for (int i = 0; i < 4; i++) {
                    ObjectAnimator a = loop(blobs[i], "alpha", 0.25f, 0.95f, 8000 + i * 2300);
                    ObjectAnimator x = loop(blobs[i], "translationX", -u(120), u(120), 15000 + i * 3100);
                    ObjectAnimator y = loop(blobs[i], "translationY", -u(90), u(90), 19000 + i * 2700);
                    l.add(a); l.add(x); l.add(y);
                }
                auroraAnims = l.toArray(new ObjectAnimator[0]);
                for (ObjectAnimator a : auroraAnims) a.start();
            } else {
                for (ObjectAnimator a : auroraAnims) a.resume();
            }
            ui.removeCallbacks(cycleRunnable);
            ui.post(cycleRunnable);                                              // LED strip drifts through colours
        }
    }

    private void hideSaver(boolean instant) {
        if (!saverOn) return;
        saverOn = false;
        saver.animate().cancel();
        ui.removeCallbacks(cycleRunnable);
        if (instant) {
            saver.setVisibility(View.GONE);
            content.setAlpha(1f);
        } else {
            saver.animate().alpha(0f).setDuration(350).withEndAction(new Runnable() {
                @Override public void run() { if (!saverOn) saver.setVisibility(View.GONE); }
            }).start();
            content.animate().alpha(1f).setDuration(450).start();
            if (drift != null) for (ObjectAnimator a : drift) a.resume();
        }
        if (saverAnims != null) for (ObjectAnimator a : saverAnims) a.pause();
        if (auroraAnims != null) for (ObjectAnimator a : auroraAnims) a.pause();
        for (View v : blobs) v.setLayerType(View.LAYER_TYPE_NONE, null);
        Ambient.applyMode(this);                                                 // back to normal lighting
        resetIdle();
    }
}
