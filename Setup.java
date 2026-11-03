package net.joeyscobblemon.gts;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.google.gson.Gson;
import com.mojang.brigadier.arguments.IntegerArgumentType;

import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * One-command spawn setup. Stand on the spawn point (south of the fountain) and run:
 *   /jcl setup all            everything below, in one go
 *   /jcl setup npcs           town NPCs + EV trainers, with their skins (old villager NPCs are removed)
 *   /jcl setup holograms      all floating text (welcome, sharestone, rules, Discord, EV pillars...)
 *   /jcl setup leaderboards   the three live boards in the Hall of Fame
 *   /jcl setup warps          spawn point + evs / crates / shop warps
 *   /jcl setup worldmap       draw the world map across the map board
 * Every spot comes from the spawn's own layout file, so nothing has to be placed by hand.
 */
@EventBusSubscriber(modid = "jcl_gts")
public final class Setup {
    private Setup() {}

    public static final class HoloSpot { public String preset; public double[] pos; }
    public static final class Board { public int xFrom, xTo, yTop, yBottom, z; public String facing; }
    public static final class Layout {
        public Map<String, int[]> npcs; public List<int[]> ev; public int[] sharestone; public Board mapboard;
        public List<HoloSpot> holograms; public Map<String, double[]> leaderboards;
    }

    static Layout layout() {
        try (InputStream in = Setup.class.getResourceAsStream("/jcl/spawn_layout.json")) {
            return new Gson().fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), Layout.class);
        } catch (Exception e) { GtsMod.LOG.error("[JCL] Could not read the spawn layout", e); return null; }
    }

    static float yawToward(double dx, double dz) { return (float) Math.toDegrees(-Math.atan2(dx, dz)); }

    static final Map<String, Float> FACING = Map.of("nurse", 0f, "clerk", -90f, "archivist", 0f);   // NPCs inside buildings face their doors

    static void npcs(ServerPlayer p, Layout l) {
        ServerLevel lvl = p.serverLevel(); BlockPos o = p.blockPosition();
        int removed = Npcs.removeAll(lvl, o.getX(), o.getZ(), 220);
        int n = 0;
        for (var en : l.npcs.entrySet()) {
            int[] r = en.getValue();
            float yaw = FACING.getOrDefault(en.getKey(), yawToward(-r[0], -r[2]));
            if (en.getKey().equals("guide")) yaw = yawToward(-r[0], 4);       // Mae faces new arrivals
            if (Npcs.spawn(lvl, en.getKey(), o.getX() + r[0] + 0.5, o.getY() + r[1], o.getZ() + r[2] + 0.5, yaw, "jcl_npc", "jcl_npc_" + en.getKey()) != null) n++;
        }
        for (int i = 0; i < l.ev.size() && i < Npcs.EV.size(); i++) {
            int[] r = l.ev.get(i); String id = Npcs.EV.get(i);
            if (Npcs.spawn(lvl, id, o.getX() + r[0] + 0.5, o.getY() + r[1], o.getZ() + r[2] + 0.5, 0f, "jcl_ev", "jcl_" + id) != null) n++;
        }
        tell(p, "Spawned " + n + " NPCs" + (removed > 0 ? " (removed " + removed + " old ones)" : "") + ".");
    }

    static void holograms(ServerPlayer p, Layout l) {
        ServerLevel lvl = p.serverLevel(); BlockPos o = p.blockPosition();
        int n = 0;
        for (HoloSpot h : l.holograms) {
            String[] lines = Holos.PRESETS.get(h.preset);
            if (lines == null) continue;
            Holos.put(h.preset, lvl, o.getX() + h.pos[0], o.getY() + h.pos[1], o.getZ() + h.pos[2], h.preset.startsWith("ev_") || h.preset.startsWith("podium") ? 1.4f : 1.2f, List.of(lines));
            n++;
        }
        tell(p, "Placed " + n + " floating texts. Fine-tune any of them with /holo move <id> or /holo scale <id> <size>.");
        tell(p, "§7Place the black sharestone on the dark pedestal beside the spawn point. The floating text above it explains it.");
    }

    static void leaderboards(ServerPlayer p, Layout l) {
        BlockPos o = p.blockPosition();
        for (var en : l.leaderboards.entrySet())
            Leaderboards.place(en.getKey(), p.serverLevel(), o.getX() + en.getValue()[0], o.getY() + en.getValue()[1], o.getZ() + en.getValue()[2]);
        tell(p, "Placed the three leaderboards in the Hall of Fame.");
    }

    static void warps(ServerPlayer p, Layout l) {
        BlockPos o = p.blockPosition(); ServerLevel lvl = p.serverLevel();
        lvl.setDefaultSpawnPos(o, p.getYRot());
        warp("evs", "Battle the EV trainers and train your team.", lvl, o, l.npcs.get("coach"), 0, 3);
        warp("crates", "Open crates with your keys.", lvl, o, l.npcs.get("crates"), -3, 0);
        warp("shop", "The Poké Mart: /shop and /sell.", lvl, o, l.npcs.get("clerk"), 0, 0);
        Teleports.save();
        tell(p, "Set the world spawn here, plus the evs, crates and shop warps.");
    }

    static void warp(String name, String desc, ServerLevel lvl, BlockPos o, int[] r, int ox, int oz) {
        Teleports.Warp w = new Teleports.Warp(); w.name = name; w.description = desc; w.dim = lvl.dimension().location().toString();
        w.x = o.getX() + r[0] + ox + 0.5; w.y = o.getY() + r[1]; w.z = o.getZ() + r[2] + oz + 0.5;
        w.yaw = yawToward(-(r[0] + ox), -(r[2] + oz)) + 180;
        w.icon = switch (name) { case "evs" -> "minecraft:experience_bottle"; case "crates" -> "jcl_gts:master_key"; default -> "cobblemon:poke_ball"; };
        Teleports.data.warps.put(name, w);
    }

    static void worldmap(ServerPlayer p, Layout l) {
        BlockPos o = p.blockPosition(); Board b = l.mapboard;
        int cols = Math.abs(b.xFrom - b.xTo) + 1, rows = b.yTop - b.yBottom + 1;
        List<BlockPos> frames = new ArrayList<>();
        int step = b.xFrom > b.xTo ? -1 : 1;
        for (int row = 0; row < rows; row++)
            for (int col = 0; col < cols; col++)
                frames.add(new BlockPos(o.getX() + b.xFrom + step * col, o.getY() + b.yTop - row, o.getZ() + b.z));
        WorldMap.start(p, p.serverLevel(), o.getX(), o.getZ() - 14, cols, rows, frames, Direction.valueOf(b.facing.toUpperCase()));
    }

    static void mapBetween(ServerPlayer p, BlockPos tl, BlockPos br, int speed) {
        Direction facing = p.getDirection().getOpposite();            // frames face back toward you
        int cols, rows = tl.getY() - br.getY() + 1;
        boolean alongX = facing == Direction.NORTH || facing == Direction.SOUTH;
        cols = Math.abs(alongX ? br.getX() - tl.getX() : br.getZ() - tl.getZ()) + 1;
        if (rows < 1 || cols < 1) { tell(p, "§cGive the top-left corner first, then the bottom-right."); return; }
        int sx = alongX ? Integer.signum(br.getX() - tl.getX()) : 0, sz = alongX ? 0 : Integer.signum(br.getZ() - tl.getZ());
        List<BlockPos> frames = new ArrayList<>();
        for (int row = 0; row < rows; row++)
            for (int col = 0; col < cols; col++)
                frames.add(new BlockPos(tl.getX() + sx * col, tl.getY() - row, tl.getZ() + sz * col));
        BlockPos spawn = p.serverLevel().getSharedSpawnPos();
        WorldMap.chunksPerTick = speed;
        WorldMap.start(p, p.serverLevel(), spawn.getX(), spawn.getZ(), cols, rows, frames, facing);
    }

    static void tell(ServerPlayer p, String s) { p.sendSystemMessage(Component.literal("§a§l[Setup] §r§a" + s)); }

    @SubscribeEvent
    public static void onCommands(RegisterCommandsEvent e) {
        // place single NPCs by hand: stand on the spot, face the way they should look
        e.getDispatcher().register(Commands.literal("jcl").requires(s -> s.hasPermission(2)).then(Commands.literal("npc")
                .then(Commands.argument("id", StringArgumentType.word()).suggests((c, b) -> SharedSuggestionProvider.suggest(Npcs.SKINS, b)).executes(c -> {
                    ServerPlayer p = c.getSource().getPlayerOrException(); String id = StringArgumentType.getString(c, "id");
                    if (!Npcs.SKINS.contains(id)) { c.getSource().sendFailure(Component.literal("Unknown NPC. Use one of: " + String.join(", ", Npcs.SKINS))); return 0; }
                    boolean ev = Npcs.EV.contains(id);
                    var en = ev ? Npcs.spawn(p.serverLevel(), id, p.getX(), p.getY(), p.getZ(), p.getYRot(), "jcl_ev", "jcl_" + id)
                                : Npcs.spawn(p.serverLevel(), id, p.getX(), p.getY(), p.getZ(), p.getYRot(), "jcl_npc", "jcl_npc_" + id);
                    tell(p, en == null ? "§cCouldn't spawn it. Check the server log." : "Spawned " + id + " here, facing the way you're facing.");
                    return 1;
                }))));
        // the world map: give the frame spots at the board's top-left and bottom-right corners, as you see them
        e.getDispatcher().register(Commands.literal("jcl").requires(s -> s.hasPermission(2)).then(Commands.literal("worldmap")
                .then(Commands.argument("topLeft", BlockPosArgument.blockPos()).then(Commands.argument("bottomRight", BlockPosArgument.blockPos())
                        .executes(c -> { mapBetween(c.getSource().getPlayerOrException(), BlockPosArgument.getBlockPos(c, "topLeft"), BlockPosArgument.getBlockPos(c, "bottomRight"), 2); return 1; })
                        .then(Commands.argument("chunksPerTick", IntegerArgumentType.integer(1, 16)).executes(c -> {
                            mapBetween(c.getSource().getPlayerOrException(), BlockPosArgument.getBlockPos(c, "topLeft"), BlockPosArgument.getBlockPos(c, "bottomRight"), IntegerArgumentType.getInteger(c, "chunksPerTick")); return 1; }))))));
        e.getDispatcher().register(Commands.literal("jcl").requires(s -> s.hasPermission(2)).then(Commands.literal("setup")
                .then(Commands.literal("all").executes(c -> { ServerPlayer p = c.getSource().getPlayerOrException(); Layout l = layout(); if (l == null) return 0;
                    warps(p, l); npcs(p, l); holograms(p, l); leaderboards(p, l); worldmap(p, l); return 1; }))
                .then(Commands.literal("npcs").executes(c -> { Layout l = layout(); if (l != null) npcs(c.getSource().getPlayerOrException(), l); return 1; }))
                .then(Commands.literal("holograms").executes(c -> { Layout l = layout(); if (l != null) holograms(c.getSource().getPlayerOrException(), l); return 1; }))
                .then(Commands.literal("leaderboards").executes(c -> { Layout l = layout(); if (l != null) leaderboards(c.getSource().getPlayerOrException(), l); return 1; }))
                .then(Commands.literal("warps").executes(c -> { Layout l = layout(); if (l != null) warps(c.getSource().getPlayerOrException(), l); return 1; }))
                .then(Commands.literal("worldmap").executes(c -> { Layout l = layout(); if (l != null) worldmap(c.getSource().getPlayerOrException(), l); return 1; })
                        .then(Commands.argument("chunksPerTick", IntegerArgumentType.integer(1, 16)).executes(c -> {
                            WorldMap.chunksPerTick = IntegerArgumentType.getInteger(c, "chunksPerTick");
                            Layout l = layout(); if (l != null) worldmap(c.getSource().getPlayerOrException(), l); return 1; })))));
    }
}
