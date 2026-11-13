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
        public int version = 2;
        public int warmupSeconds = 3;
        public int spawnCooldownSeconds = 10;
        public int warpCooldownSeconds = 10;
        public int tpaCooldownSeconds = 30;
        public int tpaExpireSeconds = 60;
        public int rtpCooldownSeconds = 300;
        public int rtpMinDistance = 3000;
        public int rtpMaxDistance = 15000;
        public int rtpNetherMin = 500, rtpNetherMax = 3000, rtpEndMin = 1500, rtpEndMax = 8000;
    }

    public static final class Warp {
        public String name, dim, icon = "minecraft:ender_pearl", description = "", display = "";
        public double x, y, z; public float yaw, pitch;
        /** Community warps (player-built safe zones) live in their own submenu. */
        public boolean community = false;
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
        LAST_COOLDOWN.put(p.getUUID(), p.getUUID() + kind);
        return false;
    }

    /** The cooldown a pending teleport started, so cancelling it (by moving) gives the cooldown back. */
    static final Map<UUID, String> LAST_COOLDOWN = new HashMap<>();

    static void warmup(ServerPlayer p, String what, Runnable go) {
        if (Frontier.inRun(p)) { p.sendSystemMessage(Component.literal("§cFinish or leave your Frontier run first (§f/frontier leave§c).")); return; }
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
            if (pd.p.position().distanceToSqr(pd.from) > 0.25) {
                it.remove();
                String cd = LAST_COOLDOWN.remove(pd.p.getUUID());
                if (cd != null) COOLDOWN.remove(cd);       // a cancelled teleport doesn't cost you the cooldown
                msg(pd.p, "§cTeleport cancelled because you moved.");
                continue;
            }
            if (--pd.ticks <= 0) { it.remove(); pd.go.run(); }
        }
        TPA.values().removeIf(r -> r.expires < System.currentTimeMillis());
    }

    // ------------------------------------------------------------------ /back: the spot you were at before your last teleport

    record Spot(String dim, double x, double y, double z, float yaw, float pitch) {}
    static final Map<UUID, Spot> BACK = new HashMap<>();
    static final int BACK_COOLDOWN = 60;

    static boolean frontierDim(String dim) { return dim.contains("frontier"); }

    /** Remembers where a player is standing, just before they're moved somewhere else. */
    static void rememberBack(ServerPlayer p) {
        String dim = p.level().dimension().location().toString();
        if (frontierDim(dim)) return;                       // never back into a Battle Frontier room
        BACK.put(p.getUUID(), new Spot(dim, p.getX(), p.getY(), p.getZ(), p.getYRot(), p.getXRot()));
    }

    static void back(ServerPlayer p) {
        Spot s = BACK.get(p.getUUID());
        if (s == null) { msg(p, "§7There's nowhere to go back to yet."); return; }
        if (onCooldown(p, "back", BACK_COOLDOWN)) return;
        ServerLevel lvl = server.getLevel(ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(s.dim())));
        if (lvl == null) { msg(p, "§cThat place isn't available any more."); return; }
        warmup(p, "your last spot", () -> tp(p, lvl, s.x(), s.y(), s.z(), s.yaw(), s.pitch()));
    }

    /** Vanilla teleports (/tp and the like) count for /back too. */
    @SubscribeEvent
    public static void onVanillaTeleport(net.neoforged.neoforge.event.entity.EntityTeleportEvent.TeleportCommand e) {
        if (e.getEntity() instanceof ServerPlayer p) rememberBack(p);
    }

    static void tp(ServerPlayer p, ServerLevel level, double x, double y, double z, float yaw, float pitch) {
        rememberBack(p);
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

    /** The Battle Frontier warp opens once the player has earned their ticket (staff can always use it). */
    static boolean frontierLocked(ServerPlayer p, String name) {
        return name.equalsIgnoreCase("frontier") && !p.hasPermissions(2) && !Jobs.done(p, "frontier_ticket");
    }

    /** Straight to a warp: no warm-up, no cooldown, no lock (used when the ferry arrives). */
    static void arrive(ServerPlayer p, String name) {
        Warp w = data.warps.get(name.toLowerCase());
        if (w == null) return;
        ServerLevel lvl = server.getLevel(ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(w.dim)));
        if (lvl != null) tp(p, lvl, w.x, w.y, w.z, w.yaw, w.pitch);
    }

    static void warp(ServerPlayer p, String name) {
        Warp w = data.warps.get(name.toLowerCase());
        if (w == null) { msg(p, "§cThere's no warp called " + name + ". Open §f/warp§c to see them all."); return; }
        if (frontierLocked(p, name)) { msg(p, "§cThe Battle Frontier is across the sea. Earn your passage with the §e\"Ticket to the Frontier\" §cjob: talk to §eCaptain Marlo§c at the ferry pier."); return; }
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
            if (w.community) continue;
            if (i >= slots.length) break;
            ItemStack icon = new ItemStack(Crates.resolve(w.icon));
            if (frontierLocked(p, w.name)) {
                s.set(slots[i++], Gui.named(new ItemStack(Items.BARRIER), nameOf(w), w.description.isEmpty() ? "§7Teleport here." : "§7" + w.description, "",
                        "§c✖ Requires: §eTicket to the Frontier", "§7Talk to §eCaptain Marlo §7at the ferry pier."), pl -> warp(pl, w.name));
                continue;
            }
            s.set(slots[i++], Gui.named(icon, nameOf(w), w.description.isEmpty() ? "§7Teleport here." : "§7" + w.description, "", "§e▶ Click to teleport"),
                    pl -> { pl.closeContainer(); warp(pl, w.name); });
        }
        s.set(28, Gui.icon(Items.ENDER_EYE, "§d§lRandom teleport", "§7Somewhere wild and far from spawn.", "§8Cooldown: " + cfg.rtpCooldownSeconds / 60 + " min", "", "§e▶ Click to teleport", "§8Works in the Overworld, Nether and End"),
                pl -> { pl.closeContainer(); rtp(pl); });
        long nCommunity = data.warps.values().stream().filter(w -> w.community).count();
        s.set(31, Gui.icon(Items.RED_DYE, "§c§l◆ §f§lCommunity Warps §c§l◆", "§7Safe zones built by players.", "§7" + nCommunity + " to visit.", "",
                "§8Built something for everyone? Ask an", "§8admin or mod to make it a Community Warp!", "", "§e▶ Click to open"), pl -> communityMenu(pl, 0));
        s.set(34, Gui.icon(Items.RED_BED, "§b§lYour Homes", "§7Save up to 3 homes with §f/sethome§7,", "§7and buy more (up to 30).", "", "§e▶ Click to open"), pl -> Homes.menu(pl));
        Gui.open(p, s);
    }

    static void communityMenu(ServerPlayer p, int page) {
        List<Warp> list = new ArrayList<>();
        for (Warp w : data.warps.values()) if (w.community) list.add(w);
        Gui.Screen s = new Gui.Screen("§c§l◆ §f§lCommunity Warps", 6);
        s.frame(Items.RED_STAINED_GLASS_PANE, Items.ORANGE_STAINED_GLASS_PANE);
        int[] slots = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34, 37, 38, 39, 40, 41, 42, 43};
        int per = slots.length, pages = Math.max(1, (list.size() + per - 1) / per), pg = Math.max(0, Math.min(page, pages - 1));
        for (int k = 0; k < per && pg * per + k < list.size(); k++) {
            Warp w = list.get(pg * per + k);
            s.set(slots[k], Gui.named(new ItemStack(Crates.resolve(w.icon)), nameOf(w), w.description.isEmpty() ? "§7A community safe zone." : "§7" + w.description, "", "§e▶ Click to teleport"),
                    pl -> { pl.closeContainer(); warp(pl, w.name); });
        }
        if (list.isEmpty()) s.set(22, Gui.icon(Items.BARRIER, "§7No Community Warps yet", "§7Built a safe zone for everyone?", "§7Ask an admin or mod to add it!"));
        s.set(4, Gui.icon(Items.RED_DYE, "§c§lCommunity Warps", "§7Safe zones built by the community.", "§7Want yours here? Ask an admin or mod."));
        s.set(49, Gui.icon(Items.ARROW, "§e« Back to Warps"), Teleports::menu);
        if (pg > 0) s.set(48, Gui.icon(Items.ARROW, "§e« Previous page"), pl -> communityMenu(pl, pg - 1));
        if (pg < pages - 1) s.set(50, Gui.icon(Items.ARROW, "§eNext page »"), pl -> communityMenu(pl, pg + 1));
        Gui.open(p, s);
    }

    // ------------------------------------------------------------------ random teleport

    static void rtp(ServerPlayer p) { rtp(p, p.level().dimension().location().toString()); }

    static final java.util.Set<String> RTP_DIMS = java.util.Set.of("minecraft:overworld", "minecraft:the_nether", "minecraft:the_end");

    /** Random teleport in the Overworld, the Nether or the End, to a safe spot well away from spawn. */
    static void rtp(ServerPlayer p, String where) {
        // /rtp always works in the dimension you're standing in (Overworld, Nether or End only)
        String dim = where;
        if (!RTP_DIMS.contains(dim)) { msg(p, "§cRandom teleport only works in the Overworld, the Nether and the End."); return; }
        ServerLevel lvl = server.getLevel(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, net.minecraft.resources.ResourceLocation.parse(dim)));
        if (lvl == null) { msg(p, "§cThat dimension isn't available."); return; }
        if (p.level().dimension().location().toString().equals(Frontier.DIM)) { msg(p, "§cYou can't random teleport out of the Battle Frontier."); return; }
        if (onCooldown(p, "rtp", cfg.rtpCooldownSeconds)) return;
        boolean nether = dim.endsWith("the_nether"), end = dim.endsWith("the_end");
        int min = nether ? cfg.rtpNetherMin : end ? cfg.rtpEndMin : cfg.rtpMinDistance, max = nether ? cfg.rtpNetherMax : end ? cfg.rtpEndMax : cfg.rtpMaxDistance;
        BlockPos centre = nether || end ? BlockPos.ZERO : lvl.getSharedSpawnPos();
        for (int attempt = 0; attempt < (end ? 40 : 20); attempt++) {
            double ang = RNG.nextDouble() * Math.PI * 2, dist = min + RNG.nextDouble() * (max - min);
            int x = centre.getX() + (int) (Math.cos(ang) * dist), z = centre.getZ() + (int) (Math.sin(ang) * dist);
            if (!nether && !end && lvl.getBiome(new BlockPos(x, 64, z)).is(BiomeTags.IS_OCEAN)) continue;
            lvl.getChunk(x >> 4, z >> 4);
            int y = -1;
            if (nether) {
                // find a pocket: solid floor, two blocks of air, no lava, under the bedrock roof
                for (int yy = 100; yy > 32; yy--) {
                    BlockPos f = new BlockPos(x, yy - 1, z);
                    if (lvl.getBlockState(f).isSolid() && lvl.getFluidState(f).isEmpty() && lvl.getBlockState(f.above()).isAir() && lvl.getBlockState(f.above(2)).isAir()
                            && lvl.getFluidState(f.below()).isEmpty()) { y = yy; break; }
                }
            } else y = lvl.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            if (y < 0) continue;
            BlockPos ground = new BlockPos(x, y - 1, z);
            if (y <= lvl.getMinBuildHeight() + 1 || !lvl.getFluidState(ground).isEmpty() || !lvl.getBlockState(ground).isSolid()) continue;
            final int fx = x, fy = y, fz = z;
            String name = nether ? "the Nether" : end ? "the End" : "the wild";
            warmup(p, name, () -> { tp(p, lvl, fx + 0.5, fy, fz + 0.5, p.getYRot(), 0); msg(p, "§aWelcome to " + name + "! §7(" + fx + ", " + fy + ", " + fz + ")"); });
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
        d.register(Commands.literal("rtp").executes(c -> { ServerPlayer p = c.getSource().getPlayerOrException(); rtp(p, p.level().dimension().location().toString()); return 1; }));
        d.register(Commands.literal("wild").executes(c -> { rtp(c.getSource().getPlayerOrException()); return 1; }));
        d.register(Commands.literal("warp").executes(c -> { menu(c.getSource().getPlayerOrException()); return 1; })
                .then(Commands.argument("name", StringArgumentType.word()).suggests((c, b) -> SharedSuggestionProvider.suggest(data.warps.keySet(), b))
                        .executes(c -> { warp(c.getSource().getPlayerOrException(), StringArgumentType.getString(c, "name")); return 1; })));
        d.register(Commands.literal("warps").executes(c -> { menu(c.getSource().getPlayerOrException()); return 1; }));
        d.register(Commands.literal("setcommunitywarp").requires(s -> s.hasPermission(2)).then(Commands.argument("name", StringArgumentType.word()).executes(c -> {
            ServerPlayer p = c.getSource().getPlayerOrException(); String n = StringArgumentType.getString(c, "name").toLowerCase();
            Warp w = new Warp(); w.name = n; w.dim = p.level().dimension().location().toString(); w.x = p.getX(); w.y = p.getY(); w.z = p.getZ(); w.yaw = p.getYRot(); w.pitch = p.getXRot();
            w.community = true; w.icon = "minecraft:red_dye"; w.description = "A community safe zone.";
            ItemStack hand = p.getMainHandItem();
            if (!hand.isEmpty()) w.icon = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(hand.getItem()).toString();
            Warp old = data.warps.get(n); if (old != null) { w.description = old.description; w.display = old.display; }
            data.warps.put(n, w); save();
            msg(p, "§aCommunity Warp §f" + n + "§a set here. §7Players find it in §f/warp §7→ Community Warps. Rename it with §f/setwarpname§7, describe it with §f/setwarpdesc§7.");
            return 1; })));
        d.register(Commands.literal("back").executes(c -> { back(c.getSource().getPlayerOrException()); return 1; }));
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
