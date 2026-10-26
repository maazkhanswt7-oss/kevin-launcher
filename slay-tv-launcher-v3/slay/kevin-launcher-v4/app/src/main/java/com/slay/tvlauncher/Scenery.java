package com.slay.tvlauncher;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;

import java.util.Random;

/**
 * Paints a layered mountain-valley landscape (think Swat) for four times of day.
 * Drawn once into a bitmap, so it costs nothing while you navigate.
 * phase: 0 night, 1 dawn, 2 day, 3 golden hour.
 */
final class Scenery {
    // top, mid, horizon, snow, ridge0..3, pine, celestial
    private static final int[][] PAL = {
            {0xFF070B1E, 0xFF14204A, 0xFF2C3A6B, 0xFF8FA0D0, 0xFF26335E, 0xFF1B2650, 0xFF121B3D, 0xFF0A1128, 0xFF050914, 0xFFEFF3FF},
            {0xFF2B2F6B, 0xFFB0658E, 0xFFFFB27A, 0xFFFFD6C0, 0xFF8B5E8F, 0xFF6A4A7E, 0xFF47356A, 0xFF281F45, 0xFF140F28, 0xFFFFE0B0},
            {0xFF2E7BD6, 0xFF6DB3EE, 0xFFCFE9F7, 0xFFFFFFFF, 0xFF8FB3C9, 0xFF5F8FA8, 0xFF3F6E7E, 0xFF264E4A, 0xFF12302C, 0xFFFFF6D6},
            {0xFF3A2A6B, 0xFFE26D5C, 0xFFFFC27A, 0xFFFFC8A0, 0xFFB0627A, 0xFF8A4A6A, 0xFF5B3560, 0xFF2D1E48, 0xFF170F2C, 0xFFFFD79A},
    };
    private static final float[][] SUN = {{0.74f, 0.22f}, {0.28f, 0.60f}, {0.76f, 0.20f}, {0.68f, 0.52f}};
    private static final float[] BASE = {0.55f, 0.63f, 0.73f, 0.85f};
    private static final float[] AMP = {0.26f, 0.13f, 0.09f, 0.06f};

    static int phaseForHour(int h) {
        if (h >= 5 && h < 7) return 1;
        if (h >= 7 && h < 16) return 2;
        if (h >= 16 && h < 19) return 3;
        return 0;
    }

    private static int alpha(int c, int a) { return (c & 0xFFFFFF) | (a << 24); }

    private static int lerp(int a, int b, float t) {
        return Color.rgb(
                (int) (Color.red(a) + (Color.red(b) - Color.red(a)) * t),
                (int) (Color.green(a) + (Color.green(b) - Color.green(a)) * t),
                (int) (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t));
    }

    private static float ridgeY(float x, int w, int h, int L, float[] ph) {
        float t = x / w, v = 0, a = 1, norm = 0, fr = 1.5f + L * 0.8f;
        for (int k = 0; k < 5; k++) {
            v += a * (float) Math.sin(2 * Math.PI * (t * fr * (1f + 1.1f * k)) + ph[k]);
            norm += a;
            a *= 0.52f;
        }
        float n = v / norm;                                     // -1..1
        float hgt = L == 0 ? (float) Math.pow(1f - Math.abs(n), 1.4) : (0.5f + 0.5f * n);   // L0 = sharp peaks
        return h * (BASE[L] - AMP[L] * hgt);
    }

    static Bitmap render(int w, int h, int phase) {
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Random rnd = new Random(1000 + phase * 37);
        int[] pal = PAL[phase];
        int top = pal[0], mid = pal[1], hor = pal[2], snow = pal[3], pine = pal[8], cel = pal[9];
        float s = h / 720f;
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        // sky
        p.setShader(new LinearGradient(0, 0, 0, h * 0.78f, new int[]{top, mid, hor},
                new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);

        // stars
        if (phase == 0) {
            p.setShader(null);
            for (int i = 0; i < 170; i++) {
                float x = rnd.nextFloat() * w, y = rnd.nextFloat() * h * 0.55f;
                float r = (0.5f + rnd.nextFloat() * 1.3f) * s;
                p.setColor(Color.argb(90 + rnd.nextInt(165), 255, 255, 255));
                c.drawCircle(x, y, r, p);
            }
        }

        // sun / moon with halo
        float cx = SUN[phase][0] * w, cy = SUN[phase][1] * h;
        float halo = h * (phase == 2 ? 0.38f : 0.32f);
        p.setShader(new RadialGradient(cx, cy, halo, new int[]{alpha(cel, 150), alpha(cel, 0)},
                null, Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, halo, p);
        p.setShader(null);
        p.setColor(cel);
        c.drawCircle(cx, cy, h * (phase == 0 ? 0.034f : 0.05f), p);

        // ridges, far -> near
        float[][] ph = new float[4][5];
        for (int L = 0; L < 4; L++) for (int k = 0; k < 5; k++) ph[L][k] = rnd.nextFloat() * 6.2832f;
        int steps = w / 4 + 1;
        float[] nearY = new float[steps + 1];
        for (int L = 0; L < 4; L++) {
            Path path = new Path();
            path.moveTo(0, h);
            for (int i = 0; i <= steps; i++) {
                float x = Math.min(i * 4f, w);
                float y = ridgeY(x, w, h, L, ph[L]);
                if (L == 3) nearY[i] = y;
                path.lineTo(x, y);
            }
            path.lineTo(w, h);
            path.close();

            int col = pal[4 + L];
            float yTop = h * (BASE[L] - AMP[L]), yBot = h * (BASE[L] + AMP[L] * 1.2f);
            int hazed = lerp(col, hor, L == 0 ? 0.55f : 0.35f);
            if (L == 0) {
                p.setShader(new LinearGradient(0, yTop, 0, yBot, new int[]{snow, col, hazed},
                        new float[]{0f, 0.42f, 1f}, Shader.TileMode.CLAMP));
            } else if (L < 3) {
                p.setShader(new LinearGradient(0, yTop, 0, yBot, col, hazed, Shader.TileMode.CLAMP));
            } else {
                p.setShader(null);
                p.setColor(col);
            }
            c.drawPath(path, p);

            if (L == 1 || L == 2) {                              // valley mist
                float y0 = h * BASE[L] - 0.05f * h, y1 = h * BASE[L] + 0.07f * h;
                p.setShader(new LinearGradient(0, y0, 0, y1,
                        new int[]{alpha(hor, 0), alpha(hor, 80), alpha(hor, 0)},
                        new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
                c.drawRect(0, y0, w, y1, p);
            }
        }

        // pine forest along the nearest ridge
        p.setShader(null);
        p.setColor(pine);
        float x = 0;
        while (x < w) {
            float ty = nearY[Math.min((int) (x / 4f), steps)];
            float th = (28 + rnd.nextFloat() * 52) * s, tw = th * 0.36f;
            Path t = new Path();
            for (int tier = 0; tier < 3; tier++) {
                float yb = ty + 4 * s - tier * th * 0.26f;
                float wd = tw * (1f - tier * 0.26f);
                t.moveTo(x - wd, yb);
                t.lineTo(x, yb - th * 0.52f);
                t.lineTo(x + wd, yb);
                t.close();
            }
            c.drawPath(t, p);
            x += (9 + rnd.nextFloat() * 14) * s;
        }

        // cinematic vignette
        p.setShader(new RadialGradient(w / 2f, h / 2f, (float) Math.hypot(w, h) / 2f,
                new int[]{0x00000000, 0x00000000, 0x66000000}, new float[]{0f, 0.55f, 1f},
                Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        return bmp;
    }
}
