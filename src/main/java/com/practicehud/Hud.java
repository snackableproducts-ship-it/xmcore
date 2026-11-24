package com.practicehud;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Shared renderer for compact, modern HUD cards.
 *
 * Panels without a custom position are flowed top-to-bottom from the HUD anchor and wrap into
 * additional columns when they would run off the bottom of the screen, so they never overlap.
 * Rows written as "Label: value" are drawn with a muted label on the left and the value,
 * in the row colour, aligned to the right.
 */
public final class Hud {
    public static final int WHITE = 0xFFF4F6FF;
    public static final int GRAY = 0xFF9AA3B8;
    public static final int GREEN = 0xFF72E0B0;
    public static final int YELLOW = 0xFFFFC66D;
    public static final int RED = 0xFFFF718A;
    public static final int AQUA = 0xFF73D9F5;
    public static final int GOLD = 0xFFFFC66D;

    // Surface colours.
    private static final int SHADOW = 0x30000000;
    private static final int BORDER = 0x3A8FA0D0;
    private static final int BG = 0xE60B101D;
    private static final int DIVIDER = 0x2EFFFFFF;
    private static final int TRACK = 0xFF232C42;

    // Metrics.
    private static final int PAD = 8;
    private static final int TITLE_H = 19;
    private static final int ROW_H = 11;
    private static final int BAR_ROW_H = 15;
    private static final int BOTTOM = 5;
    private static final int GAP = 5;
    private static final int MIN_W = 118;
    private static final int MAX_W = 230;

    public record Row(String text, int color, float frac) {
        public static Row of(String text, int color) { return new Row(text, color, -1f); }
        public static Row bar(String text, float frac, int color) {
            return new Row(text, color, Math.max(0f, Math.min(1f, frac)));
        }
    }

    // Flow layout state (reset every frame by begin()).
    private static int cursorX = 4;
    private static int cursorY = 4;
    private static int colW = 0;
    /** Widest size seen per panel, so values changing length never make a panel flicker. */
    private static final Map<String, Integer> WIDTHS = new HashMap<>();
    /** Panels already drawn this frame (several modules can share one panel; draw it once). */
    private static final Set<String> DRAWN = new HashSet<>();

    // Animation state: panels glide to their flow position and bars ease to their value.
    private static long frame = 0;
    private static long lastNanos = 0;
    private static float dt = 0.016f;
    private static final Map<String, float[]> ANIM = new HashMap<>();
    private static final Map<String, Float> BARS = new HashMap<>();
    /** Final on-screen rectangle of every panel drawn this frame: {x, y, w, h, frame}. Used by the HUD editor. */
    private static final Map<String, int[]> BOUNDS = new HashMap<>();

    /** Screen rectangle {x, y, w, h} of a panel drawn in the latest frame, or null if it was not drawn. */
    public static int[] bounds(String title) {
        int[] b = BOUNDS.get(title);
        return b != null && b[4] >= (int) frame ? b : null;
    }

    public static java.util.List<String> drawnTitles() {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (Map.Entry<String, int[]> e : BOUNDS.entrySet()) if (e.getValue()[4] >= (int) frame) out.add(e.getKey());
        return out;
    }

    private Hud() {}

    private static float ease(float rate) { return 1f - (float) Math.exp(-dt * rate); }

    public static void begin() {
        Config c = Config.get();
        cursorX = c.hudX;
        cursorY = c.hudY;
        colW = 0;
        DRAWN.clear();
        long now = System.nanoTime();
        dt = lastNanos == 0 ? 0.016f : Math.max(0.001f, Math.min(0.1f, (now - lastNanos) / 1e9f));
        lastNanos = now;
        frame++;
    }

    /** Per-widget scale from the HUD editor times the global HUD scale. */
    public static float scaleFor(String title) {
        Config c = Config.get();
        float s = c.hudScales.getOrDefault(title, 1f);
        return Math.max(0.5f, Math.min(2.5f, s * (float) c.hudScale));
    }

    /** Per-widget accent colour from the HUD editor, falling back to the module's own accent. */
    public static int accentFor(String title, int fallback) {
        Integer custom = Config.get().hudColors.get(title);
        return custom == null ? fallback : (custom | 0xFF000000);
    }

    // =====================================================================
    //  Panels
    // =====================================================================

    public static void panel(DrawContext ctx, String title, int accent, Row... rows) {
        if (!DRAWN.add(title)) return;
        accent = accentFor(title, accent);
        TextRenderer tr = MinecraftClient.getInstance().textRenderer;
        int screenW = ctx.getScaledWindowWidth();
        int limit = Math.min(MAX_W, Math.max(MIN_W, screenW - 16));

        int w = Math.max(MIN_W, tr.getWidth(title) + PAD * 2 + 10);
        int h = TITLE_H + BOTTOM;
        for (Row r : rows) {
            w = Math.max(w, rowWidth(tr, r) + PAD * 2);
            h += r.frac() >= 0 ? BAR_ROW_H : ROW_H;
        }
        w = Math.min(limit, Math.max(w, WIDTHS.getOrDefault(title, 0)));
        WIDTHS.put(title, w);

        float scale = scaleFor(title);
        boolean scaled = Math.abs(scale - 1f) > 0.01f;
        int[] pos = place(ctx, title, Math.round(w * scale), Math.round(h * scale));
        int x = pos[0], y = pos[1];
        if (scaled) {
            var m = ctx.getMatrices();
            m.pushMatrix();
            m.translate((float) x, (float) y);
            m.scale(scale, scale);
            x = 0;
            y = 0;
        }
        surface(ctx, x, y, w, h, accent);
        header(ctx, tr, title, x, y, w, accent);

        int ry = y + TITLE_H;
        int inner = w - PAD * 2;
        int barIndex = 0;
        for (Row r : rows) {
            String[] parts = split(r.text());
            if (parts == null) {
                ctx.drawTextWithShadow(tr, fit(tr, r.text(), inner), x + PAD, ry, r.color());
            } else {
                int labelW = tr.getWidth(parts[0]);
                String value = fit(tr, parts[1], Math.max(8, inner - labelW - 10));
                ctx.drawTextWithShadow(tr, parts[0], x + PAD, ry, GRAY);
                ctx.drawTextWithShadow(tr, value, x + w - PAD - tr.getWidth(value), ry, r.color());
            }
            if (r.frac() >= 0) {
                ctx.fill(x + PAD, ry + 11, x + w - PAD, ry + 13, TRACK);
                String bk = title + "#" + (barIndex++);
                float shown = BARS.getOrDefault(bk, r.frac());
                shown += (r.frac() - shown) * ease(10f);
                BARS.put(bk, shown);
                int fill = Math.round(inner * shown);
                if (fill > 0) ctx.fill(x + PAD, ry + 11, x + PAD + fill, ry + 13, r.color());
                ry += BAR_ROW_H;
            } else {
                ry += ROW_H;
            }
        }
        if (scaled) ctx.getMatrices().popMatrix();
    }

    /** Lets a module draw its own card contents inside the standard Hud surface/header/placement. */
    @FunctionalInterface
    public interface Painter { void paint(DrawContext ctx, int x, int y, int w, int h); }

    public static void custom(DrawContext ctx, String title, int accent, int w, int h, Painter painter) {
        if (!DRAWN.add(title)) return;
        accent = accentFor(title, accent);
        TextRenderer tr = MinecraftClient.getInstance().textRenderer;
        float scale = scaleFor(title);
        boolean scaled = Math.abs(scale - 1f) > 0.01f;
        int[] pos = place(ctx, title, Math.round(w * scale), Math.round(h * scale));
        int x = pos[0], y = pos[1];
        if (scaled) {
            var m = ctx.getMatrices();
            m.pushMatrix();
            m.translate((float) x, (float) y);
            m.scale(scale, scale);
            x = 0;
            y = 0;
        }
        surface(ctx, x, y, w, h, accent);
        header(ctx, tr, title, x, y, w, accent);
        painter.paint(ctx, x, y + TITLE_H, w, h - TITLE_H);
        if (scaled) ctx.getMatrices().popMatrix();
    }

    public static int titleHeight() { return TITLE_H; }
    public static int padding() { return PAD; }

    /** A small bar graph card (e.g. FPS history). {@code values} are drawn oldest to newest. */
    public static void graph(DrawContext ctx, String title, int accent, int[] values, String valueText, int valueColor) {
        if (!DRAWN.add(title)) return;
        accent = accentFor(title, accent);
        TextRenderer tr = MinecraftClient.getInstance().textRenderer;
        int graphH = 24;
        int w = Math.max(MIN_W, values.length * 3 + PAD * 2 - 1);
        int h = TITLE_H + graphH + BOTTOM;
        float scale = scaleFor(title);
        boolean scaled = Math.abs(scale - 1f) > 0.01f;
        int[] pos = place(ctx, title, Math.round(w * scale), Math.round(h * scale));
        int x = pos[0], y = pos[1];
        if (scaled) {
            var m = ctx.getMatrices();
            m.pushMatrix();
            m.translate((float) x, (float) y);
            m.scale(scale, scale);
            x = 0;
            y = 0;
        }
        surface(ctx, x, y, w, h, accent);
        header(ctx, tr, title, x, y, w, accent);
        if (valueText != null && !valueText.isEmpty()) {
            ctx.drawTextWithShadow(tr, valueText, x + w - PAD - tr.getWidth(valueText), y + 4, valueColor);
        }

        int max = 1;
        for (int v : values) max = Math.max(max, v);
        int base = y + TITLE_H + graphH;
        ctx.fill(x + PAD, base, x + w - PAD, base + 1, DIVIDER);
        for (int i = 0; i < values.length; i++) {
            int bh = Math.max(1, Math.round(values[i] / (float) max * (graphH - 2)));
            int bx = x + PAD + i * 3;
            boolean latest = i == values.length - 1;
            ctx.fill(bx, base - bh, bx + 2, base, latest ? WHITE : (accent & 0x00FFFFFF) | 0xCC000000);
        }
        if (scaled) ctx.getMatrices().popMatrix();
    }

    public static void centered(DrawContext ctx, String text, int cx, int y, int color) {
        TextRenderer tr = MinecraftClient.getInstance().textRenderer;
        ctx.drawTextWithShadow(tr, text, cx - tr.getWidth(text) / 2, y, color);
    }

    public static String time(int ticks) {
        int s = ticks / 20;
        return String.format("%d:%02d", s / 60, s % 60);
    }

    // =====================================================================
    //  Internals
    // =====================================================================

    /** Picks the top-left corner for a panel and advances the flow layout. */
    private static int[] place(DrawContext ctx, String title, int w, int h) {
        Config c = Config.get();
        int sw = ctx.getScaledWindowWidth(), sh = ctx.getScaledWindowHeight();
        int[] custom = c.hudPositions.get(title);
        int x, y;
        float[] st = ANIM.get(title);
        boolean fresh = st == null || st[2] < frame - 1;
        if (custom != null) {
            x = custom[0];
            y = custom[1];
            ANIM.put(title, new float[]{x, y, frame});
        } else {
            // Start a new column instead of running off the bottom (which used to stack panels).
            if (cursorY > c.hudY && cursorY + h > sh - 4) {
                cursorX += colW + GAP;
                cursorY = c.hudY;
                colW = 0;
            }
            float tx = cursorX, ty = cursorY;
            cursorY += h + GAP;
            colW = Math.max(colW, w);
            if (fresh) {
                st = new float[]{tx - 14f, ty, frame};   // slide in from the left
            } else {
                st[0] += (tx - st[0]) * ease(14f);
                st[1] += (ty - st[1]) * ease(14f);
                st[2] = frame;
            }
            if (Math.abs(tx - st[0]) < 0.3f) st[0] = tx;
            if (Math.abs(ty - st[1]) < 0.3f) st[1] = ty;
            ANIM.put(title, st);
            x = Math.round(st[0]);
            y = Math.round(st[1]);
        }
        x = Math.max(0, Math.min(sw - w, x));
        y = Math.max(0, Math.min(sh - h, y));
        BOUNDS.put(title, new int[]{x, y, w, h, (int) frame});
        return new int[]{x, y};
    }

    private static void surface(DrawContext ctx, int x, int y, int w, int h, int accent) {
        rounded(ctx, x + 1, y + 2, w, h, 3, SHADOW);
        rounded(ctx, x, y, w, h, 3, BORDER);
        rounded(ctx, x + 1, y + 1, w - 2, h - 2, 2, BG);
        ctx.fill(x + 1, y + 5, x + 3, y + h - 5, accent);
    }

    private static void header(DrawContext ctx, TextRenderer tr, String title, int x, int y, int w, int accent) {
        ctx.fill(x + PAD, y + 6, x + PAD + 3, y + 9, accent);
        ctx.drawTextWithShadow(tr, title, x + PAD + 7, y + 4, WHITE);
        ctx.fill(x + PAD, y + 15, x + w - PAD, y + 16, DIVIDER);
    }

    /** Filled rectangle with softly clipped corners. */
    private static void rounded(DrawContext ctx, int x, int y, int w, int h, int r, int color) {
        if (w <= 0 || h <= 0) return;
        r = Math.min(r, Math.min(w, h) / 2);
        if (r <= 0) { ctx.fill(x, y, x + w, y + h, color); return; }
        ctx.fill(x, y + r, x + w, y + h - r, color);
        for (int i = 0; i < r; i++) {
            double dy = r - i - 0.5;
            int inset = r - (int) Math.round(Math.sqrt(r * r - dy * dy));
            ctx.fill(x + inset, y + i, x + w - inset, y + i + 1, color);
            ctx.fill(x + inset, y + h - 1 - i, x + w - inset, y + h - i, color);
        }
    }

    /** Splits "Label: value" rows; returns null for rows that should be drawn as one string. */
    private static String[] split(String text) {
        int i = text.indexOf(": ");
        if (i <= 0 || i >= text.length() - 2) return null;
        return new String[]{text.substring(0, i), text.substring(i + 2)};
    }

    private static int rowWidth(TextRenderer tr, Row r) {
        String[] parts = split(r.text());
        return parts == null ? tr.getWidth(r.text()) : tr.getWidth(parts[0]) + 10 + tr.getWidth(parts[1]);
    }

    private static String fit(TextRenderer tr, String s, int maxW) {
        if (tr.getWidth(s) <= maxW) return s;
        int end = s.length();
        while (end > 0 && tr.getWidth(s.substring(0, end) + "..") > maxW) end--;
        return s.substring(0, end) + "..";
    }
}
