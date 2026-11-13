package de.timitetracker;

import com.google.gson.*;
import net.fabricmc.loader.api.FabricLoader;
import java.nio.file.*;
import java.util.*;

/** Speichert Messpunkte des #1, damit sein Tempo (Timite/Tag) geschätzt werden kann. */
public final class History {
    public record Point(long ts, double amount) {}
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("timite-tracker-history.json");
    private static final long KEEP_MS = 14L * 24 * 3600 * 1000;
    private static final long MIN_GAP_MS = 10L * 60 * 1000;

    public static List<Point> load() {
        List<Point> out = new ArrayList<>();
        try {
            if (Files.exists(FILE))
                for (JsonElement e : JsonParser.parseString(Files.readString(FILE)).getAsJsonArray()) {
                    JsonObject o = e.getAsJsonObject();
                    out.add(new Point(o.get("ts").getAsLong(), o.get("amount").getAsDouble()));
                }
        } catch (Exception ignored) {}
        return out;
    }

    public static List<Point> add(double leaderAmount) {
        List<Point> pts = load();
        long now = System.currentTimeMillis();
        pts.removeIf(p -> now - p.ts() > KEEP_MS);
        if (pts.isEmpty() || now - pts.get(pts.size() - 1).ts() >= MIN_GAP_MS) pts.add(new Point(now, leaderAmount));
        try {
            JsonArray arr = new JsonArray();
            for (Point p : pts) { JsonObject o = new JsonObject(); o.addProperty("ts", p.ts()); o.addProperty("amount", p.amount()); arr.add(o); }
            Files.writeString(FILE, arr.toString());
        } catch (Exception ignored) {}
        return pts;
    }

    /** Timite/Tag des #1 aus ältestem vs. neuestem Punkt; null wenn <12h Datenbasis. */
    public static Double leaderRatePerDay(List<Point> pts) {
        if (pts.size() < 2) return null;
        Point a = pts.get(0), b = pts.get(pts.size() - 1);
        double days = (b.ts() - a.ts()) / 86_400_000.0;
        if (days < 0.5) return null;
        return Math.max(0, (b.amount() - a.amount()) / days);
    }
}
