package net.joeyscobblemon.gts;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.StringArgumentType;

import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Live leaderboards for the Hall of Fame.
 *   caught   - Pokémon caught this month
 *   trainers - NPC trainers beaten this month (wild battles and PvP don't count)
 *   money    - richest trainers, all time (last balance seen while they were online)
 *
 * Admins stand in front of a board and run /leaderboard place <board>; the top 10 then float
 * there as text and refresh every minute. Players can check any time with /leaderboard.
 */
@EventBusSubscriber(modid = "jcl_gts")
public final class Leaderboards {
    private Leaderboards() {}

    static final List<String> BOARDS = List.of("caught", "trainers", "money");

    public static final class Spot { public String dim; public double x, y, z; }
    public static final class Data {
        public String month = "";
        public Map<String, Map<String, Long>> monthly = new HashMap<>();   // board -> uuid -> count
        public Map<String, Long> money = new HashMap<>();
        public Map<String, String> names = new HashMap<>();
        public Map<String, Spot> spots = new HashMap<>();
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    static Data data = new Data();
    static MinecraftServer server;
    private static int ticks;
    private static boolean hooked;

    private static Path file() { return server.getWorldPath(LevelResource.ROOT).resolve("data").resolve("jcl_gts").resolve("leaderboards.json"); }

    static void save() {
        try { Files.createDirectories(file().getParent()); Files.writeString(file(), GSON.toJson(data)); }
        catch (Exception e) { GtsMod.LOG.error("[JCL] Could not save leaderboards", e); }
    }

    @SubscribeEvent
    public static void onStart(ServerStartedEvent e) {
        server = e.getServer();
        try { if (Files.exists(file())) { Data d = GSON.fromJson(Files.readString(file()), Data.class); if (d != null) data = d; } }
        catch (Exception ex) { GtsMod.LOG.error("[JCL] Could not read leaderboards", ex); }
        if (data.monthly == null) data.monthly = new HashMap<>();
        if (data.money == null) data.money = new HashMap<>();
        if (data.names == null) data.names = new HashMap<>();
        if (data.spots == null) data.spots = new HashMap<>();
        checkMonth();
        hook();
    }

    @SubscribeEvent public static void onStop(ServerStoppingEvent e) { save(); }

    static String monthKey() { LocalDate d = LocalDate.now(); return d.getYear() + "-" + d.getMonthValue(); }

    static void checkMonth() {
        String m = monthKey();
        if (!m.equals(data.month)) { data.month = m; data.monthly.clear(); save(); }
    }

    static void add(String board, ServerPlayer p, long n) {
        checkMonth();
        data.names.put(p.getUUID().toString(), p.getGameProfile().getName());
        data.monthly.computeIfAbsent(board, k -> new HashMap<>()).merge(p.getUUID().toString(), n, Long::sum);
    }

    // ------------------------------------------------------------------ Cobblemon hooks (by reflection, so no compile-time dependency)

    @SuppressWarnings("unchecked")
    static void hook() {
        if (hooked) return;
        try {
            Class<?> ev = Class.forName("com.cobblemon.mod.common.api.events.CobblemonEvents");
            subscribe(ev.getField("POKEMON_CAPTURED").get(null), e -> {
                try { Object pl = call(e, "getPlayer"); if (pl instanceof ServerPlayer sp) add("caught", sp, 1); } catch (Throwable ignored) {}
            });
            subscribe(ev.getField("BATTLE_VICTORY").get(null), e -> {
                try {
                    if (Boolean.TRUE.equals(call(e, "getWasWildCapture"))) return;
                    boolean trainer = false;
                    for (Object loser : (List<Object>) call(e, "getLosers")) {
                        String n = loser.getClass().getSimpleName();
                        if (!n.equals("PlayerBattleActor") && !n.equals("PokemonBattleActor") && !n.equals("MultiPokemonBattleActor")) trainer = true;
                    }
                    if (!trainer) return;
                    for (Object w : (List<Object>) call(e, "getWinners")) {
                        if (!w.getClass().getSimpleName().equals("PlayerBattleActor")) continue;
                        UUID id = (UUID) call(w, "getUuid");
                        ServerPlayer sp = server.getPlayerList().getPlayer(id);
                        if (sp != null) add("trainers", sp, 1);
                    }
                } catch (Throwable ignored) {}
            });
            hooked = true;
            GtsMod.LOG.info("[JCL] Leaderboards are tracking catches and trainer battles.");
        } catch (Throwable t) { GtsMod.LOG.error("[JCL] Could not hook Cobblemon events for leaderboards", t); }
    }

    static void subscribe(Object observable, Consumer<Object> handler) throws Exception {
        for (Method m : observable.getClass().getMethods()) {
            if (m.getName().equals("subscribe") && m.getParameterCount() == 1 && m.getParameterTypes()[0] == Consumer.class) { m.invoke(observable, handler); return; }
        }
        throw new NoSuchMethodException("subscribe(Consumer)");
    }

    static Object call(Object o, String name) throws Exception {
        for (Method m : o.getClass().getMethods()) if (m.getName().equals(name) && m.getParameterCount() == 0) return m.invoke(o);
        throw new NoSuchMethodException(name);
    }

    // ------------------------------------------------------------------ money: remember balances while players are online

    static void sampleMoney(ServerPlayer p) {
        long b = Economy.balance(p);
        if (b < 0) return;
        data.money.put(p.getUUID().toString(), b);
        data.names.put(p.getUUID().toString(), p.getGameProfile().getName());
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent e) { if (e.getEntity() instanceof ServerPlayer p) sampleMoney(p); }

    @SubscribeEvent
    public static void onTick(ServerTickEvent.Post e) {
        if (server == null || ++ticks % 1200 != 0) return;           // once a minute
        checkMonth();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) sampleMoney(p);
        save();
        for (String b : BOARDS) refresh(b);
    }

    // ------------------------------------------------------------------ ranking + display

    static List<Map.Entry<String, Long>> top(String board, int n) {
        Map<String, Long> src = board.equals("money") ? data.money : data.monthly.getOrDefault(board, Map.of());
        List<Map.Entry<String, Long>> list = new ArrayList<>(src.entrySet());
        list.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        return list.subList(0, Math.min(n, list.size()));
    }

    static String title(String b) {
        return switch (b) { case "caught" -> "§c§lPokémon Caught"; case "trainers" -> "§9§lTrainers Defeated"; default -> "§6§lMost PokéDollars"; };
    }

    static String sub(String b) { return b.equals("money") ? "§7All-time" : "§7This month · resets on the 1st"; }

    static String value(String b, long v) { return b.equals("money") ? Fmt.cd(v) : String.format(Locale.ROOT, "%,d", v); }

    static List<String> lines(String b) {
        List<String> out = new ArrayList<>();
        out.add(title(b)); out.add(sub(b)); out.add("");
        List<Map.Entry<String, Long>> t = top(b, 10);
        String[] medal = {"§6§l1.", "§7§l2.", "§c§l3."};
        for (int i = 0; i < 10; i++) {
            String rank = i < 3 ? medal[i] : "§f" + (i + 1) + ".";
            if (i < t.size()) out.add(rank + " §f" + data.names.getOrDefault(t.get(i).getKey(), "?") + " §8- §e" + value(b, t.get(i).getValue()));
            else out.add(rank + " §8---");
        }
        return out;
    }

    static void refresh(String b) {
        Spot sp = data.spots.get(b);
        if (sp == null || server == null) return;
        ServerLevel level = null;
        for (ServerLevel l : server.getAllLevels()) if (l.dimension().location().toString().equals(sp.dim)) level = l;
        if (level == null) return;
        var src = server.createCommandSourceStack().withLevel(level).withSuppressedOutput().withPermission(4);
        server.getCommands().performPrefixedCommand(src, "kill @e[type=minecraft:text_display,tag=jcl_lb_" + b + "]");
        List<String> ls = lines(b);
        for (int i = 0; i < ls.size(); i++) {
            if (ls.get(i).isEmpty()) continue;
            JsonObject t = new JsonObject(); t.addProperty("text", ls.get(i));
            double y = sp.y - i * 0.3;
            float scale = i == 0 ? 1.4f : 1.0f;
            server.getCommands().performPrefixedCommand(src, String.format(Locale.ROOT,
                    "summon minecraft:text_display %.3f %.3f %.3f {text:'%s',billboard:\"center\",shadow:1b,background:0,transformation:{scale:[%.2ff,%.2ff,%.2ff],translation:[0f,0f,0f],left_rotation:[0f,0f,0f,1f],right_rotation:[0f,0f,0f,1f]},Tags:[\"jcl_lb\",\"jcl_lb_%s\"]}",
                    sp.x, y, sp.z, t.toString().replace("'", "\\'"), scale, scale, scale, b));
        }
    }

    // ------------------------------------------------------------------ commands

    @SubscribeEvent
    public static void onCommands(RegisterCommandsEvent e) {
        for (String root : List.of("leaderboard", "leaderboards", "halloffame")) registerRoot(e, root);
    }

    static void registerRoot(RegisterCommandsEvent e, String root) {
        e.getDispatcher().register(Commands.literal(root)
                .executes(c -> { show(c.getSource().getPlayerOrException()); return 1; })
                .then(Commands.literal("place").requires(s -> s.hasPermission(2))
                        .then(Commands.argument("board", StringArgumentType.word()).suggests((c, b) -> SharedSuggestionProvider.suggest(BOARDS, b))
                                .executes(c -> {
                                    ServerPlayer p = c.getSource().getPlayerOrException(); String b = StringArgumentType.getString(c, "board");
                                    if (!BOARDS.contains(b)) { c.getSource().sendFailure(Component.literal("Use caught, trainers or money.")); return 0; }
                                    Spot sp = new Spot(); sp.dim = p.level().dimension().location().toString();
                                    sp.x = p.getX(); sp.y = p.getEyeY() + 0.5; sp.z = p.getZ();
                                    data.spots.put(b, sp); save(); refresh(b);
                                    p.sendSystemMessage(Component.literal("§aThe " + b + " leaderboard now floats here. It refreshes every minute."));
                                    return 1;
                                })))
                .then(Commands.literal("remove").requires(s -> s.hasPermission(2))
                        .then(Commands.argument("board", StringArgumentType.word()).suggests((c, b) -> SharedSuggestionProvider.suggest(BOARDS, b))
                                .executes(c -> {
                                    String b = StringArgumentType.getString(c, "board");
                                    server.getCommands().performPrefixedCommand(c.getSource().withSuppressedOutput().withPermission(4), "kill @e[type=minecraft:text_display,tag=jcl_lb_" + b + "]");
                                    data.spots.remove(b); save();
                                    c.getSource().sendSuccess(() -> Component.literal("§aRemoved the " + b + " leaderboard."), true); return 1;
                                })))
                .then(Commands.literal("refresh").requires(s -> s.hasPermission(2)).executes(c -> {
                    for (ServerPlayer p : server.getPlayerList().getPlayers()) sampleMoney(p);
                    for (String b : BOARDS) refresh(b);
                    c.getSource().sendSuccess(() -> Component.literal("§aLeaderboards refreshed."), true); return 1;
                })));
    }

    static void show(ServerPlayer p) {
        sampleMoney(p);
        String me = p.getUUID().toString();
        p.sendSystemMessage(Component.literal("§6§l✦ Leaderboards"));
        for (String b : BOARDS) {
            List<Map.Entry<String, Long>> t = top(b, 3);
            StringBuilder sb = new StringBuilder(" " + title(b) + "§r§7: ");
            for (int i = 0; i < t.size(); i++) sb.append(i == 0 ? "§6" : "§7").append(data.names.getOrDefault(t.get(i).getKey(), "?")).append(" §8(").append(value(b, t.get(i).getValue())).append(")§7").append(i < t.size() - 1 ? ", " : "");
            if (t.isEmpty()) sb.append("§8nobody yet");
            List<Map.Entry<String, Long>> all = top(b, 100000);
            int rank = -1; for (int i = 0; i < all.size(); i++) if (all.get(i).getKey().equals(me)) rank = i + 1;
            p.sendSystemMessage(Component.literal(sb.toString()));
            p.sendSystemMessage(Component.literal("   §7You: " + (rank > 0 ? "§f#" + rank + " §8(" + value(b, all.get(rank - 1).getValue()) + ")" : "§8not ranked yet")));
        }
    }
}
