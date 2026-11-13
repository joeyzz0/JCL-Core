package net.joeyscobblemon.gts;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.phys.AABB;
import com.google.gson.*;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/**
 * Saving in-game edits as the new design. Rooms, the Frontier hub and the town are built from files inside the mod;
 * a saved copy in config/jcl/ loads first, so an edit made in game becomes how it generates from then on, and
 * deleting the copy (the revert commands) brings back the original.
 */
@EventBusSubscriber(modid = GtsMod.MODID)
public final class DesignSave {
    private DesignSave() {}

    static final Path ROOMS = Paths.get("config", "jcl", "frontier_rooms"), TOWN = Paths.get("config", "jcl", "spawn_city");
    /** Friendly names for the battle rooms (shown in /frontier debug info, accepted by /frontier debug next). */
    static final Map<String, String> BATTLE = new LinkedHashMap<>(Map.of(
            "battle_arena", "battle", "battle_dojo", "battle_dojo", "battle_garden", "battle_garden",
            "battle_port", "battle_harbour", "battle_court", "battle_court", "battle_ruins", "battle_ruins"));
    static String friendly(String key) { for (var en : BATTLE.entrySet()) if (en.getValue().equals(key)) return en.getKey(); return key; }

    /** A design file: the saved copy in config/jcl/ if there is one, otherwise the mod's own. */
    static InputStream open(String resource, Path override) throws IOException {
        if (override != null && Files.exists(override)) return Files.newInputStream(override);
        return DesignSave.class.getResourceAsStream(resource);
    }

    static String header(String resource) throws IOException {
        try (InputStream raw = DesignSave.class.getResourceAsStream(resource); GZIPInputStream in = new GZIPInputStream(raw)) {
            ByteArrayOutputStream b = new ByteArrayOutputStream(); int c;
            while ((c = in.read()) != -1 && c != '\n') b.write(c);
            return b.toString(StandardCharsets.UTF_8);
        }
    }
    static boolean skip(BlockState st) { return st.isAir() || st.is(Blocks.BARRIER) || st.is(Blocks.LIGHT); }

    // ------------------------------------------------------------------ Frontier rooms and the hub

    /** Reads a built room back from the world and saves it as `files` (same footprint as the design). */
    static String saveRoom(ServerLevel lvl, BlockPos o, String file, List<String> files) throws IOException {
        FrontierRooms.Design d = FrontierRooms.load(lvl, file);
        if (d == null) return "§cThat room's design couldn't be read.";
        List<String> pal = new ArrayList<>(List.of("air")); Map<BlockState, Integer> idx = new HashMap<>();
        byte[] data = new byte[d.w() * d.h() * d.d()];
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int x = 0; x < d.w(); x++) for (int y = 0; y < d.h(); y++) for (int z = 0; z < d.d(); z++) {
            BlockState st = lvl.getBlockState(m.set(o.getX() + d.x0() + x, o.getY() + d.y0() + y, o.getZ() + d.z0() + z));
            if (skip(st)) continue;
            Integer i = idx.get(st);
            if (i == null) { i = pal.size(); if (i > 255) return "§cToo many different blocks in this room to save (over 255)."; pal.add(BlockStateParser.serialize(st)); idx.put(st, i); }
            data[(x * d.h() + y) * d.d() + z] = (byte) (int) i;
        }
        JsonObject h = JsonParser.parseString(header("/jcl/frontier_rooms/" + file + ".bin")).getAsJsonObject();
        JsonArray pa = new JsonArray(); for (String s : pal) pa.add(s); h.add("palette", pa);
        Files.createDirectories(ROOMS);
        for (String f : files) {
            try (GZIPOutputStream out = new GZIPOutputStream(Files.newOutputStream(ROOMS.resolve(f + ".bin")))) {
                out.write((h.toString() + "\n").getBytes(StandardCharsets.UTF_8)); out.write(data);
            }
            FrontierRooms.CACHE.remove(f);
        }
        return null;
    }

    static int saveCurrentRoom(ServerPlayer p) {
        Frontier.Run r = Frontier.RUNS.get(p.getUUID());
        String file = FrontierRooms.LAST;
        if (r == null || file == null || file.startsWith("hub")) { Frontier.msg(p, "§cStand in a Frontier room during a run first (use §f/frontier debug next§c to pick one)."); return 0; }
        String[] parts = file.split("_");                      // key..._variant_side
        String side = parts[parts.length - 1], key = file.substring(0, file.lastIndexOf('_', file.lastIndexOf('_') - 1));
        int n = FrontierRooms.variants().getOrDefault(key, 1);
        List<String> files = new ArrayList<>(); for (int v = 0; v < n; v++) files.add(key + "_" + v + "_" + side);
        try {
            String err = saveRoom(p.serverLevel(), Frontier.origin(r), file, files);
            if (err != null) { Frontier.msg(p, err); return 0; }
        } catch (Exception ex) { Frontier.msg(p, "§cCouldn't save: " + ex.getMessage()); return 0; }
        String sideName = side.equals("l") ? "left" : side.equals("r") ? "right" : "center";
        Frontier.msg(p, "§a[Debug] Saved §f" + friendly(key) + " §a(" + sideName + " door). §7It generates like this from now on. "
                + "For the other doors: §f/frontier debug next " + friendly(key) + " <left|right|center>§7, edit, then save again. Undo with §f/frontier debug revert " + friendly(key) + "§7.");
        return 1;
    }

    static int saveHub(ServerPlayer p) {
        try {
            String err = saveRoom(Frontier.frontier(), FrontierGui.HUB, "hub_0", List.of("hub_0"));
            if (err != null) { Frontier.msg(p, err); return 0; }
        } catch (Exception ex) { Frontier.msg(p, "§cCouldn't save the hub: " + ex.getMessage()); return 0; }
        Frontier.msg(p, "§a[Debug] Saved the Frontier hub. §7/frontier build hub now builds it like this. Undo with §f/frontier debug revert hub§7.");
        return 1;
    }

    static int revertRoom(ServerPlayer p, String name) {
        String key = name.equals("hub") ? "hub" : BATTLE.getOrDefault(name, name);
        int n = 0;
        try (var s = Files.exists(ROOMS) ? Files.list(ROOMS) : java.util.stream.Stream.<Path>empty()) {
            for (Path f : s.toList()) {
                String fn = f.getFileName().toString();
                if (fn.startsWith(key + "_")) { Files.delete(f); FrontierRooms.CACHE.remove(fn.replace(".bin", "")); n++; }
            }
        } catch (Exception ex) { Frontier.msg(p, "§cCouldn't revert: " + ex.getMessage()); return 0; }
        Frontier.msg(p, n == 0 ? "§7No saved edits for §f" + name + "§7." : "§a[Debug] Removed " + n + " saved file" + (n == 1 ? "" : "s") + " for §f" + name + "§a: it's back to the original.");
        return 1;
    }

    // ------------------------------------------------------------------ the town

    static final Deque<Runnable> QUEUE = new ArrayDeque<>();
    @SubscribeEvent
    public static void onTick(ServerTickEvent.Post e) { for (int i = 0; i < 3 && !QUEUE.isEmpty(); i++) QUEUE.poll().run(); }

    /**
     * /jcl spawn save: a full snapshot of the spawn as it stands. Every block in the town's bounds (air included, so what you
     * cleared stays cleared), every block's contents (signs, chests, banners...), placed entities (frames, paintings, armor
     * stands, displays, carts, boats) and every NPC's exact spot and facing. /jcl spawn build and regen then place exactly this.
     */
    static int saveTown(ServerPlayer p) {
        ServerLevel lvl = p.server.overworld();
        BlockPos o = SpawnCity.savedOrigin(lvl);
        if (o == null) { p.sendSystemMessage(Component.literal("§cThere's no town built in this world to save.")); return 0; }
        JsonObject idx;
        try { idx = SpawnCity.index().deepCopy(); } catch (Exception ex) { p.sendSystemMessage(Component.literal("§cCouldn't read the town index.")); return 0; }
        JsonArray b = idx.getAsJsonArray("bounds");
        int bx0 = b.get(0).getAsInt(), bz0 = b.get(2).getAsInt(), bx1 = b.get(3).getAsInt(), bz1 = b.get(5).getAsInt();
        int y0 = Math.min(b.get(1).getAsInt(), SpawnCity.terrainFloor() - 4), y1 = idx.get("clear_to").getAsInt(), h = y1 - y0 + 1;
        var reg = lvl.registryAccess();
        List<String> names = new ArrayList<>();
        for (int cx = bx0; cx <= bx1; cx += 32) for (int cz = bz0; cz <= bz1; cz += 32) names.add("snap_" + cx + "_" + cz + ".bin");
        try { Files.createDirectories(TOWN); } catch (IOException ex) { p.sendSystemMessage(Component.literal("§cCouldn't create config/jcl/spawn_city.")); return 0; }
        p.sendSystemMessage(Component.literal("§7[Spawn] Saving the whole spawn: " + names.size() + " columns of every block, their contents, placed entities and the NPCs..."));
        // placed entities (not JCL's own NPCs, holograms or leaderboards: those are rebuilt from the town data)
        QUEUE.add(() -> {
            AABB box = new AABB(o.getX() + bx0, o.getY() + b.get(1).getAsInt(), o.getZ() + bz0, o.getX() + bx1 + 1, o.getY() + y1, o.getZ() + bz1 + 1);   // the same box a rebuild clears
            JsonArray ents = new JsonArray();
            for (net.minecraft.world.entity.Entity en : lvl.getEntities((net.minecraft.world.entity.Entity) null, box, x -> !(x instanceof ServerPlayer))) {
                boolean jcl = en.getTags().stream().anyMatch(t -> t.startsWith("jcl_") && !t.equals("jcl_saved"));
                boolean placed = en instanceof net.minecraft.world.entity.decoration.HangingEntity || en instanceof net.minecraft.world.entity.decoration.ArmorStand
                        || en instanceof net.minecraft.world.entity.Display || en instanceof net.minecraft.world.entity.vehicle.AbstractMinecart
                        || en instanceof net.minecraft.world.entity.vehicle.Boat || en.getTags().contains("jcl_saved");
                if (jcl || !placed || en.isPassenger()) continue;
                CompoundTag t = new CompoundTag();
                if (en.save(t)) ents.add(t.toString());
            }
            JsonObject ej = new JsonObject(); JsonArray or = new JsonArray(); or.add(o.getX()); or.add(o.getY()); or.add(o.getZ());
            ej.add("origin", or); ej.add("entities", ents);
            try { Files.writeString(TOWN.resolve("entities.json"), ej.toString()); } catch (IOException ex) { GtsMod.LOG.warn("[JCL] Town save: entities failed: {}", ex.toString()); }
            p.sendSystemMessage(Component.literal("§7[Spawn] Saved " + ents.size() + " placed entities (frames, paintings, stands, displays, carts, boats)."));
        });
        int[] done = {0}; int[] contents = {0};
        for (String name : names) {
            String[] parts = name.substring(5, name.length() - 4).split("_");
            int sx = Integer.parseInt(parts[0]), sz = Integer.parseInt(parts[1]);
            QUEUE.add(() -> {
                try {
                    List<String> pal = new ArrayList<>(List.of("__none__")); Map<BlockState, Integer> pidx = new HashMap<>();
                    byte[] data = new byte[32 * 32 * h * 2];
                    JsonArray bes = new JsonArray();
                    BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
                    for (int x = 0; x < 32; x++) for (int z = 0; z < 32; z++) for (int y = 0; y < h; y++) {
                        m.set(o.getX() + sx + x, o.getY() + y0 + y, o.getZ() + sz + z);
                        BlockState st = lvl.getBlockState(m);
                        Integer k = pidx.get(st); if (k == null) { k = pal.size(); pal.add(BlockStateParser.serialize(st)); pidx.put(st, k); }
                        int at = (((x * 32) + z) * h + y) * 2; data[at] = (byte) (k >> 8); data[at + 1] = (byte) (int) k;
                        if (st.hasBlockEntity()) {
                            var be = lvl.getBlockEntity(m);
                            if (be != null) { JsonObject bo = new JsonObject(); bo.addProperty("x", x); bo.addProperty("y", y); bo.addProperty("z", z); bo.addProperty("nbt", be.saveWithoutMetadata(reg).toString()); bes.add(bo); }
                        }
                    }
                    JsonObject hd = new JsonObject(); hd.addProperty("sx", sx); hd.addProperty("sz", sz); hd.addProperty("y0", y0); hd.addProperty("h", h);
                    JsonArray pa = new JsonArray(); for (String str : pal) pa.add(str); hd.add("palette", pa);
                    try (GZIPOutputStream out = new GZIPOutputStream(Files.newOutputStream(TOWN.resolve(name)))) { out.write((hd.toString() + "\n").getBytes(StandardCharsets.UTF_8)); out.write(data); }
                    Path bf = TOWN.resolve(name + ".be");
                    if (bes.isEmpty()) Files.deleteIfExists(bf); else { Files.writeString(bf, bes.toString()); contents[0] += bes.size(); }
                } catch (Exception ex) { GtsMod.LOG.warn("[JCL] Town save: column {} failed: {}", name, ex.toString()); }
                if (++done[0] == names.size()) finishSave(p, lvl, o, idx, names, contents[0]);
            });
        }
        return 1;
    }

    /** The last step of a save: the NPCs, then the town data that points every build at the snapshot. */
    static void finishSave(ServerPlayer p, ServerLevel lvl, BlockPos o, JsonObject idx, List<String> names, int contents) {
        try {
            Map<String, JsonObject> now = new java.util.LinkedHashMap<>();
            for (net.minecraft.world.entity.Entity en : lvl.getAllEntities()) {
                if (en == null || !en.getTags().contains("jcl_spawn")) continue;
                String cls = null; for (String t : en.getTags()) if (t.startsWith("jcl_npc_")) cls = t.substring(8);
                if (cls == null || now.containsKey(cls)) continue;
                JsonObject n = new JsonObject(); n.addProperty("cls", cls);
                JsonArray pos = new JsonArray();
                pos.add(Math.round((en.getX() - o.getX()) * 1000) / 1000.0); pos.add(Math.round((en.getY() - o.getY()) * 1000) / 1000.0); pos.add(Math.round((en.getZ() - o.getZ()) * 1000) / 1000.0);
                n.add("pos", pos); n.addProperty("yaw", Math.round(en.getYRot() * 10) / 10.0); n.addProperty("exact", true);
                now.put(cls, n);
            }
            JsonArray npcs = new JsonArray(); int kept = 0;
            for (JsonElement e2 : idx.getAsJsonArray("npcs")) { String cls = e2.getAsJsonObject().get("cls").getAsString(); if (now.containsKey(cls)) npcs.add(now.remove(cls)); else { npcs.add(e2); kept++; } }
            for (JsonObject extra : now.values()) npcs.add(extra);
            idx.add("npcs", npcs);
            JsonArray secs = new JsonArray(); for (String s : names) secs.add(s);
            idx.add("sections", secs); idx.addProperty("snapshot", true);
            Files.writeString(TOWN.resolve("index.json"), idx.toString());
            // old-style saved sections are replaced by the snapshot
            try (var fs = Files.list(TOWN)) { for (Path f : fs.toList()) { String fn = f.getFileName().toString(); if (fn.startsWith("sec_")) Files.delete(f); } }
            SpawnCity.index = idx;
            final int got = npcs.size() - kept, k2 = kept;
            p.sendSystemMessage(Component.literal("§a[Spawn] Saved the whole spawn: " + names.size() + " columns, " + contents + " blocks with contents, and " + got + " NPCs exactly as they stand and face"
                    + (k2 > 0 ? " §8(" + k2 + " NPCs out of range kept their last spot)" : "") + "§a."));
            p.sendSystemMessage(Component.literal("§7/jcl spawn build and §f/jcl spawn regen confirm §7now place exactly this. §f/jcl spawn revert confirm §7goes back to the original design."));
        } catch (Exception ex) { p.sendSystemMessage(Component.literal("§cThe blocks saved, but the town data couldn't: " + ex.getMessage())); }
    }

    static int revertTown(ServerPlayer p) {
        int n = 0;
        try (var s = Files.exists(TOWN) ? Files.list(TOWN) : java.util.stream.Stream.<Path>empty()) { for (Path f : s.toList()) { Files.delete(f); n++; } }
        catch (Exception ex) { p.sendSystemMessage(Component.literal("§cCouldn't revert: " + ex.getMessage())); return 0; }
        SpawnCity.index = null;                                              // forget the saved town data too
        p.sendSystemMessage(Component.literal(n == 0 ? "§7The town has no saved edits." : "§a[Spawn] Removed the saved town: the next build uses the original design."));
        return 1;
    }

    @SubscribeEvent
    public static void onCommands(RegisterCommandsEvent e) {
        var d = e.getDispatcher();
        d.register(Commands.literal("frontier").then(Commands.literal("debug").requires(s -> s.hasPermission(2))
                .then(Commands.literal("save").executes(c -> saveCurrentRoom(c.getSource().getPlayerOrException()))
                        .then(Commands.literal("hub").executes(c -> saveHub(c.getSource().getPlayerOrException()))))
                .then(Commands.literal("revert").then(Commands.argument("room", StringArgumentType.word())
                        .suggests((c, b) -> { List<String> all = new ArrayList<>(BATTLE.keySet()); all.add("hub"); all.addAll(FrontierRooms.variants().keySet()); return SharedSuggestionProvider.suggest(all, b); })
                        .executes(c -> revertRoom(c.getSource().getPlayerOrException(), StringArgumentType.getString(c, "room")))))));
        d.register(Commands.literal("jcl").requires(s -> s.hasPermission(2)).then(Commands.literal("spawn")
                .then(Commands.literal("save").executes(c -> saveTown(c.getSource().getPlayerOrException()))
                        .then(Commands.literal("confirm").executes(c -> saveTown(c.getSource().getPlayerOrException()))))
                .then(Commands.literal("regen").executes(c -> { c.getSource().sendSuccess(() -> net.minecraft.network.chat.Component.literal("§6[Spawn] §fThis rebuilds the town in place from your saved version (§f/jcl spawn save§f), or the original design if you never saved. Run §e/jcl spawn regen confirm§f."), false); return 1; })
                        .then(Commands.literal("confirm").executes(c -> { try { SpawnCity.regen(c.getSource().getPlayerOrException()); } catch (Exception ex) { c.getSource().sendFailure(net.minecraft.network.chat.Component.literal("Couldn't regenerate: " + ex.getMessage())); } return 1; })))
                .then(Commands.literal("revert").then(Commands.literal("confirm").executes(c -> revertTown(c.getSource().getPlayerOrException()))))));
    }
}
