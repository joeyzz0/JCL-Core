package net.joeyscobblemon.gts;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import com.google.gson.Gson;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/**
 * /jcl: admin tools for fixing things by hand, plus the first-join welcome.
 *   /jcl reload                                   reload every JCL Core config
 *   /jcl pity <player> <tier> [set <n>|reset]     view or change crate pity
 *   /jcl shoplimits <player> reset                clear a player's shop purchase limits
 *   /jcl job <player> <job> step <n> | reset      move someone to a job step or reset it
 *   /jcl board <board> <player> set <n> | reset   fix a leaderboard entry
 *   /jcl board <board> resetall                   wipe a leaderboard
 *   /jcl holo clear [radius]                      remove floating text near you
 *   /jcl welcome <player>                         show someone the first-join welcome again
 */
@EventBusSubscriber(modid = "jcl_gts")
public final class Admin {
    private Admin() {}

    static void ok(CommandSourceStack s, String m) { s.sendSuccess(() -> Component.literal("§a" + m), true); }

    @SubscribeEvent
    public static void onCommands(RegisterCommandsEvent e) {
        e.getDispatcher().register(Commands.literal("jcl").requires(s -> s.hasPermission(2))
                .then(Commands.literal("reload").executes(c -> {
                    Crates.load(); Shop.load(); Titles.load(); Jobs.load(); Help.load();
                    ok(c.getSource(), "Reloaded crates, shop, titles, jobs and help. (Restart for tab list, tips and teleport settings.)"); return 1;
                }))
                .then(Commands.literal("pity").then(Commands.argument("player", EntityArgument.player()).then(Commands.argument("tier", StringArgumentType.word())
                        .suggests((c, b) -> SharedSuggestionProvider.suggest(Crates.TIERS, b))
                        .executes(c -> { ServerPlayer p = EntityArgument.getPlayer(c, "player"); String t = StringArgumentType.getString(c, "tier");
                            ok(c.getSource(), p.getGameProfile().getName() + " " + t + " pity: " + Crates.pity(p, t)); return 1; })
                        .then(Commands.literal("set").then(Commands.argument("value", IntegerArgumentType.integer(0)).executes(c -> {
                            ServerPlayer p = EntityArgument.getPlayer(c, "player"); String t = StringArgumentType.getString(c, "tier");
                            Crates.setPity(p, t, IntegerArgumentType.getInteger(c, "value")); Crates.save();
                            ok(c.getSource(), "Set " + p.getGameProfile().getName() + "'s " + t + " pity to " + IntegerArgumentType.getInteger(c, "value") + "."); return 1; })))
                        .then(Commands.literal("reset").executes(c -> {
                            ServerPlayer p = EntityArgument.getPlayer(c, "player"); String t = StringArgumentType.getString(c, "tier");
                            Crates.setPity(p, t, 0); Crates.save(); ok(c.getSource(), "Reset " + t + " pity."); return 1; })))))
                .then(Commands.literal("shoplimits").then(Commands.argument("player", EntityArgument.player()).then(Commands.literal("reset").executes(c -> {
                    ServerPlayer p = EntityArgument.getPlayer(c, "player"); Shop.data.bought.remove(p.getUUID().toString()); Shop.save();
                    ok(c.getSource(), "Cleared " + p.getGameProfile().getName() + "'s shop limits."); return 1; }))))
                .then(Commands.literal("job").then(Commands.argument("player", EntityArgument.player()).then(Commands.argument("job", StringArgumentType.word())
                        .suggests((c, b) -> SharedSuggestionProvider.suggest(Jobs.cfg.jobs.stream().map(j -> j.id), b))
                        .then(Commands.literal("step").then(Commands.argument("n", IntegerArgumentType.integer(0)).executes(c -> {
                            ServerPlayer p = EntityArgument.getPlayer(c, "player");
                            Jobs.progress(p).put(StringArgumentType.getString(c, "job"), IntegerArgumentType.getInteger(c, "n")); Jobs.save();
                            ok(c.getSource(), "Moved " + p.getGameProfile().getName() + " to step " + IntegerArgumentType.getInteger(c, "n") + " (0 = first step)."); return 1; })))
                        .then(Commands.literal("reset").executes(c -> {
                            ServerPlayer p = EntityArgument.getPlayer(c, "player"); Jobs.progress(p).remove(StringArgumentType.getString(c, "job")); Jobs.save();
                            ok(c.getSource(), "Job reset."); return 1; })))))
                .then(Commands.literal("board").then(Commands.argument("board", StringArgumentType.word()).suggests((c, b) -> SharedSuggestionProvider.suggest(Leaderboards.BOARDS, b))
                        .then(Commands.literal("resetall").executes(c -> {
                            String b = StringArgumentType.getString(c, "board");
                            if (b.equals("money")) Leaderboards.data.money.clear(); else Leaderboards.data.monthly.remove(b);
                            Leaderboards.save(); Leaderboards.refresh(b); ok(c.getSource(), "Wiped the " + b + " leaderboard."); return 1; }))
                        .then(Commands.argument("player", EntityArgument.player())
                                .then(Commands.literal("set").then(Commands.argument("value", LongArgumentType.longArg(0)).executes(c -> {
                                    String b = StringArgumentType.getString(c, "board"); ServerPlayer p = EntityArgument.getPlayer(c, "player"); long v = LongArgumentType.getLong(c, "value");
                                    Leaderboards.data.names.put(p.getUUID().toString(), p.getGameProfile().getName());
                                    if (b.equals("money")) Leaderboards.data.money.put(p.getUUID().toString(), v);
                                    else Leaderboards.data.monthly.computeIfAbsent(b, k -> new HashMap<>()).put(p.getUUID().toString(), v);
                                    Leaderboards.save(); Leaderboards.refresh(b); ok(c.getSource(), "Set " + p.getGameProfile().getName() + " to " + v + " on " + b + "."); return 1; })))
                                .then(Commands.literal("reset").executes(c -> {
                                    String b = StringArgumentType.getString(c, "board"); ServerPlayer p = EntityArgument.getPlayer(c, "player");
                                    if (b.equals("money")) Leaderboards.data.money.remove(p.getUUID().toString());
                                    else { var m = Leaderboards.data.monthly.get(b); if (m != null) m.remove(p.getUUID().toString()); }
                                    Leaderboards.save(); Leaderboards.refresh(b); ok(c.getSource(), "Removed them from " + b + "."); return 1; })))))
                .then(Commands.literal("holo").then(Commands.literal("clear")
                        .executes(c -> { ServerPlayer p = c.getSource().getPlayerOrException(); int n = Holo.removeNear(p.serverLevel(), p.getX(), p.getY(), p.getZ(), 5); ok(c.getSource(), "Removed " + n + " floating text lines within 5 blocks."); return 1; })
                        .then(Commands.argument("radius", IntegerArgumentType.integer(1, 64)).executes(c -> {
                            ServerPlayer p = c.getSource().getPlayerOrException(); int r = IntegerArgumentType.getInteger(c, "radius");
                            int n = Holo.removeNear(p.serverLevel(), p.getX(), p.getY(), p.getZ(), r); ok(c.getSource(), "Removed " + n + " floating text lines within " + r + " blocks."); return 1; }))))
                .then(Commands.literal("welcome").then(Commands.argument("player", EntityArgument.player()).executes(c -> { welcome(EntityArgument.getPlayer(c, "player"), false); return 1; }))));
    }

    // ------------------------------------------------------------------ first-join welcome

    static Set<String> seen;

    static Path seenFile() { return Market.server.getWorldPath(LevelResource.ROOT).resolve("data").resolve("jcl_gts").resolve("seen.json"); }

    @SubscribeEvent
    public static void onJoin(PlayerEvent.PlayerLoggedInEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer p) || Market.server == null) return;
        try {
            if (seen == null) { seen = new HashSet<>(); if (Files.exists(seenFile())) { String[] a = new Gson().fromJson(Files.readString(seenFile()), String[].class); if (a != null) for (String s : a) seen.add(s); } }
            if (seen.add(p.getUUID().toString())) {
                Files.createDirectories(seenFile().getParent()); Files.writeString(seenFile(), new Gson().toJson(seen));
                welcome(p, true);
            }
        } catch (Exception ex) { GtsMod.LOG.error("[JCL] Welcome message failed", ex); }
    }

    static void welcome(ServerPlayer p, boolean announce) {
        if (announce) Market.server.getPlayerList().broadcastSystemMessage(Component.literal("§6§l✦ §r§eEveryone welcome §f" + p.getGameProfile().getName() + "§e to Joey's Cobblemon League! §6§l✦"), false);
        p.sendSystemMessage(Component.literal(" "));
        p.sendSystemMessage(Component.literal("§6§l✦ Welcome to Port Azure, " + p.getGameProfile().getName() + "! §6§l✦"));
        p.sendSystemMessage(Component.literal("§7You've arrived in our island harbour town. Here's how to get started:"));
        p.sendSystemMessage(Component.literal(" §e1. §fTalk to §bGuide Mae§f by the fountain to start the §eWelcome Tour§f."));
        p.sendSystemMessage(Component.literal(" §e2. §fFinish the tour for a §bfree Lapras§f you can ride across the sea!"));
        p.sendSystemMessage(Component.literal(" §e3. §fOpen your quest book for step-by-step guides. Every quest pays CobbleDollars."));
        p.sendSystemMessage(Component.literal(" ").append(Component.literal("§a§n[/help]").withStyle(s -> s.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/help"))))
                .append(Component.literal("  ")).append(Component.literal("§d§n[/jobs]").withStyle(s -> s.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/jobs"))))
                .append(Component.literal("  ")).append(Component.literal("§b§n[/warp]").withStyle(s -> s.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/warp")))));
        p.sendSystemMessage(Component.literal("§8Never pay-to-win. Everything here is earned in game. Have fun!"));
    }
}
