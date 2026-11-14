package de.timitetracker;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
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
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

public class TimiteTrackerClient implements ClientModInitializer {
    private static final String TARGET_IGN = "dinofeet_";
    private static final long REFRESH_MS = 5 * 60 * 1000L;
    /** Every pickup of one of these items counts +1 to the Timite collection. */
    private static final String[] COUNTED_ITEMS = {"youngite", "timite", "obsolite"};
    private static final Path LOCAL_FILE = FabricLoader.getInstance().getConfigDir().resolve("timite-tracker-local.json");

    /** Last data fetched from the API. */
    private record Data(double mineApi, int mineRank, EliteApi.Entry target, boolean targetFound) {}

    /** Everything the HUD needs to draw (pre-formatted). status != null -> loading/error screen. */
    record View(String status, boolean error, int rank, String you, String targetName, String targetVal,
                        boolean lead, String gap, String need, String days, double progress, String note, String updated) {}

    private static volatile Data data;
    private static volatile String error;
    private static volatile View view = new View("Loading...", false, 0, "", "", "", false, "", "", "", -1, null, "");
    private static final AtomicBoolean loading = new AtomicBoolean(false);
    private static long lastRefresh = 0;

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

        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("timite-tracker", "hud"), (graphics, tickCounter) -> {
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
            if (mc.player == null) { invBaseline = -1; lastLevel = null; return; }
            trackPickups(mc);
            long now = System.currentTimeMillis();
            if (now - lastRefresh >= REFRESH_MS) {
                lastRefresh = now;
                refresh();
            }
        });

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
            dispatcher.register(ClientCommands.literal("timite")
                .executes(ctx -> {
                    lastRefresh = System.currentTimeMillis();
                    refresh();
                    ctx.getSource().sendFeedback(Component.literal("Timite HUD refreshing..."));
                    return Command.SINGLE_SUCCESS;
                })
                .then(ClientCommands.literal("toggle").executes(ctx -> {
                    HudConfig c = HudConfig.get();
                    c.visible = !c.visible;
                    HudConfig.save();
                    ctx.getSource().sendFeedback(Component.literal("Timite HUD " + (c.visible ? "shown" : "hidden")));
                    return Command.SINGLE_SUCCESS;
                }))
                .then(ClientCommands.literal("pos")
                    .then(ClientCommands.argument("x", IntegerArgumentType.integer(0))
                        .then(ClientCommands.argument("y", IntegerArgumentType.integer(0)).executes(ctx -> {
                            HudConfig c = HudConfig.get();
                            c.x = IntegerArgumentType.getInteger(ctx, "x");
                            c.y = IntegerArgumentType.getInteger(ctx, "y");
                            HudConfig.save();
                            ctx.getSource().sendFeedback(Component.literal("HUD position set to " + c.x + ", " + c.y));
                            return Command.SINGLE_SUCCESS;
                        }))))
                .then(ClientCommands.literal("scale")
                    .then(ClientCommands.argument("size", FloatArgumentType.floatArg(0.5f, 3.0f)).executes(ctx -> {
                        HudConfig c = HudConfig.get();
                        c.scale = FloatArgumentType.getFloat(ctx, "size");
                        HudConfig.save();
                        ctx.getSource().sendFeedback(Component.literal("HUD scale: " + c.scale));
                        return Command.SINGLE_SUCCESS;
                    })))
                .then(ClientCommands.literal("bg")
                    .then(ClientCommands.argument("opacity", IntegerArgumentType.integer(0, 100)).executes(ctx -> {
                        HudConfig c = HudConfig.get();
                        c.backgroundOpacity = IntegerArgumentType.getInteger(ctx, "opacity");
                        HudConfig.save();
                        ctx.getSource().sendFeedback(Component.literal("HUD background opacity: " + c.backgroundOpacity + "%"));
                        return Command.SINGLE_SUCCESS;
                    })))
                .then(ClientCommands.literal("resetlive").executes(ctx -> {
                    localBonus = 0;
                    saveLocal();
                    rebuild();
                    ctx.getSource().sendFeedback(Component.literal("Live pickup bonus cleared"));
                    return Command.SINGLE_SUCCESS;
                }))
                .then(ClientCommands.literal("reload").executes(ctx -> {
                    HudConfig.load();
                    ctx.getSource().sendFeedback(Component.literal("Timite config reloaded"));
                    return Command.SINGLE_SUCCESS;
                }))
                .then(ClientCommands.literal("reset").executes(ctx -> {
                    HudConfig.reset();
                    ctx.getSource().sendFeedback(Component.literal("Timite HUD reset to defaults"));
                    return Command.SINGLE_SUCCESS;
                }))));
    }

    // ---------- live pickup tracking (inventory diff) ----------

    private static int countStack(ItemStack st) {
        if (st.isEmpty()) return 0;
        String name = st.getHoverName().getString().toLowerCase(Locale.ROOT);
        for (String n : COUNTED_ITEMS) if (name.contains(n)) return st.getCount();
        return 0;
    }

    /** Inventory + item held on the cursor, so moving items around never changes the total. */
    private static int countItems(Player player) {
        Inventory inv = player.getInventory();
        int total = countStack(player.containerMenu.getCarried());
        for (int i = 0; i < inv.getContainerSize(); i++) total += countStack(inv.getItem(i));
        return total;
    }

    private static void trackPickups(Minecraft mc) {
        int cur = countItems(mc.player);
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

    public static void refresh() {
        if (!loading.compareAndSet(false, true)) return;
        UUID me = Minecraft.getInstance().getUser().getProfileId();

        CompletableFuture.runAsync(() -> {
            try {
                String lb = EliteApi.findTimiteLeaderboardId();
                EliteApi.Entry mine = EliteApi.me(lb, me);
                EliteApi.Entry target = EliteApi.findInTop(lb, TARGET_IGN, 100);
                boolean found = target != null;
                if (!found) target = EliteApi.top1(lb);

                // If the API value grew, it has (partly) caught up with our local pickups
                if (lastApiAmount >= 0 && mine.amount() > lastApiAmount) {
                    long caughtUp = (long) (mine.amount() - lastApiAmount);
                    localBonus = Math.max(0, localBonus - caughtUp);
                }
                lastApiAmount = mine.amount();
                saveLocal();

                data = new Data(mine.amount(), mine.rank(), target, found);
                error = null;
            } catch (Exception e) {
                error = e.getMessage();
            } finally {
                loading.set(false);
                rebuild();
            }
        });
    }

    // ---------- HUD content ----------

    private static String fmt(double d) { return String.format(Locale.US, "%,.0f", d); }

    private static void rebuild() {
        Data d = data;
        if (d == null) {
            view = new View(error != null ? "Error: " + error : "Loading...", error != null,
                    0, "", "", "", false, "", "", "", -1, null, "");
            return;
        }
        double mine = d.mineApi() + localBonus;
        EliteApi.Entry target = d.target();
        double gap = target.amount() - mine;
        Instant deadline = LocalDate.of(2027, 1, 1).atStartOfDay(ZoneId.systemDefault()).toInstant();
        double daysLeft = Math.max(0.01, Duration.between(Instant.now(), deadline).toSeconds() / 86400.0);

        String you = fmt(mine) + (localBonus > 0 ? " (+" + localBonus + ")" : "");
        double progress = target.amount() > 0 ? Math.min(1.0, mine / target.amount()) : -1;
        String note = d.targetFound() ? null : TARGET_IGN + " not in top 100, showing #1";
        String updated = error != null ? "Update failed"
                : "Updated " + LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"));

        view = new View(null, false, d.mineRank(), you, target.ign(), fmt(target.amount()),
                gap <= 0, gap <= 0 ? "+" + fmt(-gap) : fmt(gap),
                gap <= 0 ? "-" : fmt(Math.ceil(gap / daysLeft)),
                String.format(Locale.US, "%.1f", daysLeft), progress, note, updated);
    }
}
