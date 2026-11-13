package net.joeyscobblemon.gts;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Interaction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.GZIPInputStream;

/**
 * Builds the spawn city in sections without freezing the server: /jcl build spawn (run standing on the spot that
 * becomes the centre of the plaza). It clears the footprint, places every section, then spawns the town's NPCs,
 * the GTS and Wonder Trade machines, sets the world spawn to the arrival point, and saves the podium spots.
 */
@EventBusSubscriber(modid = GtsMod.MODID)
public final class SpawnCity {
    private SpawnCity() {}

    static JsonObject index;
    static final int PER_TICK = 24000;
    static Deque<Runnable> jobs = new ArrayDeque<>();
    static ServerPlayer builder; static long started;

    static JsonObject index() throws IOException {
        java.nio.file.Path saved = DesignSave.TOWN.resolve("index.json");
        if (index == null && java.nio.file.Files.exists(saved)) index = JsonParser.parseString(java.nio.file.Files.readString(saved)).getAsJsonObject();
        if (index == null) try (InputStream in = SpawnCity.class.getResourceAsStream("/jcl/spawn_city/index.json")) {
            index = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
        }
        return index;
    }

    /** The island's seafloor height (from the terrain map), so a snapshot reaches below it. */
    static int terrainFloor() {
        try (InputStream raw = SpawnCity.class.getResourceAsStream("/jcl/spawn_city/terrain.bin"); GZIPInputStream in = new GZIPInputStream(raw)) {
            java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream(); int c;
            while ((c = in.read()) != -1 && c != '\n') b.write(c);
            return JsonParser.parseString(b.toString(StandardCharsets.UTF_8)).getAsJsonObject().get("floor").getAsInt();
        } catch (Exception e) { return -40; }
    }

    /** An NPC entry's world position: exact when it came from /jcl spawn save, else the centre of its block. */
    static double[] npcPos(JsonObject n, BlockPos o) {
        JsonArray a = n.getAsJsonArray("pos"); boolean exact = n.has("exact") && n.get("exact").getAsBoolean();
        double c = exact ? 0 : 0.5;
        return new double[]{o.getX() + a.get(0).getAsDouble() + c, o.getY() + a.get(1).getAsDouble(), o.getZ() + a.get(2).getAsDouble() + c};
    }

    /** One section: palette and a 2-byte index per block (x, z, y order). */
    record Section(int sx, int sz, int y0, int h, BlockState[] pal, byte[] data) {}

    static Section load(ServerLevel lvl, String name) throws IOException {
        try (InputStream raw = DesignSave.open("/jcl/spawn_city/" + name, DesignSave.TOWN.resolve(name))) {
            byte[] all = new GZIPInputStream(raw).readAllBytes();
            int nl = 0; while (all[nl] != '\n') nl++;
            JsonObject h = JsonParser.parseString(new String(all, 0, nl, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonArray p = h.getAsJsonArray("palette");
            BlockState[] pal = new BlockState[p.size()];
            var blocks = lvl.registryAccess().lookupOrThrow(Registries.BLOCK);
            for (int i = 0; i < p.size(); i++) {
                BlockState s = null;
                if (p.get(i).isJsonNull()) { pal[i] = null; continue; }        // a bad entry places nothing, the rest of the section still builds
                for (String opt : p.get(i).getAsString().split("\\|")) {
                    try { s = BlockStateParser.parseForBlock(blocks, opt, false).blockState(); break; } catch (Exception ignored) {}
                }
                pal[i] = s == null ? Blocks.AIR.defaultBlockState() : s;          // index 0 ("__none__") is never placed
            }
            return new Section(h.get("sx").getAsInt(), h.get("sz").getAsInt(), h.get("y0").getAsInt(), h.get("h").getAsInt(), pal, Arrays.copyOfRange(all, nl + 1, all.length));
        }
    }

    // ------------------------------------------------------------------ backup and restore (for testing)

    static java.nio.file.Path dataDir(ServerLevel lvl) { return lvl.getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("data").resolve("jcl_gts"); }
    static String BACKUP = "spawn_backup";
    static boolean SKIP_PEOPLE;
    static long RESTORED;            // restoring the old spawn must never touch the new town's NPCs
    static java.nio.file.Path backupDir(ServerLevel lvl) { return dataDir(lvl).resolve(BACKUP); }

    /** Saves every block in a strip of columns (exactly as it is now) so /jcl spawn remove can put it back. */
    static void backupStrip(ServerLevel lvl, BlockPos o, int i0, int i1, int tx0, int tz0, int td, int ya, int yb) {
        try {
            java.nio.file.Files.createDirectories(backupDir(lvl));
            java.nio.file.Path f = backupDir(lvl).resolve("strip_" + i0 + ".bin");
            if (java.nio.file.Files.exists(f)) return;                       // never overwrite the original
            Map<BlockState, Integer> pi = new HashMap<>(); List<String> pal = new ArrayList<>();
            int H = yb - ya + 1;
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bo);
            BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
            short[] idx = new short[(i1 - i0) * td * H]; int n = 0;
            for (int i = i0; i < i1; i++) for (int j = 0; j < td; j++) for (int y = ya; y <= yb; y++) {
                m.set(o.getX() + tx0 + i, o.getY() + y, o.getZ() + tz0 + j);
                BlockState st = lvl.getBlockState(m);
                Integer k = pi.get(st);
                if (k == null) { k = pal.size(); pi.put(st, k); pal.add(BlockStateParser.serialize(st)); }
                idx[n++] = (short) (int) k;
            }
            try (DataOutputStream fo = new DataOutputStream(new java.util.zip.GZIPOutputStream(java.nio.file.Files.newOutputStream(f)))) {
                fo.writeInt(i0); fo.writeInt(i1); fo.writeInt(tx0); fo.writeInt(tz0); fo.writeInt(td); fo.writeInt(ya); fo.writeInt(yb);
                fo.writeInt(pal.size()); for (String p : pal) fo.writeUTF(p);
                for (short v : idx) fo.writeShort(v);
            }
        } catch (Exception ex) { GtsMod.LOG.error("[JCL] Spawn: couldn't back up strip {}", i0, ex); }
    }

    /** Where a named town spot is in the world (null if the town hasn't been built). */
    static BlockPos markerWorld(ServerLevel lvl, String name) {
        BlockPos o = savedOrigin(lvl);
        if (o == null) return null;
        try {
            JsonObject mk = index().getAsJsonObject("markers");
            if (!mk.has(name)) return null;
            JsonArray a = mk.getAsJsonObject(name).getAsJsonArray("pos");
            return o.offset(a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt());
        } catch (Exception e) { return null; }
    }

    static BlockPos savedOrigin(ServerLevel lvl) {
        try { String[] a = java.nio.file.Files.readString(dataDir(lvl).resolve("spawn_origin.txt")).trim().split(" ");
            return new BlockPos(Integer.parseInt(a[0]), Integer.parseInt(a[1]), Integer.parseInt(a[2])); } catch (Exception e) { return null; }
    }

    /** Puts every backed-up block back, removes the town's NPCs, machines and text, and deletes the backup. */
    static void remove(ServerPlayer p) throws IOException {
        ServerLevel lvl = p.serverLevel();
        BlockPos o = savedOrigin(lvl);
        if (o == null || !java.nio.file.Files.isDirectory(backupDir(lvl))) { p.sendSystemMessage(Component.literal("§cThere's no spawn build with a backup to remove.")); return; }
        builder = p; started = System.currentTimeMillis(); jobs.clear(); RESTORED = 0;
        if (!SKIP_PEOPLE) jobs.add(() -> { int n = clearPeople(lvl, o); p.sendSystemMessage(Component.literal("§7[Spawn] Removed " + n + " town NPCs, machines and text.")); });
        jobs.add(() -> {                                                   // the town's leaderboards and podium statues go with it
            try {
                JsonArray b = index().getAsJsonArray("bounds");
                int lb = Leaderboards.clearIn(lvl, o.getX() + b.get(0).getAsInt(), o.getZ() + b.get(2).getAsInt(), o.getX() + b.get(3).getAsInt(), o.getZ() + b.get(5).getAsInt());
                int pd = Podium.clear();
                p.sendSystemMessage(Component.literal("§7[Spawn] Removed " + lb + " leaderboard(s) and " + pd + " podium statue(s)."));
            } catch (Exception ex) { GtsMod.LOG.warn("[JCL] Spawn: couldn't clear leaderboards: {}", ex.toString()); }
        });
        List<java.nio.file.Path> strips;
        try (var st = java.nio.file.Files.list(backupDir(lvl))) { strips = st.filter(f -> f.toString().endsWith(".bin")).sorted().toList(); }
        int flags = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
        for (java.nio.file.Path f : strips) jobs.add(() -> {
            try (DataInputStream in = new DataInputStream(new GZIPInputStream(java.nio.file.Files.newInputStream(f)))) {
                int i0 = in.readInt(), i1 = in.readInt(), tx0 = in.readInt(), tz0 = in.readInt(), td = in.readInt(), ya = in.readInt(), yb = in.readInt();
                int np = in.readInt(); BlockState[] pal = new BlockState[np];
                var blocks = lvl.registryAccess().lookupOrThrow(Registries.BLOCK);
                for (int k = 0; k < np; k++) { String sname = in.readUTF(); try { pal[k] = BlockStateParser.parseForBlock(blocks, sname, false).blockState(); } catch (Exception e) { pal[k] = Blocks.AIR.defaultBlockState(); } }
                BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
                for (int i = i0; i < i1; i++) for (int j = 0; j < td; j++) for (int y = ya; y <= yb; y++) {
                    BlockState want = pal[in.readShort()];
                    m.set(o.getX() + tx0 + i, o.getY() + y, o.getZ() + tz0 + j);
                    if (lvl.getBlockState(m) != want) { lvl.setBlock(m, want, flags); RESTORED++; }
                }
            } catch (Exception ex) { GtsMod.LOG.error("[JCL] Spawn: couldn't restore {}", f, ex); }
        });
        jobs.add(() -> {
            try (var st = java.nio.file.Files.walk(backupDir(lvl))) { st.sorted(Comparator.reverseOrder()).forEach(f -> { try { java.nio.file.Files.delete(f); } catch (Exception ignored) {} }); } catch (Exception ignored) {}
            try { java.nio.file.Files.deleteIfExists(dataDir(lvl).resolve("spawn_origin.txt")); } catch (Exception ignored) {}
            p.sendSystemMessage(Component.literal("§6[Spawn] §aRestored §f" + String.format("%,d", RESTORED) + "§a blocks from " + strips.size() + " backup strips (" + (System.currentTimeMillis() - started) / 1000 + "s)."));
        });
        p.sendSystemMessage(Component.literal("§6[Spawn] §fRestoring " + strips.size() + " strips from the backup. Expect some lag for a few minutes."));
    }

    // ------------------------------------------------------------------ removing the old spawn (Port Azure v1)

    /** The old town: 360 x 75 x 360 around the fountain at -13 76 202, dock to the south. */
    static final int OLD_X0 = -193, OLD_X1 = 166, OLD_Z0 = 4, OLD_Z1 = 363, OLD_Y0 = 20, OLD_Y1 = 107, SEA = 62;

    static void removeOldSpawn(ServerPlayer p) {
        ServerLevel lvl = p.serverLevel();
        BACKUP = "oldspawn_backup";
        if (java.nio.file.Files.isDirectory(backupDir(lvl))) { BACKUP = "spawn_backup"; p.sendSystemMessage(Component.literal("§cThe old spawn has already been removed (a backup exists). Use §e/jcl oldspawn restore confirm§c to bring it back first.")); return; }
        builder = p; started = System.currentTimeMillis(); jobs.clear();
        int flags = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
        BlockPos o = BlockPos.ZERO;
        int td = OLD_Z1 - OLD_Z0 + 1;
        BlockState air = Blocks.AIR.defaultBlockState(), water = Blocks.WATER.defaultBlockState(), sand = Blocks.SAND.defaultBlockState();
        BlockState gravel = Blocks.GRAVEL.defaultBlockState(), dirt = Blocks.DIRT.defaultBlockState(), grass = Blocks.GRASS_BLOCK.defaultBlockState();
        // the natural ground just outside the old town, every 8 blocks round all four sides: the new ground blends between them
        List<int[]> edge = new ArrayList<>();
        BlockPos.MutableBlockPos sm = new BlockPos.MutableBlockPos();
        for (int x = OLD_X0 - 6; x <= OLD_X1 + 6; x += 8) for (int z : new int[]{OLD_Z0 - 6, OLD_Z1 + 6}) edge.add(new int[]{x, z, groundAt(lvl, sm, x, z)});
        for (int z = OLD_Z0 - 6; z <= OLD_Z1 + 6; z += 8) for (int x : new int[]{OLD_X0 - 6, OLD_X1 + 6}) edge.add(new int[]{x, z, groundAt(lvl, sm, x, z)});
        jobs.add(() -> {
            AABB box = new AABB(OLD_X0, OLD_Y0, OLD_Z0, OLD_X1 + 1, OLD_Y1 + 1, OLD_Z1 + 1);
            int n = 0;
            for (Entity e : lvl.getEntities((Entity) null, box, e -> !(e instanceof ServerPlayer) && (e.getTags().stream().anyMatch(t -> t.startsWith("jcl_"))
                    || e instanceof net.minecraft.world.entity.decoration.ItemFrame || e instanceof net.minecraft.world.entity.decoration.ArmorStand
                    || e instanceof net.minecraft.world.entity.Display || e instanceof net.minecraft.world.entity.decoration.Painting))) { e.discard(); n++; }
            final int nn = n; p.sendSystemMessage(Component.literal("§7[Old spawn] Removed " + nn + " old town entities."));
        });
        for (int i0 = 0; i0 < OLD_X1 - OLD_X0 + 1; i0 += 3) {
            final int ia = i0;
            jobs.add(() -> {
                BACKUP = "oldspawn_backup";
                backupStrip(lvl, o, ia, Math.min(ia + 3, OLD_X1 - OLD_X0 + 1), OLD_X0, OLD_Z0, td, OLD_Y0, OLD_Y1);
                BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
                for (int i = ia; i < Math.min(ia + 3, OLD_X1 - OLD_X0 + 1); i++) for (int j = 0; j < td; j++) {
                    int wx = OLD_X0 + i, wz = OLD_Z0 + j;
                    int floor = blendGround(edge, wx, wz);          // the natural ground height for this column
                    for (int y = OLD_Y0; y <= OLD_Y1; y++) {
                        BlockState want;
                        if (y > floor) want = y <= SEA ? water : air;
                        else if (floor >= SEA) want = y == floor ? grass : y > floor - 4 ? dirt : null;          // land
                        else want = y > floor - 3 ? ((wx * 31 + wz * 17) % 7 == 0 ? gravel : sand) : null;   // sea bed
                        if (want == null) continue;                 // deeper than that stays as it is (buried)
                        m.set(wx, y, wz);
                        if (lvl.getBlockState(m) != want) lvl.setBlock(m, want, flags);
                    }
                }
            });
        }
        jobs.add(() -> {
            BACKUP = "spawn_backup";
            p.sendSystemMessage(Component.literal("§6[Old spawn] §fBlocks cleared. Now removing its NPCs, leaderboards and text chunk by chunk..."));
            EntityClear.resetCount();
            EntityClear.area(lvl, OLD_X0 - 16, OLD_Z0 - 16, OLD_X1 + 16, OLD_Z1 + 16, e -> EntityClear.jcl(e)
                    || e instanceof net.minecraft.world.entity.decoration.ItemFrame || e instanceof net.minecraft.world.entity.decoration.ArmorStand
                    || e instanceof net.minecraft.world.entity.Display || e instanceof net.minecraft.world.entity.decoration.Painting);
            EntityClear.then(() -> p.sendSystemMessage(Component.literal("§6[Old spawn] §aDone: §f" + EntityClear.count() + "§a old town entities removed. §7Undo the blocks with §e/jcl oldspawn restore confirm§7.")));
        });
        p.sendSystemMessage(Component.literal("§6[Old spawn] §fBacking up and clearing the old town (x " + OLD_X0 + " to " + OLD_X1 + ", z " + OLD_Z0 + " to " + OLD_Z1 + "). This takes a few minutes."));
    }

    /** Removes every JCL entity: everything loaded in every world, plus the town, old town and Frontier hub areas chunk by chunk. */
    static void purgeAll(ServerPlayer p) {
        int now = 0;
        for (ServerLevel l : p.getServer().getAllLevels()) for (Entity e : l.getAllEntities()) if (EntityClear.jcl(e)) { e.discard(); now++; }
        final int loaded = now;
        p.sendSystemMessage(Component.literal("§6[NPCs] §fRemoved §e" + loaded + "§f loaded JCL entities. Now checking unloaded chunks in the town and Frontier areas..."));
        EntityClear.resetCount();
        ServerLevel ow = p.getServer().overworld();
        BlockPos o = savedOrigin(ow);
        if (o != null) {
            try { JsonArray b = index().getAsJsonArray("bounds"); EntityClear.area(ow, o.getX() + b.get(0).getAsInt() - 16, o.getZ() + b.get(2).getAsInt() - 16, o.getX() + b.get(3).getAsInt() + 16, o.getZ() + b.get(5).getAsInt() + 16, EntityClear::jcl); } catch (Exception ignored) {}
        }
        EntityClear.area(ow, OLD_X0 - 16, OLD_Z0 - 16, OLD_X1 + 16, OLD_Z1 + 16, EntityClear::jcl);
        for (ServerLevel l : p.getServer().getAllLevels()) if (l.dimension().location().getPath().contains("frontier")) EntityClear.area(l, -192, -192, 192, 192, EntityClear::jcl);
        EntityClear.then(() -> p.sendSystemMessage(Component.literal("§6[NPCs] §aDone: §f" + (loaded + EntityClear.count()) + "§a JCL entities removed in total. §7Now run §e/jcl spawn npcs§7 and §e/frontier build hub§7.")));
    }

    /** The top of the natural ground at a spot (ignoring water, plants and air), clamped to the old town's depth range. */
    static int groundAt(ServerLevel lvl, BlockPos.MutableBlockPos m, int x, int z) {
        for (int y = OLD_Y1; y > OLD_Y0; y--) {
            m.set(x, y, z);
            BlockState st = lvl.getBlockState(m);
            if (st.isAir() || !st.getFluidState().isEmpty() || st.canBeReplaced() || st.is(net.minecraft.tags.BlockTags.LEAVES) || st.is(net.minecraft.tags.BlockTags.LOGS)) continue;
            return y;
        }
        return 40;
    }
    /** Smoothly blends the edge samples (closer ones count far more), so the cleared area meets its surroundings. */
    static int blendGround(List<int[]> edge, int x, int z) {
        double w = 0, sum = 0;
        for (int[] e : edge) { double d2 = (e[0] - x) * (double) (e[0] - x) + (e[1] - z) * (double) (e[1] - z) + 1; double k = 1 / (d2 * d2); w += k; sum += k * e[2]; }
        return (int) Math.round(sum / w);
    }

    static void restoreOldSpawn(ServerPlayer p) throws IOException {
        BACKUP = "oldspawn_backup";
        ServerLevel lvl = p.serverLevel();
        if (!java.nio.file.Files.isDirectory(backupDir(lvl))) { BACKUP = "spawn_backup"; p.sendSystemMessage(Component.literal("§cThere's no old-spawn backup to restore.")); return; }
        // the restore reads strips relative to x/z 0, exactly as they were saved
        java.nio.file.Path origin = dataDir(lvl).resolve("spawn_origin.txt");
        boolean had = java.nio.file.Files.exists(origin);
        String keep = had ? java.nio.file.Files.readString(origin) : null;
        java.nio.file.Files.writeString(origin, "0 0 0");
        SKIP_PEOPLE = true;
        try { remove(p); } finally { SKIP_PEOPLE = false; }
        jobs.add(() -> {
            try { if (keep != null) java.nio.file.Files.writeString(origin, keep); else java.nio.file.Files.deleteIfExists(origin); } catch (Exception ignored) {}
            BACKUP = "spawn_backup";
        });
    }

    /** Every entity the spawn build placed (NPCs, machines, text) inside the town's footprint. */
    static int clearPeople(ServerLevel lvl, BlockPos o) {
        try {
            JsonArray b = index().getAsJsonArray("bounds");
            AABB box = new AABB(o.getX() + b.get(0).getAsInt(), o.getY() - 60, o.getZ() + b.get(2).getAsInt(), o.getX() + b.get(3).getAsInt() + 1, o.getY() + 120, o.getZ() + b.get(5).getAsInt() + 1);
            int n = 0;
            for (Entity e : lvl.getEntities((Entity) null, box, e -> e.getTags().contains("jcl_spawn"))) { e.discard(); n++; }
            return n;
        } catch (Exception ex) { return 0; }
    }

    static void start(ServerPlayer p) throws IOException {
        ServerLevel lvl = p.serverLevel();
        // the plaza centre: your X and Z; the height is always street level, one above the world's sea surface
        BlockPos o = new BlockPos(p.blockPosition().getX(), p.serverLevel().getSeaLevel(), p.blockPosition().getZ());
        if (savedOrigin(lvl) != null) { p.sendSystemMessage(Component.literal("§cA spawn build already exists here. Run §e/jcl spawn remove confirm§c first.")); return; }
        java.nio.file.Files.createDirectories(dataDir(lvl));
        java.nio.file.Files.writeString(dataDir(lvl).resolve("spawn_origin.txt"), o.getX() + " " + o.getY() + " " + o.getZ());
        buildAt(p, lvl, o, true);
    }

    /** /jcl spawn regen: rebuilds the town in place at its saved origin (from your saved version, if any). The original
     *  terrain backup from the first build is kept untouched, so /jcl spawn remove still restores the land exactly. */
    static void regen(ServerPlayer p) throws IOException {
        ServerLevel lvl = p.server.overworld();
        BlockPos o = savedOrigin(lvl);
        if (o == null) { p.sendSystemMessage(Component.literal("§cThere's no town here to regenerate. Build it first.")); return; }
        boolean saved = java.nio.file.Files.isDirectory(DesignSave.TOWN) && java.nio.file.Files.list(DesignSave.TOWN).findAny().isPresent();
        p.sendSystemMessage(Component.literal("§6[Spawn] §fRegenerating the town in place from " + (saved ? "§ayour saved version" : "§7the original design") + "§f."));
        buildAt(p, lvl, o, false);
    }

    static void buildAt(ServerPlayer p, ServerLevel lvl, BlockPos o, boolean backup) throws IOException {
        JsonObject idx = index();
        JsonArray b = idx.getAsJsonArray("bounds");
        int bx0 = b.get(0).getAsInt(), by0 = b.get(1).getAsInt(), bz0 = b.get(2).getAsInt(), bx1 = b.get(3).getAsInt(), bz1 = b.get(5).getAsInt();
        int clearTo = idx.get("clear_to").getAsInt();
        final boolean snap = idx.has("snapshot") && idx.get("snapshot").getAsBoolean();     // a saved snapshot: place exactly that
        builder = p; started = System.currentTimeMillis(); jobs.clear();
        int flags = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
        // 1. remove old town entities (NPCs, text, frames, stands) from the footprint
        jobs.add(() -> {
            AABB box = new AABB(o.getX() + bx0, o.getY() + by0, o.getZ() + bz0, o.getX() + bx1 + 1, o.getY() + clearTo, o.getZ() + bz1 + 1);
            int n = 0;
            for (Entity e : lvl.getEntities((Entity) null, box, e -> !(e instanceof ServerPlayer) && (e.getTags().stream().anyMatch(t -> t.startsWith("jcl_"))
                    || e instanceof net.minecraft.world.entity.decoration.ItemFrame || e instanceof net.minecraft.world.entity.decoration.ArmorStand
                    || e instanceof net.minecraft.world.entity.Display || e instanceof net.minecraft.world.entity.decoration.Painting))) { e.discard(); n++; }
            final int nn = n; p.sendSystemMessage(Component.literal("§7[Spawn] Cleared " + nn + " old town entities."));
        });
        // 2. the island: every column shaped from the seafloor up (land, beach, sea), old buildings cleared above
        try (InputStream raw = SpawnCity.class.getResourceAsStream("/jcl/spawn_city/terrain.bin")) {
            byte[] all = new GZIPInputStream(raw).readAllBytes();
            int nl = 0; while (all[nl] != '\n') nl++;
            JsonObject th = JsonParser.parseString(new String(all, 0, nl, StandardCharsets.UTF_8)).getAsJsonObject();
            int tx0 = th.get("x0").getAsInt(), tz0 = th.get("z0").getAsInt(), tw = th.get("w").getAsInt(), td = th.get("d").getAsInt(), floor = th.get("floor").getAsInt();
            byte[] kind = Arrays.copyOfRange(all, nl + 1, nl + 1 + tw * td), depth = Arrays.copyOfRange(all, nl + 1 + tw * td, nl + 1 + 2 * tw * td);
            BlockState stone = Blocks.STONE.defaultBlockState(), dirt = Blocks.DIRT.defaultBlockState(), grass = Blocks.GRASS_BLOCK.defaultBlockState(),
                    sand = Blocks.SAND.defaultBlockState(), sandstone = Blocks.SANDSTONE.defaultBlockState(), water = Blocks.WATER.defaultBlockState(),
                    gravel = Blocks.GRAVEL.defaultBlockState(), air = Blocks.AIR.defaultBlockState();
            for (int i0 = 0; i0 < tw; i0 += 3) {
                final int ia = i0;
                jobs.add(() -> {
                    BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
                    if (backup) backupStrip(lvl, o, ia, Math.min(ia + 3, tw), tx0, tz0, td, floor - 4, clearTo);
                    if (snap) return;                                              // the snapshot holds the island too
                    for (int i = ia; i < Math.min(ia + 3, tw); i++) for (int j = 0; j < td; j++) {
                        int k = kind[i * td + j], dep = depth[i * td + j];
                        int wx = o.getX() + tx0 + i, wz = o.getZ() + tz0 + j;
                        for (int y = floor - 4; y <= clearTo; y++) {
                            BlockState want;
                            if (k == 1) want = y < -4 ? stone : y < -1 ? dirt : y == -1 ? grass : air;                      // land
                            else if (k == 2) want = y < -4 ? sandstone : y <= -1 ? sand : air;                               // beach
                            else want = y < dep - 1 ? stone : y == dep - 1 ? ((wx + wz) % 3 == 0 ? gravel : sand) : y < 0 ? water : air;   // sea
                            m.set(wx, o.getY() + y, wz);
                            if (lvl.getBlockState(m) != want) lvl.setBlock(m, want, flags);
                        }
                    }
                });
            }
        } catch (Exception ex) { p.sendSystemMessage(Component.literal("§cSpawn: couldn't read the island map: " + ex.getMessage())); return; }
        FAILED.set(0);
        // 3. the city, section by section
        for (JsonElement se : idx.getAsJsonArray("sections")) {
            String name = se.getAsString();
            jobs.add(() -> {
                try {
                    Section s = load(lvl, name);
                    BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
                    for (int x = 0; x < 32; x++) for (int z = 0; z < 32; z++) {
                        int wx = o.getX() + s.sx() + x, wz = o.getZ() + s.sz() + z;
                        for (int y = 0; y < s.h(); y++) {
                            int i = (((x * 32) + z) * s.h() + y) * 2;
                            int pi = ((s.data()[i] & 0xFF) << 8) | (s.data()[i + 1] & 0xFF);
                            m.set(wx, o.getY() + s.y0() + y, wz);
                            BlockState want = s.pal()[pi];
                            if (pi == 0 || want == null) continue;                       // nothing here: the terrain step already set it
                            if (lvl.getBlockState(m) != want) lvl.setBlock(m, want, flags);
                        }
                    }
                    if (snap) restoreContents(lvl, o, s, name);
                } catch (Exception ex) { FAILED.incrementAndGet(); GtsMod.LOG.error("[JCL] Spawn: section {} failed", name, ex); }
            });
        }
        // 3b. connecting blocks learn their neighbours: panes, bars, walls, fences and stair corners take their proper shape
        //     (the town is placed without block updates, so without this panes stand as lone posts and walls don't join)
        for (JsonElement se : snap ? new JsonArray() : idx.getAsJsonArray("sections")) {      // a snapshot's shapes are already exact
            String name = se.getAsString();
            jobs.add(() -> {
                try {
                    Section s = load(lvl, name);
                    BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
                    int shaped = 0;
                    for (int x = 0; x < 32; x++) for (int z = 0; z < 32; z++) for (int y = 0; y < s.h(); y++) {
                        int i = (((x * 32) + z) * s.h() + y) * 2;
                        int pi = ((s.data()[i] & 0xFF) << 8) | (s.data()[i + 1] & 0xFF);
                        if (pi == 0 || s.pal()[pi] == null) continue;
                        var blk = s.pal()[pi].getBlock();
                        if (!(blk instanceof net.minecraft.world.level.block.IronBarsBlock || blk instanceof net.minecraft.world.level.block.WallBlock
                                || blk instanceof net.minecraft.world.level.block.FenceBlock || blk instanceof net.minecraft.world.level.block.StairBlock)) continue;
                        m.set(o.getX() + s.sx() + x, o.getY() + s.y0() + y, o.getZ() + s.sz() + z);
                        BlockState cur = lvl.getBlockState(m), shapedState = Block.updateFromNeighbourShapes(cur, lvl, m);
                        if (shapedState != cur) { lvl.setBlock(m, shapedState, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE); shaped++; }
                    }
                    if (shaped > 0) GtsMod.LOG.debug("[JCL] Spawn: {} connecting blocks shaped in {}", shaped, name);
                } catch (Exception ex) { GtsMod.LOG.error("[JCL] Spawn: shaping {} failed", name, ex); }
            });
        }
        // 3c. a snapshot's placed entities: frames, paintings, stands, displays, carts, boats
        if (snap) jobs.add(() -> restoreEntities(p, lvl, o));
        // 4. the town's people, machines, world spawn and podiums
        jobs.add(() -> finish(p, lvl, o, idx));
        p.sendSystemMessage(Component.literal("§6[Spawn] §fShaping the island and building the city (" + jobs.size() + " steps). This takes a few minutes; expect some lag."));
    }

    /** A snapshot column's block contents (sign text, chest items, banners...), put back after its blocks. */
    static void restoreContents(ServerLevel lvl, BlockPos o, Section s, String name) {
        java.nio.file.Path f = DesignSave.TOWN.resolve(name + ".be");
        if (!java.nio.file.Files.exists(f)) return;
        try {
            BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
            for (JsonElement e : JsonParser.parseString(java.nio.file.Files.readString(f)).getAsJsonArray()) {
                JsonObject b = e.getAsJsonObject();
                m.set(o.getX() + s.sx() + b.get("x").getAsInt(), o.getY() + s.y0() + b.get("y").getAsInt(), o.getZ() + s.sz() + b.get("z").getAsInt());
                var be = lvl.getBlockEntity(m);
                if (be == null) continue;
                be.loadWithComponents(net.minecraft.nbt.TagParser.parseTag(b.get("nbt").getAsString()), lvl.registryAccess());
                be.setChanged(); BlockState st = lvl.getBlockState(m); lvl.sendBlockUpdated(m.immutable(), st, st, Block.UPDATE_CLIENTS);
            }
        } catch (Exception ex) { GtsMod.LOG.warn("[JCL] Spawn: contents of {} failed: {}", name, ex.toString()); }
    }

    /** A snapshot's placed entities, moved with the town if it's built somewhere else, each with a fresh id. */
    static void restoreEntities(ServerPlayer p, ServerLevel lvl, BlockPos o) {
        java.nio.file.Path f = DesignSave.TOWN.resolve("entities.json");
        if (!java.nio.file.Files.exists(f)) return;
        int n = 0;
        try {
            JsonObject ej = JsonParser.parseString(java.nio.file.Files.readString(f)).getAsJsonObject();
            JsonArray or = ej.getAsJsonArray("origin");
            int dx = o.getX() - or.get(0).getAsInt(), dy = o.getY() - or.get(1).getAsInt(), dz = o.getZ() - or.get(2).getAsInt();
            for (JsonElement e : ej.getAsJsonArray("entities")) {
                try {
                    net.minecraft.nbt.CompoundTag t = net.minecraft.nbt.TagParser.parseTag(e.getAsString());
                    t.remove("UUID");
                    var pos = t.getList("Pos", 6);
                    double x = pos.getDouble(0) + dx, y = pos.getDouble(1) + dy, z = pos.getDouble(2) + dz;
                    if (t.contains("TileX")) { t.putInt("TileX", t.getInt("TileX") + dx); t.putInt("TileY", t.getInt("TileY") + dy); t.putInt("TileZ", t.getInt("TileZ") + dz); }
                    if (t.contains("block_pos", 11)) { int[] bp = t.getIntArray("block_pos"); t.putIntArray("block_pos", new int[]{bp[0] + dx, bp[1] + dy, bp[2] + dz}); }
                    var rot = t.getList("Rotation", 5);
                    float yaw = rot.size() > 0 ? rot.getFloat(0) : 0f, pitch = rot.size() > 1 ? rot.getFloat(1) : 0f;
                    Entity en = net.minecraft.world.entity.EntityType.loadEntityRecursive(t, lvl, x2 -> { x2.moveTo(x, y, z, yaw, pitch); return x2; });
                    if (en == null) continue;
                    en.addTag("jcl_saved");
                    if (lvl.addFreshEntity(en)) n++;
                } catch (Exception one) { GtsMod.LOG.debug("[JCL] Spawn: an entity failed: {}", one.toString()); }
            }
        } catch (Exception ex) { GtsMod.LOG.warn("[JCL] Spawn: entities failed: {}", ex.toString()); }
        final int nn = n; p.sendSystemMessage(Component.literal("§7[Spawn] Put back " + nn + " placed entities."));
    }

    /** Sections that couldn't be placed in the current build: reported in chat, never silent. */
    static final java.util.concurrent.atomic.AtomicInteger FAILED = new java.util.concurrent.atomic.AtomicInteger();

    static void finish(ServerPlayer p, ServerLevel lvl, BlockPos o, JsonObject idx) {
        try { townHolos(lvl, o, idx); } catch (Exception ex) { GtsMod.LOG.warn("[JCL] Spawn: couldn't place the town's floating text: {}", ex.toString()); }
        if (FAILED.get() > 0) p.sendSystemMessage(Component.literal("§c[Spawn] " + FAILED.get() + " section(s) of the town couldn't be placed. Check the server log for which ones, and send it over."));
        int npcs = spawnPeople(lvl, o, idx);
        finishReport(p, lvl, o, idx, npcs);
    }

    static int spawnPeople(ServerLevel lvl, BlockPos o, JsonObject idx) {
        int npcs = 0;
        for (JsonElement e : idx.getAsJsonArray("npcs")) {
            JsonObject n = e.getAsJsonObject(); JsonArray pos = n.getAsJsonArray("pos");
            String cls = n.get("cls").getAsString();
            double[] at = npcPos(n, o);
            Npcs.spawn(lvl, cls, at[0], at[1], at[2], n.get("yaw").getAsFloat(), "jcl_npc", "jcl_npc_" + cls, "jcl_spawn");
            npcs++;
        }
        for (JsonElement e : idx.getAsJsonArray("machines")) {
            JsonObject m = e.getAsJsonObject(); JsonArray pos = m.getAsJsonArray("pos");
            String kind = m.get("kind").getAsString();
            double x = o.getX() + pos.get(0).getAsInt() + 0.5, y = o.getY() + pos.get(1).getAsInt(), z = o.getZ() + pos.get(2).getAsInt() + 0.5;
            Interaction it = new Interaction(EntityType.INTERACTION, lvl);
            it.setPos(x, y - 1, z); it.addTag("jcl_spawn"); it.addTag("jcl_machine_" + kind);
            try { it.getClass().getMethod("setWidth", float.class).invoke(it, 1.4f); it.getClass().getMethod("setHeight", float.class).invoke(it, 3.0f); } catch (Throwable ignored) {}
            lvl.addFreshEntity(it);
            Holo.spawn(lvl, x, y + 2.3, z, Component.literal(kind.equals("gts") ? "§b§lGlobal Trade System" : "§d§lWonder Trade"), "jcl_spawn", 1.0f, true);
            Holo.spawn(lvl, x, y + 2.0, z, Component.literal("§7Right-click to open"), "jcl_spawn", 0.7f, true);
            npcs += 0;
        }
        // floating text panels (the museum's Frontier exhibit, for example)
        JsonObject mks = idx.getAsJsonObject("markers");
        for (String k : mks.keySet()) {
            if (mks.get(k).isJsonObject() && mks.getAsJsonObject(k).has("item")) {            // a museum display case: the real item, floating in its glass
                JsonArray a = mks.getAsJsonObject(k).getAsJsonArray("pos");
                float yaw = mks.getAsJsonObject(k).has("yaw") ? mks.getAsJsonObject(k).get("yaw").getAsFloat() : 0f;
                exhibit(lvl, o.getX() + a.get(0).getAsInt() + 0.5, o.getY() + a.get(1).getAsInt() + 0.5, o.getZ() + a.get(2).getAsInt() + 0.5, mks.getAsJsonObject(k).get("item").getAsString(), yaw);
                continue;
            }
            if (!mks.get(k).isJsonObject() || !mks.getAsJsonObject(k).has("holo")) continue;
            JsonArray a = mks.getAsJsonObject(k).getAsJsonArray("pos"), lines = mks.getAsJsonObject(k).getAsJsonArray("holo");
            double x = o.getX() + a.get(0).getAsInt() + 0.5, y = o.getY() + a.get(1).getAsInt() + 2.6, z = o.getZ() + a.get(2).getAsInt() + 0.5;
            for (int i = 0; i < lines.size(); i++) {
                // the first line is the building's title: large enough to read from across the street
                double ly = i == 0 ? y + 0.4 : y - 0.2 - (i - 1) * 0.3;
                Holo.spawn(lvl, x, ly, z, Component.literal(lines.get(i).getAsString()), "jcl_spawn", i == 0 ? 1.8f : 0.85f, true);
            }
        }
        return npcs;
    }

    /** An item shown in a display case: an item display entity, so it can be seen but never picked up. */
    static void exhibit(ServerLevel lvl, double x, double y, double z, String id, float yaw) {
        try {
            var item = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(net.minecraft.resources.ResourceLocation.parse(id));
            if (item == null || item == net.minecraft.world.item.Items.AIR) { GtsMod.LOG.warn("[JCL] Spawn: unknown exhibit item {}", id); return; }
            net.minecraft.world.entity.Display.ItemDisplay d = net.minecraft.world.entity.EntityType.ITEM_DISPLAY.create(lvl);
            if (d == null) return;
            d.setPos(x, y, z); d.addTag("jcl_spawn"); d.addTag("jcl_exhibit"); d.setNoGravity(true);
            d.setYRot(yaw);                                                   // faces visitors, like an item frame on the case's front
            setter(d, net.minecraft.world.entity.Display.ItemDisplay.class, "setItemStack", net.minecraft.world.item.ItemStack.class, new net.minecraft.world.item.ItemStack(item));
            setter(d, net.minecraft.world.entity.Display.ItemDisplay.class, "setItemTransform", net.minecraft.world.item.ItemDisplayContext.class, net.minecraft.world.item.ItemDisplayContext.FIXED);
            setter(d, net.minecraft.world.entity.Display.class, "setTransformation", com.mojang.math.Transformation.class,
                    new com.mojang.math.Transformation(null, null, new org.joml.Vector3f(0.6f, 0.6f, 0.6f), null));   // centred in the glass
            lvl.addFreshEntity(d);
        } catch (Exception ex) { GtsMod.LOG.warn("[JCL] Spawn: couldn't place exhibit {}: {}", id, ex.toString()); }
    }

    static void setter(Object target, Class<?> owner, String name, Class<?> type, Object value) {
        try { var m = owner.getDeclaredMethod(name, type); m.setAccessible(true); m.invoke(target, value); }
        catch (Exception ex) { GtsMod.LOG.debug("[JCL] {} unavailable: {}", name, ex.toString()); }
    }

    /** The town's floating text: old layout holograms (placed for the previous spawn) are removed, and the ones that
     *  still mean something are put on the new town's own spots. Building names come from the town's labels. */
    static final List<String> OLD_LAYOUT_HOLOS = List.of("pokecenter", "mart", "discord", "podium1", "podium2", "podium3", "ev", "ev_hp", "ev_atk", "ev_def",
            "ev_spa", "ev_spd", "ev_spe", "court", "harbour", "gateway", "welcome", "hall", "rules");

    static void townHolos(ServerLevel lvl, BlockPos o, JsonObject idx) {
        for (String id : OLD_LAYOUT_HOLOS) { try { Holos.delete(id); } catch (Exception ignored) {} }
        JsonObject mk = idx.getAsJsonObject("markers");
        java.util.function.BiConsumer<String, double[]> put = (preset, at) -> {
            String[] lines = Holos.PRESETS.get(preset);
            if (lines != null) Holos.put(preset, lvl, at[0], at[1], at[2], 1f, List.of(lines));
        };
        java.util.function.Function<String, double[]> marker = k -> {
            if (!mk.has(k)) return null; JsonArray a = mk.getAsJsonObject(k).getAsJsonArray("pos");
            return new double[]{o.getX() + a.get(0).getAsInt() + 0.5, o.getY() + a.get(1).getAsInt(), o.getZ() + a.get(2).getAsInt() + 0.5};
        };
        double[] arrival = marker.apply("arrival");
        if (arrival != null) {
            put.accept("welcome", new double[]{arrival[0], arrival[1] + 4.2, arrival[2] - 3});
            put.accept("discord", new double[]{arrival[0] - 9, arrival[1] + 3.2, arrival[2] - 3});
        }
        double[] rules = marker.apply("rules");
        if (rules != null) put.accept("rules", new double[]{rules[0], rules[1] + 3.2, rules[2]});
        for (int i = 1; i <= 3; i++) { double[] pd = marker.apply("podium_" + i); if (pd != null) put.accept("podium" + i, new double[]{pd[0], pd[1] + 2.9, pd[2]}); }
        for (JsonElement e : idx.getAsJsonArray("npcs")) {
            JsonObject n = e.getAsJsonObject(); String cls = n.get("cls").getAsString();
            if (!cls.startsWith("ev_")) continue;
            double[] at = npcPos(n, o);
            put.accept(cls, new double[]{at[0], at[1] + 2.6, at[2]});
        }
    }

    /** /jcl spawn warps: points every town warp at the built town (a few steps in front of each building's NPC). */
    static int setWarps(ServerPlayer p) {
        ServerLevel lvl = p.server.overworld();
        BlockPos o = savedOrigin(lvl);
        if (o == null) { p.sendSystemMessage(Component.literal("§cBuild the town first: there's no spawn here to point warps at.")); return 0; }
        JsonObject idx; try { idx = index(); } catch (Exception ex) { p.sendSystemMessage(Component.literal("§cCouldn't read the town index.")); return 0; }
        Map<String, JsonObject> npc = new HashMap<>();
        for (JsonElement e : idx.getAsJsonArray("npcs")) npc.putIfAbsent(e.getAsJsonObject().get("cls").getAsString(), e.getAsJsonObject());
        JsonObject mk = idx.getAsJsonObject("markers");
        // warp name, the NPC (or marker) it stands in front of, icon, description, display name
        String[][] W = {
            {"evs", "coach", "minecraft:experience_bottle", "Battle the EV trainers and train your team.", ""},
            {"crates", "crates", "jcl_gts:master_key", "Open crates with your keys.", ""},
            {"shop", "clerk", "cobblemon:poke_ball", "The Poké Mart: buy with /shop, sell with /sell.", ""},
            {"pokecenter", "nurse", "cobblemon:healing_machine", "Heal your team, trade on the GTS and Wonder Trade.", ""},
            {"hall", "archivist", "minecraft:totem_of_undying", "The Hall of Champions and this month's leaderboards.", ""},
            {"court", "#arena", "minecraft:iron_sword", "The Tournament Arena: challenge a friend!", ""},
            {"harbor", "captain", "minecraft:oak_boat", "The S.S. Seabreeze and Captain Marlo.", ""},
            {"lighthouse", "#lighthouse", "minecraft:lantern", "The Port Azure lighthouse.", "&e&l✦ &f&lLighthouse &e&l✦"},
            {"museum", "curator", "minecraft:bone_block", "Fossils, treasures and the whale skeleton.", "&3&l◆ &b&lOceanic Museum &3&l◆"},
            {"library", "librarian", "minecraft:book", "Shelves of books and quiet reading tables.", "&6&l◆ &e&lLibrary &6&l◆"},
            {"lab", "professor", "minecraft:spyglass", "Professor Cedar's research lab.", "&2&l◆ &a&lPokémon Lab &2&l◆"},
            {"contest", "contest_host", "minecraft:pink_dye", "The Contest Hall and its stage.", "&d&l◆ &f&lContest Hall &d&l◆"},
            {"market_west", "vendor_1", "minecraft:apple", "Fruit, flowers, berries, bakeries and Apricorns.", "&a&l◆ &f&lWest Market &a&l◆"},
            {"market_east", "vendor_19", "minecraft:cod", "Fish, medicine, souvenirs, gardening and adventurers.", "&b&l◆ &f&lEast Market &b&l◆"}};
        int set = 0; List<String> missing = new ArrayList<>();
        for (String[] w : W) {
            double x, y, z; float yaw;
            if (w[1].startsWith("#")) {
                JsonObject m = mk.has(w[1].substring(1)) ? mk.getAsJsonObject(w[1].substring(1)) : null;
                if (m == null) { missing.add(w[0]); continue; }
                JsonArray a = m.getAsJsonArray("pos");
                x = o.getX() + a.get(0).getAsInt() + 0.5; y = o.getY() + a.get(1).getAsInt(); z = o.getZ() + a.get(2).getAsInt() - 9.5; yaw = 0f;
            } else {
                JsonObject n = npc.get(w[1]);
                if (n == null) { missing.add(w[0]); continue; }
                double[] at = npcPos(n, o); float ny = n.has("yaw") ? n.get("yaw").getAsFloat() : 0f;
                double rad = Math.toRadians(ny);                                // a few steps in front of the NPC, facing them
                x = at[0] - Math.sin(rad) * 3; z = at[2] + Math.cos(rad) * 3;
                y = Math.floor(at[1]); yaw = ny + 180f;
            }
            Teleports.Warp old = Teleports.data.warps.get(w[0]);
            Teleports.Warp t = new Teleports.Warp();
            t.name = w[0]; t.dim = "minecraft:overworld"; t.x = x; t.y = y; t.z = z; t.yaw = yaw; t.pitch = 0;
            t.icon = old != null ? old.icon : w[2];
            t.description = old != null && !old.description.isBlank() ? old.description : w[3];
            t.display = old != null && !old.display.isBlank() ? old.display : (w[4].isEmpty() ? Teleports.DEFAULT_NAMES.getOrDefault(w[0], "") : w[4]);
            t.community = false;
            Teleports.data.warps.put(w[0], t); set++;
        }
        Teleports.save();
        p.sendSystemMessage(Component.literal("§6[Spawn] §aPointed " + set + " warps at the town." + (missing.isEmpty() ? "" : " §7(No spot for: " + String.join(", ", missing) + ".)") + " §7Check them in §f/warp§7."));
        return 1;
    }

    static void finishReport(ServerPlayer p, ServerLevel lvl, BlockPos o, JsonObject idx, int npcs) {
        JsonObject mk = idx.getAsJsonObject("markers");
        if (mk.has("arrival")) {
            JsonArray a = mk.getAsJsonObject("arrival").getAsJsonArray("pos");
            lvl.setDefaultSpawnPos(new BlockPos(o.getX() + a.get(0).getAsInt(), o.getY() + a.get(1).getAsInt(), o.getZ() + a.get(2).getAsInt()), 180f);
        }
        // podium statues follow the new podiums
        for (int i = 1; i <= 3; i++) {
            if (!mk.has("podium_" + i)) continue;
            JsonArray a = mk.getAsJsonObject("podium_" + i).getAsJsonArray("pos");
            Podium.SPOTS[i - 1] = new int[]{o.getX() + a.get(0).getAsInt(), o.getY() + a.get(1).getAsInt(), o.getZ() + a.get(2).getAsInt()};
        }
        Podium.saveSpots();
        // the three leaderboards go straight onto their stands in the Hall of Champions
        for (String b : List.of("caught", "trainers", "money")) {
            if (!mk.has("board_" + b)) continue;
            JsonArray a = mk.getAsJsonObject("board_" + b).getAsJsonArray("pos");
            try { Leaderboards.place(b, lvl, o.getX() + a.get(0).getAsInt() + 0.5, o.getY() + a.get(1).getAsInt() + 3, o.getZ() + a.get(2).getAsInt() + 0.5); }   // three blocks higher on its stand
            catch (Exception ex) { GtsMod.LOG.warn("[JCL] Spawn: couldn't place the {} leaderboard: {}", b, ex.toString()); }
        }
        StringBuilder where = new StringBuilder();
        JsonObject labels = mk.has("_labels") ? mk.getAsJsonObject("_labels") : new JsonObject();
        for (String k : mk.keySet()) {
            if (k.startsWith("_") || !mk.get(k).isJsonObject()) continue;
            JsonArray a = mk.getAsJsonObject(k).getAsJsonArray("pos");
            String label = labels.has(k) ? labels.get(k).getAsString() : k;
            where.append("\n§7 • ").append(label).append(": §f").append(o.getX() + a.get(0).getAsInt()).append(", ").append(o.getY() + a.get(1).getAsInt()).append(", ").append(o.getZ() + a.get(2).getAsInt());
        }
        final int nn = npcs;
        p.sendSystemMessage(Component.literal("§6[Spawn] §aDone in " + (System.currentTimeMillis() - started) / 1000 + "s. §7" + nn + " NPCs and the GTS and Wonder Trade machines are in place, the world spawn is set to the arrival point, and the podiums are updated." + "\n§fEvery spot in the new town (NPCs are already placed; put boards and holograms here):" + where));
    }

    @SubscribeEvent
    public static void onTick(ServerTickEvent.Post e) {
        if (jobs.isEmpty()) return;
        long t0 = System.nanoTime();
        while (!jobs.isEmpty() && System.nanoTime() - t0 < 35_000_000L) {   // up to ~35 ms of work per tick
            Runnable r = jobs.poll();
            try { r.run(); } catch (Throwable t) { GtsMod.LOG.error("[JCL] Spawn build step failed", t); }
        }
        if (builder != null && !jobs.isEmpty() && e.getServer().getTickCount() % 100 == 0)
            builder.sendSystemMessage(Component.literal("§7[Spawn] " + jobs.size() + " steps left..."));
    }

    /** GTS and Wonder Trade machines: right-click opens the service. */
    @SubscribeEvent
    public static void onInteract(PlayerInteractEvent.EntityInteract e) {
        if (!(e.getEntity() instanceof ServerPlayer p) || !(e.getTarget() instanceof Interaction it)) return;
        var tags = it.getTags();
        String cmd = tags.contains("jcl_machine_gts") ? "gts" : tags.contains("jcl_machine_wondertrade") ? "wondertrade" : null;
        if (cmd == null) return;
        e.setCanceled(true);
        if (e.getHand() != net.minecraft.world.InteractionHand.MAIN_HAND) return;
        p.getServer().getCommands().performPrefixedCommand(p.createCommandSourceStack(), cmd);
    }

    @SubscribeEvent
    public static void onCommands(RegisterCommandsEvent e) {
        var warn = Component.literal("§6[Spawn] §fThis shapes a whole island (721 × 731 blocks, down to 46 below you) around the spot you stand on, which becomes the centre of the plaza. The area is backed up first, so §e/jcl spawn remove confirm§f can undo it. Run §e/jcl spawn build confirm§f to start.");
        e.getDispatcher().register(Commands.literal("jcl").requires(s -> s.hasPermission(4))
                .then(Commands.literal("build").then(Commands.literal("spawn")
                        .executes(c -> { c.getSource().sendSuccess(() -> warn, false); return 1; })
                        .then(Commands.literal("confirm").executes(c -> { try { start(c.getSource().getPlayerOrException()); } catch (Exception ex) { c.getSource().sendFailure(Component.literal("Spawn build failed: " + ex.getMessage())); } return 1; }))))
                .then(Commands.literal("npcs").then(Commands.literal("purge").executes(c -> { c.getSource().sendSuccess(() -> Component.literal("§6[NPCs] §fThis removes §cevery§f JCL NPC, leaderboard, floating text and machine in the game (town, old town area, Frontier hub). Run §e/jcl npcs purge confirm§f, then §e/jcl spawn npcs§f and §e/frontier build hub§f to put clean copies back."), false); return 1; })
                        .then(Commands.literal("confirm").executes(c -> { purgeAll(c.getSource().getPlayerOrException()); return 1; }))))
                .then(Commands.literal("oldspawn")
                        .then(Commands.literal("remove").executes(c -> { c.getSource().sendSuccess(() -> Component.literal("§6[Old spawn] §fThis clears the old Port Azure (360 × 360 around -13 76 202, dock to the south) down to open water. It's backed up first. Run §e/jcl oldspawn remove confirm§f."), false); return 1; })
                                .then(Commands.literal("confirm").executes(c -> { removeOldSpawn(c.getSource().getPlayerOrException()); return 1; })))
                        .then(Commands.literal("restore").then(Commands.literal("confirm").executes(c -> {
                            try { restoreOldSpawn(c.getSource().getPlayerOrException()); } catch (Exception ex) { c.getSource().sendFailure(Component.literal("Restore failed: " + ex.getMessage())); } return 1; }))))
                .then(Commands.literal("spawn")
                        .then(Commands.literal("build").executes(c -> { c.getSource().sendSuccess(() -> warn, false); return 1; })
                                .then(Commands.literal("confirm").executes(c -> { try { start(c.getSource().getPlayerOrException()); } catch (Exception ex) { c.getSource().sendFailure(Component.literal("Spawn build failed: " + ex.getMessage())); } return 1; })))
                        .then(Commands.literal("warps").executes(c -> setWarps(c.getSource().getPlayerOrException())))
                        .then(Commands.literal("remove").executes(c -> { c.getSource().sendSuccess(() -> Component.literal("§6[Spawn] §fThis puts the whole area back exactly as it was before the build and removes all the town's NPCs. Run §e/jcl spawn remove confirm§f."), false); return 1; })
                                .then(Commands.literal("confirm").executes(c -> { try { remove(c.getSource().getPlayerOrException()); } catch (Exception ex) { c.getSource().sendFailure(Component.literal("Remove failed: " + ex.getMessage())); } return 1; })))
                        .then(Commands.literal("npcs").executes(c -> {
                            ServerPlayer p = c.getSource().getPlayerOrException(); BlockPos o = savedOrigin(p.serverLevel());
                            if (o == null) { c.getSource().sendFailure(Component.literal("No spawn build found.")); return 0; }
                            try { clearPeople(p.serverLevel(), o); int n = spawnPeople(p.serverLevel(), o, index()); c.getSource().sendSuccess(() -> Component.literal("§a[Spawn] Respawned " + n + " town NPCs and the machines."), true); }
                            catch (Exception ex) { c.getSource().sendFailure(Component.literal("Failed: " + ex.getMessage())); }
                            return 1; }))
                        .then(Commands.literal("clearnpcs").executes(c -> {
                            ServerPlayer p = c.getSource().getPlayerOrException(); BlockPos o = savedOrigin(p.serverLevel());
                            if (o == null) { c.getSource().sendFailure(Component.literal("No spawn build found.")); return 0; }
                            int n = clearPeople(p.serverLevel(), o);
                            c.getSource().sendSuccess(() -> Component.literal("§7[Spawn] Removed " + n + " town NPCs, machines and text."), true); return 1; }))));
    }
}
