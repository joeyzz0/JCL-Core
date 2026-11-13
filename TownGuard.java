package net.joeyscobblemon.gts;

import com.google.gson.JsonArray;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.level.GameType;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.FinalizeSpawnEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * Port Azure's own rules: decorative food can't be eaten, and hostile mobs never spawn in or near town
 * (48 blocks past its edges, so the docks and beaches are covered too).
 */
@EventBusSubscriber(modid = GtsMod.MODID)
public final class TownGuard {
    private TownGuard() {}

    static int[] box;              // x0, z0, x1, z1 in world coordinates
    static long boxRead;

    /** The town's footprint, re-read every 30 seconds (it changes when the town is rebuilt or removed). */
    static int[] box(ServerLevel lvl) {
        if (System.currentTimeMillis() - boxRead < 30_000) return box;
        boxRead = System.currentTimeMillis();
        try {
            BlockPos o = SpawnCity.savedOrigin(lvl);
            if (o == null) return box = null;
            JsonArray b = SpawnCity.index().getAsJsonArray("bounds");
            box = new int[]{o.getX() + b.get(0).getAsInt(), o.getZ() + b.get(2).getAsInt(), o.getX() + b.get(3).getAsInt(), o.getZ() + b.get(5).getAsInt()};
        } catch (Throwable t) { box = null; }
        return box;
    }
    static boolean inTown(ServerLevel lvl, double x, double z, int margin) {
        if (lvl.dimension() != net.minecraft.world.level.Level.OVERWORLD) return false;
        int[] b = box(lvl);
        return b != null && x >= b[0] - margin && x <= b[2] + margin && z >= b[1] - margin && z <= b[3] + margin;
    }

    /** Placed food that a right-click would eat or slice: cakes, candle cakes, pies and feasts. */
    static boolean edibleDecor(String id) {
        String path = id.substring(id.indexOf(':') + 1);
        return path.equals("cake") || path.endsWith("candle_cake") || path.endsWith("_pie") || path.endsWith("cheesecake")
                || path.endsWith("_feast") || path.endsWith("_block") && (path.contains("roast") || path.contains("ham") || path.contains("stuffed") || path.contains("medley") || path.contains("shepherds"));
    }

    @SubscribeEvent
    public static void onUseBlock(PlayerInteractEvent.RightClickBlock e) {
        if (!(e.getEntity() instanceof ServerPlayer p) || !(e.getLevel() instanceof ServerLevel lvl)) return;
        if (p.hasPermissions(2) && p.gameMode.getGameModeForPlayer() == GameType.CREATIVE) return;      // staff can still edit decor
        String id = BuiltInRegistries.BLOCK.getKey(lvl.getBlockState(e.getPos()).getBlock()).toString();
        if (!edibleDecor(id) || !inTown(lvl, e.getPos().getX(), e.getPos().getZ(), 0)) return;
        e.setCanceled(true);
        e.setCancellationResult(net.minecraft.world.InteractionResult.FAIL);
        p.displayClientMessage(net.minecraft.network.chat.Component.literal("§7That's just for show!"), true);
    }

    @SubscribeEvent
    public static void onSpawn(FinalizeSpawnEvent e) {
        if (!(e.getEntity() instanceof Enemy) || !(e.getLevel().getLevel() instanceof ServerLevel lvl)) return;
        var type = e.getSpawnType();
        if (type == net.minecraft.world.entity.MobSpawnType.COMMAND || type == net.minecraft.world.entity.MobSpawnType.SPAWN_EGG) return;
        if (inTown(lvl, e.getX(), e.getZ(), 48)) e.setSpawnCancelled(true);
    }
}
