package net.joeyscobblemon.gts;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.brigadier.arguments.StringArgumentType;

import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

/**
 * Titles and name colours.
 *
 * Unlocks are saved by JCL Core itself (world/data/jcl_gts/titles.json), so they work with or
 * without LuckPerms. Staff can still hand titles out through LuckPerms groups with the
 * permission jcl.title.<id> (or jcl.title.* for every title).
 *
 * Players:  /titles, /titles list, /titles set <id>, /titles clear, /titles color [id|clear]
 * Admins:   /titles grant <player> <id>, /titles revoke <player> <id>, /titles grantcolor <player> <id>, /titles reload
 */
@EventBusSubscriber(modid = "jcl_gts")
public final class Titles {
    private Titles() {}

    public static final class Title { public String id, prefix, name, category, how; }
    public static final class NameColor { public String id, code, name, how; }

    public static final class Config {
        public int version;
        public List<Title> titles = new ArrayList<>();
        public List<NameColor> colors = new ArrayList<>();
    }

    public static final class Data {
        public Map<String, Set<String>> unlocked = new HashMap<>();
        public Map<String, Set<String>> unlockedColors = new HashMap<>();
        public Map<String, String> chosen = new HashMap<>();
        public Map<String, String> chosenColor = new HashMap<>();
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    static Config cfg = new Config();
    static Data data = new Data();
    static MinecraftServer server;

    private static Path dataFile() { return server.getWorldPath(LevelResource.ROOT).resolve("data").resolve("jcl_gts").resolve("titles.json"); }

    static void load() {
        try {
            String json = Defaults.load("jcl_titles.json");
            Config c = json == null ? null : GSON.fromJson(json, Config.class);
            if (c != null) cfg = c;
        } catch (Exception e) { GtsMod.LOG.error("[JCL] Could not read config/jcl_titles.json", e); }
        try {
            if (Files.exists(dataFile())) { Data d = GSON.fromJson(Files.readString(dataFile()), Data.class); if (d != null) data = d; }
        } catch (Exception e) { GtsMod.LOG.error("[JCL] Could not read title data", e); }
        if (data.unlocked == null) data.unlocked = new HashMap<>();
        if (data.unlockedColors == null) data.unlockedColors = new HashMap<>();
        if (data.chosen == null) data.chosen = new HashMap<>();
        if (data.chosenColor == null) data.chosenColor = new HashMap<>();
    }

    static void save() {
        try { Files.createDirectories(dataFile().getParent()); Files.writeString(dataFile(), GSON.toJson(data)); }
        catch (Exception e) { GtsMod.LOG.error("[JCL] Could not save title data", e); }
    }

    @SubscribeEvent public static void onStart(ServerStartedEvent e) { server = e.getServer(); load(); }
    @SubscribeEvent public static void onStop(ServerStoppingEvent e) { save(); }

    static Title find(String id) { for (Title t : cfg.titles) if (t.id.equalsIgnoreCase(id)) return t; return null; }
    static NameColor findColor(String id) { for (NameColor c : cfg.colors) if (c.id.equalsIgnoreCase(id)) return c; return null; }
    static String colour(String s) { return s == null ? "" : s.replace('&', '§'); }

    // ------------------------------------------------------------------ unlocks

    static boolean has(ServerPlayer p, String id) {
        Set<String> s = data.unlocked.get(p.getUUID().toString());
        if (s != null && s.contains(id)) return true;
        return luckPerms(p.getUUID(), "jcl.title." + id) || luckPerms(p.getUUID(), "jcl.title.*");
    }

    static boolean hasColor(ServerPlayer p, String id) {
        Set<String> s = data.unlockedColors.get(p.getUUID().toString());
        return (s != null && s.contains(id)) || luckPerms(p.getUUID(), "jcl.color." + id) || luckPerms(p.getUUID(), "jcl.color.*");
    }

    /** Optional LuckPerms check, for staff groups. Returns false if LuckPerms isn't reachable. */
    static boolean luckPerms(UUID id, String node) {
        try {
            Object lp = Class.forName("net.luckperms.api.LuckPermsProvider").getMethod("get").invoke(null);
            Object um = lp.getClass().getMethod("getUserManager").invoke(lp);
            Object user = um.getClass().getMethod("getUser", UUID.class).invoke(um, id);
            if (user == null) return false;
            Object cached = user.getClass().getMethod("getCachedData").invoke(user);
            Object perms = cached.getClass().getMethod("getPermissionData").invoke(cached);
            Object tri = perms.getClass().getMethod("checkPermission", String.class).invoke(perms, node);
            return Boolean.TRUE.equals(tri.getClass().getMethod("asBoolean").invoke(tri));
        } catch (Throwable t) { return false; }
    }

    static void grant(ServerPlayer p, String id) {
        Title t = find(id);
        if (t == null) return;
        if (data.unlocked.computeIfAbsent(p.getUUID().toString(), k -> new HashSet<>()).add(t.id)) {
            save();
            p.sendSystemMessage(Component.literal("§6§l✦ §r§eNew title unlocked: " + colour(t.prefix) + "§e! Wear it with §f/titles§e."));
            p.playNotifySound(SoundEvents.PLAYER_LEVELUP, SoundSource.MASTER, 0.6f, 1.4f);
        }
    }

    static void grantColor(ServerPlayer p, String id) {
        NameColor c = findColor(id);
        if (c == null) return;
        if (data.unlockedColors.computeIfAbsent(p.getUUID().toString(), k -> new HashSet<>()).add(c.id)) {
            save();
            p.sendSystemMessage(Component.literal("§6§l✦ §r§eNew name colour: " + colour(c.code) + c.name + "§e! Use it with §f/titles color§e."));
        }
    }

    // ------------------------------------------------------------------ display

    static String prefix(Player p) {
        if (!(p instanceof ServerPlayer sp)) return null;
        String id = data.chosen.get(p.getUUID().toString());
        Title t = id == null ? null : find(id);
        return t != null && has(sp, t.id) ? colour(t.prefix) : null;
    }

    static String nameColor(Player p) {
        if (!(p instanceof ServerPlayer sp)) return "";
        String id = data.chosenColor.get(p.getUUID().toString());
        NameColor c = id == null ? null : findColor(id);
        return c != null && hasColor(sp, c.id) ? colour(c.code) : "";
    }

    /** "[Title] Name" with the chosen name colour, or null when the player uses neither. */
    static Component decorated(Player p) {
        String pre = prefix(p), col = nameColor(p);
        if (pre == null && col.isEmpty()) return null;
        return Component.literal((pre == null ? "" : pre + " ") + "§r" + col + p.getGameProfile().getName());
    }

    @SubscribeEvent
    public static void onName(PlayerEvent.NameFormat e) {
        Component c = decorated(e.getEntity());
        if (c != null) e.setDisplayname(c);
    }

    static void refresh(ServerPlayer p) { p.refreshDisplayName(); p.refreshTabListName(); }

    // ------------------------------------------------------------------ commands

    @SubscribeEvent
    public static void onCommands(RegisterCommandsEvent e) {
        e.getDispatcher().register(Commands.literal("titles")
                .executes(c -> { menu(c.getSource().getPlayerOrException()); return 1; })
                .then(Commands.literal("list").executes(c -> { list(c.getSource().getPlayerOrException()); return 1; }))
                .then(Commands.literal("clear").executes(c -> { set(c.getSource().getPlayerOrException(), null); return 1; }))
                .then(Commands.literal("set").then(Commands.argument("id", StringArgumentType.word())
                        .suggests((c, b) -> SharedSuggestionProvider.suggest(cfg.titles.stream().map(t -> t.id), b))
                        .executes(c -> {
                            ServerPlayer p = c.getSource().getPlayerOrException(); Title t = find(StringArgumentType.getString(c, "id"));
                            if (t == null || !has(p, t.id)) { c.getSource().sendFailure(Component.literal("You haven't unlocked that title. See them all with /titles.")); return 0; }
                            set(p, t.id); return 1;
                        })))
                .then(Commands.literal("color")
                        .executes(c -> { colorMenu(c.getSource().getPlayerOrException()); return 1; })
                        .then(Commands.literal("clear").executes(c -> { setColor(c.getSource().getPlayerOrException(), null); return 1; }))
                        .then(Commands.argument("id", StringArgumentType.word()).suggests((c, b) -> SharedSuggestionProvider.suggest(cfg.colors.stream().map(x -> x.id), b))
                                .executes(c -> {
                                    ServerPlayer p = c.getSource().getPlayerOrException(); NameColor col = findColor(StringArgumentType.getString(c, "id"));
                                    if (col == null || !hasColor(p, col.id)) { c.getSource().sendFailure(Component.literal("You haven't unlocked that colour. Name colours are sold in /shop.")); return 0; }
                                    setColor(p, col.id); return 1;
                                })))
                .then(Commands.literal("grant").requires(s -> s.hasPermission(2))
                        .then(Commands.argument("player", EntityArgument.player()).then(Commands.argument("id", StringArgumentType.word())
                                .suggests((c, b) -> SharedSuggestionProvider.suggest(cfg.titles.stream().map(t -> t.id), b))
                                .executes(c -> { grant(EntityArgument.getPlayer(c, "player"), StringArgumentType.getString(c, "id")); return 1; }))))
                .then(Commands.literal("revoke").requires(s -> s.hasPermission(2))
                        .then(Commands.argument("player", EntityArgument.player()).then(Commands.argument("id", StringArgumentType.word())
                                .suggests((c, b) -> SharedSuggestionProvider.suggest(cfg.titles.stream().map(t -> t.id), b))
                                .executes(c -> {
                                    ServerPlayer p = EntityArgument.getPlayer(c, "player"); String id = StringArgumentType.getString(c, "id");
                                    Set<String> s = data.unlocked.get(p.getUUID().toString()); if (s != null) s.remove(id);
                                    if (id.equals(data.chosen.get(p.getUUID().toString()))) data.chosen.remove(p.getUUID().toString());
                                    save(); refresh(p);
                                    c.getSource().sendSuccess(() -> Component.literal("§aRemoved " + id + "."), true); return 1;
                                }))))
                .then(Commands.literal("grantcolor").requires(s -> s.hasPermission(2))
                        .then(Commands.argument("player", EntityArgument.player()).then(Commands.argument("id", StringArgumentType.word())
                                .suggests((c, b) -> SharedSuggestionProvider.suggest(cfg.colors.stream().map(x -> x.id), b))
                                .executes(c -> { grantColor(EntityArgument.getPlayer(c, "player"), StringArgumentType.getString(c, "id")); return 1; }))))
                .then(Commands.literal("reload").requires(s -> s.hasPermission(2)).executes(c -> { load(); c.getSource().sendSuccess(() -> Component.literal("§aTitles reloaded."), true); return 1; })));
    }

    static void set(ServerPlayer p, String id) {
        if (id == null) data.chosen.remove(p.getUUID().toString()); else data.chosen.put(p.getUUID().toString(), id);
        save(); refresh(p);
        p.playNotifySound(SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.MASTER, 0.6f, 1.3f);
        Title t = id == null ? null : find(id);
        p.sendSystemMessage(Component.literal(t == null ? "§6§l✦ §r§7Title removed." : "§6§l✦ §r§aNow wearing " + colour(t.prefix) + "§a."));
    }

    static void setColor(ServerPlayer p, String id) {
        if (id == null) data.chosenColor.remove(p.getUUID().toString()); else data.chosenColor.put(p.getUUID().toString(), id);
        save(); refresh(p);
        NameColor c = id == null ? null : findColor(id);
        p.sendSystemMessage(Component.literal(c == null ? "§6§l✦ §r§7Name colour removed." : "§6§l✦ §r§aYour name is now " + colour(c.code) + p.getGameProfile().getName() + "§a."));
    }

    private static final List<String> ORDER = List.of("quest", "shop", "crate", "staff");

    static String catName(String c) {
        return switch (c) { case "quest" -> "§aQuest reward"; case "shop" -> "§eSold in /shop"; case "crate" -> "§dCrate exclusive"; case "staff" -> "§cStaff and events only"; default -> "§7Title"; };
    }

    static void list(ServerPlayer p) {
        p.sendSystemMessage(Component.literal("§6§l✦ Your titles"));
        int n = 0;
        for (Title t : cfg.titles) if (has(p, t.id)) { p.sendSystemMessage(Component.literal(" §8• " + colour(t.prefix) + " §7→ /titles set " + t.id)); n++; }
        if (n == 0) p.sendSystemMessage(Component.literal(" §7None yet. Open §f/titles§7 to see how to unlock them."));
    }

    static void menu(ServerPlayer p) { menu(p, 0); }

    static void menu(ServerPlayer p, int page) {
        List<Title> list = new ArrayList<>(cfg.titles);
        list.sort((a, b) -> {
            boolean ha = has(p, a.id), hb = has(p, b.id);
            if (ha != hb) return ha ? -1 : 1;
            return ORDER.indexOf(a.category) - ORDER.indexOf(b.category);
        });
        String current = data.chosen.get(p.getUUID().toString());
        int owned = 0;
        for (Title t : list) if (has(p, t.id)) owned++;
        int pages = Math.max(1, (list.size() + 44) / 45);
        int pg = Math.max(0, Math.min(page, pages - 1));
        Gui.Screen s = new Gui.Screen("§6§l✦ Titles §8(" + owned + "/" + list.size() + ")" + (pages > 1 ? " §8" + (pg + 1) + "/" + pages : ""), 6);
        for (int i = 0; i < Math.min(45, list.size() - pg * 45); i++) {
            Title t = list.get(pg * 45 + i);
            boolean unlocked = has(p, t.id), on = t.id.equals(current);
            String[] lore = unlocked
                    ? new String[]{catName(t.category), "", "§7Looks like:", colour(t.prefix) + " §f" + p.getGameProfile().getName(), "", on ? "§a✔ Wearing" : "§e▶ Click to wear"}
                    : new String[]{catName(t.category), "", "§7How to unlock:", "§f" + t.how};
            var icon = Gui.icon(unlocked ? (on ? Items.NAME_TAG : Items.PAPER) : Items.GRAY_DYE, unlocked ? colour(t.prefix) : "§8🔒 §7" + t.name, lore);
            s.set(i, on ? Gui.glow(icon) : icon, pl -> { if (has(pl, t.id)) { set(pl, t.id); menu(pl, pg); } else pl.playNotifySound(SoundEvents.VILLAGER_NO, SoundSource.MASTER, 0.6f, 1f); });
        }
        for (int i = 45; i < 54; i++) s.set(i, Gui.pane(Items.BLACK_STAINED_GLASS_PANE));
        s.set(45, Gui.icon(Items.BOOK, "§e§lAbout titles", "§7Earn titles from quests, buy them", "§7in §f/shop§7, pull them from crates,", "§7or win them at events.", "", "§7Your title shows in chat and", "§7in the tab list."));
        s.set(49, Gui.icon(Items.BARRIER, "§c§lTake off title", "§7Show your name without a title."), pl -> { set(pl, null); menu(pl, pg); });
        if (pg > 0) s.set(48, Gui.icon(Items.ARROW, "§e« Previous page"), pl -> menu(pl, pg - 1));
        if (pg < pages - 1) s.set(50, Gui.icon(Items.ARROW, "§eNext page »"), pl -> menu(pl, pg + 1));
        s.set(53, Gui.glow(Gui.icon(Items.MAGENTA_DYE, "§d§lName colours", "§7Pick a colour for your name.")), Titles::colorMenu);
        Gui.open(p, s);
    }

    static void colorMenu(ServerPlayer p) {
        Gui.Screen s = new Gui.Screen("§d§l✦ Name Colours", 4);
        s.frame(Items.MAGENTA_STAINED_GLASS_PANE, Items.PINK_STAINED_GLASS_PANE);
        String cur = data.chosenColor.get(p.getUUID().toString());
        int[] slots = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25};
        for (int i = 0; i < Math.min(slots.length, cfg.colors.size()); i++) {
            NameColor c = cfg.colors.get(i);
            boolean ok = hasColor(p, c.id), on = c.id.equals(cur);
            var icon = Gui.icon(ok ? Items.LIME_DYE : Items.GRAY_DYE, colour(c.code) + c.name,
                    ok ? new String[]{colour(c.code) + p.getGameProfile().getName(), "", on ? "§a✔ Using" : "§e▶ Click to use"} : new String[]{"§7" + c.how});
            s.set(slots[i], on ? Gui.glow(icon) : icon, pl -> { if (hasColor(pl, c.id)) { setColor(pl, c.id); colorMenu(pl); } });
        }
        s.set(27, Gui.icon(Items.ARROW, "§e« Back to titles"), Titles::menu);
        s.set(31, Gui.icon(Items.BARRIER, "§cDefault colour", "§7Go back to a plain white name."), pl -> { setColor(pl, null); colorMenu(pl); });
        Gui.open(p, s);
    }
}
