package net.joeyscobblemon.gts;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.GZIPInputStream;

/**
 * The Frontier's themed rooms. Each design ships in the jar as a small data file (three variations per room) and is
 * built block by block in the Frontier dimension the moment that room comes up in a run.
 */
public final class FrontierRooms {
    private FrontierRooms() {}

    record Design(int w, int h, int d, int x0, int y0, int z0, BlockState[] palette, byte[] data, int[] spawn, int[] trainer, Map<String, int[]> markers) {}

    static final Map<String, Design> CACHE = new HashMap<>();
    static Map<String, Integer> variants;
    static final Random RNG = new Random();

    /** Which design a room uses. Types without their own design yet borrow the closest one. */
    /** Set by /frontier debug next: the exact design and door side (0 centre, 1 left, 2 right) for the next room built. */
    static String FORCE_KEY = null;
    static int FORCE_SIDE = -1;

    static String designFor(String room, String type) {
        switch (room) {
            case "type" -> {
                String own = "type_" + (type == null ? "" : type);
                return variants().containsKey(own) ? own : "battle";
            }
            case "doubles" -> { return "doubles"; }
            case "elite" -> { return "elite"; }
            case "champion" -> { return "champion"; }
            case "brain" -> { return "brain"; }
            case "loot" -> { return "loot"; }
            case "heal", "clover" -> { return "heal"; }
            case "shrine" -> { return "shrine"; }
            case "merchant" -> { return "merchant"; }
            case "gamble", "luckyroll" -> { return "gamble"; }
            case "cursed" -> { return "cursed"; }
            default -> {
                // the basic battle room: any of the six arenas, so it never gets old
                String[] arenas = {"battle", "battle_dojo", "battle_garden", "battle_harbour", "battle_court", "battle_ruins"};
                List<String> have = new ArrayList<>();
                for (String a : arenas) if (variants().containsKey(a)) have.add(a);
                return have.isEmpty() ? "battle" : have.get(RNG.nextInt(have.size()));
            }
        }
    }

    /** Wall, ceiling and light blocks for each room, so every room is a closed chamber that matches its theme. */
    static String[] shellFor(String key) {
        return switch (key) {
            case "type_fire" -> new String[]{"minecraft:polished_basalt", "minecraft:blackstone", "minecraft:shroomlight"};
            case "type_water" -> new String[]{"minecraft:dark_prismarine", "minecraft:prismarine_bricks", "minecraft:sea_lantern"};
            case "type_grass", "type_bug" -> new String[]{"minecraft:mossy_stone_bricks", "minecraft:oak_leaves[persistent=true]", "minecraft:ochre_froglight"};
            case "type_fairy" -> new String[]{"minecraft:cherry_planks", "minecraft:cherry_leaves[persistent=true]", "minecraft:pearlescent_froglight"};
            case "type_ghost", "type_dark", "cursed" -> new String[]{"minecraft:deepslate_bricks", "minecraft:deepslate_tiles", "minecraft:soul_lantern[hanging=true]"};
            case "type_dragon", "type_psychic" -> new String[]{"minecraft:obsidian", "minecraft:crying_obsidian", "minecraft:end_rod"};
            case "type_ice" -> new String[]{"minecraft:packed_ice", "minecraft:blue_ice", "minecraft:sea_lantern"};
            case "type_electric", "type_steel" -> new String[]{"create:andesite_casing|minecraft:polished_andesite", "minecraft:iron_block", "minecraft:redstone_lamp[lit=true]"};
            case "type_ground", "type_rock" -> new String[]{"minecraft:smooth_sandstone", "minecraft:cut_sandstone", "minecraft:lantern[hanging=true]"};
            case "type_poison" -> new String[]{"minecraft:mud_bricks", "minecraft:packed_mud", "minecraft:verdant_froglight"};
            case "type_flying" -> new String[]{"minecraft:calcite", "minecraft:white_concrete", "minecraft:sea_lantern"};
            case "type_fighting" -> new String[]{"minecraft:dark_oak_planks", "minecraft:spruce_planks", "minecraft:lantern[hanging=true]"};
            case "champion" -> new String[]{"minecraft:quartz_bricks", "minecraft:smooth_quartz", "minecraft:glowstone"};
            case "brain", "elite" -> new String[]{"minecraft:polished_blackstone_bricks", "minecraft:obsidian", "minecraft:soul_lantern[hanging=true]"};
            case "loot" -> new String[]{"minecraft:deepslate_bricks", "minecraft:deepslate_tiles", "minecraft:lantern[hanging=true]"};
            case "heal" -> new String[]{"minecraft:calcite", "minecraft:dripstone_block", "minecraft:sea_lantern"};
            case "gamble" -> new String[]{"minecraft:black_concrete", "minecraft:black_concrete", "minecraft:redstone_lamp[lit=true]"};
            case "shrine", "merchant" -> new String[]{"minecraft:dark_oak_planks", "minecraft:spruce_planks", "minecraft:lantern[hanging=true]"};
            default -> new String[]{"minecraft:stone_bricks", "minecraft:deepslate_tiles", "minecraft:lantern[hanging=true]"};
        };
    }

    static BlockState state(ServerLevel lvl, String s) {
        var blocks = lvl.registryAccess().lookupOrThrow(Registries.BLOCK);
        for (String opt : s.split("\\|")) {
            try { return BlockStateParser.parseForBlock(blocks, opt, false).blockState(); } catch (Exception ignored) {}
        }
        return Blocks.STONE_BRICKS.defaultBlockState();
    }

    static Map<String, Integer> variants() {
        if (variants == null) {
            variants = new HashMap<>();
            try (InputStream in = FrontierRooms.class.getResourceAsStream("/jcl/frontier_rooms/index.json")) {
                if (in != null) {
                    JsonObject o = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
                    for (var e : o.entrySet()) variants.put(e.getKey(), e.getValue().getAsInt());
                }
            } catch (Exception e) { GtsMod.LOG.error("[JCL] Could not read the Frontier room list", e); }
        }
        return variants;
    }

    static Design load(ServerLevel lvl, String file) {
        return CACHE.computeIfAbsent(file, f -> {
            try (InputStream raw = DesignSave.open("/jcl/frontier_rooms/" + f + ".bin", DesignSave.ROOMS.resolve(f + ".bin"))) {
                if (raw == null) return null;
                byte[] all;
                try (GZIPInputStream in = new GZIPInputStream(raw)) { all = in.readAllBytes(); }
                int nl = 0; while (all[nl] != '\n') nl++;
                JsonObject h = JsonParser.parseString(new String(all, 0, nl, StandardCharsets.UTF_8)).getAsJsonObject();
                var pal = h.getAsJsonArray("palette");
                BlockState[] states = new BlockState[pal.size()];
                var blocks = lvl.registryAccess().lookupOrThrow(Registries.BLOCK);
                for (int i = 0; i < pal.size(); i++) {
                    String s = pal.get(i).getAsString();
                    BlockState st = null;
                    if (!s.equals("air")) for (String opt : s.split("\\|")) {
                        try { st = BlockStateParser.parseForBlock(blocks, opt, false).blockState(); break; } catch (Exception ignored) {}
                    }
                    states[i] = st == null ? Blocks.AIR.defaultBlockState() : st;
                }
                byte[] data = Arrays.copyOfRange(all, nl + 1, all.length);
                int[] sp = arr(h.getAsJsonArray("spawn")), tr = arr(h.getAsJsonArray("trainer"));
                Map<String, int[]> mk = new HashMap<>();
                if (h.has("markers")) for (var en : h.getAsJsonObject("markers").entrySet()) mk.put(en.getKey(), arr(en.getValue().getAsJsonArray()));
                return new Design(h.get("w").getAsInt(), h.get("h").getAsInt(), h.get("d").getAsInt(), h.get("x0").getAsInt(), h.get("y0").getAsInt(), h.get("z0").getAsInt(), states, data, sp, tr, mk);
            } catch (Exception e) { GtsMod.LOG.error("[JCL] Could not load Frontier room {}", f, e); return null; }
        });
    }

    /** Clears everything inside a design's footprint (plus a margin), so rebuilding never leaves old blocks behind. */
    static void clearArea(ServerLevel lvl, BlockPos o, String file, int margin) {
        Design d = load(lvl, file);
        if (d == null) return;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        BlockState air = Blocks.AIR.defaultBlockState();
        for (int x = d.x0 - margin; x < d.x0 + d.w + margin; x++) for (int z = d.z0 - margin; z < d.z0 + d.d + margin; z++)
            for (int y = d.y0 - 4; y < d.y0 + d.h + margin; y++) {
                m.set(o.getX() + x, o.getY() + y, o.getZ() + z);
                if (!lvl.getBlockState(m).isAir()) lvl.setBlock(m, air, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
            }
    }

    /** Named spots saved with a design (the hub's NPC, board and arrival spots). */
    static Map<String, int[]> markers(ServerLevel lvl, String file) { Design d = load(lvl, file); return d == null ? Map.of() : d.markers; }

    static int[] arr(com.google.gson.JsonArray a) { return new int[]{a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt()}; }

    /**
     * Builds a themed room at the plot origin. Returns {playerSpot, trainerSpot}, or null if no design could be built.
     * Liquids are sealed in with invisible barriers so lava and water never flow out of the room.
     */
    static BlockPos[] build(ServerLevel lvl, BlockPos o, String room, String type) {
        String key = FORCE_KEY != null ? FORCE_KEY : designFor(room, type);      // /frontier debug next can pick the exact design
        FORCE_KEY = null;
        int n = variants().getOrDefault(key, 0);
        if (n <= 0) return null;
        // every room comes pre-built and sealed, with its doorway on the front, left or right wall
        String[] sides = {"f", "l", "r"};
        int sideIdx = FORCE_SIDE >= 0 ? FORCE_SIDE : RNG.nextInt(3);
        FORCE_SIDE = -1;
        String file = key + "_" + RNG.nextInt(n) + "_" + sides[sideIdx];
        BlockPos[] spots = buildFile(lvl, o, file);
        if (spots == null) return null;
        Design d = CACHE.get(file);
        LAST_SIDE = sideIdx;
        barrierShell(lvl, o, d);
        int[] door = d.markers.get("door");
        return door == null ? spots : new BlockPos[]{spots[0], spots[1], o.offset(door[0], door[1], door[2])};
    }

    /** An invisible barrier box one block outside the sealed room, so nobody can ever leave it. */
    static void barrierShell(ServerLevel lvl, BlockPos o, Design d) {
        BlockState barrier = Blocks.BARRIER.defaultBlockState();
        int flags = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
        int bx0 = o.getX() + d.x0 - 1, bx1 = o.getX() + d.x0 + d.w, bz0 = o.getZ() + d.z0 - 1, bz1 = o.getZ() + d.z0 + d.d;
        int by0 = o.getY() + d.y0 - 1, by1 = o.getY() + d.y0 + d.h;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int x = bx0; x <= bx1; x++) for (int z = bz0; z <= bz1; z++) for (int y = by0; y <= by1; y++) {
            boolean edge = x == bx0 || x == bx1 || z == bz0 || z == bz1 || y == by0 || y == by1;
            if (!edge) continue;
            m.set(x, y, z);
            if (lvl.getBlockState(m).isAir()) lvl.setBlock(m, barrier, flags);
        }
    }

    static String LAST;

    /** Repaints the biome over an area to Battle Frontier (so the minimap shows its name, not "The Void"). */
    static void paintBiome(ServerLevel lvl, int x0, int z0, int x1, int z1) {
        try {
            var reg = lvl.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.BIOME);
            var key = net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.BIOME, net.minecraft.resources.ResourceLocation.parse("jcl_gts:battle_frontier"));
            var holder = reg.getHolder(key).orElse(null);
            if (holder == null) return;
            List<net.minecraft.world.level.chunk.ChunkAccess> changed = new ArrayList<>();
            for (int cx = x0 >> 4; cx <= x1 >> 4; cx++) for (int cz = z0 >> 4; cz <= z1 >> 4; cz++) {
                var ch = lvl.getChunk(cx, cz);
                ch.fillBiomesFromNoise((x, y, z, s) -> holder, lvl.getChunkSource().randomState().sampler());
                ch.setUnsaved(true);
                changed.add(ch);
            }
            lvl.getChunkSource().chunkMap.resendBiomesForChunks(changed);
        } catch (Throwable t) { GtsMod.LOG.warn("[JCL] Could not set the Frontier biome: {}", t.toString()); }
    }

    /**
     * Seals a room into a chamber: themed walls on all four sides and a lit ceiling, then an invisible barrier shell
     * one block further out so nobody can leave. The east wall gets a dark doorway behind the trainer: that's where
     * the run continues. Returns the doorway's centre (at floor level).
     */
    static BlockPos enclose(ServerLevel lvl, BlockPos o, Design d, String key, BlockPos trainer) {
        String[] sh = shellFor(key);
        BlockState wall = state(lvl, sh[0]), ceil = state(lvl, sh[1]), light = state(lvl, sh[2]);
        BlockState barrier = Blocks.BARRIER.defaultBlockState(), dark = Blocks.BLACK_CONCRETE.defaultBlockState(), frame = Blocks.POLISHED_BLACKSTONE_BRICKS.defaultBlockState();
        int flags = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
        int x0 = o.getX() + d.x0 - 1, x1 = o.getX() + d.x0 + d.w, z0 = o.getZ() + d.z0 - 1, z1 = o.getZ() + d.z0 + d.d;
        int yb = o.getY() + Math.min(d.y0, 0) - 1, yt = o.getY() + Math.max(d.y0 + d.h + 2, 12);
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) {
            boolean edge = x == x0 || x == x1 || z == z0 || z == z1;
            // ceiling, with a light every 6 blocks
            m.set(x, yt, z);
            boolean lamp = !edge && Math.floorMod(x - x0, 6) == 3 && Math.floorMod(z - z0, 6) == 3;
            lvl.setBlock(m, ceil, flags);
            if (lamp) { m.set(x, yt - 1, z); if (lvl.getBlockState(m).isAir()) lvl.setBlock(m, light, flags); }
            // the floor under the whole room, so nothing is open below
            m.set(x, yb, z); if (lvl.getBlockState(m).isAir()) lvl.setBlock(m, wall, flags);
            if (!edge) continue;
            for (int y = yb; y < yt; y++) { m.set(x, y, z); lvl.setBlock(m, wall, flags); }
        }
        // the barrier shell, one block outside the walls (sides, top and bottom)
        for (int x = x0 - 1; x <= x1 + 1; x++) for (int z = z0 - 1; z <= z1 + 1; z++) {
            boolean edge = x == x0 - 1 || x == x1 + 1 || z == z0 - 1 || z == z1 + 1;
            m.set(x, yt + 1, z); lvl.setBlock(m, barrier, flags);
            m.set(x, yb - 1, z); lvl.setBlock(m, barrier, flags);
            if (edge) for (int y = yb - 1; y <= yt + 1; y++) { m.set(x, y, z); lvl.setBlock(m, barrier, flags); }
        }
        // invisible light all through the room, so nothing is gloomy
        BlockState lit = state(lvl, "minecraft:light[level=15]");
        for (int x = x0 + 2; x < x1 - 1; x += 5) for (int z = z0 + 2; z < z1 - 1; z += 5)
            for (int y = o.getY() + 3; y < yt - 1; y += 5) { m.set(x, y, z); if (lvl.getBlockState(m).isAir()) lvl.setBlock(m, lit, flags); }
        // the doorway: in the far wall (front), or the left or right wall, picked at random, like a labyrinth
        int fy = trainer.getY(), tx = trainer.getX(), tz = trainer.getZ();
        // Where does the doorway go? Every spot along the front, left and right walls is scored by how much of the
        // room's own decoration it (and the path to it) would cut through, and the cleanest one wins: pillars,
        // stands, trees and machines stay whole, and the door sits where the room has open floor.
        int bestSide = 0, bestC = tz; double bestScore = Double.MAX_VALUE;
        for (int side = 0; side < 3; side++) {
            int lo = side == 0 ? z0 + 4 : x0 + 4, hi = side == 0 ? z1 - 4 : x1 - 4;
            for (int c = lo; c <= hi; c++) {
                double s = doorScore(lvl, m, side, c, x0, x1, z0, z1, tx, tz, fy) + RNG.nextDouble() * 0.5;
                if (side == 0) s += Math.abs(c - tz) * 0.02; else s += Math.abs(c - tx) * 0.02;    // mild preference for facing the player's way out
                if (s < bestScore) { bestScore = s; bestSide = side; bestC = c; }
            }
        }
        final int side = bestSide, dc = bestC;
        LAST_SIDE = side;
        BlockPos door = side == 0 ? new BlockPos(x1, fy, dc) : side == 1 ? new BlockPos(dc, fy, z0) : new BlockPos(dc, fy, z1);
        BlockState path = state(lvl, sh[0]);
        // a clear 3-wide way to the door: only where something would actually block it, with floor where there's none
        for (int[] pt : pathTo(side, dc, x1, z0, z1, tx, tz))
            for (int k = -1; k <= 1; k++) clearAndFloor(lvl, m, pt[0] + (pt[2] == 1 ? 0 : k), fy, pt[1] + (pt[2] == 1 ? k : 0), path, flags);
        // the doorway, built from the room's own stone: a framed arch around a dark opening, lit either side
        BlockState trim = ceil, edge = wall;
        for (int u = -3; u <= 3; u++) for (int y = fy - 1; y <= fy + 5; y++) {
            if (side == 0) m.set(x1, y, dc + u); else m.set(dc + u, y, door.getZ());
            int au = Math.abs(u);
            boolean inside = au <= 1 && y >= fy && y <= fy + 3;
            boolean archTop = y == fy + 4 && au <= 1;
            boolean frameCol = au == 2 && y <= fy + 4;
            boolean lintel = y == fy + 5 && au <= 2;
            if (inside) lvl.setBlock(m, dark, flags);
            else if (archTop || frameCol || lintel) lvl.setBlock(m, trim, flags);
            else if (au == 3 && y <= fy + 3) lvl.setBlock(m, edge, flags);
        }
        for (int u = -1; u <= 1; u++) {
            if (side == 0) m.set(x1 - 1, fy - 1, dc + u); else m.set(dc + u, fy - 1, side == 1 ? z0 + 1 : z1 - 1);
            lvl.setBlock(m, trim, flags);
        }
        for (int u : new int[]{-3, 3}) {
            if (side == 0) m.set(x1 - 1, fy + 2, dc + u); else m.set(dc + u, fy + 2, side == 1 ? z0 + 1 : z1 - 1);
            if (lvl.getBlockState(m).isAir()) lvl.setBlock(m, light, flags);
        }
        return door;
    }

    static int LAST_SIDE;

    /** The route from the trainer to a door: along the room's length first, then across to the wall. {x, z, axis} (axis 0 = moving in x). */
    static List<int[]> pathTo(int side, int dc, int x1, int z0, int z1, int tx, int tz) {
        List<int[]> pts = new ArrayList<>();
        if (side == 0) {
            int x = tx + 1;
            for (; x < x1 - 2; x++) pts.add(new int[]{x, tz, 0});
            int step = dc >= tz ? 1 : -1;
            for (int z = tz; z != dc + step; z += step) pts.add(new int[]{x1 - 2, z, 1});
            for (int xx = x1 - 2; xx < x1; xx++) pts.add(new int[]{xx, dc, 0});
        } else {
            int step = dc >= tx ? 1 : -1;
            for (int x = tx; x != dc + step; x += step) pts.add(new int[]{x, tz, 0});
            int zs = side == 1 ? -1 : 1, end = side == 1 ? z0 : z1;
            for (int z = tz + zs; z != end; z += zs) pts.add(new int[]{dc, z, 1});
        }
        return pts;
    }

    /** How much decoration a doorway at this spot (and the walk to it) would destroy. Lower is better. */
    static double doorScore(ServerLevel lvl, BlockPos.MutableBlockPos m, int side, int dc, int x0, int x1, int z0, int z1, int tx, int tz, int fy) {
        double s = 0;
        // the opening, its frame, and the three blocks in front of it
        for (int u = -3; u <= 3; u++) for (int y = fy; y <= fy + 5; y++) for (int d = 1; d <= 3; d++) {
            if (side == 0) m.set(x1 - d, y, dc + u); else m.set(dc + u, y, side == 1 ? z0 + d : z1 - d);
            if (!lvl.getBlockState(m).isAir()) s += d == 1 ? 3 : 2;
        }
        // the path: blocks that would have to be cleared, and gaps (lava, water, void) that need bridging
        for (int[] pt : pathTo(side, dc, x1, z0, z1, tx, tz)) for (int k = -1; k <= 1; k++) {
            int x = pt[0] + (pt[2] == 1 ? 0 : k), z = pt[1] + (pt[2] == 1 ? k : 0);
            for (int y = fy; y <= fy + 3; y++) { m.set(x, y, z); if (!lvl.getBlockState(m).isAir() && lvl.getFluidState(m).isEmpty()) s += 1; }
            m.set(x, fy - 1, z); var st = lvl.getBlockState(m); if (st.isAir() || !st.getFluidState().isEmpty()) s += 0.3;
        }
        return s;
    }

    static void clearAndFloor(ServerLevel lvl, BlockPos.MutableBlockPos m, int x, int fy, int z, BlockState floor, int flags) {
        for (int y = fy; y <= fy + 3; y++) { m.set(x, y, z); if (!lvl.getBlockState(m).isAir()) lvl.setBlock(m, Blocks.AIR.defaultBlockState(), flags); }
        m.set(x, fy - 1, z);
        var st = lvl.getBlockState(m);
        if (st.isAir() || !st.getFluidState().isEmpty() || st.canBeReplaced()) lvl.setBlock(m, floor, flags);
    }

    /** Builds one exact design and variation (used by /frontier debug build). */
    static BlockPos[] buildDesign(ServerLevel lvl, BlockPos o, String key, int variant) {
        if (!key.equals("hub")) { BlockPos[] s = buildFile(lvl, o, key + "_" + variant + "_f"); if (s != null) { barrierShell(lvl, o, CACHE.get(key + "_" + variant + "_f")); return s; } }
        return buildFile(lvl, o, key + "_" + variant);
    }

    static BlockPos[] buildFile(ServerLevel lvl, BlockPos o, String file) {
        LAST = file;
        Design d = load(lvl, file);
        if (d == null) return null;
        int flags = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        List<BlockPos> liquids = new ArrayList<>();
        for (int x = 0; x < d.w; x++) for (int y = 0; y < d.h; y++) for (int z = 0; z < d.d; z++) {
            int idx = d.data[(x * d.h + y) * d.d + z] & 0xFF;
            if (idx == 0) continue;
            BlockState st = d.palette[idx];
            if (st.isAir()) continue;
            m.set(o.getX() + d.x0 + x, o.getY() + d.y0 + y, o.getZ() + d.z0 + z);
            lvl.setBlock(m, st, flags);
            if (!st.getFluidState().isEmpty()) liquids.add(m.immutable());
        }
        for (BlockPos p : liquids) {
            if (p.getY() - o.getY() >= 1) {
                // above the floor (fountain tops, falls): left free and told to flow; each design catches its own flow
                var fs = lvl.getBlockState(p).getFluidState();
                lvl.scheduleTick(p, fs.getType(), 2);
                continue;
            }
            // lakes and pools at or below the floor stay perfectly still behind invisible barriers
            for (Direction dir : new Direction[]{Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
                BlockPos q = p.relative(dir);
                if (lvl.getBlockState(q).isAir()) lvl.setBlock(q, Blocks.BARRIER.defaultBlockState(), flags);
            }
        }
        paintBiome(lvl, o.getX() + d.x0 - 2, o.getZ() + d.z0 - 2, o.getX() + d.x0 + d.w + 2, o.getZ() + d.z0 + d.d + 2);
        BlockPos player = o.offset(d.spawn[0], d.spawn[1] + 1, d.spawn[2]);
        BlockPos trainer = o.offset(d.trainer[0], d.trainer[1] + 1, d.trainer[2]);
        return new BlockPos[]{player, trainer};
    }
}
