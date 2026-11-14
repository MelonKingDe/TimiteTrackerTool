package de.collectiontracker;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.Command;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

public class CollectionTrackerClient implements ClientModInitializer {
    private static final long REFRESH_MS = 5 * 60 * 1000L;
    private static final Path LOCAL_FILE = FabricLoader.getInstance().getConfigDir().resolve("collection-tracker-local.json");

    /** Last data fetched from the API. */
    private record Data(double mineApi, int mineRank, EliteApi.Entry target, String note) {}

    /** Everything the HUD needs to draw (pre-formatted). status != null -> loading/error screen. */
    record View(String title, String status, boolean error, int rank, String you, String targetName, String targetVal,
                boolean lead, String gap, String need, String days, double progress, String note, String updated) {}

    private static volatile Data data;
    private static volatile String error;
    private static volatile EliteApi.LbInfo lbInfo;
    private static volatile String lbQuery = "";
    private static volatile View view = new View("Collection", "Loading...", false, 0, "", "", "", false, "", "", "", -1, null, "");
    private static final AtomicBoolean loading = new AtomicBoolean(false);
    private static volatile boolean pendingRefresh = false;
    private static volatile int gen = 0;
    private static long lastRefresh = 0;
    private static volatile boolean openGui = false;

    /** Items picked up locally that the API does not know about yet. */
    private static volatile long localBonus = 0;
    private static volatile double lastApiAmount = -1;
    private static int invBaseline = -1;
    /** The Rift has its own inventory: after any world change we ignore inventory changes for a few seconds. */
    private static Object lastLevel;
    private static int graceTicks = 0;
    private static final int GRACE_TICKS_AFTER_WORLD_CHANGE = 100; // 5 seconds

    @Override
    public void onInitializeClient() {
        HudConfig.load();
        loadLocal();
        // preload the leaderboard list so tab completion has collection names
        CompletableFuture.runAsync(() -> { try { EliteApi.leaderboards(); } catch (Exception ignored) {} });

        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("collection-tracker", "hud"), (graphics, tickCounter) -> {
            HudConfig cfg = HudConfig.get();
            if (!cfg.visible) return;
            Minecraft mc = Minecraft.getInstance();
            // Resize: translate to the HUD position, then scale everything drawn afterwards
            var pose = graphics.pose();
            pose.pushMatrix();
            pose.translate((float) cfg.x, (float) cfg.y);
            pose.scale(cfg.scale, cfg.scale);
            HudRenderer.draw(mc, cfg, view, 0, 0,
                    (x1, y1, x2, y2, c) -> graphics.fill(x1, y1, x2, y2, c),
                    (comp, x, y, color) -> graphics.text(mc.font, comp, x, y, color));
            pose.popMatrix();
        });

        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (openGui || OpenKey.pressed()) {
                openGui = false;
                mc.gui.setScreen(new SettingsScreen());
            }
            if (mc.player == null) { invBaseline = -1; lastLevel = null; return; }
            trackPickups(mc);
            long now = System.currentTimeMillis();
            if (now - lastRefresh >= REFRESH_MS) {
                lastRefresh = now;
                refresh();
            }
        });

        // /ctrack opens the settings GUI (the key bind does too)
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
            dispatcher.register(ClientCommands.literal("ctrack").executes(ctx -> {
                openGui = true; // opened on the next tick, after the chat screen has closed
                return Command.SINGLE_SUCCESS;
            })));
        OpenKey.register();
    }

    /** Called when collection/rival/deadline changed. resetProgress = collection changed -> drop old numbers. */
    static void settingsChanged(boolean resetProgress) {
        if (resetProgress) {
            localBonus = 0;
            lastApiAmount = -1;
            data = null;
            error = null;
            lbInfo = null;
            saveLocal();
            invBaseline = -1;
        }
        invBaseline = -1;
        gen++;
        rebuild();
        lastRefresh = System.currentTimeMillis();
        if (loading.get()) pendingRefresh = true; else refresh();
    }

    // ---------- live pickup tracking (inventory diff) ----------

    /** Item names (lowercase) whose pickups count +1 each. */
    static List<String> activeItems() {
        HudConfig cfg = HudConfig.get();
        if (!cfg.liveTracking) return List.of();
        if (!cfg.liveItems.isEmpty()) return cfg.liveItems;
        String q = EliteApi.norm(cfg.collection);
        if (q.equals("timite") || q.equals("rifttimite")) return List.of("youngite", "timite", "obsolite");
        EliteApi.LbInfo info = lbInfo;
        String name = info != null ? cleanTitle(info.title()) : cfg.collection;
        return List.of(name.toLowerCase(Locale.ROOT));
    }

    private static int countStack(ItemStack st, List<String> items) {
        if (st.isEmpty()) return 0;
        String name = st.getHoverName().getString().toLowerCase(Locale.ROOT);
        for (String n : items) if (name.contains(n)) return st.getCount();
        return 0;
    }

    /** Inventory + item held on the cursor, so moving items around never changes the total. */
    private static int countItems(Player player, List<String> items) {
        Inventory inv = player.getInventory();
        int total = countStack(player.containerMenu.getCarried(), items);
        for (int i = 0; i < inv.getContainerSize(); i++) total += countStack(inv.getItem(i), items);
        return total;
    }

    private static void trackPickups(Minecraft mc) {
        List<String> items = activeItems();
        if (items.isEmpty()) { invBaseline = -1; return; }
        int cur = countItems(mc.player, items);
        // New world (e.g. entering/leaving the Rift) -> inventory gets swapped, don't count it as pickups
        Object level = mc.player.level();
        if (level != lastLevel) {
            lastLevel = level;
            graceTicks = GRACE_TICKS_AFTER_WORLD_CHANGE;
        }
        if (graceTicks > 0) { graceTicks--; invBaseline = cur; return; }
        // While a chest/other container is open, items are only being moved around -> don't count
        boolean containerOpen = mc.player.containerMenu != mc.player.inventoryMenu;
        if (containerOpen || invBaseline < 0) { invBaseline = cur; return; }
        if (cur > invBaseline) {
            localBonus += cur - invBaseline;
            saveLocal();
            rebuild();
        }
        invBaseline = cur;
    }

    private static void loadLocal() {
        try {
            if (Files.exists(LOCAL_FILE)) {
                JsonObject o = JsonParser.parseString(Files.readString(LOCAL_FILE)).getAsJsonObject();
                localBonus = o.get("bonus").getAsLong();
                lastApiAmount = o.get("lastApi").getAsDouble();
            }
        } catch (Exception ignored) {}
    }

    private static void saveLocal() {
        try {
            JsonObject o = new JsonObject();
            o.addProperty("bonus", localBonus);
            o.addProperty("lastApi", lastApiAmount);
            Files.writeString(LOCAL_FILE, o.toString());
        } catch (Exception ignored) {}
    }

    // ---------- API refresh ----------

    /** Refresh right now (used by the settings GUI). */
    static void forceRefresh() {
        lastRefresh = System.currentTimeMillis();
        refresh();
    }

    public static void refresh() {
        if (!loading.compareAndSet(false, true)) return;
        final int myGen = gen;
        final HudConfig cfg = HudConfig.get();
        final String collection = cfg.collection, rival = cfg.rival;
        final UUID me = Minecraft.getInstance().getUser().getProfileId();

        CompletableFuture.runAsync(() -> {
            try {
                // resolve the leaderboard for the chosen collection
                EliteApi.LbInfo info = lbInfo;
                if (info == null || !EliteApi.norm(lbQuery).equals(EliteApi.norm(collection))) {
                    info = EliteApi.findCollection(collection);
                    if (myGen == gen) { lbInfo = info; lbQuery = collection; }
                }
                String lb = info.id();

                EliteApi.Entry mine = EliteApi.rankOf(lb, me, "You");

                // resolve the rival
                EliteApi.Entry target = null;
                String note = null;
                if (rival.equalsIgnoreCase("top")) {
                    target = EliteApi.top1(lb);
                } else {
                    target = EliteApi.findInTop(lb, rival, 100);
                    if (target == null) {
                        try { target = EliteApi.rankOf(lb, EliteApi.uuidOf(rival), rival); }
                        catch (Exception ignored) {}
                    }
                    if (target == null) {
                        target = EliteApi.top1(lb);
                        note = rival + " not found, showing #1";
                    }
                }

                if (myGen != gen) return; // settings changed while loading -> discard

                // If the API value grew, it has (partly) caught up with our local pickups
                if (lastApiAmount >= 0 && mine.amount() > lastApiAmount) {
                    long caughtUp = (long) (mine.amount() - lastApiAmount);
                    localBonus = Math.max(0, localBonus - caughtUp);
                }
                lastApiAmount = mine.amount();
                saveLocal();

                data = new Data(mine.amount(), mine.rank(), target, note);
                error = null;
            } catch (Exception e) {
                if (myGen == gen) error = e.getMessage();
            } finally {
                loading.set(false);
                if (pendingRefresh) { pendingRefresh = false; refresh(); }
                else rebuild();
            }
        });
    }

    // ---------- HUD content ----------

    private static String fmt(double d) { return String.format(Locale.US, "%,.0f", d); }

    private static String cleanTitle(String t) {
        if (t == null) return "";
        return t.replaceAll("(?i)\\s*collection\\s*$", "").trim();
    }

    private static void rebuild() {
        HudConfig cfg = HudConfig.get();
        EliteApi.LbInfo info = lbInfo;
        String title = info != null && info.title() != null ? cleanTitle(info.title()) : cfg.collection;

        Data d = data;
        if (d == null) {
            view = new View(title, error != null ? "Error: " + error : "Loading...", error != null,
                    0, "", "", "", false, "", "", "", -1, null, "");
            return;
        }
        double mine = d.mineApi() + localBonus;
        EliteApi.Entry target = d.target();
        double gap = target.amount() - mine;

        LocalDate dl = cfg.deadlineDate();
        Instant deadline = dl.atStartOfDay(ZoneId.systemDefault()).toInstant();
        double daysLeftRaw = Duration.between(Instant.now(), deadline).toSeconds() / 86400.0;
        boolean passed = daysLeftRaw <= 0;
        double daysLeft = Math.max(0.01, daysLeftRaw);

        String you = fmt(mine) + (localBonus > 0 ? " (+" + localBonus + ")" : "");
        double progress = target.amount() > 0 ? Math.min(1.0, mine / target.amount()) : -1;
        String note = passed ? "Deadline passed" : d.note();
        String updated = (error != null ? "Update failed" : "Updated " + LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm")))
                + " | goal " + dl.format(DateTimeFormatter.ofPattern("dd.MM.yyyy"));

        view = new View(title, null, false, d.mineRank(), you, target.ign(), fmt(target.amount()),
                gap <= 0, gap <= 0 ? "+" + fmt(-gap) : fmt(gap),
                (gap <= 0 || passed) ? "-" : fmt(Math.ceil(gap / daysLeft)),
                passed ? "0.0" : String.format(Locale.US, "%.1f", daysLeft), progress, note, updated);
    }
}
