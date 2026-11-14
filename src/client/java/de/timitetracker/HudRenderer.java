package de.timitetracker;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * "Custom client" style HUD: rounded dark panel, header with rank chip, accent gradient line,
 * stat cards, pill progress bar. Only uses fill + text, which are passed in as small interfaces
 * so all Minecraft-API-specific calls live in one place (TimiteTrackerClient).
 */
final class HudRenderer {
    interface Fill { void fill(int x1, int y1, int x2, int y2, int argb); }
    interface Txt { void text(Component c, int x, int y, int argb); }

    private static final int GRAY = 0xFF9AA0AA, DARK = 0xFF5C6370, WHITE = 0xFFFFFFFF;

    private static void roundRect(Fill g, int x, int y, int w, int h, int r, int c) {
        if (r <= 0 || h < 2 * r || w < 2 * r) { g.fill(x, y, x + w, y + h, c); return; }
        g.fill(x, y + r, x + w, y + h - r, c);
        for (int i = 0; i < r; i++) {
            double dy = r - i - 0.5;
            int inset = r - (int) Math.round(Math.sqrt(r * r - dy * dy));
            g.fill(x + inset, y + i, x + w - inset, y + i + 1, c);
            g.fill(x + inset, y + h - 1 - i, x + w - inset, y + h - i, c);
        }
    }

    private static Component txt(String s, ChatFormatting... f) { return Component.literal(s).withStyle(f); }

    static void draw(Minecraft mc, HudConfig cfg, TimiteTrackerClient.View v, Fill g, Txt t) {
        final int pad = 6, hdrH = 18, rowH = mc.font.lineHeight + 3, cardH = mc.font.lineHeight * 2 + 9, gapPx = 12;
        final int rgb = cfg.accentArgb() & 0xFFFFFF;
        final int x = cfg.x, y = cfg.y;

        Component title = txt("TIMITE", ChatFormatting.GOLD, ChatFormatting.BOLD);
        Component sub = txt(" COLLECTION", ChatFormatting.DARK_GRAY);
        int headW = mc.font.width(title) + mc.font.width(sub);

        // ---- loading / error ----
        if (v.status() != null) {
            Component st = txt(v.status(), v.error() ? ChatFormatting.RED : ChatFormatting.GRAY);
            int w = Math.max(150, Math.max(headW, mc.font.width(st)) + pad * 2);
            int h = hdrH + 1 + pad + rowH + pad - 2;
            roundRect(g, x, y, w, h, 3, cfg.backgroundArgb());
            roundRect(g, x, y, w, hdrH, 3, 0x30000000);
            t.text(title, x + pad, y + 5, WHITE);
            t.text(sub, x + pad + mc.font.width(title), y + 5, WHITE);
            accentLine(g, x + pad, y + hdrH, w - pad * 2, rgb);
            t.text(st, x + pad, y + hdrH + 1 + pad, WHITE);
            return;
        }

        Component rank = txt("#" + v.rank(), ChatFormatting.GREEN, ChatFormatting.BOLD);
        int chipW = mc.font.width(rank) + 10;
        Component youV = txt(v.you(), ChatFormatting.GREEN), tgtV = txt(v.targetVal(), ChatFormatting.RED);
        Component daysV = txt(v.days(), ChatFormatting.WHITE);
        boolean bar = cfg.showProgressBar && v.progress() >= 0;
        boolean foot = cfg.showUpdated;
        boolean note = v.note() != null;
        Component pctV = txt(String.format(java.util.Locale.US, "%.2f%%", v.progress() * 100), ChatFormatting.WHITE);

        // width: widest of header, rows, cards
        int need = Math.max(headW + chipW + 10, 0);
        need = Math.max(need, mc.font.width("You") + gapPx + mc.font.width(youV));
        need = Math.max(need, mc.font.width(v.targetName()) + gapPx + mc.font.width(tgtV));
        if (note) need = Math.max(need, mc.font.width(v.note()));
        String cardL = v.lead() ? "LEAD" : "GAP", cardR = "NEED / DAY";
        int cardNeed = Math.max(mc.font.width(cardL), mc.font.width(v.gap())) + 12;
        cardNeed = Math.max(cardNeed, Math.max(mc.font.width(cardR), mc.font.width(v.need())) + 12);
        need = Math.max(need, v.lead() ? cardNeed : cardNeed * 2 + 4);
        int w = Math.max(150, need + pad * 2);

        int h = hdrH + 1 + 5 + rowH * 2 + (note ? rowH : 0) + 3 + cardH + 5 + rowH
                + (bar ? rowH + 6 : 0) + (foot ? rowH : 0) + pad - 5;

        // ---- panel + header ----
        roundRect(g, x, y, w, h, 3, cfg.backgroundArgb());
        roundRect(g, x, y, w, hdrH, 3, 0x30000000);
        t.text(title, x + pad, y + 5, WHITE);
        t.text(sub, x + pad + mc.font.width(title), y + 5, WHITE);
        roundRect(g, x + w - pad - chipW, y + 3, chipW, hdrH - 6, 3, 0x5530D060);   // rank chip
        t.text(rank, x + w - pad - chipW + 5, y + 5, WHITE);
        accentLine(g, x + pad, y + hdrH, w - pad * 2, rgb);

        int cx = x + pad, cy = y + hdrH + 1 + 5, iw = w - pad * 2;

        // ---- rows ----
        t.text(txt("You", ChatFormatting.GRAY), cx, cy, WHITE);
        t.text(youV, cx + iw - mc.font.width(youV), cy, WHITE);
        cy += rowH;
        t.text(txt(v.targetName(), ChatFormatting.GRAY), cx, cy, WHITE);
        t.text(tgtV, cx + iw - mc.font.width(tgtV), cy, WHITE);
        cy += rowH;
        if (note) { t.text(txt(v.note(), ChatFormatting.YELLOW), cx, cy, WHITE); cy += rowH; }
        cy += 3;

        // ---- stat cards ----
        if (v.lead()) {
            card(g, t, mc, cx, cy, iw, cardH, rgb, cardL, txt(v.gap(), ChatFormatting.AQUA, ChatFormatting.BOLD));
        } else {
            int cw = (iw - 4) / 2;
            card(g, t, mc, cx, cy, cw, cardH, rgb, cardL, txt(v.gap(), ChatFormatting.YELLOW, ChatFormatting.BOLD));
            card(g, t, mc, cx + cw + 4, cy, iw - cw - 4, cardH, rgb, cardR, txt(v.need(), ChatFormatting.AQUA, ChatFormatting.BOLD));
        }
        cy += cardH + 5;

        // ---- days left + progress ----
        t.text(txt("Days left", ChatFormatting.GRAY), cx, cy, WHITE);
        t.text(daysV, cx + iw - mc.font.width(daysV), cy, WHITE);
        cy += rowH;
        if (bar) {
            t.text(txt("Progress", ChatFormatting.GRAY), cx, cy, WHITE);
            t.text(pctV, cx + iw - mc.font.width(pctV), cy, WHITE);
            cy += rowH;
            roundRect(g, cx, cy, iw, 4, 2, 0x33FFFFFF);                                    // track
            int filled = Math.max(4, (int) Math.round(iw * Math.min(1.0, Math.max(0.0, v.progress()))));
            roundRect(g, cx, cy, filled, 4, 2, 0xFF000000 | rgb);                          // fill
            cy += 6;
        }
        if (foot) t.text(txt(v.updated(), ChatFormatting.DARK_GRAY), cx, cy, WHITE);
    }

    private static void card(Fill g, Txt t, Minecraft mc, int x, int y, int w, int h, int rgb, String label, Component value) {
        roundRect(g, x, y, w, h, 3, 0x22FFFFFF);
        g.fill(x, y + 3, x + 1, y + h - 3, 0xFF000000 | rgb);
        t.text(txt(label, ChatFormatting.GRAY), x + 6, y + 4, WHITE);
        t.text(value, x + 6, y + 4 + mc.font.lineHeight + 1, WHITE);
    }

    /** 1px accent line that fades out to the right. */
    private static void accentLine(Fill g, int x, int y, int len, int rgb) {
        for (int i = 0; i < len; i++) {
            int a = 255 - (int) (215.0 * i / Math.max(1, len));
            g.fill(x + i, y, x + i + 1, y + 1, (a << 24) | rgb);
        }
    }
}
