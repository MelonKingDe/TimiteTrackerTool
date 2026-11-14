package de.collectiontracker;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleFunction;

/** Settings GUI: two tabs (Tracking / HUD). Text fields are applied when the screen closes, look settings live. */
public class SettingsScreen extends Screen {
    private static final int W = 230, ROW = 22;
    private static final String[][] PRESETS = {
            {"FFAA00"}, {"33CCFF"}, {"3DDC84"}, {"FF5DA2"}, {"FF4D4D"}, {"FFFFFF"}};

    private int tab = 0;
    // drafts of the text fields (they survive tab switches)
    private String dCollection, dRival, dDeadline, dItems;

    private StringWidget collectionStatus, rivalStatus, deadlineStatus;

    public SettingsScreen() {
        super(Component.literal("Collection Tracker"));
        HudConfig c = HudConfig.get();
        dCollection = c.collection;
        dRival = c.rival;
        dDeadline = c.deadlineDate().toString();
        dItems = String.join(", ", c.liveItems);
    }

    // ---------------------------------------------------------------- layout

    @Override
    protected void init() { build(); }

    private void build() {
        clearWidgets();
        Minecraft mc = Minecraft.getInstance();
        int cx = this.width / 2, left = cx - W / 2;
        int y = Math.max(6, this.height / 2 - 112);

        // title
        Component title = Component.literal("COLLECTION TRACKER").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD);
        int tw = mc.font.width(title);
        addRenderableWidget(new StringWidget(cx - tw / 2, y, tw, 10, title, mc.font));
        y += 16;

        // tabs
        Button t0 = Button.builder(Component.literal("Tracking"), b -> { tab = 0; build(); })
                .bounds(left, y, W / 2 - 2, 20).build();
        Button t1 = Button.builder(Component.literal("HUD"), b -> { tab = 1; build(); })
                .bounds(left + W / 2 + 2, y, W / 2 - 2, 20).build();
        (tab == 0 ? t0 : t1).active = false;
        addRenderableWidget(t0);
        addRenderableWidget(t1);
        y += 28;

        if (tab == 0) buildTracking(left, y); else buildHud(left, y);

        // bottom buttons
        int by = this.height / 2 + 96;
        by = Math.min(by, this.height - 26);
        int bw = (W - 8) / 3;
        addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose())
                .bounds(left, by, bw, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Refresh"), b -> {
            commit();
            CollectionTrackerClient.forceRefresh();
        }).bounds(left + bw + 4, by, bw, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Reset"), b -> {
            HudConfig.reset();
            CollectionTrackerClient.settingsChanged(true);
            HudConfig c = HudConfig.get();
            dCollection = c.collection; dRival = c.rival; dDeadline = c.deadlineDate().toString(); dItems = "";
            build();
        }).bounds(left + (bw + 4) * 2, by, bw, 20).build());
    }

    // ---------------------------------------------------------------- tracking tab

    private void buildTracking(int left, int y) {
        Minecraft mc = Minecraft.getInstance();
        HudConfig cfg = HudConfig.get();
        int labelW = 62, fx = left + labelW, fw = W - labelW;

        // collection
        label(left, y + 6, "Collection");
        EditBox col = new EditBox(mc.font, fx, y, fw, 20, Component.literal("Collection"));
        col.setMaxLength(48);
        col.setHint(Component.literal("e.g. timite, wheat").withStyle(ChatFormatting.DARK_GRAY));
        col.setValue(dCollection);
        col.setResponder(v -> { dCollection = v; updateCollectionStatus(); });
        addRenderableWidget(col);
        y += 22;
        collectionStatus = new StringWidget(fx, y, fw, 10, Component.empty(), mc.font);
        addRenderableWidget(collectionStatus);
        updateCollectionStatus();
        y += 13;

        // rival
        label(left, y + 6, "Rival");
        EditBox riv = new EditBox(mc.font, fx, y, fw - 44, 20, Component.literal("Rival"));
        riv.setMaxLength(32);
        riv.setHint(Component.literal("player name").withStyle(ChatFormatting.DARK_GRAY));
        riv.setValue(dRival.equalsIgnoreCase("top") ? "" : dRival);
        riv.setResponder(v -> { dRival = v.isBlank() ? "top" : v.trim(); updateRivalStatus(); });
        addRenderableWidget(riv);
        addRenderableWidget(Button.builder(Component.literal("#1"), b -> {
            dRival = "top"; riv.setValue(""); updateRivalStatus();
        }).bounds(fx + fw - 42, y, 42, 20).build());
        y += 22;
        rivalStatus = new StringWidget(fx, y, fw, 10, Component.empty(), mc.font);
        addRenderableWidget(rivalStatus);
        updateRivalStatus();
        y += 13;

        // deadline
        label(left, y + 6, "Deadline");
        EditBox dl = new EditBox(mc.font, fx, y, fw, 20, Component.literal("Deadline"));
        dl.setMaxLength(12);
        dl.setHint(Component.literal("yyyy-MM-dd").withStyle(ChatFormatting.DARK_GRAY));
        dl.setValue(dDeadline);
        dl.setResponder(v -> { dDeadline = v; updateDeadlineStatus(); });
        addRenderableWidget(dl);
        y += 22;
        deadlineStatus = new StringWidget(fx, y, fw, 10, Component.empty(), mc.font);
        addRenderableWidget(deadlineStatus);
        updateDeadlineStatus();
        y += 15;

        // live counting
        Button live = Button.builder(liveText(cfg), b -> {
            cfg.liveTracking = !cfg.liveTracking;
            b.setMessage(liveText(cfg));
        }).bounds(left, y, W, 20).build();
        addRenderableWidget(live);
        y += ROW;
        label(left, y + 6, "Items");
        EditBox items = new EditBox(mc.font, fx, y, fw, 20, Component.literal("Items"));
        items.setMaxLength(100);
        items.setHint(Component.literal("auto (or: youngite, timite)").withStyle(ChatFormatting.DARK_GRAY));
        items.setValue(dItems);
        items.setResponder(v -> dItems = v);
        addRenderableWidget(items);
    }

    private static Component liveText(HudConfig c) {
        return Component.literal("Live item counting: ").append(
                Component.literal(c.liveTracking ? "ON" : "OFF")
                        .withStyle(c.liveTracking ? ChatFormatting.GREEN : ChatFormatting.RED));
    }

    private void updateCollectionStatus() {
        if (collectionStatus == null) return;
        if (dCollection.isBlank()) { collectionStatus.setMessage(Component.literal("enter a collection").withStyle(ChatFormatting.GRAY)); return; }
        if (!EliteApi.isLoaded()) { collectionStatus.setMessage(Component.literal("loading collection list...").withStyle(ChatFormatting.GRAY)); return; }
        EliteApi.LbInfo info = EliteApi.findCollectionCached(dCollection);
        if (info == null) collectionStatus.setMessage(Component.literal("not found").withStyle(ChatFormatting.RED));
        else collectionStatus.setMessage(Component.literal("-> " + (info.title() != null ? info.title() : info.id())).withStyle(ChatFormatting.GREEN));
    }

    private void updateRivalStatus() {
        if (rivalStatus == null) return;
        rivalStatus.setMessage(dRival.equalsIgnoreCase("top")
                ? Component.literal("catching up with the current #1").withStyle(ChatFormatting.GRAY)
                : Component.literal("catching up with " + dRival).withStyle(ChatFormatting.GRAY));
    }

    private void updateDeadlineStatus() {
        if (deadlineStatus == null) return;
        LocalDate d = HudConfig.parseDate(dDeadline);
        if (d == null) { deadlineStatus.setMessage(Component.literal("invalid (yyyy-MM-dd or dd.MM.yyyy)").withStyle(ChatFormatting.RED)); return; }
        long days = ChronoUnit.DAYS.between(LocalDate.now(), d);
        deadlineStatus.setMessage(days < 0
                ? Component.literal("date is in the past").withStyle(ChatFormatting.YELLOW)
                : Component.literal(days + " days from today").withStyle(ChatFormatting.GREEN));
    }

    // ---------------------------------------------------------------- HUD tab

    private void buildHud(int left, int y) {
        Minecraft mc = Minecraft.getInstance();
        HudConfig cfg = HudConfig.get();

        addRenderableWidget(new Slider(left, y, W, 20, "Size", 0.5, 3.0, cfg.scale,
                v -> cfg.scale = (float) (Math.round(v * 20) / 20.0), v -> String.format(Locale.US, "%.2fx", Math.round(v * 20) / 20.0)));
        y += ROW;
        addRenderableWidget(new Slider(left, y, W, 20, "Background", 0, 100, cfg.backgroundOpacity,
                v -> cfg.backgroundOpacity = (int) Math.round(v), v -> Math.round(v) + "%"));
        y += ROW;
        addRenderableWidget(new Slider(left, y, W, 20, "Glow speed", 1, 15, cfg.borderSpeed,
                v -> cfg.borderSpeed = (float) (Math.round(v * 2) / 2.0), v -> String.format(Locale.US, "%.1f s/lap", Math.round(v * 2) / 2.0)));
        y += ROW;
        addRenderableWidget(new Slider(left, y, W, 20, "Position X", 0, Math.max(1, this.width), cfg.x,
                v -> cfg.x = (int) Math.round(v), v -> String.valueOf(Math.round(v))));
        y += ROW;
        addRenderableWidget(new Slider(left, y, W, 20, "Position Y", 0, Math.max(1, this.height), cfg.y,
                v -> cfg.y = (int) Math.round(v), v -> String.valueOf(Math.round(v))));
        y += ROW + 2;

        // toggles
        int tw = (W - 12) / 4;
        addRenderableWidget(toggle(left, y, tw, "HUD", cfg.visible, v -> cfg.visible = v));
        addRenderableWidget(toggle(left + tw + 4, y, tw, "Bar", cfg.showProgressBar, v -> cfg.showProgressBar = v));
        addRenderableWidget(toggle(left + (tw + 4) * 2, y, tw, "Time", cfg.showUpdated, v -> cfg.showUpdated = v));
        addRenderableWidget(toggle(left + (tw + 4) * 3, y, tw, "Glow", cfg.borderGlow, v -> cfg.borderGlow = v));
        y += ROW + 2;

        // accent color: presets + hex
        EditBox hex = new EditBox(mc.font, left + W - 56, y, 56, 20, Component.literal("Accent"));
        hex.setMaxLength(6);
        hex.setValue(cfg.accentColor.replace("#", ""));
        hex.setResponder(v -> { if (v.matches("(?i)[0-9a-f]{6}")) cfg.accentColor = v.toUpperCase(Locale.ROOT); });
        addRenderableWidget(hex);
        int px = left;
        for (String[] p : PRESETS) {
            int rgb = Integer.parseInt(p[0], 16);
            addRenderableWidget(Button.builder(Component.literal("\u25A0").withStyle(s -> s.withColor(rgb)), b -> {
                cfg.accentColor = p[0];
                hex.setValue(p[0]);
            }).bounds(px, y, 28, 20).build());
            px += 29;
        }
    }

    private Button toggle(int x, int y, int w, String name, boolean on, java.util.function.Consumer<Boolean> set) {
        final boolean[] state = {on};
        return Button.builder(toggleText(name, on), b -> {
            state[0] = !state[0];
            set.accept(state[0]);
            b.setMessage(toggleText(name, state[0]));
        }).bounds(x, y, w, 20).build();
    }

    private static Component toggleText(String name, boolean on) {
        return Component.literal(name + ": ").append(
                Component.literal(on ? "ON" : "OFF").withStyle(on ? ChatFormatting.GREEN : ChatFormatting.RED));
    }

    // ---------------------------------------------------------------- helpers

    private void label(int x, int y, String text) {
        Minecraft mc = Minecraft.getInstance();
        Component c = Component.literal(text).withStyle(ChatFormatting.GRAY);
        addRenderableWidget(new StringWidget(x, y, mc.font.width(c), 10, c, mc.font));
    }

    /** Applies the text fields to the config and tells the tracker what changed. */
    private void commit() {
        HudConfig c = HudConfig.get();
        boolean collectionChanged = false, otherChanged = false;

        String col = dCollection == null ? "" : dCollection.trim();
        if (!col.isBlank() && !EliteApi.norm(col).equals(EliteApi.norm(c.collection))) { c.collection = col; collectionChanged = true; }

        String riv = dRival == null || dRival.isBlank() ? "top" : dRival.trim();
        if (!riv.equalsIgnoreCase(c.rival)) { c.rival = riv; otherChanged = true; }

        LocalDate d = HudConfig.parseDate(dDeadline);
        if (d != null && !d.toString().equals(c.deadline)) { c.deadline = d.toString(); otherChanged = true; }

        List<String> list = new ArrayList<>();
        if (dItems != null)
            for (String s : dItems.split(",")) if (!s.isBlank()) list.add(s.trim().toLowerCase(Locale.ROOT));
        if (!list.equals(c.liveItems)) { c.liveItems = list; otherChanged = true; }

        HudConfig.save();
        if (collectionChanged) CollectionTrackerClient.settingsChanged(true);
        else if (otherChanged) CollectionTrackerClient.settingsChanged(false);
    }

    @Override
    public void onClose() {
        commit();
        Minecraft.getInstance().setScreen(null);
    }

    /** Slider with a label and a formatted value. */
    private static final class Slider extends AbstractSliderButton {
        private final String label;
        private final double min, max;
        private final DoubleConsumer onChange;
        private final DoubleFunction<String> fmt;

        Slider(int x, int y, int w, int h, String label, double min, double max, double cur,
               DoubleConsumer onChange, DoubleFunction<String> fmt) {
            super(x, y, w, h, Component.empty(), Math.max(0, Math.min(1, (cur - min) / (max - min))));
            this.label = label; this.min = min; this.max = max; this.onChange = onChange; this.fmt = fmt;
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            if (label == null) return;
            setMessage(Component.literal(label + ": " + fmt.apply(min + (max - min) * this.value)));
        }

        @Override
        protected void applyValue() {
            onChange.accept(min + (max - min) * this.value);
        }
    }
}
