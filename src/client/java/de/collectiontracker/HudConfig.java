package de.collectiontracker;

import com.google.gson.*;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * JSON config: .minecraft/config/collection-tracker.json
 * Edit the file and run /ctrack reload, or use the in-game commands (/ctrack ...).
 */
public final class HudConfig {
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("collection-tracker.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // ---- what to track ----
    /** Collection name, e.g. "timite", "wheat", "nether wart". */
    public String collection = "timite";
    /** Player to catch up with, or "top" for whoever is currently #1. */
    public String rival = "dinofeet_";
    /** Goal date (yyyy-MM-dd or dd.MM.yyyy). The goal is that date at 00:00 local time. */
    public String deadline = "2027-01-01";
    /** Count picked-up items live (+1 each) between API updates. */
    public boolean liveTracking = true;
    /** Item names to count live. Empty = automatic (collection name; Youngite/Timite/Obsolite for Timite). */
    public List<String> liveItems = new ArrayList<>();

    // ---- look ----
    public int x = 6;
    public int y = 6;
    /** HUD size multiplier (0.5 - 3.0). */
    public float scale = 1.0f;
    /** Background opacity 0-100. */
    public int backgroundOpacity = 70;
    public boolean visible = true;
    public boolean showProgressBar = true;
    public boolean showUpdated = true;
    /** Animated glowing border around the HUD. */
    public boolean borderGlow = true;
    /** Seconds the light needs for one lap around the border (1 - 15). */
    public float borderSpeed = 4.0f;
    /** Accent color (RGB hex without alpha). */
    public String accentColor = "FFAA00";

    private static HudConfig instance = new HudConfig();

    public static HudConfig get() { return instance; }

    public static void load() {
        try {
            if (Files.exists(FILE)) {
                HudConfig c = GSON.fromJson(Files.readString(FILE), HudConfig.class);
                if (c != null) instance = c;
            }
        } catch (Exception ignored) {}
        instance.clamp();
        save(); // writes defaults / newly added fields
    }

    public static void save() {
        try { Files.writeString(FILE, GSON.toJson(instance)); } catch (Exception ignored) {}
    }

    public static void reset() {
        instance = new HudConfig();
        save();
    }

    private void clamp() {
        x = Math.max(0, x);
        y = Math.max(0, y);
        backgroundOpacity = Math.max(0, Math.min(100, backgroundOpacity));
        if (!(scale >= 0.5f)) scale = 0.5f;
        if (scale > 3.0f) scale = 3.0f;
        if (!(borderSpeed >= 1.0f)) borderSpeed = 1.0f;
        if (borderSpeed > 15.0f) borderSpeed = 15.0f;
        if (accentColor == null) accentColor = "FFAA00";
        if (collection == null || collection.isBlank()) collection = "timite";
        if (rival == null || rival.isBlank()) rival = "top";
        if (deadline == null) deadline = "2027-01-01";
        if (liveItems == null) liveItems = new ArrayList<>();
    }

    // ---- helpers ----
    /** Accepts yyyy-MM-dd or dd.MM.yyyy; null if invalid. */
    public static LocalDate parseDate(String s) {
        if (s == null) return null;
        s = s.trim();
        try { return LocalDate.parse(s); } catch (Exception ignored) {}
        try { return LocalDate.parse(s, DateTimeFormatter.ofPattern("d.M.yyyy")); } catch (Exception ignored) {}
        return null;
    }

    public LocalDate deadlineDate() {
        LocalDate d = parseDate(deadline);
        return d != null ? d : LocalDate.of(2027, 1, 1);
    }

    public int accentArgb() {
        try { return 0xFF000000 | Integer.parseInt(accentColor.replace("#", ""), 16); }
        catch (Exception e) { return 0xFFFFAA00; }
    }

    public int backgroundArgb() {
        int a = backgroundOpacity * 255 / 100;
        return (a << 24) | 0x0E0E14;
    }
}
