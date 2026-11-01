package net.joeyscobblemon.gts;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
 * Chat titles. Each title is unlocked by the LuckPerms permission jcl.title.<id>
 * (give it with: lp user <name> permission set jcl.title.<id> true).
 * Players pick which unlocked title to show with /titles. The chosen title appears
 * in front of their name in chat and in the tab list.
 * Titles are defined in config/jcl_titles.json.
 */
@EventBusSubscriber(modid = "jcl_gts")
public final class Titles {
    private Titles() {}

    public static final class Title {
        public String id, prefix, name, category, how;
    }

    public static final class Config { public List<Title> titles = new ArrayList<>(); }
    public static final class Data { public Map<String, String> chosen = new HashMap<>(); }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    static Config cfg = new Config();
    static Data data = new Data();
    static MinecraftServer server;

    private static Path dataFile() { return server.getWorldPath(LevelResource.ROOT).resolve("data").resolve("jcl_gts").resolve("titles.json"); }

    static void load() {
        try {
            Path f = Path.of("config", "jcl_titles.json");
            if (!Files.exists(f)) {
                String d = Crates.readDefault("titles.json");
                Files.createDirectories(f.getParent());
                if (d != null) Files.writeString(f, d);
            }
            Config c = GSON.fromJson(Files.readString(f), Config.class);
            if (c != null) cfg = c;
        } catch (Exception e) { GtsMod.LOG.error("[JCL] Could not read config/jcl_titles.json", e); }
        try {
            if (Files.exists(dataFile())) { Data d = GSON.fromJson(Files.readString(dataFile()), Data.class); if (d != null) data = d; }
        } catch (Exception e) { GtsMod.LOG.error("[JCL] Could not read title data", e); }
        if (data.chosen == null) data.chosen = new HashMap<>();
    }

    static void save() {
        try { Files.createDirectories(dataFile().getParent()); Files.writeString(dataFile(), GSON.toJson(data)); }
        catch (Exception e) { GtsMod.LOG.error("[JCL] Could not save title data", e); }
    }

    @SubscribeEvent public static void onStart(ServerStartedEvent e) { server = e.getServer(); load(); }
    @SubscribeEvent public static void onStop(ServerStoppingEvent e) { save(); }

    static Title find(String id) {
        for (Title t : cfg.titles) if (t.id.equals(id)) return t;
        return null;
    }

    static String colour(String s) { return s == null ? "" : s.replace('&', '§'); }

    /** The prefix to show for this player, or null if they haven't picked one (or lost it). */
    static String prefix(Player p) {
        String id = data.chosen.get(p.getUUID().toString());
        if (id == null) return null;
        Title t = find(id);
        if (t == null || !(p instanceof ServerPlayer sp) || !has(sp, id)) return null;
        return colour(t.prefix);
    }

    // ------------------------------------------------------------------ LuckPerms

    static boolean has(ServerPlayer p, String id) {
        try {
            Object lp = Class.forName("net.luckperms.api.LuckPermsProvider").getMethod("get").invoke(null);
            Object um = lp.getClass().getMethod("getUserManager").invoke(lp);
            Object user = um.getClass().getMethod("getUser", UUID.class).invoke(um, p.getUUID());
            if (user == null) return false;
            Object cached = user.getClass().getMethod("getCachedData").invoke(user);
            Object perms = cached.getClass().getMethod("getPermissionData").invoke(cached);
            Object tri = perms.getClass().getMethod("checkPermission", String.class).invoke(perms, "jcl.title." + id);
            return Boolean.TRUE.equals(tri.getClass().getMethod("asBoolean").invoke(tri));
        } catch (Throwable t) { return false; }
    }

    static void grant(ServerPlayer p, String id) {
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(),
                "lp user " + p.getGameProfile().getName() + " permission set jcl.title." + id + " true");
        Title t = find(id);
        if (t != null) p.sendSystemMessage(Component.literal("§6§l[Titles] §r§aUnlocked " + colour(t.prefix) + "§a! Equip it with §f/titles§a."));
    }

    // ------------------------------------------------------------------ display

    @SubscribeEvent
    public static void onName(PlayerEvent.NameFormat e) {
        String pre = prefix(e.getEntity());
        if (pre != null) e.setDisplayname(Component.literal(pre + " §r").append(e.getUsername()));
    }

    static void refresh(ServerPlayer p) {
        p.refreshDisplayName();
        p.refreshTabListName();
    }

    // ------------------------------------------------------------------ commands + menu

    @SubscribeEvent
    public static void onCommands(RegisterCommandsEvent e) {
        e.getDispatcher().register(Commands.literal("titles")
                .executes(c -> { menu(c.getSource().getPlayerOrException()); return 1; })
                .then(Commands.literal("clear").executes(c -> { set(c.getSource().getPlayerOrException(), null); return 1; }))
                .then(Commands.literal("grant").requires(s -> s.hasPermission(2))
                        .then(Commands.argument("player", EntityArgument.player()).then(Commands.argument("id", StringArgumentType.word())
                                .suggests((c, b) -> SharedSuggestionProvider.suggest(cfg.titles.stream().map(t -> t.id), b))
                                .executes(c -> { grant(EntityArgument.getPlayer(c, "player"), StringArgumentType.getString(c, "id")); return 1; }))))
                .then(Commands.literal("reload").requires(s -> s.hasPermission(2)).executes(c -> { load(); c.getSource().sendSuccess(() -> Component.literal("§aTitles reloaded."), true); return 1; })));
    }

    static void set(ServerPlayer p, String id) {
        if (id == null) data.chosen.remove(p.getUUID().toString()); else data.chosen.put(p.getUUID().toString(), id);
        save();
        refresh(p);
        p.playNotifySound(SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.MASTER, 0.6f, 1.3f);
        Title t = id == null ? null : find(id);
        p.sendSystemMessage(Component.literal(t == null ? "§6§l[Titles] §r§7Title removed." : "§6§l[Titles] §r§aNow showing " + colour(t.prefix) + "§a."));
    }

    private static final List<String> ORDER = List.of("quest", "shop", "crate", "staff");

    static String catName(String c) {
        return switch (c) { case "quest" -> "§aQuest title"; case "shop" -> "§eShop title"; case "crate" -> "§dCrate exclusive"; case "staff" -> "§cStaff and events only"; default -> "§7Title"; };
    }

    static void menu(ServerPlayer p) {
        List<Title> list = new ArrayList<>(cfg.titles);
        list.sort((a, b) -> {
            boolean ha = has(p, a.id), hb = has(p, b.id);
            if (ha != hb) return ha ? -1 : 1;
            return ORDER.indexOf(a.category) - ORDER.indexOf(b.category);
        });
        String current = data.chosen.get(p.getUUID().toString());
        int owned = 0;
        for (Title t : list) if (has(p, t.id)) owned++;
        Gui.Screen s = new Gui.Screen("§6§l✦ Titles §8(" + owned + "/" + list.size() + ")", 6);
        for (int i = 0; i < Math.min(45, list.size()); i++) {
            Title t = list.get(i);
            boolean unlocked = has(p, t.id);
            boolean on = t.id.equals(current);
            String[] lore = unlocked
                    ? new String[]{catName(t.category), "", "§7Shows as: " + colour(t.prefix) + " §f" + p.getGameProfile().getName(), "", on ? "§a✔ Equipped" : "§e▶ Click to equip"}
                    : new String[]{catName(t.category), "", "§7How to unlock:", "§f" + t.how};
            var icon = Gui.icon(unlocked ? (on ? Items.NAME_TAG : Items.PAPER) : Items.GRAY_DYE, unlocked ? colour(t.prefix) : "§8??? §7" + t.name, lore);
            s.set(i, on ? Gui.glow(icon) : icon, pl -> { if (has(pl, t.id)) { set(pl, t.id); menu(pl); } else pl.playNotifySound(SoundEvents.VILLAGER_NO, SoundSource.MASTER, 0.6f, 1f); });
        }
        for (int i = 45; i < 54; i++) s.set(i, Gui.pane(Items.BLACK_STAINED_GLASS_PANE));
        s.set(49, Gui.icon(Items.BARRIER, "§c§lRemove title", "§7Show your name with no title."), pl -> { set(pl, null); menu(pl); });
        s.set(45, Gui.icon(Items.BOOK, "§e§lAbout titles", "§7Earn titles from quests, buy them", "§7in §f/shop§7, pull them from crates,", "§7or win them in events.", "", "§7Your title shows in chat and", "§7the tab list."));
        Gui.open(p, s);
    }
}
