package net.joeyscobblemon.gts;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.numbers.BlankFormat;
import net.minecraft.network.protocol.game.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.time.*;
import java.util.*;

/**
 * The sidebar on the right: your money, playtime, Pokédex, title, the server and the daily reset during normal play;
 * your run (room, streak, pot, format, modifiers) while in the Battle Frontier. Sent to each player separately,
 * so everyone sees their own numbers. /sidebar toggles it.
 */
@EventBusSubscriber(modid = GtsMod.MODID)
public final class Sidebar {
    private Sidebar() {}
    static MinecraftServer server;
    static final Scoreboard BOARD = new Scoreboard();
    static final Map<UUID, List<String>> SHOWN = new HashMap<>();
    static final Set<UUID> HIDDEN = new HashSet<>();
    static final Map<UUID, Integer> DEX = new HashMap<>();
    static int tick;
    static final String OBJ = "jcl_side";

    @SubscribeEvent public static void onStart(ServerStartedEvent e) { server = e.getServer(); loadPlaces(); }

    @SubscribeEvent
    public static void onTick(ServerTickEvent.Post e) {
        if (server == null || ++tick % 40 != 0) return;              // every 2 seconds
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (HIDDEN.contains(p.getUUID())) continue;
            try { show(p, lines(p)); } catch (Throwable t) { /* never let the sidebar break a tick */ }
        }
    }

    @SubscribeEvent public static void onLogout(PlayerEvent.PlayerLoggedOutEvent e) { SHOWN.remove(e.getEntity().getUUID()); DEX.remove(e.getEntity().getUUID()); }

    static String time(long ticks) {
        long m = ticks / 1200; long h = m / 60; m %= 60;
        return h > 0 ? h + "h " + m + "m" : m + "m";
    }

    static int dexCaught(ServerPlayer p) {
        if (tick % 400 != 0 && DEX.containsKey(p.getUUID())) return DEX.get(p.getUUID());     // refreshed every 20 s
        int n = 0;
        try {
            Object dex = Cob.pokedexOf(p);
            Map<?, ?> recs = (Map<?, ?>) Cob.call(dex, "getSpeciesRecords");
            for (Object rec : recs.values()) if (String.valueOf(Cob.call(rec, "getKnowledge")).equals("OWNED")) n++;
        } catch (Throwable ignored) {}
        DEX.put(p.getUUID(), n);
        return n;
    }

    static final java.time.format.DateTimeFormatter DATE = java.time.format.DateTimeFormatter.ofPattern("MM/dd/yy");

    static String clock(long dayTime) {
        long t = (dayTime + 6000) % 24000;                 // Minecraft noon is 6000
        int h = (int) (t / 1000), m = (int) ((t % 1000) * 60 / 1000);
        String ap = h < 12 ? "am" : "pm"; int h12 = h % 12 == 0 ? 12 : h % 12;
        return (h >= 6 && h < 19 ? "§e☀ " : "§9☽ ") + "§f" + h12 + ":" + String.format("%02d", m - m % 10) + ap;
    }

    static List<String> lines(ServerPlayer p) {
        List<String> l = new ArrayList<>();
        ZonedDateTime now = ZonedDateTime.now(ZoneId.of("America/New_York"));
        l.add("§c§lJOEY'S §f§lCOBBLEMON §e§lLEAGUE");
        l.add("§7" + now.format(DATE) + " §8" + GtsMod.VERSION);
        l.add(" ");
        Frontier.Run r = Frontier.RUNS.get(p.getUUID());
        if (r != null) {
            l.add("§fRoom: §e" + (r.floor + 1) + (r.setup.daily ? " §d(Daily)" : ""));
            l.add("§fStreak: §c" + r.streak);
            l.add("§fBracket: §e" + Frontier.cap(r.setup.bracket) + " §7" + Frontier.cap(r.setup.format) + (r.setup.rental ? " §8(Rental)" : ""));
            l.add("  ");
            l.add("§fPot: §b" + r.potBp + " BP");
            l.add("§fCash: §6" + Fmt.cd(r.potCd));
            l.add("§fBonus: §a×" + String.format(Locale.ROOT, "%.2f", r.mult));
            if (!r.setup.mods.isEmpty()) l.add("§fRules: §c" + String.join(", ", r.setup.mods.stream().map(Frontier::cap).toList()));
            l.add("   ");
            l.add("§e/frontier quit §7to leave");
        } else {
            l.add("§fWallet: §6" + Fmt.cd(Economy.balance(p)));
            l.add("§fPokédex: §c" + dexCaught(p) + " §7caught");
            l.add("§fPlaytime: §e" + time(p.getStats().getValue(Stats.CUSTOM.get(Stats.PLAY_TIME))));
            l.add("  ");
            l.add("§fTime: " + clock(p.level().getDayTime()));
            Duration left = Duration.between(now, now.toLocalDate().plusDays(1).atStartOfDay(now.getZone()));
            l.add("§fDailies reset: §e" + left.toHours() + "h " + (left.toMinutes() % 60) + "m");
            l.add("§fOnline: §a" + server.getPlayerCount());
            l.add("   ");
            l.add("§ejoeyscobblemonleague.com");
        }
        return l;
    }

    // ------------------------------------------------------------------ where each player wants it (saved)

    static final Map<String, float[]> PLACE = new HashMap<>();          // uuid -> {x%, y%, scale}
    static final float[] DEFAULT = {100f, 40f, 0.85f};
    static java.nio.file.Path placeFile() { return server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("data").resolve("jcl_gts").resolve("sidebar.json"); }
    static void loadPlaces() {
        try { var o = com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(placeFile())).getAsJsonObject();
            for (var e : o.entrySet()) { var a = e.getValue().getAsJsonArray(); PLACE.put(e.getKey(), new float[]{a.get(0).getAsFloat(), a.get(1).getAsFloat(), a.get(2).getAsFloat()}); }
        } catch (Exception ignored) {}
    }
    static void savePlaces() {
        try { var o = new com.google.gson.JsonObject();
            for (var e : PLACE.entrySet()) { var a = new com.google.gson.JsonArray(); for (float v : e.getValue()) a.add(v); o.add(e.getKey(), a); }
            java.nio.file.Files.createDirectories(placeFile().getParent()); java.nio.file.Files.writeString(placeFile(), o.toString());
        } catch (Exception ignored) {}
    }
    static float[] place(ServerPlayer p) { return PLACE.getOrDefault(p.getUUID().toString(), DEFAULT); }

    static void show(ServerPlayer p, List<String> lines) {
        List<String> old = SHOWN.get(p.getUUID());
        if (p.connection.hasChannel(SidebarPayload.TYPE)) {
            float[] pl = place(p);
            List<String> key = new ArrayList<>(lines); key.add(pl[0] + "," + pl[1] + "," + pl[2]);
            if (key.equals(old)) return;
            p.connection.send(new SidebarPayload(lines, pl[0], pl[1], pl[2], true));
            SHOWN.put(p.getUUID(), key);
            return;
        }
        if (lines.equals(old)) return;
        Objective obj = BOARD.getObjective(OBJ);
        if (obj == null) obj = BOARD.addObjective(OBJ, ObjectiveCriteria.DUMMY, Component.literal("§b§lJoey's §f§lCobblemon §b§lLeague"), ObjectiveCriteria.RenderType.INTEGER, false, BlankFormat.INSTANCE);
        var conn = p.connection;
        if (old == null) { conn.send(new ClientboundSetObjectivePacket(obj, ClientboundSetObjectivePacket.METHOD_ADD)); conn.send(new ClientboundSetDisplayObjectivePacket(DisplaySlot.SIDEBAR, obj)); }
        else for (int i = 0; i < old.size(); i++) conn.send(new ClientboundResetScorePacket("jcl_line_" + i, OBJ));
        for (int i = 0; i < lines.size(); i++)
            conn.send(new ClientboundSetScorePacket("jcl_line_" + i, OBJ, lines.size() - i, Optional.of(Component.literal(lines.get(i))), Optional.of(BlankFormat.INSTANCE)));
        SHOWN.put(p.getUUID(), lines);
    }

    static void hide(ServerPlayer p) {
        if (p.connection.hasChannel(SidebarPayload.TYPE)) { p.connection.send(new SidebarPayload(List.of(), 0, 0, 1, false)); SHOWN.remove(p.getUUID()); return; }
        Objective obj = BOARD.getObjective(OBJ);
        if (obj != null && SHOWN.remove(p.getUUID()) != null) p.connection.send(new ClientboundSetObjectivePacket(obj, ClientboundSetObjectivePacket.METHOD_REMOVE));
    }

    @SubscribeEvent
    public static void onCommands(RegisterCommandsEvent e) {
        var F = com.mojang.brigadier.arguments.FloatArgumentType.class;
        e.getDispatcher().register(net.minecraft.commands.Commands.literal("sidebar")
                .executes(c -> {
                    ServerPlayer p = c.getSource().getPlayerOrException();
                    if (HIDDEN.remove(p.getUUID())) c.getSource().sendSuccess(() -> Component.literal("§aSidebar on. §7/sidebar move, /sidebar size and /sidebar reset change where it sits."), false);
                    else { HIDDEN.add(p.getUUID()); hide(p); c.getSource().sendSuccess(() -> Component.literal("§7Sidebar off. §8/sidebar to bring it back."), false); }
                    return 1; })
                .then(net.minecraft.commands.Commands.literal("move")
                        .then(net.minecraft.commands.Commands.argument("x", com.mojang.brigadier.arguments.FloatArgumentType.floatArg(0, 100))
                        .then(net.minecraft.commands.Commands.argument("y", com.mojang.brigadier.arguments.FloatArgumentType.floatArg(0, 100)).executes(c -> {
                            ServerPlayer p = c.getSource().getPlayerOrException(); float[] pl = place(p).clone();
                            pl[0] = com.mojang.brigadier.arguments.FloatArgumentType.getFloat(c, "x"); pl[1] = com.mojang.brigadier.arguments.FloatArgumentType.getFloat(c, "y");
                            PLACE.put(p.getUUID().toString(), pl); savePlaces(); SHOWN.remove(p.getUUID()); show(p, lines(p));
                            c.getSource().sendSuccess(() -> Component.literal("§aSidebar moved. §7(0 0 is the top-left corner, 100 100 the bottom-right.)"), false); return 1; }))))
                .then(net.minecraft.commands.Commands.literal("size")
                        .then(net.minecraft.commands.Commands.argument("scale", com.mojang.brigadier.arguments.FloatArgumentType.floatArg(0.5f, 1.5f)).executes(c -> {
                            ServerPlayer p = c.getSource().getPlayerOrException(); float[] pl = place(p).clone();
                            pl[2] = com.mojang.brigadier.arguments.FloatArgumentType.getFloat(c, "scale");
                            PLACE.put(p.getUUID().toString(), pl); savePlaces(); SHOWN.remove(p.getUUID()); show(p, lines(p));
                            c.getSource().sendSuccess(() -> Component.literal("§aSidebar size set. §7(1 is normal, 0.85 is the default.)"), false); return 1; })))
                .then(net.minecraft.commands.Commands.literal("reset").executes(c -> {
                    ServerPlayer p = c.getSource().getPlayerOrException();
                    PLACE.remove(p.getUUID().toString()); savePlaces(); SHOWN.remove(p.getUUID()); show(p, lines(p));
                    c.getSource().sendSuccess(() -> Component.literal("§aSidebar back to its usual spot and size."), false); return 1; })));
    }
}
