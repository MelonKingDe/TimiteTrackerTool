package de.timitetracker;

import com.google.gson.*;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

/** Dünner Client für die Elite-Skyblock-API (https://api.eliteskyblock.com). */
public final class EliteApi {
    private static final String BASE = "https://api.eliteskyblock.com";
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();

    private static JsonElement get(String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(BASE + path))
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "application/json")
                .header("User-Agent", "TimiteTrackerMod/1.0")
                .GET().build();
        HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (res.statusCode() != 200) throw new RuntimeException("HTTP " + res.statusCode() + " bei " + path);
        return JsonParser.parseString(res.body());
    }

    /** Sucht die Timite-Leaderboard-ID über GET /leaderboards (Match auf collection/itemId/Titel). */
    public static String findTimiteLeaderboardId() throws Exception {
        JsonObject lbs = get("/leaderboards").getAsJsonObject().getAsJsonObject("leaderboards");
        String fallback = null;
        for (var e : lbs.entrySet()) {
            JsonObject o = e.getValue().getAsJsonObject();
            String coll = str(o, "collection"), item = str(o, "itemId"), title = str(o, "title"), cat = str(o, "category");
            boolean timite = has(coll) || has(item) || has(title) || e.getKey().toLowerCase().contains("timite");
            if (!timite) continue;
            // Bevorzugt: dauerhaftes (Current) Collection-Leaderboard, keine Monats-/Wochenvarianten
            String type = str(o, "intervalType");
            if (type == null || type.equalsIgnoreCase("Current")) return e.getKey();
            if (fallback == null) fallback = e.getKey();
        }
        if (fallback != null) return fallback;
        throw new RuntimeException("Kein Timite-Leaderboard in /leaderboards gefunden");
    }

    private static boolean has(String s) { return s != null && s.toLowerCase().contains("timite"); }
    private static String str(JsonObject o, String k) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : null;
    }

    public record Entry(int rank, String ign, double amount) {}

    /** Sucht einen Spieler in den Top-N des Leaderboards (z.B. dinofeet_). */
    public static Entry findInTop(String lb, String ign, int limit) throws Exception {
        JsonArray arr = get("/leaderboard/" + enc(lb) + "?limit=" + limit).getAsJsonObject().getAsJsonArray("entries");
        for (int i = 0; i < arr.size(); i++) {
            JsonObject o = arr.get(i).getAsJsonObject();
            String name = str(o, "ign");
            if (name != null && name.equalsIgnoreCase(ign))
                return new Entry(i + 1, name, o.get("amount").getAsDouble());
        }
        return null;
    }

    /** Aktueller #1 des Leaderboards. */
    public static Entry top1(String lb) throws Exception {
        JsonArray arr = get("/leaderboard/" + enc(lb) + "?limit=1").getAsJsonObject().getAsJsonArray("entries");
        if (arr.isEmpty()) throw new RuntimeException("Leaderboard leer");
        JsonObject o = arr.get(0).getAsJsonObject();
        String name = str(o, "ign");
        return new Entry(1, name == null ? "?" : name, o.get("amount").getAsDouble());
    }

    /** Eigene Position/Menge über das ausgewählte Profil. */
    public static Entry me(String lb, UUID player) throws Exception {
        String p = player.toString().replace("-", "");
        String profileId = get("/profile/" + p + "/selected").getAsJsonObject().get("profileId").getAsString();
        JsonObject pos = get("/leaderboard/rank/" + enc(lb) + "/" + p + "/" + profileId).getAsJsonObject();
        return new Entry(pos.get("rank").getAsInt(), "du", pos.get("amount").getAsDouble());
    }

    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
}
