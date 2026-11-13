package net.joeyscobblemon.gts;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.*;

/**
 * Player challenges (replaces Cobblemon Challenge): /challenge <player> [level] [singles|doubles] [wager].
 * Both teams are battle copies of the players' real parties, fully healed and set to the chosen level, so nothing
 * happens to the real Pokémon (no experience, no fainting). An optional CobbleDollar wager goes to the winner.
 */
@EventBusSubscriber(modid = GtsMod.MODID)
public final class Challenge {
    private Challenge() {}
    static MinecraftServer server;
    static final int RANGE = 64, EXPIRE_TICKS = 20 * 60, DEFAULT_LEVEL = 50;

    record Invite(UUID from, UUID to, int level, String format, long wager, long expires) {}
    record Live(UUID a, UUID b, long wager, long started, UUID battle) {}
    static final Map<UUID, Invite> INVITES = new HashMap<>();      // keyed by the challenged player
    static final List<Live> LIVE = new ArrayList<>();
    static final Set<UUID> BLOCKED = new HashSet<>();
    static long tick;

    @SubscribeEvent public static void onStart(ServerStartedEvent e) { server = e.getServer(); hook(); }

    static void msg(ServerPlayer p, String s) { p.sendSystemMessage(Component.literal("§6§l[Challenge] §r" + s)); }
    static String name(ServerPlayer p) { return p.getGameProfile().getName(); }

    static String problem(ServerPlayer a, ServerPlayer b, long wager) {
        if (a == b) return "You can't challenge yourself.";
        if (a.level() != b.level() || a.distanceTo(b) > RANGE) return name(b) + " needs to be within " + RANGE + " blocks of you.";
        if (Cob.battleOf(a) != null) return "You're already in a battle.";
        if (Cob.battleOf(b) != null) return name(b) + " is already in a battle.";
        if (Cob.party(a).stream().noneMatch(Objects::nonNull)) return "You don't have any Pokémon with you.";
        if (Cob.party(b).stream().noneMatch(Objects::nonNull)) return name(b) + " doesn't have any Pokémon with them.";
        if (wager > 0 && Economy.balance(a) < wager) return "You can't cover that wager.";
        if (wager > 0 && Economy.balance(b) < wager) return name(b) + " can't cover that wager.";
        return null;
    }

    static void send(ServerPlayer from, ServerPlayer to, int level, String format, long wager) {
        if (BLOCKED.contains(to.getUUID())) { msg(from, "§c" + name(to) + " isn't taking challenges right now."); return; }
        String bad = problem(from, to, wager); if (bad != null) { msg(from, "§c" + bad); return; }
        INVITES.put(to.getUUID(), new Invite(from.getUUID(), to.getUUID(), level, format, wager, tick + EXPIRE_TICKS));
        String terms = "§f" + cap(format) + " §7· " + (level > 0 ? "everyone at §fLv. " + level : "§fyour own levels") + (wager > 0 ? " §7· wager §6" + Fmt.cd(wager) : "");
        msg(from, "§aChallenge sent to §f" + name(to) + "§a. " + terms);
        to.sendSystemMessage(Component.literal("§6§l[Challenge] §r§f" + name(from) + " §7challenges you! " + terms + " ")
                .append(Component.literal("§a§l[Accept]").withStyle(s -> s.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/challenge accept " + name(from)))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("Battle " + name(from))))))
                .append(Component.literal(" "))
                .append(Component.literal("§c§l[Decline]").withStyle(s -> s.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/challenge decline " + name(from))))));
        to.playNotifySound(SoundEvents.NOTE_BLOCK_BELL.value(), SoundSource.MASTER, 0.8f, 1.4f);
    }

    // ------------------------------------------------------------------ the challenge menu

    /** What the challenger has picked so far in the menu. */
    static final class Draft { UUID target; int level = DEFAULT_LEVEL; String format = "singles"; long wager; }
    static final Map<UUID, Draft> DRAFTS = new HashMap<>();

    static net.minecraft.world.item.ItemStack icon(net.minecraft.world.item.Item it, String name, String... lore) { return Gui.icon(it, name, lore); }

    /** Opens the challenge menu (from /challenge <player> or Cobblemon's R-menu "Challenge"). */
    public static void openMenu(ServerPlayer p, ServerPlayer target) {
        Draft d = DRAFTS.computeIfAbsent(p.getUUID(), k -> new Draft());
        if (!target.getUUID().equals(d.target)) { d.target = target.getUUID(); d.wager = 0; }
        menu(p);
    }

    static void menu(ServerPlayer p) {
        Draft d = DRAFTS.get(p.getUUID());
        ServerPlayer t = d == null ? null : server.getPlayerList().getPlayer(d.target);
        if (t == null) { msg(p, "§cThat player isn't online any more."); DRAFTS.remove(p.getUUID()); return; }
        Gui.Screen g = new Gui.Screen("§6§lChallenge §8· §f" + name(t), 5);
        g.frame(net.minecraft.world.item.Items.ORANGE_STAINED_GLASS_PANE, net.minecraft.world.item.Items.BLACK_STAINED_GLASS_PANE);
        var I = net.minecraft.world.item.Items.class;
        // level
        String lv = d.level == 0 ? "Their own levels" : "Lv. " + d.level;
        g.set(10, icon(net.minecraft.world.item.Items.RED_DYE, "§c-10"), pl -> { d.level = Math.max(1, (d.level == 0 ? DEFAULT_LEVEL : d.level) - 10); menu(pl); });
        g.set(11, icon(net.minecraft.world.item.Items.PINK_DYE, "§c-1"), pl -> { d.level = Math.max(1, (d.level == 0 ? DEFAULT_LEVEL : d.level) - 1); menu(pl); });
        g.set(12, icon(net.minecraft.world.item.Items.EXPERIENCE_BOTTLE, "§e§lLevel: §f" + lv, "§7Every Pokémon on both teams battles", "§7at this level. Your real Pokémon", "§7never gain or lose anything."), pl -> {});
        g.set(13, icon(net.minecraft.world.item.Items.LIME_DYE, "§a+1"), pl -> { d.level = Math.min(100, (d.level == 0 ? DEFAULT_LEVEL : d.level) + 1); menu(pl); });
        g.set(14, icon(net.minecraft.world.item.Items.GREEN_DYE, "§a+10"), pl -> { d.level = Math.min(100, (d.level == 0 ? DEFAULT_LEVEL : d.level) + 10); menu(pl); });
        g.set(15, icon(net.minecraft.world.item.Items.GOLD_NUGGET, "§fQuick: §eLv. 50"), pl -> { d.level = 50; menu(pl); });
        g.set(16, icon(net.minecraft.world.item.Items.GOLD_INGOT, "§fQuick: §eLv. 100"), pl -> { d.level = 100; menu(pl); });
        g.set(19, icon(net.minecraft.world.item.Items.TOTEM_OF_UNDYING, "§fUse their own levels", d.level == 0 ? "§a✔ Selected" : "§7No level change at all"), pl -> { d.level = 0; menu(pl); });
        // format
        g.set(21, icon(d.format.equals("singles") ? net.minecraft.world.item.Items.IRON_SWORD : net.minecraft.world.item.Items.DIAMOND_SWORD,
                "§b§lFormat: §f" + cap(d.format), "§7Click to switch to " + (d.format.equals("singles") ? "Doubles" : "Singles") + "."), pl -> { d.format = d.format.equals("singles") ? "doubles" : "singles"; menu(pl); });
        // wager
        g.set(23, icon(net.minecraft.world.item.Items.RED_DYE, "§c-1,000"), pl -> { d.wager = Math.max(0, d.wager - 1000); menu(pl); });
        g.set(24, icon(net.minecraft.world.item.Items.SUNFLOWER, "§6§lWager: §f" + (d.wager == 0 ? "None" : Fmt.cd(d.wager)), "§7Both trainers pay it when the battle", "§7starts. The winner takes both.", "§8Click for +100"), pl -> { d.wager += 100; menu(pl); });
        g.set(25, icon(net.minecraft.world.item.Items.LIME_DYE, "§a+1,000"), pl -> { d.wager = Math.min(10_000_000, d.wager + 1000); menu(pl); });
        g.set(26, icon(net.minecraft.world.item.Items.EMERALD, "§a+10,000"), pl -> { d.wager = Math.min(10_000_000, d.wager + 10_000); menu(pl); });
        // accessories (the Accessories update fills this)
        g.set(28, icon(net.minecraft.world.item.Items.BARRIER, "§d§lAccessories: §7Coming Soon", "§7Turn accessory bonuses on or off", "§7for this battle. Arrives with the", "§7Accessories update."), pl -> {});
        // send / cancel
        String summary = "§7" + cap(d.format) + " · " + (d.level == 0 ? "own levels" : "Lv. " + d.level) + (d.wager > 0 ? " · wager " + Fmt.cd(d.wager) : "");
        g.set(31, icon(net.minecraft.world.item.Items.LIME_CONCRETE, "§a§lSend Challenge", summary), pl -> { pl.closeContainer(); DRAFTS.remove(pl.getUUID()); send(pl, t, d.level, d.format, d.wager); });
        g.set(33, icon(net.minecraft.world.item.Items.RED_CONCRETE, "§c§lCancel"), pl -> { pl.closeContainer(); DRAFTS.remove(pl.getUUID()); });
        Gui.open(p, g);
    }

    static String cap(String s) { return s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1); }

    static void accept(ServerPlayer to, ServerPlayer from) {
        Invite inv = INVITES.get(to.getUUID());
        if (inv == null || !inv.from().equals(from.getUUID())) { msg(to, "§cThere's no challenge from " + name(from) + " waiting."); return; }
        INVITES.remove(to.getUUID());
        start(from, to, inv.level(), inv.format(), inv.wager());
    }

    /** Starts the battle (also used by the staff force command). */
    static void start(ServerPlayer a, ServerPlayer b, int level, String format, long wager) {
        String bad = problem(a, b, wager); if (bad != null) { msg(a, "§c" + bad); msg(b, "§c" + bad); return; }
        if (wager > 0) {
            if (!Economy.take(a, wager)) { msg(a, "§cYour wager couldn't be taken."); return; }
            if (!Economy.take(b, wager)) { Economy.give(a, wager); msg(b, "§cYour wager couldn't be taken."); return; }
        }
        String err = Cob.startPvp(a, b, level, format);
        if (err != null) {
            if (wager > 0) { Economy.give(a, wager); Economy.give(b, wager); }
            msg(a, "§cThe battle couldn't start: " + err); msg(b, "§cThe battle couldn't start: " + err); return;
        }
        Object battle = Cob.battleOf(a);
        UUID id = null; try { id = (UUID) Cob.call(battle, "getBattleId"); } catch (Throwable ignored) {}
        LIVE.add(new Live(a.getUUID(), b.getUUID(), wager, tick, id));
        for (ServerPlayer p : List.of(a, b)) msg(p, "§aBattle on! §7" + cap(format) + (level > 0 ? " at Lv. " + level : "") + (wager > 0 ? " · §6" + Fmt.cd(wager * 2) + " §7to the winner" : "") + "§7. Your real Pokémon won't gain or lose anything.");
    }

    static Live liveFor(UUID battle) { for (Live l : LIVE) if (l.battle() != null && l.battle().equals(battle)) return l; return null; }

    static void finish(Live l, UUID winner, String how) {
        LIVE.remove(l);
        ServerPlayer a = server.getPlayerList().getPlayer(l.a()), b = server.getPlayerList().getPlayer(l.b());
        if (winner == null) {        // no result: everyone gets their wager back
            if (l.wager() > 0) { if (a != null) Economy.give(a, l.wager()); if (b != null) Economy.give(b, l.wager()); }
            for (ServerPlayer p : new ServerPlayer[]{a, b}) if (p != null) msg(p, "§7The battle ended without a winner" + (l.wager() > 0 ? ". Wagers returned." : "."));
            return;
        }
        ServerPlayer w = server.getPlayerList().getPlayer(winner);
        if (l.wager() > 0 && w != null) Economy.give(w, l.wager() * 2);
        String wn = w != null ? name(w) : "Your opponent";
        for (ServerPlayer p : new ServerPlayer[]{a, b}) if (p != null)
            msg(p, (p.getUUID().equals(winner) ? "§a§lYou win" + how + "!" : "§c" + wn + " wins" + how + ".") + (l.wager() > 0 ? " §6" + Fmt.cd(l.wager() * 2) + " §7goes to " + wn + "." : ""));
    }

    static void hook() {
        try {
            Class<?> ev = Class.forName("com.cobblemon.mod.common.api.events.CobblemonEvents");
            Leaderboards.subscribe(ev.getField("BATTLE_VICTORY").get(null), x -> {
                try {
                    Object battle = Cob.call(x, "getBattle"); Live l = liveFor((UUID) Cob.call(battle, "getBattleId"));
                    if (l == null) return;
                    UUID win = null;
                    for (Object w : (List<?>) Cob.call(x, "getWinners")) if (w.getClass().getSimpleName().equals("PlayerBattleActor")) win = (UUID) Cob.call(w, "getUuid");
                    finish(l, win, "");
                } catch (Throwable ignored) {}
            });
            Leaderboards.subscribe(ev.getField("BATTLE_FLED").get(null), x -> {
                try {
                    Object battle = Cob.call(x, "getBattle"); Live l = liveFor((UUID) Cob.call(battle, "getBattleId"));
                    if (l == null) return;
                    UUID fled = (UUID) Cob.call(Cob.call(x, "getPlayer"), "getUuid");
                    finish(l, fled.equals(l.a()) ? l.b() : l.a(), " (the other trainer forfeited)");
                } catch (Throwable ignored) {}
            });
        } catch (Throwable t) { GtsMod.LOG.error("[JCL] Challenge: couldn't hook battle events", t); }
    }

    @SubscribeEvent
    public static void onTick(ServerTickEvent.Post e) {
        tick++;
        if (tick % 20 != 0 || server == null) return;
        INVITES.values().removeIf(i -> {
            if (i.expires() > tick) return false;
            ServerPlayer f = server.getPlayerList().getPlayer(i.from());
            if (f != null) msg(f, "§7Your challenge expired.");
            return true;
        });
        // a battle that's gone (server restart, crash, disconnect) without a result refunds the wagers
        for (Live l : new ArrayList<>(LIVE)) {
            ServerPlayer a = server.getPlayerList().getPlayer(l.a());
            if (tick - l.started() > 40 && (a == null || Cob.battleOf(a) == null)) finish(l, null, "");
        }
    }

    @SubscribeEvent
    public static void onCommands(RegisterCommandsEvent e) {
        e.getDispatcher().register(Commands.literal("challenge")
                .then(Commands.literal("accept").then(Commands.argument("from", EntityArgument.player()).executes(c -> { accept(c.getSource().getPlayerOrException(), EntityArgument.getPlayer(c, "from")); return 1; })))
                .then(Commands.literal("decline").then(Commands.argument("from", EntityArgument.player()).executes(c -> {
                    ServerPlayer to = c.getSource().getPlayerOrException(), from = EntityArgument.getPlayer(c, "from");
                    Invite i = INVITES.get(to.getUUID());
                    if (i != null && i.from().equals(from.getUUID())) { INVITES.remove(to.getUUID()); msg(to, "§7Challenge declined."); msg(from, "§7" + name(to) + " declined your challenge."); }
                    return 1; })))
                .then(Commands.literal("toggle").executes(c -> {
                    ServerPlayer p = c.getSource().getPlayerOrException();
                    if (BLOCKED.remove(p.getUUID())) msg(p, "§aYou're taking challenges again."); else { BLOCKED.add(p.getUUID()); msg(p, "§7You won't receive challenges. §8/challenge toggle to turn them back on."); }
                    return 1; }))
                .then(Commands.argument("player", EntityArgument.player())
                        .executes(c -> { openMenu(c.getSource().getPlayerOrException(), EntityArgument.getPlayer(c, "player")); return 1; })));
        // staff: start a challenge between two players straight away (for testing)
        e.getDispatcher().register(Commands.literal("jcl").requires(s -> s.hasPermission(2)).then(Commands.literal("challenge").then(Commands.literal("force")
                .then(Commands.argument("a", EntityArgument.player()).then(Commands.argument("b", EntityArgument.player())
                        .executes(c -> { start(EntityArgument.getPlayer(c, "a"), EntityArgument.getPlayer(c, "b"), DEFAULT_LEVEL, "singles", 0); return 1; })
                        .then(Commands.argument("level", IntegerArgumentType.integer(0, 100)).executes(c -> { start(EntityArgument.getPlayer(c, "a"), EntityArgument.getPlayer(c, "b"), IntegerArgumentType.getInteger(c, "level"), "singles", 0); return 1; }))))))
                .then(Commands.literal("challenge").then(Commands.literal("list").executes(c -> {
                    c.getSource().sendSuccess(() -> Component.literal("§7Open challenges: " + INVITES.size() + " · battles running: " + LIVE.size()), false); return 1; }))));
    }
    static String fmt(String s) { return s.equalsIgnoreCase("doubles") ? "doubles" : "singles"; }
}
