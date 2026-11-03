package net.joeyscobblemon.gts;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.Gson;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * The world map wall: a grid of real, locked maps rendered from the actual world around spawn, one
 * map per item frame across the whole map board. Rendering is spread over many ticks so it never
 * freezes the server; ungenerated chunks get generated as it goes (pre-generating with Chunky first
 * makes it much faster). Run it again any time to refresh the picture.
 */
@EventBusSubscriber(modid = "jcl_gts")
public final class WorldMap {
    private WorldMap() {}

    static final class Job {
        ServerLevel level; ServerPlayer who;
        int cols, rows, x0, z0;                 // world corner of the whole picture (north-west)
        byte[][] colors;                        // per tile
        short[] heights;                        // per pixel, for shading
        int chunkX, chunkZ, chunksW, chunksH, done;
        List<BlockPos> frames = new ArrayList<>();
        Direction facing;
    }

    static Job job;
    static int chunksPerTick = 2;

    public static final class Saved { public List<Integer> ids = new ArrayList<>(); }

    static Path file(ServerLevel lvl) { return lvl.getServer().getWorldPath(LevelResource.ROOT).resolve("data").resolve("jcl_gts").resolve("worldmap.json"); }

    /** frames: one block position per tile, row-major starting at the top-left as players see it. */
    static void start(ServerPlayer who, ServerLevel level, int centerX, int centerZ, int cols, int rows, List<BlockPos> frames, Direction facing) {
        if (job != null) { who.sendSystemMessage(Component.literal("§cThe world map is already being drawn.")); return; }
        Job j = new Job(); j.level = level; j.who = who; j.cols = cols; j.rows = rows; j.frames = frames; j.facing = facing;
        j.x0 = centerX - cols * 64; j.z0 = centerZ - rows * 64;
        j.colors = new byte[cols * rows][16384];
        j.heights = new short[cols * 128 * rows * 128];
        j.chunksW = cols * 8; j.chunksH = rows * 8;
        job = j;
        who.sendSystemMessage(Component.literal("§aDrawing the world map: " + cols + " × " + rows + " maps (" + (cols * 128) + " × " + (rows * 128)
                + " blocks). This takes a few minutes. You'll get updates in chat."));
    }

    @SubscribeEvent
    public static void onTick(ServerTickEvent.Post e) {
        if (job == null) return;
        Job j = job;
        for (int n = 0; n < chunksPerTick && job != null; n++) {
            renderChunk(j, j.chunkX, j.chunkZ);
            j.done++;
            if (++j.chunkX >= j.chunksW) { j.chunkX = 0; j.chunkZ++; }
            if (j.done % 500 == 0 && j.who != null && !j.who.hasDisconnected())
                j.who.sendSystemMessage(Component.literal("§7World map: " + (100 * j.done / (j.chunksW * j.chunksH)) + "%"));
            if (j.chunkZ >= j.chunksH) { finish(j); job = null; }
        }
    }

    static void renderChunk(Job j, int cxIdx, int czIdx) {
        int wx = j.x0 + cxIdx * 16, wz = j.z0 + czIdx * 16;
        ChunkAccess ch = j.level.getChunk(wx >> 4, wz >> 4, ChunkStatus.FULL, true);
        int W = j.cols * 128;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int dz = 0; dz < 16; dz++) {
            for (int dx = 0; dx < 16; dx++) {
                int px = cxIdx * 16 + dx, pz = czIdx * 16 + dz;
                int x = wx + dx, z = wz + dz;
                int y = ch.getHeight(Heightmap.Types.WORLD_SURFACE, x & 15, z & 15);
                BlockState st; MapColor mc;
                do { pos.set(x, y, z); st = ch.getBlockState(pos); mc = st.getMapColor(j.level, pos); y--; }
                while (mc == MapColor.NONE && y > j.level.getMinBuildHeight());
                int top = y + 1;
                MapColor.Brightness br;
                if (!st.getFluidState().isEmpty()) {
                    int depth = 0; BlockPos.MutableBlockPos d = pos.mutable();
                    while (depth < 12 && !ch.getBlockState(d.move(0, -1, 0)).getFluidState().isEmpty()) depth++;
                    double v = depth * 0.1 + ((x + z) & 1) * 0.2;
                    br = v < 0.5 ? MapColor.Brightness.HIGH : v > 0.9 ? MapColor.Brightness.LOW : MapColor.Brightness.NORMAL;
                } else {
                    int north = pz > 0 ? j.heights[(pz - 1) * W + px] : top;
                    double v = (top - north) * 4.0 / 4 + (((x + z) & 1) - 0.5) * 0.4;
                    br = v > 0.6 ? MapColor.Brightness.HIGH : v < -0.6 ? MapColor.Brightness.LOW : MapColor.Brightness.NORMAL;
                }
                j.heights[pz * W + px] = (short) top;
                int tile = (pz / 128) * j.cols + (px / 128);
                j.colors[tile][(pz % 128) * 128 + (px % 128)] = mc.getPackedId(br);
            }
        }
    }

    static void finish(Job j) {
        ServerLevel lvl = j.level;
        Saved saved = new Saved();
        try { if (Files.exists(file(lvl))) saved = new Gson().fromJson(Files.readString(file(lvl)), Saved.class); } catch (Exception ignored) {}
        if (saved == null) saved = new Saved();
        List<Integer> ids = new ArrayList<>();
        for (int t = 0; t < j.cols * j.rows; t++) {
            int tx = t % j.cols, tz = t / j.cols;
            int cx = j.x0 + tx * 128 + 64, cz = j.z0 + tz * 128 + 64;
            MapItemSavedData fresh = MapItemSavedData.createFresh(cx, cz, (byte) 0, false, false, lvl.dimension());
            System.arraycopy(j.colors[t], 0, fresh.colors, 0, 16384);
            MapItemSavedData locked = fresh.locked();
            MapId id = t < saved.ids.size() ? new MapId(saved.ids.get(t)) : lvl.getFreeMapId();
            lvl.setMapData(id, locked);
            locked.setDirty();
            ids.add(id.id());
        }
        saved.ids = ids;
        try { Files.createDirectories(file(lvl).getParent()); Files.writeString(file(lvl), new Gson().toJson(saved)); } catch (Exception ex) { GtsMod.LOG.error("[JCL] Could not save world map ids", ex); }
        // hang the frames: remove any old ones first
        for (BlockPos fp : j.frames)
            for (Entity en : lvl.getEntities((Entity) null, new AABB(fp).inflate(0.1), en -> en instanceof ItemFrame)) en.discard();
        for (int t = 0; t < j.frames.size() && t < ids.size(); t++) {
            ItemFrame f = new ItemFrame(lvl, j.frames.get(t), j.facing);
            ItemStack map = new ItemStack(Items.FILLED_MAP);
            map.set(DataComponents.MAP_ID, new MapId(ids.get(t)));
            f.setItem(map);
            f.setInvisible(true);
            f.setInvulnerable(true);
            try { Field fx = ItemFrame.class.getDeclaredField("fixed"); fx.setAccessible(true); fx.setBoolean(f, true); } catch (Throwable ignored) {}
            lvl.addFreshEntity(f);
        }
        if (j.who != null && !j.who.hasDisconnected()) j.who.sendSystemMessage(Component.literal("§a§lThe world map is finished! §7Run the same command again any time to refresh it."));
    }
}
