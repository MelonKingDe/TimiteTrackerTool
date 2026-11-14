package de.timitetracker;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.Command;
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
    /** One HUD row: label on the left, value right-aligned. A null value = plain full-width text. */
    private record Row(Component label, Component value) {}
    private record View(List<Row> rows, double progress) {}

    private static volatile View view = new View(
            List.of(new Row(Component.literal("Loading...").withStyle(ChatFormatting.GRAY), null)), -1);
    private static final AtomicBoolean loading = new AtomicBoolean(false);
    private static long lastRefresh = 0;

    /** Items picked up locally that the API does not know about yet. */
    private static volatile long localBonus = 0;
    private static volatile double lastApiAmount = -1;
    private static int invBaseline = -1;

    @Override
    public void onInitializeClient() {
        HudConfig.load();
        loadLocal();

        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("timite-tracker", "hud"), (graphics, tickCounter) -> {
            HudConfig cfg = HudConfig.get();
            if (!cfg.visible) return;
            Minecraft mc = Minecraft.getInstance();
            View v = view;

            final int pad = 5, barW = 2, rowH = mc.font.lineHeight + 2, gap = 12;
            // width = widest row
            int inner = 90;
            for (Row r : v.rows())
                inner = Math.max(inner, mc.font.width(r.label()) + (r.value() == null ? 0 : gap + mc.font.width(r.value())));
            int w = pad + barW + pad + inner + pad;
            boolean bar = cfg.showProgressBar && v.progress() >= 0;
            int h = pad * 2 + v.rows().size() * rowH + (bar ? 9 : 0) - 2;

            int x = cfg.x, y = cfg.y;
            graphics.fill(x, y, x + w, y + h, cfg.backgroundArgb());          // panel
            graphics.fill(x, y, x + barW, y + h, cfg.accentArgb());           // accent bar

            int tx = x + barW + pad, ty = y + pad;
            for (Row r : v.rows()) {
                graphics.text(mc.font, r.label(), tx, ty, 0xFFFFFFFF);
                if (r.value() != null)
                    graphics.text(mc.font, r.value(), tx + inner - mc.font.width(r.value()), ty, 0xFFFFFFFF);
                ty += rowH;
            }
            if (bar) {
                int by = ty + 1, bw = inner;
                graphics.fill(tx, by, tx + bw, by + 3, 0x33FFFFFF);   // track
                int filled = (int) Math.round(bw * Math.min(1.0, Math.max(0.0, v.progress())));
                graphics.fill(tx, by, tx + filled, by + 3, cfg.accentArgb());                   // fill
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
                .then(ClientCommands.literal("bg")
                    .then(ClientCommands.argument("opacity", IntegerArgumentType.integer(0, 100)).executes(ctx -> {
                        HudConfig c = HudConfig.get();
                        c.backgroundOpacity = IntegerArgumentType.getInteger(ctx, "opacity");
                        HudConfig.save();
                        ctx.getSource().sendFeedback(Component.literal("HUD background opacity: " + c.backgroundOpacity + "%"));
                        return Command.SINGLE_SUCCESS;
                    })))
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

    // ---------- HUD content ----------

    private static Row row(String label, String value, ChatFormatting valueColor) {
        return new Row(Component.literal(label).withStyle(ChatFormatting.GRAY),
                Component.literal(value).withStyle(valueColor));
    }
    private static String fmt(double d) { return String.format(Locale.US, "%,.0f", d); }

    private static void rebuild() {
        HudConfig cfg = HudConfig.get();
        List<Row> rows = new ArrayList<>();
        Data d = data;

        Component title = Component.literal("TIMITE").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD);
        if (d == null) {
            rows.add(new Row(title, null));
            rows.add(new Row(Component.literal(error != null ? "Error: " + error : "Loading...")
                    .withStyle(error != null ? ChatFormatting.RED : ChatFormatting.GRAY), null));
            view = new View(rows, -1);
            return;
        }

        double mine = d.mineApi() + localBonus;
        EliteApi.Entry target = d.target();
        double gap = target.amount() - mine;
        Instant deadline = LocalDate.of(2027, 1, 1).atStartOfDay(ZoneId.systemDefault()).toInstant();
        double daysLeft = Math.max(0.01, Duration.between(Instant.now(), deadline).toSeconds() / 86400.0);

        rows.add(new Row(title, Component.literal("#" + d.mineRank()).withStyle(ChatFormatting.GREEN)));
        String bonus = localBonus > 0 ? " (+" + localBonus + ")" : "";
        rows.add(row("You", fmt(mine) + bonus, ChatFormatting.GREEN));
        rows.add(row(target.ign(), fmt(target.amount()), ChatFormatting.RED));
        if (!d.targetFound())
            rows.add(new Row(Component.literal(TARGET_IGN + " not in top 100").withStyle(ChatFormatting.YELLOW), null));

        if (gap <= 0) {
            rows.add(row("Lead", "+" + fmt(-gap), ChatFormatting.AQUA));
        } else {
            rows.add(row("Gap", fmt(gap), ChatFormatting.YELLOW));
            rows.add(row("Days left", String.format(Locale.US, "%.1f", daysLeft), ChatFormatting.WHITE));
            rows.add(row("Need / day", fmt(Math.ceil(gap / daysLeft)), ChatFormatting.AQUA));
            if (cfg.showPace) {
                Double rate = d.leaderRate();
                if (rate != null) {
                    rows.add(row("#1 pace", fmt(rate) + "/day", ChatFormatting.RED));
                    rows.add(row("Need / day vs #1", fmt(Math.ceil((gap + rate * daysLeft) / daysLeft)), ChatFormatting.AQUA));
                } else {
                    rows.add(new Row(Component.literal("#1 pace: collecting data...").withStyle(ChatFormatting.DARK_GRAY), null));
                }
            }
        }
        double progress = target.amount() > 0 ? Math.min(1.0, mine / target.amount()) : -1;
        if (progress >= 0)
            rows.add(row("Progress", String.format(Locale.US, "%.2f%%", progress * 100), ChatFormatting.WHITE));
        if (error != null)
            rows.add(new Row(Component.literal("Update failed").withStyle(ChatFormatting.RED), null));
        else if (cfg.showUpdated)
            rows.add(new Row(Component.literal("Updated " + LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm")))
                    .withStyle(ChatFormatting.DARK_GRAY), null));
        view = new View(rows, progress);
    }
}
