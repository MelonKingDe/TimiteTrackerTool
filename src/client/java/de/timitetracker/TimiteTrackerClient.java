package de.timitetracker;

import com.mojang.brigadier.Command;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;

import java.time.*;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class TimiteTrackerClient implements ClientModInitializer {
    private static final String TARGET_IGN = "dinofeet_";
    private static volatile FabricClientCommandSource source;

    @Override
    public void onInitializeClient() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
            dispatcher.register(ClientCommands.literal("timite").executes(ctx -> {
                source = ctx.getSource();
                refresh();
                return Command.SINGLE_SUCCESS;
            })));
    }

    private static void say(Component c) {
        Minecraft mc = Minecraft.getInstance();
        FabricClientCommandSource src = source;
        if (src != null) mc.execute(() -> src.sendFeedback(c));
    }
    private static Component line(String label, String value, ChatFormatting color) {
        return Component.literal(label).withStyle(ChatFormatting.GRAY)
                .append(Component.literal(value).withStyle(color));
    }
    private static String fmt(double d) { return String.format("%,.0f", d); }

    public static void refresh() {
        Minecraft mc = Minecraft.getInstance();
        UUID me = mc.getUser().getProfileId();
        say(Component.literal("[Timite] lade Daten von Elite …").withStyle(ChatFormatting.DARK_GRAY));

        CompletableFuture.runAsync(() -> {
            try {
                String lb = EliteApi.findTimiteLeaderboardId();
                EliteApi.Entry mine = EliteApi.me(lb, me);
                EliteApi.Entry target = EliteApi.findInTop(lb, TARGET_IGN, 100);
                EliteApi.Entry top = EliteApi.top1(lb);
                if (target == null) target = top; // dinofeet_ nicht in Top 100 -> echten #1 nehmen

                List<History.Point> hist = History.add(top.amount());
                Double leaderRate = History.leaderRatePerDay(hist);

                double gap = target.amount() - mine.amount();
                ZoneId zone = ZoneId.systemDefault();
                Instant deadline = LocalDate.of(2027, 1, 1).atStartOfDay(zone).toInstant();
                double daysLeft = Math.max(0.01, Duration.between(Instant.now(), deadline).toSeconds() / 86400.0);

                say(Component.literal("=== Timite Collection ===").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
                say(line("Du: ", fmt(mine.amount()) + "  (Rang #" + mine.rank() + ")", ChatFormatting.GREEN));
                say(line(target.ign() + ": ", fmt(target.amount()) + "  (Rang #" + target.rank() + ")", ChatFormatting.RED));
                if (!target.ign().equalsIgnoreCase(TARGET_IGN))
                    say(Component.literal("(" + TARGET_IGN + " nicht in den Top 100 gefunden – zeige aktuellen #1)").withStyle(ChatFormatting.YELLOW));

                if (gap <= 0) {
                    say(Component.literal("Du führst bereits um " + fmt(-gap) + " 🎉").withStyle(ChatFormatting.AQUA));
                    return;
                }
                say(line("Abstand: ", fmt(gap), ChatFormatting.YELLOW));
                say(line("Tage bis 01.01.2027: ", String.format("%.1f", daysLeft), ChatFormatting.WHITE));
                say(line("Nötig/Tag (Gegner steht still): ", fmt(Math.ceil(gap / daysLeft)), ChatFormatting.AQUA));
                if (leaderRate != null) {
                    double need = (gap + leaderRate * daysLeft) / daysLeft;
                    say(line("#1 sammelt aktuell ca.: ", fmt(leaderRate) + "/Tag", ChatFormatting.RED));
                    say(line("Nötig/Tag (mit seinem Tempo): ", fmt(Math.ceil(need)), ChatFormatting.AQUA));
                } else {
                    say(Component.literal("Tempo des #1: noch zu wenig Messdaten – /timite in >12h nochmal ausführen.")
                            .withStyle(ChatFormatting.DARK_GRAY));
                }
            } catch (Exception e) {
                say(Component.literal("[Timite] Fehler: " + e.getMessage()).withStyle(ChatFormatting.RED));
            }
        });
    }
}
