package net.joeyscobblemon.gts;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;

import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

/**
 * Saved floating text. Each text has an id, a spot and some lines; it is rebuilt on every startup,
 * so it can never go missing or double up.
 *   /holo preset <name>                 place a ready-made text where you stand
 *   /holo create <id> <text>            your own text: & colour codes, | for a new line
 *   /holo addline|setline|removeline    edit lines
 *   /holo move|scale|delete|list        manage
 *   /holo leaderboard <board>           place a live leaderboard
 */
@EventBusSubscriber(modid = "jcl_gts")
public final class Holos {
    private Holos() {}

    public static final class Text { public String dim; public double x, y, z; public float scale = 1f; public List<String> lines = new ArrayList<>(); }
    public static final class Data { public Map<String, Text> texts = new LinkedHashMap<>(); }

    static final Map<String, String[]> PRESETS = new LinkedHashMap<>();
    static {
        PRESETS.put("welcome", new String[]{"&6&l✦ Welcome to Port Azure ✦", "&eJoey's Cobblemon League", "&7New here? Talk to &bGuide Mae&7!", "&f/help &8· &f/warp &8· &f/jobs"});
        PRESETS.put("sharestone", new String[]{"&8&l◆ &0&lBlack Sharestone &8&l◆", "&7Links every &fofficial&7 safe zone", "&c&lRed sharestones&7 are community-built safe zones", "&7Both can take you back to spawn"});
        PRESETS.put("pokecenter", new String[]{"&c&lPokémon Center", "&7Free healing &8· &7GTS &8· &7Wonder Trade"});
        PRESETS.put("rules", new String[]{"&c&lServer Rules", "&f1. Be kind to everyone", "&f2. No griefing or stealing", "&f3. No cheating or exploits", "&f4. No spam", "&f5. Fair trades only", "&f6. Have fun!"});
        PRESETS.put("gts", new String[]{"&b&lGTS", "&7Buy, sell and auction", "&f/gts"});
        PRESETS.put("wondertrade", new String[]{"&d&lWonder Trade", "&7Send one, get a surprise", "&f/wondertrade"});
        PRESETS.put("mart", new String[]{"&9&lPoké Mart", "&7Buy with &f/shop &8· &7Sell with &f/sell"});
        PRESETS.put("worldmap", new String[]{"&2&lWorld Map", "&7Pin your base with a map in an item frame"});
        PRESETS.put("discord", new String[]{"&9&lJoin our Discord", "&fdiscord.gg/4hHNvcJagV", "&7Watch live: &dtwitch.tv/joeyzz0", "&7Server rules are in the Pokémon Center"});
        PRESETS.put("ev", new String[]{"&5&lEV Training", "&7Battle the trainers by the pillars", "&7or use &f/warp evs"});
        PRESETS.put("ev_hp", new String[]{"&c&lHP"});
        PRESETS.put("ev_atk", new String[]{"&6&lAttack"});
        PRESETS.put("ev_def", new String[]{"&e&lDefense"});
        PRESETS.put("ev_spa", new String[]{"&9&lSp. Atk"});
        PRESETS.put("ev_spd", new String[]{"&a&lSp. Def"});
        PRESETS.put("ev_spe", new String[]{"&d&lSpeed"});
        PRESETS.put("court", new String[]{"&a&lTrainer Court", "&7PvP battles &8· &7Challenge a friend!"});
        PRESETS.put("hall", new String[]{"&6&lHall of Fame", "&7The greatest trainers in Port Azure"});
        PRESETS.put("podium1", new String[]{"&6&l1st"});
        PRESETS.put("podium2", new String[]{"&7&l2nd"});
        PRESETS.put("podium3", new String[]{"&c&l3rd"});
        PRESETS.put("harbour", new String[]{"&3&lHarbour Office"});
        PRESETS.put("gateway", new String[]{"&6&lWelcome to Port Azure", "&eJoey's Cobblemon League"});
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    static Data data = new Data();
    static MinecraftServer server;

    private static Path file() { return server.getWorldPath(LevelResource.ROOT).resolve("data").resolve("jcl_gts").resolve("holograms.json"); }

    static void save() {
        try { Files.createDirectories(file().getParent()); Files.writeString(file(), GSON.toJson(data)); }
        catch (Exception e) { GtsMod.LOG.error("[JCL] Could not save floating text", e); }
    }

    @SubscribeEvent
    public static void onStart(ServerStartedEvent e) {
        server = e.getServer();
        try { if (Files.exists(file())) { Data d = GSON.fromJson(Files.readString(file()), Data.class); if (d != null && d.texts != null) data = d; } }
        catch (Exception ex) { GtsMod.LOG.error("[JCL] Could not read floating text", ex); }
        for (String id : data.texts.keySet()) build(id);
    }

    static String c(String s) { return s.replace('&', '§'); }
    static String tag(String id) { return "jcl_h_" + id; }

    static ServerLevel level(Text t) { return server.getLevel(ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(t.dim))); }

    /** (Re)build the entities for one text. The first line is drawn larger, as a title. */
    static void build(String id) {
        Text t = data.texts.get(id);
        if (t == null) return;
        ServerLevel lvl = level(t);
        if (lvl == null) return;
        Holo.remove(lvl, tag(id));
        double y = t.y;
        for (int i = 0; i < t.lines.size(); i++) {
            String line = t.lines.get(i);
            float s = t.scale * (i == 0 && t.lines.size() > 1 ? 1.35f : 1f);
            if (!line.isBlank()) Holo.spawn(lvl, t.x, y, t.z, Component.literal(c(line)), tag(id), s, false);
            y -= 0.3 * s;
        }
    }

    static void put(String id, ServerLevel lvl, double x, double y, double z, float scale, List<String> lines) {
        Text t = new Text(); t.dim = lvl.dimension().location().toString(); t.x = x; t.y = y; t.z = z; t.scale = scale; t.lines = new ArrayList<>(lines);
        Text old = data.texts.get(id);
        if (old != null && level(old) != null) Holo.remove(level(old), tag(id));
        data.texts.put(id, t); save(); build(id);
    }

    static void delete(String id) {
        Text t = data.texts.remove(id);
        if (t != null && level(t) != null) Holo.remove(level(t), tag(id));
        save();
    }

    static void msg(ServerPlayer p, String s) { p.sendSystemMessage(Component.literal("§a" + s)); }

    @SubscribeEvent
    public static void onCommands(RegisterCommandsEvent e) {
        var ids = (com.mojang.brigadier.suggestion.SuggestionProvider<net.minecraft.commands.CommandSourceStack>) (c, b) -> SharedSuggestionProvider.suggest(data.texts.keySet(), b);
        e.getDispatcher().register(Commands.literal("holo").requires(s -> s.hasPermission(2))
                .then(Commands.literal("preset").then(Commands.argument("name", StringArgumentType.word()).suggests((c, b) -> SharedSuggestionProvider.suggest(PRESETS.keySet(), b))
                        .executes(c -> {
                            ServerPlayer p = c.getSource().getPlayerOrException(); String n = StringArgumentType.getString(c, "name");
                            String[] lines = PRESETS.get(n);
                            if (lines == null) { c.getSource().sendFailure(Component.literal("No preset called " + n + ".")); return 0; }
                            put(n, p.serverLevel(), p.getX(), p.getEyeY() + 0.6, p.getZ(), 1.2f, List.of(lines));
                            msg(p, "Placed the " + n + " text. Edit it like any other with its id: " + n); return 1;
                        })
                        .then(Commands.argument("pos", Vec3Argument.vec3()).executes(c -> {
                            ServerPlayer p = c.getSource().getPlayerOrException(); String n = StringArgumentType.getString(c, "name");
                            String[] lines = PRESETS.get(n);
                            if (lines == null) { c.getSource().sendFailure(Component.literal("No preset called " + n + ".")); return 0; }
                            var v = Vec3Argument.getVec3(c, "pos");
                            put(n, p.serverLevel(), v.x, v.y, v.z, n.startsWith("ev_") || n.startsWith("podium") ? 1.4f : 1.2f, List.of(lines));
                            msg(p, "Placed the " + n + " text."); return 1;
                        }))))
                .then(Commands.literal("create").then(Commands.argument("id", StringArgumentType.word()).then(Commands.argument("text", StringArgumentType.greedyString()).executes(c -> {
                    ServerPlayer p = c.getSource().getPlayerOrException();
                    put(StringArgumentType.getString(c, "id"), p.serverLevel(), p.getX(), p.getEyeY() + 0.6, p.getZ(), 1f, List.of(StringArgumentType.getString(c, "text").split("\\|")));
                    msg(p, "Created."); return 1;
                }))))
                .then(Commands.literal("addline").then(Commands.argument("id", StringArgumentType.word()).suggests(ids).then(Commands.argument("text", StringArgumentType.greedyString()).executes(c -> {
                    Text t = data.texts.get(StringArgumentType.getString(c, "id"));
                    if (t == null) { c.getSource().sendFailure(Component.literal("No such text.")); return 0; }
                    t.lines.add(StringArgumentType.getString(c, "text")); save(); build(StringArgumentType.getString(c, "id")); return 1;
                }))))
                .then(Commands.literal("setline").then(Commands.argument("id", StringArgumentType.word()).suggests(ids).then(Commands.argument("line", IntegerArgumentType.integer(1))
                        .then(Commands.argument("text", StringArgumentType.greedyString()).executes(c -> {
                            Text t = data.texts.get(StringArgumentType.getString(c, "id")); int n = IntegerArgumentType.getInteger(c, "line");
                            if (t == null || n > t.lines.size()) { c.getSource().sendFailure(Component.literal("No such text or line.")); return 0; }
                            t.lines.set(n - 1, StringArgumentType.getString(c, "text")); save(); build(StringArgumentType.getString(c, "id")); return 1;
                        })))))
                .then(Commands.literal("removeline").then(Commands.argument("id", StringArgumentType.word()).suggests(ids).then(Commands.argument("line", IntegerArgumentType.integer(1)).executes(c -> {
                    Text t = data.texts.get(StringArgumentType.getString(c, "id")); int n = IntegerArgumentType.getInteger(c, "line");
                    if (t == null || n > t.lines.size()) { c.getSource().sendFailure(Component.literal("No such text or line.")); return 0; }
                    t.lines.remove(n - 1); save(); build(StringArgumentType.getString(c, "id")); return 1;
                }))))
                .then(Commands.literal("move").then(Commands.argument("id", StringArgumentType.word()).suggests(ids).executes(c -> {
                    ServerPlayer p = c.getSource().getPlayerOrException(); Text t = data.texts.get(StringArgumentType.getString(c, "id"));
                    if (t == null) { c.getSource().sendFailure(Component.literal("No such text.")); return 0; }
                    put(StringArgumentType.getString(c, "id"), p.serverLevel(), p.getX(), p.getEyeY() + 0.6, p.getZ(), t.scale, t.lines); msg(p, "Moved."); return 1;
                })))
                .then(Commands.literal("scale").then(Commands.argument("id", StringArgumentType.word()).suggests(ids).then(Commands.argument("size", FloatArgumentType.floatArg(0.2f, 10f)).executes(c -> {
                    Text t = data.texts.get(StringArgumentType.getString(c, "id"));
                    if (t == null) { c.getSource().sendFailure(Component.literal("No such text.")); return 0; }
                    t.scale = FloatArgumentType.getFloat(c, "size"); save(); build(StringArgumentType.getString(c, "id")); return 1;
                }))))
                .then(Commands.literal("delete").then(Commands.argument("id", StringArgumentType.word()).suggests(ids).executes(c -> {
                    delete(StringArgumentType.getString(c, "id")); c.getSource().sendSuccess(() -> Component.literal("§aDeleted."), false); return 1;
                })))
                .then(Commands.literal("list").executes(c -> {
                    c.getSource().sendSuccess(() -> Component.literal("§6§lFloating text §7(" + data.texts.size() + ")"), false);
                    for (var en : data.texts.entrySet()) {
                        Text t = en.getValue();
                        String tp = String.format(Locale.ROOT, "/execute in %s run tp @s %.1f %.1f %.1f", t.dim, t.x, t.y, t.z);
                        c.getSource().sendSuccess(() -> Component.literal(" §e" + en.getKey() + " §7" + (t.lines.isEmpty() ? "" : c(t.lines.get(0)).replaceAll("§.", "")) + " §8[tp]")
                                .withStyle(st -> st.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, tp))), false);
                    }
                    return 1;
                }))
                .then(Commands.literal("leaderboard").then(Commands.argument("board", StringArgumentType.word()).suggests((c, b) -> SharedSuggestionProvider.suggest(Leaderboards.BOARDS, b))
                        .executes(c -> {
                            ServerPlayer p = c.getSource().getPlayerOrException(); String b = StringArgumentType.getString(c, "board");
                            if (!Leaderboards.BOARDS.contains(b)) { c.getSource().sendFailure(Component.literal("Use caught, trainers or money.")); return 0; }
                            Leaderboards.place(b, p.serverLevel(), p.getX(), p.getEyeY() + 2.5, p.getZ());
                            msg(p, "The " + b + " leaderboard now floats here."); return 1;
                        })
                        .then(Commands.argument("pos", Vec3Argument.vec3()).executes(c -> {
                            ServerPlayer p = c.getSource().getPlayerOrException(); String b = StringArgumentType.getString(c, "board");
                            if (!Leaderboards.BOARDS.contains(b)) { c.getSource().sendFailure(Component.literal("Use caught, trainers or money.")); return 0; }
                            var v = Vec3Argument.getVec3(c, "pos");
                            Leaderboards.place(b, p.serverLevel(), v.x, v.y, v.z);
                            msg(p, "Placed the " + b + " leaderboard."); return 1;
                        })))));
    }
}
