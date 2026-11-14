package de.timitetracker;

import com.google.gson.*;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Simple JSON config: .minecraft/config/timite-tracker.json
 * Edit the file and run /timite reload, or use the in-game commands (/timite pos, /timite bg, ...).
 */
public final class HudConfig {
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("timite-tracker.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** HUD position in GUI pixels from the top-left corner. */
    public int x = 6;
    public int y = 6;
    /** HUD size multiplier (0.5 - 3.0). */
    public float scale = 1.0f;
    /** Background opacity 0-100. */
    public int backgroundOpacity = 70;
    public boolean visible = true;
    public boolean showProgressBar = true;
    public boolean showUpdated = true;
    /** Accent color (RGB hex without alpha) used for the left bar and progress bar. */
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
        if (accentColor == null) accentColor = "FFAA00";
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
