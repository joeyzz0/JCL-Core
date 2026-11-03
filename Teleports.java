package net.joeyscobblemon.gts;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.brigadier.arguments.StringArgumentType;

import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Teleporting: /spawn, /warp (menu), /rtp, /tpa, /tpahere, /tpaccept, /tpdeny.
 * Admins: /setwarp <name>, /delwarp <name>. Warps are saved in world/data/jcl_gts/warps.json.
 * Every teleport has a short warm-up (cancelled if you move) and a cooldown. Ops skip both.
 */
@EventBusSubscriber(modid = "jcl_gts")
public final class Teleports {
    private Teleports() {}

    public static final class Config {
        public int version = 1;
        public int warmupSeconds = 3;
        public int spawnCooldownSeconds = 10;
        public int warpCooldownSeconds = 10;
        public int tpaCooldownSeconds = 30;
        public int tpaExpireSeconds = 60;
        public int rtpCooldownSeconds = 300;
        public int rtpMinDistance = 1500;
        public int rtpMaxDistance = 6000;
    }

    public static final class Warp {
        public String name, dim, icon = "minecraft:ender_pearl", description = "", display = "";
        public double x, y, z; public float yaw, pitch;
    }

    public static final class Data { public int presets = 0; public Map<String, Warp> warps = new LinkedHashMap<>(); }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    static Config cfg = new Config();
    static Data data = new Data();
    static MinecraftServer server;
    private static final Random RNG = new Random();

    private static Path file() { return server.getWorldPath(LevelResource.ROOT).resolve("data").resolve("jcl_gts").resolve("warps.json"); }

    @SubscribeEvent
    public static void onStart(ServerStartedEvent e) {
        server = e.getServer();
        cfg = Tablist.loadConfig("jcl_teleport.json", Config.class, new Config());
        try { if (Files.exists(file())) { Data d = GSON.fromJson(Files.readString(file()), Data.class); if (d != null && d.warps != null) data = d; } }
        catch (Exception ex) { GtsMod.LOG.error("[JCL] Could not read warps", ex); }
        if (data.presets < 1) { presetWarps(); data.presets = 1; save(); }
    }

    /** The Port Azure warps, set up the first time the server starts with this version. */
    static void presetWarps() {
        // name, x, y, z, facing yaw (S = 0, W = 90, N = 180, E = -90), icon, description
        Object[][] w = {
            {"evs", -74, 67, 206, 0f, "minecraft:experience_bottle", "Battle the EV trainers and train your team."},
            {"crates", 46, 67, 208, 180f, "jcl_gts:master_key", "Open crates with your keys."},
            {"shop", -63, 69, 162, 90f, "cobblemon:poke_ball", "The Poké Mart: buy with /shop, sell with /sell."},
            {"pokecenter", -13, 69, 163, 180f, "cobblemon:healing_machine", "Heal your team, trade on the GTS and Wonder Trade."},
            {"hall", 40, 74, 146, 180f, "minecraft:totem_of_undying", "The Hall of Fame and this month's leaderboards."},
            {"court", 48, 70, 243, 90f, "minecraft:iron_sword", "PvP battles: challenge a friend!"},
            {"harbor", -13, 68, 321, 0f, "minecraft:oak_boat", "The docks, ships and Captain Marlo."},
            {"lighthouse", 8, 65, 339, -90f, "minecraft:lantern", "The Port Azure lighthouse."}};
        for (Object[] r : w) {
            Warp x = new Warp(); x.name = (String) r[0]; x.dim = "minecraft:overworld";
            x.x = (int) r[1] + 0.5; x.y = (int) r[2]; x.z = (int) r[3] + 0.5; x.yaw = (float) r[4]; x.pitch = 0;
            x.icon = (String) r[5]; x.description = (String) r[6];
            x.display = DEFAULT_NAMES.getOrDefault(x.name, "");
            data.warps.put(x.name, x);
        }
        GtsMod.LOG.info("[JCL] Set up the Port Azure warps.");
    }

    @SubscribeEvent public static void onStop(ServerStoppingEvent e) { save(); }

    static void save() {
        try { Files.createDirectories(file().getParent()); Files.writeString(file(), GSON.toJson(data)); }
        catch (Exception e) { GtsMod.LOG.error("[JCL] Could not save warps", e); }
    }

    // ------------------------------------------------------------------ warm-up + cooldowns

    static final class Pending { ServerPlayer p; Vec3 from; int ticks; Runnable go; String what; }
    static final Map<UUID, Pending> PENDING = new HashMap<>();
    static final Map<String, Long> COOLDOWN = new HashMap<>();

    static boolean onCooldown(ServerPlayer p, String kind, int seconds) {
        if (p.hasPermissions(2)) return false;
        long until = COOLDOWN.getOrDefault(p.getUUID() + kind, 0L), now = System.currentTimeMillis();
        if (until > now) { msg(p, "§cYou can use that again in " + ((until - now) / 1000 + 1) + "s."); return true; }
        COOLDOWN.put(p.getUUID() + kind, now + seconds * 1000L);
        return false;
    }

    static void warmup(ServerPlayer p, String what, Runnable go) {
        if (p.hasPermissions(2) || cfg.warmupSeconds <= 0) { go.run(); return; }
        Pending pd = new Pending(); pd.p = p; pd.from = p.position(); pd.ticks = cfg.warmupSeconds * 20; pd.go = go; pd.what = what;
        PENDING.put(p.getUUID(), pd);
        msg(p, "§7Teleporting to §f" + what + "§7 in " + cfg.warmupSeconds + "s. Don't move!");
    }

    @SubscribeEvent
    public static void onTick(ServerTickEvent.Post e) {
        if (PENDING.isEmpty()) return;
        Iterator<Pending> it = PENDING.values().iterator();
        while (it.hasNext()) {
            Pending pd = it.next();
            if (pd.p.hasDisconnected()) { it.remove(); continue; }
            if (pd.p.position().distanceToSqr(pd.from) > 0.25) { it.remove(); msg(pd.p, "§cTeleport cancelled because you moved."); continue; }
            if (--pd.ticks <= 0) { it.remove(); pd.go.run(); }
        }
        TPA.values().removeIf(r -> r.expires < System.currentTimeMillis());
    }

    static void tp(ServerPlayer p, ServerLevel level, double x, double y, double z, float yaw, float pitch) {
        p.teleportTo(level, x, y, z, yaw, pitch);
        p.playNotifySound(SoundEvents.ENDERMAN_TELEPORT, SoundSource.MASTER, 0.6f, 1.2f);
    }

    /** Coloured names for the standard warps; any warp can be renamed with /setwarpname. */
    static final java.util.Map<String, String> DEFAULT_NAMES = java.util.Map.of(
            "evs", "&5&l✦ &d&lEV Training &5&l✦",
            "crates", "&6&l★ &e&lCrates &6&l★",
            "shop", "&9&l● &b&lPoké Mart &9&l●",
            "pokecenter", "&c&l✚ &f&lPokémon Center &c&l✚",
            "hall", "&6&l♛ &e&lHall of Fame &6&l♛",
            "court", "&a&l⚔ &2&lTrainer Court &a&l⚔",
            "harbour", "&3&l⚓ &b&lHarbour &3&l⚓",
            "harbor", "&3&l⚓ &b&lHarbor &3&l⚓",
            "lighthouse", "&c&l☀ &f&lLighthouse &c&l☀");

    static String nameOf(Warp w) {
        String d = w.display != null && !w.display.isEmpty() ? w.display : DEFAULT_NAMES.getOrDefault(w.name, "&b&l" + w.name);
        return d.replace('&', '§');
    }

    static void msg(ServerPlayer p, String s) { p.sendSystemMessage(Component.literal("§b§l[Travel] §r" + s)); }

    // ------------------------------------------------------------------ spawn + warps

    static void spawn(ServerPlayer p) {
        if (onCooldown(p, "spawn", cfg.spawnCooldownSeconds)) return;
        ServerLevel ow = server.overworld();
        BlockPos s = ow.getSharedSpawnPos();
        warmup(p, "spawn", () -> tp(p, ow, s.getX() + 0.5, s.getY(), s.getZ() + 0.5, ow.getSharedSpawnAngle(), 0));
    }

    static void warp(ServerPlayer p, String name) {
        Warp w = data.warps.get(name.toLowerCase());
        if (w == null) { msg(p, "§cThere's no warp called " + name + ". Open §f/warp§c to see them all."); return; }
        ServerLevel lvl = server.getLevel(ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(w.dim)));
        if (lvl == null) { msg(p, "§cThat warp's dimension isn't loaded."); return; }
        if (onCooldown(p, "warp", cfg.warpCooldownSeconds)) return;
        warmup(p, nameOf(w) + "§7", () -> tp(p, lvl, w.x, w.y, w.z, w.yaw, w.pitch));
    }

    static void menu(ServerPlayer p) {
        Gui.Screen s = new Gui.Screen("§b§l✈ Warps", 4);
        s.frame(Items.LIGHT_BLUE_STAINED_GLASS_PANE, Items.CYAN_STAINED_GLASS_PANE);
        s.set(10, Gui.glow(Gui.icon(Items.COMPASS, "§e§l⌂ §6§lSpawn §e§l⌂", "§7Back to Port Azure.", "", "§e▶ Click to teleport")), pl -> { pl.closeContainer(); spawn(pl); });
        int[] slots = {11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25};
        int i = 0;
        for (Warp w : data.warps.values()) {
            if (i >= slots.length) break;
            ItemStack icon = new ItemStack(Crates.resolve(w.icon));
            s.set(slots[i++], Gui.named(icon, nameOf(w), w.description.isEmpty() ? "§7Teleport here." : "§7" + w.description, "", "§e▶ Click to teleport"),
                    pl -> { pl.closeContainer(); warp(pl, w.name); });
        }
        s.set(28, Gui.icon(Items.ENDER_EYE, "§d§lRandom teleport", "§7Somewhere wild and far from spawn.", "§8Cooldown: " + cfg.rtpCooldownSeconds / 60 + " min", "", "§e▶ Click to teleport"),
                pl -> { pl.closeContainer(); rtp(pl); });
        s.set(34, Gui.icon(Items.BLACK_DYE, "§8§lSharestones", "§7The §0§lblack sharestone§7 at spawn links every", "§7official safe zone. §c§lRed sharestones§7 are",
                "§7community-built safe zones. Craft one or", "§7use the one at spawn. Both can take you", "§7back to spawn."));
        Gui.open(p, s);
    }

    // ------------------------------------------------------------------ random teleport

    static void rtp(ServerPlayer p) {
        ServerLevel ow = server.overworld();
        if (p.level() != ow && !p.hasPermissions(2)) { msg(p, "§cRandom teleport only works in the overworld."); return; }
        if (onCooldown(p, "rtp", cfg.rtpCooldownSeconds)) return;
        BlockPos spawn = ow.getSharedSpawnPos();
        for (int attempt = 0; attempt < 12; attempt++) {
            double ang = RNG.nextDouble() * Math.PI * 2, dist = cfg.rtpMinDistance + RNG.nextDouble() * (cfg.rtpMaxDistance - cfg.rtpMinDistance);
            int x = spawn.getX() + (int) (Math.cos(ang) * dist), z = spawn.getZ() + (int) (Math.sin(ang) * dist);
            if (ow.getBiome(new BlockPos(x, 64, z)).is(BiomeTags.IS_OCEAN)) continue;
            ow.getChunk(x >> 4, z >> 4);
            int y = ow.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            BlockPos ground = new BlockPos(x, y - 1, z);
            if (y <= ow.getMinBuildHeight() + 1 || !ow.getFluidState(ground).isEmpty() || !ow.getBlockState(ground).isSolid()) continue;
            final int fx = x, fy = y, fz = z;
            warmup(p, "the wild", () -> { tp(p, ow, fx + 0.5, fy, fz + 0.5, p.getYRot(), 0); msg(p, "§aWelcome to the wild! §7(" + fx + ", " + fy + ", " + fz + ")"); });
            return;
        }
        COOLDOWN.remove(p.getUUID() + "rtp");
        msg(p, "§cCouldn't find a safe spot this time. Try again in a moment.");
    }

    // ------------------------------------------------------------------ tpa

    static final class Req { UUID from, to; boolean here; long expires; }
    static final Map<UUID, Req> TPA = new HashMap<>();   // keyed by the player being asked

    static void request(ServerPlayer from, ServerPlayer to, boolean here) {
        if (from == to) { msg(from, "§cYou can't send a request to yourself."); return; }
        if (onCooldown(from, "tpa", cfg.tpaCooldownSeconds)) return;
        Req r = new Req(); r.from = from.getUUID(); r.to = to.getUUID(); r.here = here; r.expires = System.currentTimeMillis() + cfg.tpaExpireSeconds * 1000L;
        TPA.put(to.getUUID(), r);
        msg(from, "§7Request sent to §f" + to.getGameProfile().getName() + "§7. It expires in " + cfg.tpaExpireSeconds + "s.");
        MutableComponent line = Component.literal("§b§l[Travel] §r§f" + from.getGameProfile().getName() + (here ? " §7wants you to teleport to them. " : " §7wants to teleport to you. "));
        line.append(button("§a§l[Accept]", "/tpaccept", "§7Accept the request"));
        line.append(Component.literal(" "));
        line.append(button("§c§l[Deny]", "/tpdeny", "§7Deny the request"));
        to.sendSystemMessage(line);
        to.playNotifySound(SoundEvents.NOTE_BLOCK_PLING.value(), SoundSource.MASTER, 0.6f, 1.5f);
    }

    static MutableComponent button(String t, String cmd, String hover) {
        return Component.literal(t).withStyle(st -> st.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, cmd)).withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(hover))));
    }

    static void accept(ServerPlayer to) {
        Req r = TPA.remove(to.getUUID());
        if (r == null) { msg(to, "§cYou have no pending requests."); return; }
        ServerPlayer from = server.getPlayerList().getPlayer(r.from);
        if (from == null) { msg(to, "§cThat player has gone offline."); return; }
        msg(to, "§aAccepted."); msg(from, "§f" + to.getGameProfile().getName() + "§a accepted your request.");
        ServerPlayer mover = r.here ? to : from, target = r.here ? from : to;
        warmup(mover, target.getGameProfile().getName(), () -> tp(mover, target.serverLevel(), target.getX(), target.getY(), target.getZ(), target.getYRot(), target.getXRot()));
    }

    static void deny(ServerPlayer to) {
        Req r = TPA.remove(to.getUUID());
        if (r == null) { msg(to, "§cYou have no pending requests."); return; }
        msg(to, "§7Request denied.");
        ServerPlayer from = server.getPlayerList().getPlayer(r.from);
        if (from != null) msg(from, "§f" + to.getGameProfile().getName() + "§c denied your request.");
    }

    // ------------------------------------------------------------------ commands

    @SubscribeEvent
    public static void onCommands(RegisterCommandsEvent e) {
        var d = e.getDispatcher();
        d.register(Commands.literal("spawn").executes(c -> { spawn(c.getSource().getPlayerOrException()); return 1; }));
        d.register(Commands.literal("rtp").executes(c -> { rtp(c.getSource().getPlayerOrException()); return 1; }));
        d.register(Commands.literal("wild").executes(c -> { rtp(c.getSource().getPlayerOrException()); return 1; }));
        d.register(Commands.literal("warp").executes(c -> { menu(c.getSource().getPlayerOrException()); return 1; })
                .then(Commands.argument("name", StringArgumentType.word()).suggests((c, b) -> SharedSuggestionProvider.suggest(data.warps.keySet(), b))
                        .executes(c -> { warp(c.getSource().getPlayerOrException(), StringArgumentType.getString(c, "name")); return 1; })));
        d.register(Commands.literal("warps").executes(c -> { menu(c.getSource().getPlayerOrException()); return 1; }));
        d.register(Commands.literal("setwarp").requires(s -> s.hasPermission(2)).then(Commands.argument("name", StringArgumentType.word()).executes(c -> {
            ServerPlayer p = c.getSource().getPlayerOrException(); String n = StringArgumentType.getString(c, "name").toLowerCase();
            Warp w = new Warp(); w.name = n; w.dim = p.level().dimension().location().toString(); w.x = p.getX(); w.y = p.getY(); w.z = p.getZ(); w.yaw = p.getYRot(); w.pitch = p.getXRot();
            ItemStack hand = p.getMainHandItem();
            if (!hand.isEmpty()) w.icon = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(hand.getItem()).toString();
            Warp old = data.warps.get(n); if (old != null) { w.description = old.description; w.display = old.display; }
            data.warps.put(n, w); save();
            msg(p, "§aWarp §f" + n + "§a set here" + (hand.isEmpty() ? "" : " with the item in your hand as its icon") + ". Add a description with §f/setwarpdesc " + n + " <text>§a.");
            return 1;
        })));
        d.register(Commands.literal("setwarpdesc").requires(s -> s.hasPermission(2)).then(Commands.argument("name", StringArgumentType.word()).suggests((c, b) -> SharedSuggestionProvider.suggest(data.warps.keySet(), b))
                .then(Commands.argument("text", StringArgumentType.greedyString()).executes(c -> {
                    Warp w = data.warps.get(StringArgumentType.getString(c, "name").toLowerCase());
                    if (w == null) { c.getSource().sendFailure(Component.literal("No such warp.")); return 0; }
                    w.description = StringArgumentType.getString(c, "text"); save();
                    c.getSource().sendSuccess(() -> Component.literal("§aDescription saved."), false); return 1;
                }))));
        d.register(Commands.literal("setwarpname").requires(s -> s.hasPermission(2)).then(Commands.argument("name", StringArgumentType.word()).suggests((c, b) -> SharedSuggestionProvider.suggest(data.warps.keySet(), b))
                .then(Commands.argument("display", StringArgumentType.greedyString()).executes(c -> {
                    Warp w = data.warps.get(StringArgumentType.getString(c, "name").toLowerCase());
                    if (w == null) { c.getSource().sendFailure(Component.literal("No such warp.")); return 0; }
                    w.display = StringArgumentType.getString(c, "display"); save();
                    c.getSource().sendSuccess(() -> Component.literal("§aRenamed to " + nameOf(w)), false); return 1;
                }))));
        d.register(Commands.literal("delwarp").requires(s -> s.hasPermission(2)).then(Commands.argument("name", StringArgumentType.word()).suggests((c, b) -> SharedSuggestionProvider.suggest(data.warps.keySet(), b))
                .executes(c -> { boolean ok = data.warps.remove(StringArgumentType.getString(c, "name").toLowerCase()) != null; save();
                    c.getSource().sendSuccess(() -> Component.literal(ok ? "§aWarp removed." : "§cNo such warp."), false); return 1; })));
        d.register(Commands.literal("tpa").then(Commands.argument("player", EntityArgument.player())
                .executes(c -> { request(c.getSource().getPlayerOrException(), EntityArgument.getPlayer(c, "player"), false); return 1; })));
        d.register(Commands.literal("tpahere").then(Commands.argument("player", EntityArgument.player())
                .executes(c -> { request(c.getSource().getPlayerOrException(), EntityArgument.getPlayer(c, "player"), true); return 1; })));
        d.register(Commands.literal("tpaccept").executes(c -> { accept(c.getSource().getPlayerOrException()); return 1; }));
        d.register(Commands.literal("tpdeny").executes(c -> { deny(c.getSource().getPlayerOrException()); return 1; }));
    }
}
