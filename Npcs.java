package net.joeyscobblemon.gts;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Consumer;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

/**
 * Town NPCs and EV trainers are Cobblemon NPCs (player-shaped, with our own skins) instead of villagers.
 *
 * Skins ship inside the jar and are copied to config/jcl_skins/ on startup. Cobblemon doesn't save an
 * NPC's skin, so it is put back every time the NPC loads: the skin can never change or go missing.
 *
 * EV trainers charge a fee right before the battle starts. If the player can't pay, the battle is cancelled.
 */
@EventBusSubscriber(modid = "jcl_gts")
public final class Npcs {
    private Npcs() {}

    static final List<String> SKINS = List.of("guide", "nurse", "clerk", "crates", "archivist", "coach", "referee", "captain",
            "ev_hp", "ev_atk", "ev_def", "ev_spa", "ev_spd", "ev_spe", "frontier_receptionist", "frontier_clerk", "frontier_loaner",
            "professor", "librarian", "curator", "barista", "barista2", "contest_host",
            "vendor_1", "vendor_2", "vendor_3", "vendor_4", "vendor_5", "vendor_6", "vendor_7", "vendor_8", "vendor_9", "vendor_10", "vendor_11", "vendor_12", "vendor_13", "vendor_14", "vendor_15", "vendor_16", "vendor_17", "vendor_18", "vendor_19", "vendor_20", "vendor_21", "vendor_22", "vendor_23", "vendor_24", "vendor_25", "vendor_26", "vendor_27", "vendor_28", "vendor_29", "vendor_30", "vendor_31", "vendor_32", "vendor_33", "vendor_34", "vendor_35", "vendor_36",
            "local_1", "local_2", "local_3", "local_4", "local_5", "local_6", "local_7", "local_8", "local_9", "local_10", "local_11", "local_12", "local_13");
    /** Skins that exist only for the Frontier's trainers and Brains (copied to config/jcl_skins too). */
    static final int SKIN_SET = 11;

    static final List<String> EXTRA_SKINS = List.of("brain_tycoon", "brain_tera", "brain_factory", "brain_mega", "brain_status", "brain_doubles", "brain_mono",
            "ft_1", "ft_2", "ft_3", "ft_4", "ft_5", "ft_6", "ft_7", "ft_8", "ft_9", "ft_10", "ft_11", "ft_12",
            "ft_13", "ft_14", "ft_15", "ft_16", "ft_17", "ft_18", "ft_19", "ft_20", "ft_21", "ft_22", "ft_23", "ft_24");
    static final List<String> EV = List.of("ev_hp", "ev_atk", "ev_def", "ev_spa", "ev_spd", "ev_spe");
    static final long EV_FEE = 1000;
    static MinecraftServer server;

    static Path skinDir() { return FMLPaths.CONFIGDIR.get().resolve("jcl_skins"); }

    @SubscribeEvent
    public static void onStart(ServerStartedEvent e) {
        server = e.getServer();
        try {
            Files.createDirectories(skinDir());
            List<String> all = new java.util.ArrayList<>(SKINS); all.addAll(EXTRA_SKINS);
            for (String c : new String[]{"red", "blue", "lance", "steven", "cynthia", "alder", "iris", "diantha", "geeta", "nemona", "n", "ghetsis", "giovanni", "archie", "maxie",
                    "arven", "marlon", "leon", "ash", "gary", "paul", "tobias", "alain", "volo", "kieran", "gladion", "lusamine", "trip", "joey"}) all.add("champ_" + c);
            // Skin set 2 (picked from the server's trainer textures) replaces the earlier drawn skins once; skins an
            // admin added under other names are never touched, and after this, existing files are left alone again.
            Path marker = skinDir().resolve(".skinset");
            int have = 0;
            try { if (Files.exists(marker)) have = Integer.parseInt(Files.readString(marker).trim()); } catch (Exception ignored) {}
            boolean refresh = have < SKIN_SET;
            for (String s : all) {
                Path out = skinDir().resolve(s + ".png");
                if (Files.exists(out) && !refresh) continue;          // an admin may have swapped in their own skin
                try (InputStream in = Npcs.class.getResourceAsStream("/jcl_skins/" + s + ".png")) { if (in != null) Files.copy(in, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
            }
            if (refresh) { Files.writeString(marker, String.valueOf(SKIN_SET)); GtsMod.LOG.info("[JCL] Installed NPC skin set {}.", SKIN_SET); }
        } catch (Exception ex) { GtsMod.LOG.error("[JCL] Could not unpack NPC skins", ex); }
        hookBattles();
    }

    // ------------------------------------------------------------------ skins

    static boolean isCobblemonNpc(Entity e) { return e != null && e.getClass().getSimpleName().equals("NPCEntity"); }

    /** Applies a skin straight from a URL (the podium statues use the players' own Mojang skins). */
    static void applySkinUrl(Entity e, String url, boolean slim) {
        try {
            Class<?> modelType = Class.forName("com.cobblemon.mod.common.entity.npc.NPCPlayerModelType");
            Object type = modelType.getField(slim ? "SLIM" : "DEFAULT").get(null);
            e.getClass().getMethod("loadTexture", java.net.URI.class, modelType).invoke(e, java.net.URI.create(url), type);
        } catch (Throwable t) { GtsMod.LOG.warn("[JCL] Could not apply a skin from {}: {}", url, t.toString()); }
    }

    static boolean skinExists(String skin) { return Files.exists(skinDir().resolve(skin + ".png")); }

    /** Finds a skin file by name, ignoring case ("Cynthia.png" and "champ_cynthia.png" both work for cynthia). */
    static String findSkin(String... names) {
        try (var files = Files.list(skinDir())) {
            var all = files.map(p -> p.getFileName().toString()).filter(s -> s.toLowerCase(java.util.Locale.ROOT).endsWith(".png")).toList();
            for (String want : names) for (String f : all) if (f.equalsIgnoreCase(want + ".png")) return f.substring(0, f.length() - 4);
        } catch (Exception ignored) {}
        return null;
    }

    /** Alex-style (slim-armed) skins have a transparent pixel where a classic arm would be. */
    static boolean isSlim(Path f) {
        try {
            var img = javax.imageio.ImageIO.read(f.toFile());
            if (img == null || img.getHeight() < 64) return false;
            return ((img.getRGB(54, 20) >>> 24) & 0xFF) == 0;
        } catch (Exception ex) { return false; }
    }

    static void applySkin(Entity e, String skin) {
        try {
            Path f = skinDir().resolve(skin + ".png");
            if (!Files.exists(f)) { GtsMod.LOG.warn("[JCL] No skin file at {}", f); return; }
            Class<?> modelType = Class.forName("com.cobblemon.mod.common.entity.npc.NPCPlayerModelType");
            Object type = modelType.getField(isSlim(f) ? "SLIM" : "DEFAULT").get(null);
            Method load = e.getClass().getMethod("loadTexture", java.net.URI.class, modelType);
            load.invoke(e, f.toUri(), type);
            GtsMod.LOG.info("[JCL] Applied skin {} to an NPC.", skin);
        } catch (Throwable t) { GtsMod.LOG.warn("[JCL] Could not apply skin {} to an NPC", skin, t); }
    }

    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent e) {
        if (e.getLevel().isClientSide() || !isCobblemonNpc(e.getEntity())) return;
        for (String t : e.getEntity().getTags()) if (t.startsWith("jcl_skin_")) { applySkin(e.getEntity(), t.substring(9)); return; }
    }

    // ------------------------------------------------------------------ spawning

    /** Spawns Cobblemon NPC class jcl_gts:<cls> at a spot, facing yaw, tags it and gives it its skin.
     *  Built directly the same way Cobblemon's own /spawnnpc does it, so there's no searching for it afterwards. */
    static Entity spawn(ServerLevel level, String cls, double x, double y, double z, float yaw, String... tags) {
        return spawnSkinned(level, cls, cls, x, y, z, yaw, tags);
    }

    /** If set, the next spawned NPC gets this UUID (the Frontier uses fixed UUIDs so tooltip mods can name the trainer). */
    static java.util.UUID NEXT_UUID;

    static Entity spawnSkinned(ServerLevel level, String cls, String skin, double x, double y, double z, float yaw, String... tags) {
        java.util.UUID forced = NEXT_UUID; NEXT_UUID = null;
        try {
            Class<?> classes = Class.forName("com.cobblemon.mod.common.api.npc.NPCClasses");
            Object npcClass = classes.getMethod("getByIdentifier", net.minecraft.resources.ResourceLocation.class)
                    .invoke(null, net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("jcl_gts", cls));
            if (npcClass == null) { GtsMod.LOG.warn("[JCL] NPC class jcl_gts:{} isn't loaded. Check the server log for Cobblemon data errors.", cls); return null; }
            Class<?> npcType = Class.forName("com.cobblemon.mod.common.entity.npc.NPCEntity");
            Entity npc = (Entity) npcType.getConstructor(net.minecraft.world.level.Level.class).newInstance(level);
            npc.moveTo(x, y, z, yaw, 0f);
            npcType.getMethod("setNpc", Class.forName("com.cobblemon.mod.common.api.npc.NPCClass")).invoke(npc, npcClass);
            npcType.getMethod("initialize", int.class).invoke(npc, 1);
            if (npc instanceof LivingEntity le) { le.setYHeadRot(yaw); le.setYBodyRot(yaw); }
            for (String t : tags) npc.addTag(t);
            npc.addTag("jcl_skin_" + skin);
            if (forced != null) { Entity old = level.getEntity(forced); if (old != null) old.discard(); npc.setUUID(forced); }
            level.addFreshEntity(npc);
            applySkin(npc, skin);
            return npc;
        } catch (Throwable t) {
            GtsMod.LOG.error("[JCL] Could not spawn NPC jcl_gts:{}", cls, t);
            return null;
        }
    }

    static int removeAll(ServerLevel level, double x, double z, double radius) {
        int n = 0;
        for (Entity en : level.getEntities((Entity) null, new AABB(x - radius, level.getMinBuildHeight(), z - radius, x + radius, level.getMaxBuildHeight(), z + radius),
                en -> en.getTags().contains("jcl_npc") || en.getTags().contains("jcl_ev"))) { en.discard(); n++; }
        return n;
    }

    // ------------------------------------------------------------------ EV trainer fee

    static void hookBattles() {
        try {
            Class<?> ev = Class.forName("com.cobblemon.mod.common.api.events.CobblemonEvents");
            Object obs = ev.getField("BATTLE_STARTED_PRE").get(null);
            Consumer<Object> handler = Npcs::onBattlePre;
            for (Method m : obs.getClass().getMethods())
                if (m.getName().equals("subscribe") && m.getParameterCount() == 1 && m.getParameterTypes()[0] == Consumer.class) { m.invoke(obs, handler); break; }
        } catch (Throwable t) { GtsMod.LOG.error("[JCL] Could not hook battle starts for EV trainer fees", t); }
    }

    /** The entity behind an NPC battle actor, if any. */
    static Entity npcOf(Object actor) {
        try { if (actor.getClass().getSimpleName().equals("NPCBattleActor")) return (Entity) Leaderboards.call(actor, "getEntity"); } catch (Throwable ignored) {}
        return null;
    }

    static boolean isEvTrainer(Entity e) { return e != null && e.getTags().contains("jcl_ev"); }

    static void onBattlePre(Object event) {
        try {
            Object battle = Leaderboards.call(event, "getBattle");
            boolean ev = false; ServerPlayer payer = null;
            for (Object actor : (Iterable<?>) Leaderboards.call(battle, "getActors")) {
                if (isEvTrainer(npcOf(actor))) ev = true;
                if (actor.getClass().getSimpleName().equals("PlayerBattleActor")) payer = server.getPlayerList().getPlayer((UUID) Leaderboards.call(actor, "getUuid"));
            }
            if (!ev || payer == null) return;
            if (Economy.balance(payer) < EV_FEE || !Economy.take(payer, EV_FEE)) {
                Leaderboards.call(event, "cancel");
                for (Method m : event.getClass().getMethods())
                    if (m.getName().equals("setReason")) m.invoke(event, Component.literal("You need " + Fmt.cd(EV_FEE) + " to train here."));
                payer.sendSystemMessage(Component.literal("§c§l[EV Training] §r§cYou need " + Fmt.cd(EV_FEE) + " for a training battle."));
                return;
            }
            payer.sendSystemMessage(Component.literal("§a§l[EV Training] §r§7Paid §6" + Fmt.cd(EV_FEE) + "§7. Good luck!"));
        } catch (Throwable t) { GtsMod.LOG.error("[JCL] EV trainer fee check failed", t); }
    }
}
