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
            "ev_hp", "ev_atk", "ev_def", "ev_spa", "ev_spd", "ev_spe");
    static final List<String> EV = List.of("ev_hp", "ev_atk", "ev_def", "ev_spa", "ev_spd", "ev_spe");
    static final long EV_FEE = 1000;
    static MinecraftServer server;

    static Path skinDir() { return FMLPaths.CONFIGDIR.get().resolve("jcl_skins"); }

    @SubscribeEvent
    public static void onStart(ServerStartedEvent e) {
        server = e.getServer();
        try {
            Files.createDirectories(skinDir());
            for (String s : SKINS) {
                Path out = skinDir().resolve(s + ".png");
                if (Files.exists(out)) continue;                       // an admin may have swapped in their own skin
                try (InputStream in = Npcs.class.getResourceAsStream("/jcl_skins/" + s + ".png")) { if (in != null) Files.copy(in, out); }
            }
        } catch (Exception ex) { GtsMod.LOG.error("[JCL] Could not unpack NPC skins", ex); }
        hookBattles();
    }

    // ------------------------------------------------------------------ skins

    static boolean isCobblemonNpc(Entity e) { return e != null && e.getClass().getSimpleName().equals("NPCEntity"); }

    static void applySkin(Entity e, String skin) {
        try {
            Path f = skinDir().resolve(skin + ".png");
            if (!Files.exists(f)) return;
            Class<?> modelType = Class.forName("com.cobblemon.mod.common.entity.npc.NPCPlayerModelType");
            Object def = modelType.getField("DEFAULT").get(null);
            Method load = e.getClass().getMethod("loadTexture", java.net.URI.class, modelType);
            load.invoke(e, f.toUri(), def);
        } catch (Throwable t) { GtsMod.LOG.warn("[JCL] Could not apply skin {} to an NPC", skin, t); }
    }

    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent e) {
        if (e.getLevel().isClientSide() || !isCobblemonNpc(e.getEntity())) return;
        for (String t : e.getEntity().getTags()) if (t.startsWith("jcl_skin_")) { applySkin(e.getEntity(), t.substring(9)); return; }
    }

    // ------------------------------------------------------------------ spawning

    /** Spawns Cobblemon NPC class jcl_gts:<cls> at a spot, facing yaw, tags it and gives it its skin. */
    static Entity spawn(ServerLevel level, String cls, double x, double y, double z, float yaw, String... tags) {
        var src = server.createCommandSourceStack().withLevel(level).withSuppressedOutput().withPermission(4);
        server.getCommands().performPrefixedCommand(src, String.format(Locale.ROOT, "spawnnpcat %.2f %.2f %.2f jcl_gts:%s 1", x, y, z, cls));
        Entity found = null; double best = 9;
        for (Entity en : level.getEntities((Entity) null, new AABB(x - 2, y - 2, z - 2, x + 2, y + 3, z + 2), Npcs::isCobblemonNpc)) {
            if (en.getTags().stream().anyMatch(t -> t.startsWith("jcl_"))) continue;
            double d = en.distanceToSqr(x, y, z);
            if (d < best) { best = d; found = en; }
        }
        if (found == null) { GtsMod.LOG.warn("[JCL] Cobblemon didn't spawn NPC class jcl_gts:{}. Is the class loaded?", cls); return null; }
        found.teleportTo(x, y, z);
        found.setYRot(yaw); found.setXRot(0);
        if (found instanceof LivingEntity le) { le.setYHeadRot(yaw); le.setYBodyRot(yaw); }
        for (String t : tags) found.addTag(t);
        found.addTag("jcl_skin_" + cls);
        applySkin(found, cls);
        return found;
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
