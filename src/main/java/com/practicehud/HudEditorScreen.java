package com.practicehud;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * HUD Studio: a live editor. The real HUD is drawn on screen and every panel can be grabbed and
 * dragged directly. Dragging snaps to screen edges/centre, to other panels and to a grid, with
 * alignment guides. A side panel lists every widget (including ones whose module is off) and edits
 * scale, colour and alignment. Everything saves automatically.
 */
public final class HudEditorScreen extends Screen {
    /** True only while this screen is drawing the HUD itself (the normal HUD pass is skipped meanwhile). */
    public static boolean drawing = false;

    private static final List<String> KNOWN = List.of(
            "Performance", "FPS History", "Session Stats", "Coordinates", "Armor Status", "Potion Effects",
            "Keystrokes", "Held Item", "World Time", "Hotbar Layout", "Safe Mode",
            "Target", "Combat", "Combat Tag", "Totem Pops", "Totems", "Critical Hit", "Cooldown", "Shield",
            "Blast Damage", "Crystal Assist", "Crystal Setup", "Anchor Safety", "Respawn Anchor",
            "Mace", "Wind Burst Trainer", "Wind Guide", "Movement", "Movement Mode", "Vertical Speed",
            "Guard Timing", "Elytra", "Minecarts", "Cart Safety", "Rail Supplies", "Pearl Assist",
            "Throw Landing", "Potion Supplies", "Storage Display",
            "DonutSMP Balance", "DonutSMP Live Tracker", "DonutSMP Top Earners",
            "DonutSMP Auction", "DonutSMP Live Auction", "DonutSMP Stream Info");
    private static final int[] SWATCHES = {0, 0xFF55D6FF, 0xFFB48BEE, 0xFF72E0B0, 0xFFFFC66D, 0xFFFF718A, 0xFFE78FD5, 0xFFF4F6FF};
    private static final int[] GRIDS = {1, 2, 4, 8, 16};

    private static final int SIDE_W = 152, BAR_H = 24, SNAP = 5, ROW = 12, INSPECTOR_H = 112;
    private static final int PANEL = 0xF2101626, CARD = 0xFF1A2236, HOVER = 0xFF27304A, MUTED = 0xFF8994AD, TEXT = 0xFFF2F5FF;

    private record Hit(int x, int y, int w, int h, Runnable action) {}

    private static String selected = null;
    private static boolean sideOpen = true;

    private final List<Hit> hits = new ArrayList<>();
    private final List<Integer> guideX = new ArrayList<>();
    private final List<Integer> guideY = new ArrayList<>();
    private boolean dragging, moved;
    private int dragDX, dragDY;
    private int listScroll = 0;
    private String hoverTitle;
    private long confirmResetUntil = 0L;

    public HudEditorScreen() { super(Text.literal("HUD Studio")); }

    // ---------------------------------------------------------------- data helpers

    private int accent() { return Config.get().accentColor | 0xFF000000; }

    private List<String> widgets() {
        Set<String> set = new LinkedHashSet<>(KNOWN);
        set.addAll(Hud.drawnTitles());
        List<String> out = new ArrayList<>(set);
        out.sort(Comparator.comparing((String t) -> Hud.bounds(t) == null).thenComparing(t -> t));
        return out;
    }

    /** {x, y, w, h} of a widget: its live bounds, or a ghost box at its saved position when its module is off. */
    private int[] rect(String t) {
        int[] b = Hud.bounds(t);
        if (b != null) return new int[]{b[0], b[1], b[2], b[3]};
        int[] p = Config.get().hudPositions.get(t);
        if (p == null) return null;
        float s = Hud.scaleFor(t);
        return new int[]{p[0], p[1], Math.round(130 * s), Math.round(44 * s)};
    }

    private void ensurePinned(String t) {
        if (Config.get().hudPositions.get(t) != null) return;
        int[] r = rect(t);
        if (r != null) Config.get().hudPositions.put(t, new int[]{r[0], r[1]});
    }

    private void setPos(String t, int x, int y) {
        int[] r = rect(t);
        int w = r == null ? 130 : r[2], h = r == null ? 44 : r[3];
        x = Math.max(0, Math.min(Math.max(0, width - w), x));
        y = Math.max(0, Math.min(Math.max(0, height - h), y));
        Config.get().hudPositions.put(t, new int[]{x, y});
    }

    private void select(String t) {
        selected = t;
        if (rect(t) == null) {
            Config.get().hudPositions.put(t, new int[]{width / 2 - 65, height / 2 - 22});
            Config.save();
        }
    }

    private void setScale(String t, float v) {
        v = Math.round(Math.max(0.5f, Math.min(2.5f, v)) * 20f) / 20f;
        if (Math.abs(v - 1f) < 0.01f) Config.get().hudScales.remove(t);
        else Config.get().hudScales.put(t, v);
        Config.save();
    }

    private float ownScale(String t) { return Config.get().hudScales.getOrDefault(t, 1f); }

    private boolean key(int k) {
        long w = MinecraftClient.getInstance().getWindow().getHandle();
        return GLFW.glfwGetKey(w, k) == GLFW.GLFW_PRESS;
    }
    private boolean shift() { return key(GLFW.GLFW_KEY_LEFT_SHIFT) || key(GLFW.GLFW_KEY_RIGHT_SHIFT); }
    private boolean noSnap() { return key(GLFW.GLFW_KEY_LEFT_ALT) || key(GLFW.GLFW_KEY_RIGHT_ALT)
            || key(GLFW.GLFW_KEY_LEFT_CONTROL) || key(GLFW.GLFW_KEY_RIGHT_CONTROL); }

    private String fit(String s, int maxW) {
        if (textRenderer.getWidth(s) <= maxW) return s;
        String r = s;
        while (!r.isEmpty() && textRenderer.getWidth(r + "..") > maxW) r = r.substring(0, r.length() - 1);
        return r + "..";
    }

    private boolean inside(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    // ---------------------------------------------------------------- drawing helpers

    private int btn(DrawContext ctx, int mx, int my, int x, int y, int w, int h, String label, boolean active, Runnable action) {
        boolean hov = inside(mx, my, x, y, w, h);
        ctx.fill(x, y, x + w, y + h, active ? (accent() & 0x00FFFFFF) | 0x66000000 : hov ? HOVER : CARD);
        if (active) ctx.fill(x, y + h - 1, x + w, y + h, accent());
        ctx.drawCenteredTextWithShadow(textRenderer, label, x + w / 2, y + (h - 8) / 2, hov || active ? TEXT : 0xFFC7CFE2);
        hits.add(new Hit(x, y, w, h, action));
        return x + w + 4;
    }

    private void outline(DrawContext ctx, int x, int y, int w, int h, int color, int t) {
        ctx.fill(x - t, y - t, x + w + t, y, color);
        ctx.fill(x - t, y + h, x + w + t, y + h + t, color);
        ctx.fill(x - t, y, x, y + h, color);
        ctx.fill(x + w, y, x + w + t, y + h, color);
    }

    private String pick(double mx, double my) {
        String best = null;
        int bestArea = Integer.MAX_VALUE;
        for (String t : widgets()) {
            int[] r = rect(t);
            if (r == null || !inside(mx, my, r[0], r[1], r[2], r[3])) continue;
            int area = r[2] * r[3];
            if (area < bestArea) { best = t; bestArea = area; }
        }
        return best;
    }

    // ---------------------------------------------------------------- render

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        hits.clear();

        // The editor draws the real HUD so panels can be grabbed exactly where they appear.
        MinecraftClient mc = MinecraftClient.getInstance();
        drawing = true;
        try { Modules.renderAll(ctx, mc); } finally { drawing = false; }

        Config cfg = Config.get();
        int acc = accent();
        boolean dragNow = dragging && moved;
        boolean sideShown = sideOpen && !dragNow;
        ctx.fill(0, 0, width, height, 0x22000000);

        if (dragNow && cfg.hudSnap && cfg.hudGrid >= 4) {
            for (int gx = 0; gx < width; gx += cfg.hudGrid) ctx.fill(gx, 0, gx + 1, height, 0x0FFFFFFF);
            for (int gy = 0; gy < height; gy += cfg.hudGrid) ctx.fill(0, gy, width, gy + 1, 0x0FFFFFFF);
        }

        boolean overUi = mouseY < BAR_H || (sideShown && mouseX >= width - SIDE_W);
        hoverTitle = overUi || dragNow ? null : pick(mouseX, mouseY);

        List<String> all = widgets();
        for (String t : all) {
            int[] r = rect(t);
            if (r == null) continue;
            boolean drawn = Hud.bounds(t) != null;
            boolean sel = t.equals(selected), hov = t.equals(hoverTitle);
            if (!drawn) {
                ctx.fill(r[0], r[1], r[0] + r[2], r[1] + r[3], 0x30FFFFFF);
                ctx.drawCenteredTextWithShadow(textRenderer, fit(t, r[2] - 6), r[0] + r[2] / 2, r[1] + r[3] / 2 - 8, 0xFFCDD4E6);
                ctx.drawCenteredTextWithShadow(textRenderer, "(module off)", r[0] + r[2] / 2, r[1] + r[3] / 2 + 2, MUTED);
            }
            if (sel) outline(ctx, r[0], r[1], r[2], r[3], acc, 1);
            else outline(ctx, r[0], r[1], r[2], r[3], hov ? 0xCCFFFFFF : drawn ? 0x40FFFFFF : 0x55FFFFFF, 1);
        }

        // Selected widget: handles and info tag.
        int[] sr = selected == null ? null : rect(selected);
        if (sr != null) {
            for (int[] c : new int[][]{{sr[0], sr[1]}, {sr[0] + sr[2], sr[1]}, {sr[0], sr[1] + sr[3]}, {sr[0] + sr[2], sr[1] + sr[3]}})
                ctx.fill(c[0] - 2, c[1] - 2, c[0] + 3, c[1] + 3, acc);
            int[] p = cfg.hudPositions.get(selected);
            String info = selected + "   " + sr[2] + "x" + sr[3] + "  @ " + sr[0] + "," + sr[1] + (p == null ? "  auto" : "  pinned");
            int iw = textRenderer.getWidth(info) + 8;
            int ix = Math.max(2, Math.min(width - iw - 2, sr[0]));
            int iy = sr[1] >= BAR_H + 14 ? sr[1] - 13 : sr[1] + sr[3] + 3;
            ctx.fill(ix, iy, ix + iw, iy + 11, 0xEE0A0E18);
            ctx.drawText(textRenderer, info, ix + 4, iy + 2, TEXT, false);
        }

        // Alignment guides.
        for (int gx : guideX) ctx.fill(gx, 0, gx + 1, height, 0xFFFF5FA8);
        for (int gy : guideY) ctx.fill(0, gy, width, gy + 1, 0xFFFF5FA8);

        // ---- Top bar ----
        ctx.fill(0, 0, width, BAR_H, 0xF40C111F);
        ctx.fill(0, BAR_H - 1, width, BAR_H, 0x44FFFFFF);
        ctx.drawTextWithShadow(textRenderer, "HUD STUDIO", 8, 8, acc);
        int bx = 92;
        bx = btn(ctx, mouseX, mouseY, bx, 4, 58, 16, "Snap " + (cfg.hudSnap ? "ON" : "OFF"), cfg.hudSnap,
                () -> { cfg.hudSnap = !cfg.hudSnap; Config.save(); });
        bx = btn(ctx, mouseX, mouseY, bx, 4, 54, 16, "Grid " + cfg.hudGrid, false, () -> {
            int idx = 0;
            for (int i = 0; i < GRIDS.length; i++) if (GRIDS[i] == cfg.hudGrid) idx = i;
            cfg.hudGrid = GRIDS[(idx + 1) % GRIDS.length];
            Config.save();
        });
        bx = btn(ctx, mouseX, mouseY, bx, 4, 14, 16, "-", false,
                () -> { cfg.hudScale = Math.max(0.75, Math.round((cfg.hudScale - 0.05) * 100) / 100.0); Config.save(); });
        ctx.drawCenteredTextWithShadow(textRenderer, String.format("All x%.2f", cfg.hudScale), bx + 22, 8, 0xFFC7CFE2);
        bx += 48;
        btn(ctx, mouseX, mouseY, bx, 4, 14, 16, "+", false,
                () -> { cfg.hudScale = Math.min(1.5, Math.round((cfg.hudScale + 0.05) * 100) / 100.0); Config.save(); });
        int rx = width - 48;
        btn(ctx, mouseX, mouseY, rx, 4, 44, 16, "Done", false, this::close);
        boolean confirming = System.currentTimeMillis() < confirmResetUntil;
        btn(ctx, mouseX, mouseY, rx - 74, 4, 70, 16, confirming ? "Sure?" : "Reset all", confirming, () -> {
            if (System.currentTimeMillis() < confirmResetUntil) {
                cfg.hudPositions.clear();
                cfg.hudScales.clear();
                cfg.hudColors.clear();
                confirmResetUntil = 0L;
                Config.save();
            } else {
                confirmResetUntil = System.currentTimeMillis() + 3000L;
            }
        });
        if (!sideOpen) btn(ctx, mouseX, mouseY, rx - 126, 4, 48, 16, "Panel", false, () -> sideOpen = true);

        if (sideShown) drawSidebar(ctx, mouseX, mouseY, all);

        if (!dragNow && width > 340) {
            ctx.drawCenteredTextWithShadow(textRenderer, "Drag to move  -  Arrows nudge  -  Alt: no snap  -  Tab: next  -  Del: reset  -  H: panel",
                    width / 2 - (sideShown ? SIDE_W / 2 : 0), height - 12, 0xFF8F9AB4);
        }
    }

    private void drawSidebar(DrawContext ctx, int mx, int my, List<String> all) {
        Config cfg = Config.get();
        int acc = accent();
        int sx = width - SIDE_W, top = BAR_H;
        ctx.fill(sx, top, width, height, PANEL);
        ctx.fill(sx, top, sx + 1, height, 0x44FFFFFF);
        int pad = 8;
        ctx.drawText(textRenderer, "WIDGETS", sx + pad, top + 6, MUTED, false);
        btn(ctx, mx, my, width - 22, top + 3, 16, 12, ">", false, () -> sideOpen = false);

        int inspH = selected == null ? 26 : INSPECTOR_H;
        int listY = top + 18;
        int listH = Math.max(36, height - listY - inspH - 6);
        int rows = Math.max(1, listH / ROW);
        int maxScroll = Math.max(0, all.size() - rows);
        listScroll = Math.max(0, Math.min(maxScroll, listScroll));
        ctx.fill(sx + 4, listY, width - 4, listY + rows * ROW, 0xFF0D1220);
        for (int i = 0; i < rows; i++) {
            int idx = listScroll + i;
            if (idx >= all.size()) break;
            String t = all.get(idx);
            int ry = listY + i * ROW;
            boolean sel = t.equals(selected), drawn = Hud.bounds(t) != null;
            if (sel) ctx.fill(sx + 4, ry, width - 4, ry + ROW, (acc & 0x00FFFFFF) | 0x55000000);
            else if (inside(mx, my, sx + 4, ry, SIDE_W - 8, ROW)) ctx.fill(sx + 4, ry, width - 4, ry + ROW, HOVER);
            ctx.fill(sx + 8, ry + 4, sx + 11, ry + 7, drawn ? 0xFF72E0B0 : 0xFF4A536A);
            boolean pinned = cfg.hudPositions.containsKey(t);
            ctx.drawText(textRenderer, fit(t, SIDE_W - 44), sx + 15, ry + 2, drawn ? TEXT : 0xFF7C86A0, false);
            if (pinned) ctx.drawText(textRenderer, "P", width - 14, ry + 2, acc, false);
            hits.add(new Hit(sx + 4, ry, SIDE_W - 8, ROW, () -> select(t)));
        }
        if (all.size() > rows) {
            int th = Math.max(10, listH * rows / all.size());
            int ty = listY + Math.round((rows * ROW - th) * (maxScroll == 0 ? 0f : listScroll / (float) maxScroll));
            ctx.fill(width - 6, listY, width - 4, listY + rows * ROW, 0xFF1A2236);
            ctx.fill(width - 6, ty, width - 4, ty + th, acc);
        }

        int iy = height - inspH;
        ctx.fill(sx + 4, iy - 3, width - 4, iy - 2, 0x33FFFFFF);
        if (selected == null) {
            ctx.drawText(textRenderer, "Click a widget to edit", sx + pad, iy + 6, MUTED, false);
            return;
        }
        final String t = selected;
        int[] r = rect(t);
        ctx.drawTextWithShadow(textRenderer, fit(t, SIDE_W - 16), sx + pad, iy + 2, TEXT);
        if (r != null) ctx.drawText(textRenderer, "X " + r[0] + "  Y " + r[1] + "  " + r[2] + "x" + r[3], sx + pad, iy + 14, MUTED, false);

        // Scale.
        int y = iy + 28;
        ctx.drawText(textRenderer, "Scale", sx + pad, y + 3, 0xFFC7CFE2, false);
        btn(ctx, mx, my, sx + 50, y, 16, 14, "-", false, () -> setScale(t, ownScale(t) - 0.05f));
        ctx.drawCenteredTextWithShadow(textRenderer, String.format("%.2fx", ownScale(t)), sx + 86, y + 3, TEXT);
        btn(ctx, mx, my, sx + 108, y, 16, 14, "+", false, () -> setScale(t, ownScale(t) + 0.05f));
        btn(ctx, mx, my, sx + 126, y, 18, 14, "1", false, () -> setScale(t, 1f));

        // Colour.
        y += 20;
        ctx.drawText(textRenderer, "Colour", sx + pad, y, 0xFFC7CFE2, false);
        y += 11;
        Integer custom = cfg.hudColors.get(t);
        for (int i = 0; i < SWATCHES.length; i++) {
            int cx = sx + pad + i * 17;
            int col = SWATCHES[i];
            boolean on = col == 0 ? custom == null : custom != null && (custom | 0xFF000000) == col;
            if (col == 0) {
                ctx.fill(cx, y, cx + 14, y + 12, CARD);
                ctx.drawCenteredTextWithShadow(textRenderer, "A", cx + 7, y + 2, MUTED);
            } else {
                ctx.fill(cx, y, cx + 14, y + 12, col);
            }
            if (on) outline(ctx, cx, y, 14, 12, 0xFFFFFFFF, 1);
            final int pick = col;
            hits.add(new Hit(cx, y, 14, 12, () -> {
                if (pick == 0) cfg.hudColors.remove(t); else cfg.hudColors.put(t, pick);
                Config.save();
            }));
        }

        // Align.
        y += 17;
        String[] labels = {"L", "C", "R", "T", "M", "B"};
        for (int i = 0; i < labels.length; i++) {
            final int mode = i;
            btn(ctx, mx, my, sx + pad + i * 22, y, 19, 13, labels[i], false, () -> align(t, mode));
        }

        // Resets.
        y += 17;
        btn(ctx, mx, my, sx + pad, y, 64, 13, "Auto pos", false, () -> { cfg.hudPositions.remove(t); Config.save(); });
        btn(ctx, mx, my, sx + pad + 68, y, 68, 13, "Reset look", false, () -> {
            cfg.hudScales.remove(t);
            cfg.hudColors.remove(t);
            Config.save();
        });
    }

    private void align(String t, int mode) {
        int[] r = rect(t);
        if (r == null) return;
        ensurePinned(t);
        int x = r[0], y = r[1];
        switch (mode) {
            case 0 -> x = 4;
            case 1 -> x = (width - r[2]) / 2;
            case 2 -> x = width - r[2] - 4;
            case 3 -> y = 4;
            case 4 -> y = (height - r[3]) / 2;
            default -> y = height - r[3] - 4;
        }
        setPos(t, x, y);
        Config.save();
    }

    // ---------------------------------------------------------------- snapping

    /** Returns the snapped position; records the guide line used (if any). */
    private int snapAxis(int pos, int size, List<Integer> targets, List<Integer> guides) {
        int bestD = SNAP + 1, shift = 0, line = 0;
        for (int off : new int[]{0, size / 2, size}) {
            for (int tg : targets) {
                int d = tg - (pos + off);
                if (Math.abs(d) < bestD) { bestD = Math.abs(d); shift = d; line = tg; }
            }
        }
        if (bestD <= SNAP) {
            guides.add(line);
            return pos + shift;
        }
        int grid = Config.get().hudGrid;
        if (grid > 1) return Math.round(pos / (float) grid) * grid;
        return pos;
    }

    // ---------------------------------------------------------------- input

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        double mx = click.x(), my = click.y();
        if (click.button() == 0) {
            for (int i = hits.size() - 1; i >= 0; i--) {
                Hit h = hits.get(i);
                if (inside(mx, my, h.x(), h.y(), h.w(), h.h())) { h.action().run(); return true; }
            }
        }
        if (my < BAR_H || (sideOpen && mx >= width - SIDE_W)) return true;
        String t = pick(mx, my);
        if (click.button() == 1) {
            if (t != null) { Config.get().hudPositions.remove(t); Config.save(); selected = t; }
            return true;
        }
        if (click.button() == 0) {
            if (t == null) { selected = null; return true; }
            selected = t;
            int[] r = rect(t);
            dragging = true;
            moved = false;
            dragDX = (int) Math.round(mx) - r[0];
            dragDY = (int) Math.round(my) - r[1];
            return true;
        }
        return true;
    }

    @Override
    public boolean mouseDragged(Click click, double offsetX, double offsetY) {
        if (!dragging || selected == null || click.button() != 0) return super.mouseDragged(click, offsetX, offsetY);
        if (!moved) { moved = true; ensurePinned(selected); }
        int[] r = rect(selected);
        if (r == null) return true;
        int nx = (int) Math.round(click.x()) - dragDX, ny = (int) Math.round(click.y()) - dragDY;
        guideX.clear();
        guideY.clear();
        Config cfg = Config.get();
        if (cfg.hudSnap && !noSnap()) {
            List<Integer> tx = new ArrayList<>(List.of(0, 6, width / 2, width - 6, width));
            List<Integer> ty = new ArrayList<>(List.of(0, 6, height / 2, height - 6, height));
            for (String t : widgets()) {
                if (t.equals(selected)) continue;
                int[] o = Hud.bounds(t);
                if (o == null) continue;
                tx.add(o[0]); tx.add(o[0] + o[2] / 2); tx.add(o[0] + o[2]);
                ty.add(o[1]); ty.add(o[1] + o[3] / 2); ty.add(o[1] + o[3]);
            }
            nx = snapAxis(nx, r[2], tx, guideX);
            ny = snapAxis(ny, r[3], ty, guideY);
        }
        setPos(selected, nx, ny);
        return true;
    }

    @Override
    public boolean mouseReleased(Click click) {
        if (dragging) {
            dragging = false;
            if (moved) Config.save();
            moved = false;
            guideX.clear();
            guideY.clear();
            return true;
        }
        return super.mouseReleased(click);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (sideOpen && mouseX >= width - SIDE_W && mouseY > BAR_H) {
            listScroll = Math.max(0, listScroll - (int) Math.signum(verticalAmount) * 2);
            return true;
        }
        if (selected != null && shift()) {
            setScale(selected, ownScale(selected) + (float) Math.signum(verticalAmount) * 0.05f);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        int k = input.key();
        if (k == GLFW.GLFW_KEY_H) { sideOpen = !sideOpen; return true; }
        if (k == GLFW.GLFW_KEY_G) { Config.get().hudSnap = !Config.get().hudSnap; Config.save(); return true; }
        if (k == GLFW.GLFW_KEY_TAB) {
            List<String> all = widgets();
            int idx = selected == null ? -1 : all.indexOf(selected);
            idx = (idx + (shift() ? -1 : 1) + all.size()) % all.size();
            select(all.get(idx));
            return true;
        }
        if (selected != null) {
            int step = shift() ? 8 : 1;
            int dx = 0, dy = 0;
            if (k == GLFW.GLFW_KEY_LEFT) dx = -step;
            else if (k == GLFW.GLFW_KEY_RIGHT) dx = step;
            else if (k == GLFW.GLFW_KEY_UP) dy = -step;
            else if (k == GLFW.GLFW_KEY_DOWN) dy = step;
            if (dx != 0 || dy != 0) {
                ensurePinned(selected);
                int[] p = Config.get().hudPositions.get(selected);
                if (p != null) { setPos(selected, p[0] + dx, p[1] + dy); Config.save(); }
                return true;
            }
            if (k == GLFW.GLFW_KEY_DELETE || k == GLFW.GLFW_KEY_BACKSPACE) {
                Config.get().hudPositions.remove(selected);
                Config.save();
                return true;
            }
            if (k == GLFW.GLFW_KEY_EQUAL || k == GLFW.GLFW_KEY_KP_ADD) { setScale(selected, ownScale(selected) + 0.1f); return true; }
            if (k == GLFW.GLFW_KEY_MINUS || k == GLFW.GLFW_KEY_KP_SUBTRACT) { setScale(selected, ownScale(selected) - 0.1f); return true; }
        }
        return super.keyPressed(input);
    }

    @Override
    public void close() {
        Config.save();
        if (client != null) client.setScreen(new ConfigScreen());
    }

    @Override
    public boolean shouldPause() { return false; }
}
