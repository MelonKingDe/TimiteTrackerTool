package de.collectiontracker;

import com.google.gson.*;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/** Thin client for the Elite Skyblock API (https://api.eliteskyblock.com). */
public final class EliteApi {
    private static final String BASE = "https://api.eliteskyblock.com";
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();

    private static JsonElement get(String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(BASE + path))
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "application/json")
                .header("User-Agent", "CollectionTrackerMod/1.0")
                .GET().build();
        HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (res.statusCode() != 200) throw new RuntimeException("HTTP " + res.statusCode() + " at " + path);
        return JsonParser.parseString(res.body());
    }

    // ---------- leaderboards ----------

    public record LbInfo(String id, String title, String collection, String itemId, String shortName,
                         String interval, String bestiary) {}

    private static volatile List<LbInfo> lbCache = List.of();
    private static volatile long lbCacheTime = 0;

    private static String str(JsonObject o, String k) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : null;
    }

    /** All leaderboards (cached for one hour). */
    public static synchronized List<LbInfo> leaderboards() throws Exception {
        if (!lbCache.isEmpty() && System.currentTimeMillis() - lbCacheTime < 3_600_000L) return lbCache;
        JsonObject lbs = get("/leaderboards").getAsJsonObject().getAsJsonObject("leaderboards");
        List<LbInfo> out = new ArrayList<>();
        for (var e : lbs.entrySet()) {
            JsonObject o = e.getValue().getAsJsonObject();
            out.add(new LbInfo(e.getKey(), str(o, "title"), str(o, "collection"), str(o, "itemId"),
                    str(o, "short"), str(o, "intervalType"), str(o, "bestiary")));
        }
        lbCache = out;
        lbCacheTime = System.currentTimeMillis();
        return out;
    }

    /** Collection names for tab completion (non-blocking: uses whatever is cached). */
    public static List<String> cachedCollectionNames() {
        TreeSet<String> names = new TreeSet<>();
        for (LbInfo l : lbCache) {
            if (l.collection() == null || l.bestiary() != null) continue;
            if (l.interval() != null && !l.interval().equalsIgnoreCase("Current")) continue;
            names.add(l.collection().toLowerCase(Locale.ROOT));
        }
        return new ArrayList<>(names);
    }

    public static String norm(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    /** True once the leaderboard list has been downloaded. */
    public static boolean isLoaded() { return !lbCache.isEmpty(); }

    /** Like findCollection, but only uses the cache (safe on the render thread). Null if unknown / not loaded. */
    public static LbInfo findCollectionCached(String query) {
        try { return pick(lbCache, query); } catch (Exception e) { return null; }
    }

    /** Finds the best matching collection leaderboard for a user-typed name like "timite" or "nether wart". */
    public static LbInfo findCollection(String query) throws Exception {
        return pick(leaderboards(), query);
    }

    private static LbInfo pick(List<LbInfo> all, String query) {
        String q = norm(query);
        if (q.isEmpty()) throw new RuntimeException("No collection set");
        LbInfo best = null;
        int bestScore = 0;
        for (LbInfo l : all) {
            if (l.bestiary() != null) continue;
            int score = 0;
            for (String f : new String[]{l.collection(), l.itemId(), l.shortName(), l.title(), l.id()}) {
                String n = norm(f);
                if (n.isEmpty()) continue;
                if (n.equals(q)) score = Math.max(score, 100);
                else if (n.equals(q + "collection") || n.equals("rift" + q)) score = Math.max(score, 90);
                else if (n.endsWith(q) || n.startsWith(q)) score = Math.max(score, 40);
                else if (n.contains(q)) score = Math.max(score, 20);
            }
            if (score == 0) continue;
            if (l.collection() != null) score += 10;
            if (l.interval() == null || l.interval().equalsIgnoreCase("Current")) score += 5;
            if (score > bestScore) { bestScore = score; best = l; }
        }
        if (best == null) throw new RuntimeException("No collection leaderboard for '" + query + "'");
        return best;
    }

    // ---------- entries ----------

    public record Entry(int rank, String ign, double amount) {}

    /** Looks for a player in the top-N of a leaderboard. */
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

    /** Current #1 of the leaderboard. */
    public static Entry top1(String lb) throws Exception {
        JsonArray arr = get("/leaderboard/" + enc(lb) + "?limit=1").getAsJsonObject().getAsJsonArray("entries");
        if (arr.isEmpty()) throw new RuntimeException("Leaderboard is empty");
        JsonObject o = arr.get(0).getAsJsonObject();
        String name = str(o, "ign");
        return new Entry(1, name == null ? "?" : name, o.get("amount").getAsDouble());
    }

    /** Rank and amount of a player (selected profile). */
    public static Entry rankOf(String lb, UUID player, String displayName) throws Exception {
        String p = player.toString().replace("-", "");
        String profileId = get("/profile/" + p + "/selected").getAsJsonObject().get("profileId").getAsString();
        JsonObject pos = get("/leaderboard/rank/" + enc(lb) + "/" + p + "/" + profileId).getAsJsonObject();
        return new Entry(pos.get("rank").getAsInt(), displayName, pos.get("amount").getAsDouble());
    }

    /** Resolves a Minecraft name to a UUID through Elite's account endpoint. */
    public static UUID uuidOf(String ign) throws Exception {
        String id = get("/account/" + enc(ign)).getAsJsonObject().get("id").getAsString();
        if (id.length() == 32)
            id = id.replaceFirst("(\\w{8})(\\w{4})(\\w{4})(\\w{4})(\\w{12})", "$1-$2-$3-$4-$5");
        return UUID.fromString(id);
    }

    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
}
