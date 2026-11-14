package de.timitetracker;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.Command;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

public class TimiteTrackerClient implements ClientModInitializer {
    private static final String TARGET_IGN = "dinofeet_";
    private static final long REFRESH_MS = 5 * 60 * 1000L;
    private static final int HUD_X = 4, HUD_Y = 4;
    /** Every pickup of one of these items counts +1 to the Timite collection. */
    private static final String[] COUNTED_ITEMS = {"youngite", "timite", "obsolite"};
    private static final Path LOCAL_FILE = FabricLoader.getInstance().getConfigDir().resolve("timite-tracker-local.json");

    /** Last data fetched from the API. */
    private record Data(double mineApi, int mineRank, EliteApi.Entry target, boolean targetFound, Double leaderRate) {}

    private static volatile Data data;
    private static volatile String error;
    private static volatile List<Component> lines = List.of(
            Component.literal("Timite: loading...").withStyle(ChatFormatting.GRAY));
    private static volatile boolean hudVisible = true;
    private static final AtomicBoolean loading = new AtomicBoolean(false);
    private static long lastRefresh = 0;

    /** Items picked up locally that the API does not know about yet. */
    private static volatile long localBonus = 0;
    private static volatile double lastApiAmount = -1;
    private static int invBaseline = -1;

    @Override
    public void onInitializeClient() {
        loadLocal();

        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("timite-tracker", "hud"), (graphics, tickCounter) -> {
            if (!hudVisible) return;
            Minecraft mc = Minecraft.getInstance();
            int y = HUD_Y;
            for (Component line : lines) {
                graphics.text(mc.font, line, HUD_X, y, 0xFFFFFFFF);
                y += mc.font.lineHeight + 2;
            }
        });

        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (mc.player == null) { invBaseline = -1; return; }
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
                    hudVisible = !hudVisible;
                    ctx.getSource().sendFeedback(Component.literal("Timite HUD " + (hudVisible ? "shown" : "hidden")));
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
                EliteApi.Entry top = EliteApi.top1(lb);
                boolean found = target != null;
                if (!found) target = top;

                List<History.Point> hist = History.add(top.amount());

                // If the API value grew, it has (partly) caught up with our local pickups
                if (lastApiAmount >= 0 && mine.amount() > lastApiAmount) {
                    long caughtUp = (long) (mine.amount() - lastApiAmount);
                    localBonus = Math.max(0, localBonus - caughtUp);
                }
                lastApiAmount = mine.amount();
                saveLocal();

                data = new Data(mine.amount(), mine.rank(), target, found, History.leaderRatePerDay(hist));
                error = null;
            } catch (Exception e) {
                error = e.getMessage();
            } finally {
                loading.set(false);
                rebuild();
            }
        });
    }

    // ---------- HUD text ----------

    private static Component line(String label, String value, ChatFormatting color) {
        return Component.literal(label).withStyle(ChatFormatting.GRAY)
                .append(Component.literal(value).withStyle(color));
    }
    private static String fmt(double d) { return String.format(Locale.US, "%,.0f", d); }

    private static void rebuild() {
        List<Component> out = new ArrayList<>();
        out.add(Component.literal("Timite Collection").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
        Data d = data;
        if (d == null) {
            out.add(Component.literal(error != null ? "Error: " + error : "loading...")
                    .withStyle(error != null ? ChatFormatting.RED : ChatFormatting.GRAY));
            lines = out;
            return;
        }

        double mine = d.mineApi() + localBonus;
        EliteApi.Entry target = d.target();
        double gap = target.amount() - mine;
        Instant deadline = LocalDate.of(2027, 1, 1).atStartOfDay(ZoneId.systemDefault()).toInstant();
        double daysLeft = Math.max(0.01, Duration.between(Instant.now(), deadline).toSeconds() / 86400.0);

        String bonus = localBonus > 0 ? " (+" + localBonus + " live)" : "";
        out.add(line("You: ", fmt(mine) + bonus + " (#" + d.mineRank() + ")", ChatFormatting.GREEN));
        out.add(line(target.ign() + ": ", fmt(target.amount()) + " (#" + target.rank() + ")", ChatFormatting.RED));
        if (!d.targetFound())
            out.add(Component.literal(TARGET_IGN + " not in top 100, showing #1").withStyle(ChatFormatting.YELLOW));

        if (gap <= 0) {
            out.add(line("Lead: ", "+" + fmt(-gap), ChatFormatting.AQUA));
        } else {
            out.add(line("Gap: ", fmt(gap), ChatFormatting.YELLOW));
            out.add(line("Days until 2027: ", String.format(Locale.US, "%.1f", daysLeft), ChatFormatting.WHITE));
            out.add(line("Need/day: ", fmt(Math.ceil(gap / daysLeft)), ChatFormatting.AQUA));
            Double rate = d.leaderRate();
            if (rate != null) {
                double need = (gap + rate * daysLeft) / daysLeft;
                out.add(line("#1 pace: ", fmt(rate) + "/day", ChatFormatting.RED));
                out.add(line("Need/day (vs pace): ", fmt(Math.ceil(need)), ChatFormatting.AQUA));
            } else {
                out.add(Component.literal("#1 pace: collecting data...").withStyle(ChatFormatting.DARK_GRAY));
            }
        }
        if (error != null)
            out.add(Component.literal("Update failed: " + error).withStyle(ChatFormatting.RED));
        else
            out.add(Component.literal("Updated " + LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm")))
                    .withStyle(ChatFormatting.DARK_GRAY));
        lines = out;
    }
}
