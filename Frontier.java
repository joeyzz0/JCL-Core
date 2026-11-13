package net.joeyscobblemon.gts;

import java.time.LocalDate;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import net.joeyscobblemon.gts.FrontierData.Bracket;
import net.joeyscobblemon.gts.FrontierData.Format;
import net.joeyscobblemon.gts.FrontierData.Length;
import net.joeyscobblemon.gts.FrontierData.Set6;

/**
 * The Battle Frontier: a dungeon-crawler of battles in its own dimension.
 *
 * Safety rules this class follows everywhere:
 *  - The player's own Pokémon are never touched. A run copies the chosen team into a party that lives only
 *    in memory, and all battles use that copy. Rentals live in the same kind of in-memory party, so they can
 *    never be traded, stored or kept: when the run ends, the party simply stops existing.
 *  - Rewards build up in the run's "pot" and are paid when the run ends, however it ends (win, loss, leaving,
 *    logging out, server stopping). A crash is caught on the next start and the pot is paid on next login.
 */
@EventBusSubscriber(modid = "jcl_gts")
public final class Frontier {
    private Frontier() {}

    static final String DIM = "jcl_gts:frontier";
    static final int PLOT_SPACING = 1024, ROOM_Y = 64, ROOM_SIZE = 48, ROOM_HEIGHT = 48, ROOM_PAD = 10;
    static final Random RNG = new Random();
    static MinecraftServer server;

    // ------------------------------------------------------------------ run state

    enum State { STARTING, BATTLE, DOORS, BUSY, ENDED }

    static final class Setup {
        String bracket = "rookie", format = "singles", length = "sprint";
        final Map<String, String> gimmicks = new LinkedHashMap<>();   // id -> off / both / enemy / player
        final Set<String> mods = new HashSet<>();
        boolean rental, legendaries, daily;
        String ally;      // co-op with a Champion as your partner instead of a friend
        Setup copy() { Setup s = new Setup(); s.bracket = bracket; s.format = format; s.length = length; s.gimmicks.putAll(gimmicks); s.mods.addAll(mods); s.rental = rental; s.legendaries = legendaries; s.daily = daily; s.ally = ally; return s; }
    }

    static final class Door { String room; String type; String champion; List<Set6> preview; boolean hidden; }
    /** Admin debugging: the next room this run goes into, whatever door is picked. */
    static final Map<UUID, String[]> FORCE_NEXT = new HashMap<>();

    static final class Run {
        UUID player; String name; Setup setup; int plot; double mult;
        /** The run copies that actually battle, and the UUIDs of the real party Pokémon they're shown as. */
        List<Object> team = new ArrayList<>(); List<UUID> origIds = new ArrayList<>();
        List<Object> partnerTeam = new ArrayList<>(); List<UUID> partnerOrigIds = new ArrayList<>();
        int floor;                    // rooms cleared
        long potCd, potBp; int streak, cleanRooms; boolean anyFaint;
        State state = State.STARTING; String room = "battle", roomType = null, champion = null;
        Entity npc; UUID battleId; int battleStartTick = -1;
        List<Door> doors = new ArrayList<>();
        Set<String> boons = new HashSet<>(); int curse, clover, extraRolls, gambleStreak, championWins; boolean secondChanceUsed, faintThisBattle, brainFight, skipNext;
        String brain; long roomMult100 = 100; List<Set6> preview; int missing;
        UUID partner; String partnerName; Entity npc2;     // co-op only
        Map<UUID, List<ItemStack>> overflow = new HashMap<>();     // loot that didn't fit, handed over back in the lobby
        int forceDuo = -1;                                 // admin debugging: the tag team for the next co-op Champion Room
        boolean openDoorsNow;                              // a menu room just finished: show the doors straight away
        BlockPos door; int doorSide;                       // the doorway out of the current room (0 front, 1 left, 2 right)
        List<Object[]> pairs = new ArrayList<>();         // {battle clone, run copy} for the current battle
        String ally; Entity allyNpc;                                             // co-op with a Champion partner
        boolean coop() { return partner != null || ally != null; }
        List<UUID> members() { List<UUID> m = new ArrayList<>(); m.add(player); if (partner != null) m.add(partner); return m; }
    }

    /** Co-op partners (both directions) and pending invites (invitee -> inviter). */
    static final Map<UUID, UUID> COOP = new HashMap<>();
    static final Map<UUID, UUID> INVITES = new HashMap<>();

    static List<ServerPlayer> online(Run r) {
        List<ServerPlayer> out = new ArrayList<>();
        for (UUID u : r.members()) { ServerPlayer p = server.getPlayerList().getPlayer(u); if (p != null) out.add(p); }
        return out;
    }

    static java.util.Collection<Run> runs() { return new java.util.LinkedHashSet<>(RUNS.values()); }

    static final Map<UUID, Run> RUNS = new HashMap<>();
    static boolean SPAWNING;
    static final Map<UUID, Setup> SETUPS = new HashMap<>();
    static final Set<Integer> PLOTS = new HashSet<>();
    static int tick;
    static final List<long[]> TIMERS_AT = new ArrayList<>();
    static final List<Runnable> TIMERS = new ArrayList<>();

    static void later(int ticks, Runnable r) { TIMERS_AT.add(new long[]{tick + ticks}); TIMERS.add(r); }

    static boolean inRun(ServerPlayer p) { return RUNS.containsKey(p.getUUID()); }
    static ServerLevel frontier() { return server == null ? null : Cob.level(server, DIM); }
    static void msg(ServerPlayer p, String s) { p.sendSystemMessage(Component.literal("§b§l[Frontier] §r" + s)); }

    // ------------------------------------------------------------------ lifecycle

    @SubscribeEvent
    public static void onStart(ServerStartedEvent e) {
        server = e.getServer();
        STOPPING = false;
        FrontierData.load();
        FrontierData.read(server);
        if (frontier() == null) GtsMod.LOG.error("[JCL] The Frontier dimension (jcl_gts:frontier) didn't load. Runs can't start until it does.");
        rollDay();
        later(100, FrontierBoards::refreshAll);
    }

    @SubscribeEvent
    public static void onStop(ServerStoppingEvent e) {
        STOPPING = true;     // no teleports while the server shuts down; players return to the lobby on their next login
        for (Run r : runs()) finish(r, null, false, "§7The server is restarting. Your rewards so far have been paid.");
        FrontierData.save();
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer p)) return;
        String id = p.getUUID().toString();
        FrontierData.Pending pd = FrontierData.data.pending.remove(id);
        if (pd != null) {
            if (pd.cd > 0) Economy.give(p, pd.cd);
            FrontierData.addBp(id, pd.bp);
            FrontierData.save();
            msg(p, "§7Your last Frontier run was cut short, so its rewards were paid now: §6" + Fmt.cd(pd.cd) + "§7 and §b" + pd.bp + " BP§7.");
        }
        // Anyone who logs in inside the Frontier without a run (for example after a crash) is sent back to the lobby.
        if (p.level().dimension().location().toString().equals(DIM)) later(20, () -> toLobby(p));
        if (!RUNS.containsKey(p.getUUID())) {
            int gone = Cob.removeRentals(p);
            if (gone > 0) msg(p, "§7" + gone + " rental Pokémon from an unfinished run went back to the Frontier.");
        }
        FrontierQuests.onLogin(p);
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer p)) return;
        Run r = RUNS.get(p.getUUID());
        if (r != null) finish(r, p, false, r.partner != null ? "§7Your co-op partner logged out, so the run ended." : null);
        SETUPS.remove(p.getUUID());
        UUID mate = COOP.remove(p.getUUID());
        if (mate != null) COOP.remove(mate);
        INVITES.remove(p.getUUID());
    }

    // ------------------------------------------------------------------ starting a run

    static String why(ServerPlayer p, Setup s) {
        if (inRun(p)) return "You're already in a run.";
        if (Cob.battleOf(p) != null) return "Finish your current battle first.";
        if (frontier() == null) return "The Frontier isn't open yet (its dimension didn't load).";
        Bracket b = FrontierData.bracket(s.bracket);
        if (!unlocked(p, b)) return b.name + " isn't unlocked yet. " + unlockText(b);
        Format f = FrontierData.format(s.format);
        if (s.rental) {
            if (!Cob.party(p).isEmpty()) return "Rental runs need an empty party. Put all your Pokémon in your PC first.";
            return null;
        }
        List<Object> team = pickTeam(p, f.team);
        if (team.size() < f.team) return "You need at least " + f.team + " Pokémon in your party for " + f.name + ". The first " + f.team + " in your party are used.";
        for (Object pk : team) {
            Set<String> l = Cob.labels(pk);
            boolean restricted = l.contains("legendary") || l.contains("mythical") || l.contains("ultra_beast") || l.contains("paradox") || l.contains("restricted");
            if (restricted && !s.legendaries) return Cob.speciesName(pk) + " is a legendary or restricted Pokémon. Turn on \"Allow Legendaries\" (rewards −80%) or swap it out.";
            if (b.littleCup && !littleCupOk(pk)) return Cob.speciesName(pk) + " can't enter the Little Cup: only Pokémon that can still evolve and haven't evolved yet.";
        }
        if (Loaner.hasLoaners(p)) return "Loaner Pokémon can't enter the Battle Frontier. Return them to Quartermaster Rhea first.";
        if (!s.rental && s.mods.contains("monotype") && sharedType(team) == null) return "Monotype is on, but your team doesn't share a type.";
        for (var g : s.gimmicks.entrySet()) {
            String v = g.getValue();
            if (v.equals("both") || v.equals("player")) continue;          // the player may use it
            for (String id : GEAR.getOrDefault(g.getKey(), List.of())) {
                var it = Crates.resolve(id);
                if (it == net.minecraft.world.item.Items.BARRIER) continue;
                if (carries(p, id) || Cob.equippedAccessory(p, it)) {
                    var gm = FrontierData.gimmick(g.getKey());
                    return "Your " + (gm == null ? g.getKey() : gm.name) + " setting doesn't let you use it, so take off your " + new ItemStack(it).getHoverName().getString() + " first (check your accessory slots too).";
                }
            }
        }
        return null;
    }

    /** Gear that lets a player use each gimmick. The Omni Ring counts for all four. */
    static final Map<String, List<String>> GEAR = Map.of(
            "mega", List.of("mega_showdown:omni_ring", "mega_showdown:mega_bracelet", "mega_showdown:mega_ring", "mega_showdown:may_bracelet", "mega_showdown:brendan_mega_cuff",
                    "mega_showdown:lysandre_ring", "mega_showdown:mega_bracelet_red", "mega_showdown:mega_bracelet_pink", "mega_showdown:mega_bracelet_yellow",
                    "mega_showdown:mega_bracelet_green", "mega_showdown:mega_bracelet_blue", "mega_showdown:mega_bracelet_black"),
            "zmove", List.of("mega_showdown:omni_ring", "mega_showdown:z_ring", "mega_showdown:z_ring_red", "mega_showdown:z_ring_pink", "mega_showdown:z_ring_yellow",
                    "mega_showdown:z_ring_green", "mega_showdown:z_ring_blue", "mega_showdown:z_ring_black", "mega_showdown:olivias_z_ring", "mega_showdown:hapus_z_ring",
                    "mega_showdown:z_power_ring", "mega_showdown:olivia_z_power_ring", "mega_showdown:gladion_z_power_ring", "mega_showdown:nanu_z_power_ring",
                    "mega_showdown:hapu_z_power_ring", "mega_showdown:rocket_z_power_ring"),
            "dynamax", List.of("mega_showdown:omni_ring", "mega_showdown:dynamax_band"),
            "tera", List.of("mega_showdown:omni_ring", "mega_showdown:tera_orb"));

    static boolean carries(ServerPlayer p, String id) {
        var it = Crates.resolve(id);
        for (ItemStack st : p.getInventory().items) if (st.is(it)) return true;
        for (ItemStack st : p.getInventory().offhand) if (st.is(it)) return true;
        return false;
    }

    static List<Object> pickTeam(ServerPlayer p, int n) {
        List<Object> out = new ArrayList<>();
        for (Object pk : Cob.party(p)) { if (out.size() >= n) break; out.add(pk); }
        return out;
    }

    static String sharedType(List<Object> team) {
        if (team.isEmpty()) return null;
        for (String t : Cob.types(team.get(0))) {
            boolean all = true;
            for (Object pk : team) if (!Cob.types(pk).contains(t)) { all = false; break; }
            if (all) return t;
        }
        return null;
    }

    static boolean littleCupOk(Object pk) {
        try {
            Object sp = Cob.call(pk, "getSpecies");
            Object pre = Cob.call(sp, "getPreEvolution");
            Object evos = Cob.call(sp, "getEvolutions");
            boolean canEvolve = evos instanceof java.util.Collection<?> c && !c.isEmpty();
            return pre == null && canEvolve;
        } catch (Throwable t) { return true; }
    }

    static boolean unlocked(ServerPlayer p, Bracket b) {
        if (b.unlock == null || b.unlock.isBlank() || p.hasPermissions(2)) return true;
        String id = p.getUUID().toString();
        for (String req : b.unlock.split("\\|")) {
            if (req.startsWith("title:")) {
                if (Titles.data.unlocked.getOrDefault(id, Set.of()).contains(req.substring(6))) return true;
            } else if (FrontierData.cleared(id, req)) return true;
        }
        return false;
    }

    static String unlockText(Bracket b) {
        List<String> parts = new ArrayList<>();
        boolean titleListed = false;
        for (String req : b.unlock.split("\\|")) {
            if (req.startsWith("title:")) { if (!titleListed) parts.add("hold any regional Champion title"); titleListed = true; }
            else { String[] r = req.split(":"); parts.add("clear a " + FrontierData.length(r[1]).name + " run in the " + FrontierData.bracket(r[0]).name); }
        }
        return "To unlock it, " + String.join(", or ", parts) + ".";
    }

    /** The total reward multiplier for a setup. */
    /** Every effect on the reward multiplier, as menu lines ("+20% Mega (enemy only)", "×0.70 Champion partner"...). */
    static List<String> breakdown(Setup s) {
        List<String> out = new ArrayList<>();
        int enemyAll = 0;
        for (FrontierData.Gimmick g : FrontierData.cfg.gimmicks) {
            String v = s.gimmicks.getOrDefault(g.id, "off");
            if (v.equals("enemy")) { out.add(sign(g.enemy) + " §7" + g.name + " (enemy only)"); enemyAll++; }
            else if (v.equals("player")) out.add(sign(g.player) + " §7" + g.name + " (you only)");
        }
        if (enemyAll == FrontierData.cfg.gimmicks.size() && enemyAll > 0) out.add(sign(FrontierData.cfg.gauntletBonus) + " §7Gauntlet Mode bonus");
        for (String id : s.mods) { var md = FrontierData.modifier(id); if (md != null) out.add(sign(md.bonus) + " §7" + md.name); }
        var fm = FrontierData.format(s.format);
        if (fm.mult != 1) out.add(times(fm.mult) + " §7" + fm.name);
        if (s.rental) { if (FrontierData.cfg.rentalMult != 1) out.add(times(FrontierData.cfg.rentalMult) + " §7Rental team"); out.add(times(1.3) + " §7Rental team bonus"); }
        if (s.legendaries) out.add(times(FrontierData.cfg.legendaryMult) + " §7Legendaries allowed");
        if (s.format.equals("coop") && s.ally != null) out.add(times(0.7) + " §7Champion partner");
        if (s.daily) out.add("§b×2 BP §7Daily Challenge");
        if (out.isEmpty()) out.add("§8No modifiers: standard rewards.");
        return out;
    }

    static String sign(double d) { return (d >= 0 ? "§a+" : "§c") + Math.round(d * 100) + "%"; }
    static String times(double d) { return (d >= 1 ? "§a×" : "§c×") + String.format(java.util.Locale.ROOT, "%.2f", d); }

    static double multiplier(Setup s) {
        double m = 1;
        int enemyAll = 0;
        for (FrontierData.Gimmick g : FrontierData.cfg.gimmicks) {
            String v = s.gimmicks.getOrDefault(g.id, "off");
            if (v.equals("enemy")) { m += g.enemy; enemyAll++; }
            else if (v.equals("player")) m += g.player;
        }
        if (enemyAll == FrontierData.cfg.gimmicks.size() && enemyAll > 0) m += FrontierData.cfg.gauntletBonus;
        for (String id : s.mods) { var md = FrontierData.modifier(id); if (md != null) m += md.bonus; }
        m *= FrontierData.format(s.format).mult;
        if (s.rental) m *= FrontierData.cfg.rentalMult;
        if (s.rental) m *= 1.3;     // a random team you didn't build pays 30% more
        if (s.legendaries) m *= FrontierData.cfg.legendaryMult;
        if (s.format.equals("coop") && s.ally != null) m *= 0.7;     // a Champion partner makes runs easier
        return Math.max(FrontierData.cfg.minMult, m);
    }

    /**
     * Fills a player's run team. Own team: copies of the first N party Pokémon. Rental: the rentals go into the
     * player's (empty) real party, tagged as rentals, so they're owned and sent out normally; they're removed the
     * moment the run ends, and any that survive a crash are removed on the next login.
     */
    static boolean buildTeam(ServerPlayer p, Setup s, Bracket b, Format f, List<Object> copies, List<UUID> origIds) {
        List<Object> originals = new ArrayList<>();
        if (s.rental) {
            ADDING_RENTALS.add(p.getUUID());     // rentals never count as caught in the Pokédex
            try {
                for (Object pk : FrontierTeams.rental(b, f.team, s)) {
                    Cob.markRental(pk);
                    Object[] snap = Cob.dexSnapshot(p, pk);
                    if (snap != null) DEX_SNAP.computeIfAbsent(p.getUUID(), k -> new ArrayList<>()).add(snap);
                    if (Cob.addToParty(p, pk)) originals.add(pk);
                }
            } finally { ADDING_RENTALS.remove(p.getUUID()); }
        } else originals.addAll(pickTeam(p, f.team));
        for (Object orig : originals) {
            Object c = Cob.clone(orig, server.registryAccess());
            if (c == null) continue;
            int lvl = Cob.level(c);
            Cob.setLevel(c, b.mode.equals("flat") ? b.level : Math.min(lvl, b.level));
            Cob.heal(c);
            if (s.mods.contains("noitems")) Cob.setHeldItem(c, ItemStack.EMPTY);
            else stripPlayerGimmicks(c, s);
            copies.add(c); origIds.add(Cob.uuid(orig));
        }
        if (copies.isEmpty()) { if (s.rental) Cob.removeRentals(p); return false; }
        return true;
    }

    /** The real party Pokémon behind a run team, in order. Null if any have gone missing. */
    static List<Object> originals(ServerPlayer p, List<UUID> ids) {
        List<Object> party = Cob.party(p), out = new ArrayList<>();
        for (UUID id : ids) {
            Object found = null;
            for (Object pk : party) if (id != null && id.equals(Cob.uuid(pk))) found = pk;
            if (found == null) return null;
            out.add(found);
        }
        return out;
    }

    static void start(ServerPlayer p, Setup setup) {
        Setup s = setup.copy();
        if (s.daily) applyDaily(s);
        if (s.mods.contains("randomrental")) s.rental = true;
        boolean coop = s.format.equals("coop");
        ServerPlayer mate = null;
        if (coop) {
            UUID m = COOP.get(p.getUUID());
            mate = m == null ? null : server.getPlayerList().getPlayer(m);
            if (mate == null && (s.ally == null || !FrontierData.teams.champions.containsKey(s.ally))) { msg(p, "§cCo-op needs a partner: invite a friend with §f/frontier coop invite <player>§c, or pick a Champion partner in the menu."); return; }
            if (mate != null) {
                s.ally = null;
                if (!nearLobby(mate)) { msg(p, "§cYour partner needs to be in the Frontier lobby too."); return; }
                String theirs = why(mate, s);
                if (theirs != null) { msg(p, "§cYour partner can't start: " + theirs); msg(mate, "§cYou can't start the co-op run: " + theirs); return; }
            }
        }
        String problem = why(p, s);
        if (problem != null) { msg(p, "§c" + problem); return; }
        Bracket b = FrontierData.bracket(s.bracket);
        Format f = FrontierData.format(s.format);
        Run r = new Run();
        if (!buildTeam(p, s, b, f, r.team, r.origIds)) { msg(p, "§cThe Frontier couldn't prepare your team. Please tell an admin."); return; }
        if (coop && mate != null && !buildTeam(mate, s, b, f, r.partnerTeam, r.partnerOrigIds)) {
            if (s.rental) Cob.removeRentals(p);
            msg(p, "§cThe Frontier couldn't prepare your partner's team. Please tell an admin."); return;
        }
        int plot = 0; while (PLOTS.contains(plot)) plot++;
        PLOTS.add(plot);
        r.player = p.getUUID(); r.name = p.getGameProfile().getName(); r.setup = s; r.plot = plot; r.mult = multiplier(s);
        RUNS.put(p.getUUID(), r);
        FrontierData.data.names.put(p.getUUID().toString(), r.name);
        if (coop && mate == null) r.ally = s.ally;
        if (coop && mate != null) {
            r.partner = mate.getUUID(); r.partnerName = mate.getGameProfile().getName();
            RUNS.put(mate.getUUID(), r);
            FrontierData.data.names.put(mate.getUUID().toString(), r.partnerName);
        }
        forceChunks(r, true);
        for (ServerPlayer pl : online(r)) {
            msg(pl, "§aRun started! §f" + b.name + " §8· §f" + f.name + " §8· §f" + FrontierData.length(s.length).name + " §8· §7reward ×" + String.format(java.util.Locale.ROOT, "%.2f", r.mult)
                    + (s.rental ? " §8· §dRental team" : "") + (r.partner != null ? " §8· §bwith " + (pl == p ? r.partnerName : r.name) : "")
                    + (r.ally != null ? " §8· §bpartnered with " + FrontierData.teams.champions.get(r.ally).name : ""));
            if (s.rental) msg(pl, "§7Your rental team is on loan. It goes back to the Frontier when the run ends.");
        }
        if (mate != null) msg(mate, "§7" + r.name + " leads the run and chooses the doors.");
        enterRoom(r, p, "battle", null);
    }

    /** If a gimmick isn't allowed for the player, take that gimmick's item off their run copy. */
    static void stripPlayerGimmicks(Object pk, Setup s) {
        ItemStack held = Cob.heldItem(pk);
        if (held.isEmpty()) return;
        String id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(held.getItem()).toString();
        boolean mega = FrontierData.teams.megaStones.containsValue(id);
        boolean z = id.startsWith("mega_showdown:") && id.endsWith("_z");
        String megaSet = s.gimmicks.getOrDefault("mega", "off"), zSet = s.gimmicks.getOrDefault("zmove", "off");
        if ((mega && !(megaSet.equals("both") || megaSet.equals("player"))) || (z && !(zSet.equals("both") || zSet.equals("player")))) Cob.setHeldItem(pk, ItemStack.EMPTY);
    }

    // ------------------------------------------------------------------ rooms

    static BlockPos origin(Run r) { return new BlockPos(r.plot * PLOT_SPACING, ROOM_Y, 0); }

    static void forceChunks(Run r, boolean on) {
        ServerLevel lvl = frontier(); if (lvl == null) return;
        BlockPos o = origin(r);
        for (int cx = (o.getX() - ROOM_PAD) >> 4; cx <= (o.getX() + ROOM_SIZE + ROOM_PAD) >> 4; cx++)
            for (int cz = (o.getZ() - ROOM_PAD) >> 4; cz <= (o.getZ() + ROOM_SIZE + ROOM_PAD) >> 4; cz++) lvl.setChunkForced(cx, cz, on);
    }

    static void clearPlot(Run r) {
        ServerLevel lvl = frontier(); if (lvl == null) return;
        BlockPos o = origin(r);
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int x = -ROOM_PAD; x < ROOM_SIZE + ROOM_PAD; x++) for (int y = -12; y < ROOM_HEIGHT; y++) for (int z = -ROOM_PAD; z < ROOM_SIZE + ROOM_PAD; z++) {
            m.set(o.getX() + x, o.getY() + y, o.getZ() + z);
            if (!lvl.getBlockState(m).isAir()) lvl.setBlock(m, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
        }
        for (Entity en : lvl.getEntities((Entity) null, new net.minecraft.world.phys.AABB(o.offset(-ROOM_PAD, -12, -ROOM_PAD)).expandTowards(ROOM_SIZE + 2 * ROOM_PAD, ROOM_HEIGHT + 12, ROOM_SIZE + 2 * ROOM_PAD), en -> !(en instanceof ServerPlayer)))
            en.discard();
    }

    /** Places the room. Returns {playerSpot, trainerSpot}. Uses a structure template if an admin made one, otherwise a plain arena. */
    static BlockPos[] buildRoom(Run r, String key) {
        ServerLevel lvl = frontier();
        BlockPos o = origin(r);
        Optional<StructureTemplate> tmpl = Optional.empty();
        for (String name : new String[]{key, key.contains("_") ? key.substring(0, key.indexOf('_')) : key, "default"}) {
            try { tmpl = lvl.getStructureManager().get(Cob.rl("jcl_gts:frontier/" + name)); } catch (Exception ignored) {}
            if (tmpl.isPresent()) break;
        }
        BlockPos player = o.offset(5, 1, 16), trainer = o.offset(27, 1, 16);
        if (tmpl.isPresent()) {
            tmpl.get().placeInWorld(lvl, o, o, new StructurePlaceSettings(), lvl.random, Block.UPDATE_CLIENTS);
            var size = tmpl.get().getSize();
            BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
            for (int x = 0; x < size.getX(); x++) for (int y = 0; y < size.getY(); y++) for (int z = 0; z < size.getZ(); z++) {
                m.set(o.getX() + x, o.getY() + y, o.getZ() + z);
                BlockState st = lvl.getBlockState(m);
                if (st.is(Blocks.EMERALD_BLOCK)) { player = m.above().immutable(); lvl.setBlock(m, lvl.getBlockState(m.east()), Block.UPDATE_CLIENTS); }
                else if (st.is(Blocks.REDSTONE_BLOCK)) { trainer = m.above().immutable(); lvl.setBlock(m, lvl.getBlockState(m.east()), Block.UPDATE_CLIENTS); }
            }
        } else {
            BlockPos[] themed = FrontierRooms.build(lvl, o, r.room, r.roomType);
            if (themed != null) return themed;
            Block floor = floorFor(r.roomType != null ? r.roomType : key);
            for (int x = 0; x <= 32; x++) for (int z = 0; z <= 32; z++) {
                lvl.setBlock(o.offset(x, 0, z), floor.defaultBlockState(), Block.UPDATE_CLIENTS);
                if (x == 0 || z == 0 || x == 32 || z == 32) for (int y = 1; y <= 9; y++) lvl.setBlock(o.offset(x, y, z), Blocks.TINTED_GLASS.defaultBlockState(), Block.UPDATE_CLIENTS);
            }
            for (int[] c : new int[][]{{1, 1}, {31, 1}, {1, 31}, {31, 31}, {16, 1}, {16, 31}, {1, 16}, {31, 16}}) lvl.setBlock(o.offset(c[0], 0, c[1]), Blocks.SEA_LANTERN.defaultBlockState(), Block.UPDATE_CLIENTS);
        }
        return new BlockPos[]{player, trainer};
    }

    static Block floorFor(String type) {
        return switch (type == null ? "" : type) {
            case "fire" -> Blocks.RED_CONCRETE; case "water" -> Blocks.BLUE_CONCRETE; case "grass" -> Blocks.MOSS_BLOCK; case "electric" -> Blocks.YELLOW_CONCRETE;
            case "ice" -> Blocks.PACKED_ICE; case "fighting" -> Blocks.BROWN_CONCRETE; case "poison" -> Blocks.PURPLE_CONCRETE; case "ground" -> Blocks.COARSE_DIRT;
            case "flying" -> Blocks.LIGHT_BLUE_CONCRETE; case "psychic" -> Blocks.PINK_CONCRETE; case "bug" -> Blocks.LIME_CONCRETE; case "rock" -> Blocks.STONE_BRICKS;
            case "ghost" -> Blocks.SOUL_SOIL; case "dragon" -> Blocks.PURPUR_BLOCK; case "dark" -> Blocks.BLACK_CONCRETE; case "steel" -> Blocks.IRON_BLOCK;
            case "fairy" -> Blocks.MAGENTA_CONCRETE; case "champion", "brain" -> Blocks.GOLD_BLOCK; default -> Blocks.POLISHED_DEEPSLATE;
        };
    }

    static boolean isBattle(String room) {
        return switch (room) { case "battle", "type", "doubles", "elite", "champion", "brain", "mystery" -> true; default -> false; };
    }

    static void enterRoom(Run r, ServerPlayer p, String room, Door door) {
        r.state = State.BUSY;
        r.room = room;
        r.roomType = door != null ? door.type : null;
        r.champion = door != null ? door.champion : null;
        r.preview = door != null ? door.preview : null;
        r.roomMult100 = 100;
        if (room.equals("mystery")) {
            String[] pool = {"battle", "type", "elite", "champion", "loot", "heal"};
            r.room = pool[RNG.nextInt(pool.length)];
            r.roomMult100 = 125;
            if (r.room.equals("type")) r.roomType = randomType();
            if (r.room.equals("champion")) r.champion = randomChampion(r);
            r.preview = null;      // the mystery door's preview was for a different room
        }
        var cfgRoom = FrontierData.room(r.room);
        if (cfgRoom != null && cfgRoom.mult > 1) r.roomMult100 = Math.round(r.roomMult100 * cfgRoom.mult);
        clearPlot(r);
        String key = r.room.equals("type") && r.roomType != null ? "type_" + r.roomType : r.room.equals("brain") && r.brain != null ? "brain_" + r.brain : r.room;
        BlockPos[] spots = buildRoom(r, key);
        r.door = spots.length > 2 ? spots[2] : null;
        r.doorSide = FrontierRooms.LAST_SIDE;
        if (r.door != null) later(2, () -> spawnDoor(r, false));
        ServerLevel lvl = frontier();
        p.teleportTo(lvl, spots[0].getX() + 0.5, spots[0].getY(), spots[0].getZ() + 0.5, -90f, 0f);
        ServerPlayer mate = r.partner != null ? server.getPlayerList().getPlayer(r.partner) : null;
        if (mate != null) mate.teleportTo(lvl, spots[0].getX() + 0.5, spots[0].getY(), spots[0].getZ() + 2.5, -90f, 0f);
        if (isBattle(r.room)) {
            if (r.skipNext) { r.skipNext = false; for (ServerPlayer pl : online(r)) msg(pl, "§7You slip past this room."); r.floor++; later(10, () -> afterRoom(r)); return; }
            spawnTrainer(r, lvl, spots[1]);
            if (r.ally != null) spawnAlly(r, lvl, spots[0]);
            r.state = State.STARTING;
            r.battleStartTick = tick + 60;
            for (ServerPlayer pl : online(r)) { title(pl, "", roomTitle(r)); pl.playNotifySound(SoundEvents.IRON_DOOR_OPEN, SoundSource.MASTER, 0.6f, 0.8f); }
        } else {
            for (ServerPlayer pl : online(r)) title(pl, "", roomTitle(r));
            roomEffect(r, p);
        }
    }

    static final Map<String, String> TYPE_COLOUR = Map.ofEntries(Map.entry("normal", "§f"), Map.entry("fire", "§c"), Map.entry("water", "§9"), Map.entry("grass", "§a"),
            Map.entry("electric", "§e"), Map.entry("ice", "§b"), Map.entry("fighting", "§6"), Map.entry("poison", "§5"), Map.entry("ground", "§6"), Map.entry("flying", "§3"),
            Map.entry("psychic", "§d"), Map.entry("bug", "§2"), Map.entry("rock", "§7"), Map.entry("ghost", "§5"), Map.entry("dragon", "§9"), Map.entry("dark", "§8"),
            Map.entry("steel", "§7"), Map.entry("fairy", "§d"));

    /** The small, themed line that shows as you enter a room. */
    static String roomTitle(Run r) {
        String name = switch (r.room) {
            case "type" -> TYPE_COLOUR.getOrDefault(r.roomType, "§f") + cap(r.roomType) + " Room";
            case "champion" -> "§6Champion Room";
            case "brain" -> "§cThe Frontier Brain";
            case "elite" -> "§4Elite Room";
            case "doubles" -> "§bDouble Trouble";
            case "loot" -> "§6Loot Room";
            case "heal" -> "§bHealing Spring";
            case "luckyroll" -> "§eLucky Roll";
            case "clover" -> "§aLucky Clover";
            case "shrine" -> "§6The Shrine";
            case "merchant" -> "§2Wandering Merchant";
            case "gamble" -> "§cThe Gamble Room";
            case "cursed" -> "§5The Cursed Room";
            default -> "§fBattle Room";
        };
        return "§8✦ " + name + " §8· §7Room " + (r.floor + 1) + " §8✦";
    }

    static void title(ServerPlayer p, String t, String sub) {
        p.connection.send(new net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket(Component.literal(t)));
        p.connection.send(new net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket(Component.literal(sub)));
    }

    static String cap(String s) { return s == null || s.isEmpty() ? "" : s.substring(0, 1).toUpperCase() + s.substring(1); }

    // ------------------------------------------------------------------ trainers

    static int enemyLevel(Run r) {
        Bracket b = FrontierData.bracket(r.setup.bracket);
        int l = b.level;
        if (r.setup.mods.contains("levelgap")) l += 5;
        if (r.setup.mods.contains("weaker")) l -= 5;
        if (r.curse > 0) l += 3;
        return Math.max(1, Math.min(100, l));
    }

    /**
     * Regular trainers bring as many Pokémon as you do. Champions, Brains and Elite trainers always bring a full six,
     * and so does everyone with the Full Teams modifier. Small Teams takes one away.
     */
    static int enemyTeamSize(Run r) {
        int mine = Math.max(1, r.team.size());
        int n = mine;
        if (r.room.equals("champion") || r.room.equals("brain") || r.room.equals("elite") || r.setup.mods.contains("bigteams")) n = 6;
        if (r.room.equals("doubles")) n = Math.max(n, 4);
        if (r.setup.mods.contains("smallteams")) n = Math.max(1, n - 1);
        return Math.min(6, n);
    }

    static String battleKind(Run r) {
        if (r.room.equals("doubles")) return "doubles";
        return r.setup.format;
    }

    static final int GENERIC_SKINS = 24;

    /**
     * A Champion's skin: config/jcl_skins/champ_<id>.png if an admin has added one, otherwise a generic trainer
     * skin that's always the same for that Champion.
     */
    static String skinFor(String champion) {
        if (champion == null) return "ft_1";
        var ch = FrontierData.teams.champions.get(champion);
        String found = Npcs.findSkin("champ_" + champion, champion, ch == null ? champion : ch.name.replaceAll("^[^ ]+ ", ""), ch == null ? champion : ch.name.replace(' ', '_'));
        if (found != null) return found;
        return "ft_" + (1 + Math.floorMod(champion.hashCode(), GENERIC_SKINS));
    }

    /** Champion pairs that can appear together in co-op Champion Rooms. */
    static final String[][] DUOS = {{"red", "blue"}, {"maxie", "archie"}, {"n", "ghetsis"}, {"ash", "gary"}, {"ash", "paul"}, {"nemona", "arven"},
            {"gladion", "lusamine"}, {"alain", "tobias"}, {"cynthia", "volo"}, {"lance", "iris"}};

    /** Spawns the player's Champion partner beside them for this battle. */
    static void spawnAlly(Run r, ServerLevel lvl, BlockPos playerSpot) {
        var ch = FrontierData.teams.champions.get(r.ally);
        if (ch == null) return;
        Bracket b = FrontierData.bracket(r.setup.bracket);
        Setup plain = r.setup.copy(); plain.gimmicks.replaceAll((k, v) -> v.equals("enemy") ? "off" : v);   // "enemy only" gimmicks aren't for your side
        List<Object> team = FrontierTeams.build(b.littleCup ? FrontierTeams.littleCup(3) : aces(ch.team, 3), FrontierData.bracket(r.setup.bracket).level, true, plain, b, null);
        r.allyNpc = spawnOne(r, lvl, playerSpot.south(2), ch.name, skinFor(r.ally), team, true, -90f);
    }

    static void spawnTrainer(Run r, ServerLevel lvl, BlockPos at) {
        if (r.setup.daily) { long seed = daySeed() * 31337 + r.floor * 7907L + r.room.hashCode(); FrontierTeams.RNG.setSeed(seed); RNG.setSeed(seed ^ 0x9E3779B9L); }
        try { spawnTrainer0(r, lvl, at); }
        finally { if (r.setup.daily) { FrontierTeams.RNG.setSeed(System.nanoTime()); RNG.setSeed(System.nanoTime() ^ 17); } }
    }

    static void spawnTrainer0(Run r, ServerLevel lvl, BlockPos at) {
        boolean elite = r.setup.mods.contains("elite") || r.room.equals("elite") || r.room.equals("champion") || r.room.equals("brain");
        int level = enemyLevel(r);
        String name; String skin; List<Set6> sets;
        Bracket b = FrontierData.bracket(r.setup.bracket);
        var brain = r.room.equals("brain") ? FrontierData.teams.brains.get(r.brain) : null;
        String colour = "";     // plain names: colour codes leak into Cobblemon's battle messages
        // Co-op: sometimes two famous trainers team up against you.
        boolean wantDuo = r.forceDuo >= 0;
        if (r.coop() && r.room.equals("champion") && !b.littleCup && (wantDuo || RNG.nextDouble() < 0.35)) {
            List<String[]> ok = new ArrayList<>();
            for (String[] d : DUOS) if (FrontierData.teams.champions.containsKey(d[0]) && FrontierData.teams.champions.containsKey(d[1])) ok.add(d);
            if (!ok.isEmpty()) {
                String[] d = wantDuo && r.forceDuo < ok.size() ? ok.get(r.forceDuo) : ok.get(RNG.nextInt(ok.size()));
                r.forceDuo = -1;
                var c1 = FrontierData.teams.champions.get(d[0]); var c2 = FrontierData.teams.champions.get(d[1]);
                List<Object> t1 = FrontierTeams.build(aces(c1.team, 3), level, true, r.setup, b, null);
                List<Object> t2 = FrontierTeams.build(aces(c2.team, 3), level, true, r.setup, b, null);
                r.npc = spawnOne(r, lvl, at.north(1), colour + c1.name, skinFor(d[0]), t1, true);
                r.npc2 = spawnOne(r, lvl, at.south(1), colour + c2.name, skinFor(d[1]), t2, true);
                for (ServerPlayer pl : online(r)) msg(pl, "§6§lA tag team! §r§f" + c1.name + " §7and §f" + c2.name + " §7stand together.");
                return;
            }
        }
        if (brain != null) {
            name = brain.name; skin = "brain_" + r.brain;
            sets = FrontierTeams.brainTeam(r.brain, b);
        } else if (r.room.equals("champion") && r.champion != null && FrontierData.teams.champions.containsKey(r.champion)) {
            var ch = FrontierData.teams.champions.get(r.champion);
            name = ch.name; skin = skinFor(r.champion);
            sets = b.littleCup ? FrontierTeams.littleCup(6) : ch.team;     // Little Cup Champions bring unevolved teams
        } else {
            skin = "ft_" + (1 + RNG.nextInt(GENERIC_SKINS));
            name = r.room.equals("type") ? cap(r.roomType) + " Specialist" : FrontierTeams.trainerNameFor(skin);    // the name always fits the skin
            int want = r.coop() ? 6 : enemyTeamSize(r);
            sets = r.preview != null && !r.preview.isEmpty() ? new ArrayList<>(r.preview) : FrontierTeams.randomTeam(r.room.equals("type") ? r.roomType : null, want, b);
            if (sets.size() < want) {                         // a co-op room needs a full six, even if only part was scouted
                for (Set6 extra : FrontierTeams.randomTeam(r.room.equals("type") ? r.roomType : null, want, b)) {
                    if (sets.size() >= want) break;
                    boolean dupe = false; for (Set6 have : sets) if (have.s.equals(extra.s)) dupe = true;
                    if (!dupe) sets.add(extra);
                }
            }
        }
        if (sets == null || sets.isEmpty()) sets = FrontierTeams.randomTeam(null, r.coop() ? 6 : enemyTeamSize(r), b);
        if (sets.isEmpty()) return;
        int size = r.coop() ? Math.min(6, sets.size()) : Math.min(sets.size(), enemyTeamSize(r));
        List<Object> team = FrontierTeams.build(sets.subList(0, Math.max(1, size)), level, elite, r.setup, b, brain);
        if (!r.coop()) { r.npc = spawnOne(r, lvl, at, colour + name, skin, team, elite); return; }
        // Co-op battles need two enemy "actors". The Doubles Duo really are two people; everyone else is one
        // trainer whose team is split with an invisible stand-in, so it looks and plays like one trainer with six.
        int half = (team.size() + 1) / 2;
        List<Object> first = new ArrayList<>(team.subList(0, half)), second = new ArrayList<>(team.subList(half, team.size()));
        if (second.isEmpty() && first.size() > 1) second.add(first.remove(first.size() - 1));
        if (brain != null && "doubles".equals(r.brain)) {
            r.npc = spawnOne(r, lvl, at.north(1), colour + "Nova", skin, first, elite);
            r.npc2 = spawnOne(r, lvl, at.south(1), colour + "Lyra", skin, second, elite);
            return;
        }
        r.npc = spawnOne(r, lvl, at, colour + name, skin, first, elite);
        r.npc2 = spawnOne(r, lvl, at, colour + name, skin, second, elite);
        if (r.npc2 != null) hide(r.npc2);
    }

    /** A Champion's strongest few: the end of their team, where the aces are. */
    static List<Set6> aces(List<Set6> team, int n) {
        if (team.size() <= n) return team;
        return new ArrayList<>(team.subList(team.size() - n, team.size()));
    }

    /** Makes the co-op stand-in trainer invisible, silent and nameless, so only the real trainer is seen. */
    static void hide(Entity e) {
        e.setInvisible(true);
        e.setSilent(true);
        e.setCustomNameVisible(false);
        try { Cob.call(e, "setHideNameTag", true); } catch (Throwable ignored) {}
        e.addTag("jcl_frontier_twin");
    }

    static Entity spawnOne(Run r, ServerLevel lvl, BlockPos at, String name, String skin, List<Object> team, boolean elite) {
        return spawnOne(r, lvl, at, name, skin, team, elite, 90f);
    }

    static Entity spawnOne(Run r, ServerLevel lvl, BlockPos at, String name, String skin, List<Object> team, boolean elite, float yaw) {
        // A fixed UUID per plot and slot, registered under the trainer's name, so tooltip mods (Jade) can show who owns
        // the Pokémon instead of erroring on an unknown owner.
        int slot = r.npc == null ? 0 : r.npc2 == null ? 1 : r.allyNpc == null ? 2 : 3;
        UUID id = UUID.nameUUIDFromBytes(("jcl_frontier:" + r.plot + ":" + slot + ":" + yaw).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        boolean isAlly = yaw < 0;      // the Champion partner faces the other way, and fights on your side
        if (!isAlly) {
            try { var cache = server.getProfileCache(); if (cache != null) cache.add(new com.mojang.authlib.GameProfile(id, name.length() > 16 ? name.substring(0, 16) : name)); } catch (Throwable ignored) {}
            Npcs.NEXT_UUID = id;
        }
        SPAWNING = true;
        Entity npc;
        String sk = Npcs.skinExists(skin) ? skin : "ft_1";
        try { npc = Npcs.spawnSkinned(lvl, "frontier_trainer", sk, at.getX() + 0.5, at.getY(), at.getZ() + 0.5, yaw, "jcl_frontier", "jcl_frontier_" + r.player); }
        finally { SPAWNING = false; }
        if (npc == null) return null;
        npc.setCustomName(Component.literal(name));
        npc.setCustomNameVisible(true);
        for (Object pk : team) { try { Cob.call(pk, "setOriginalTrainer", name); } catch (Throwable ignored) {} }   // tooltip mods expect an original trainer
        Cob.setNpcParty(npc, team);
        Cob.setNpcSkill(npc, r.setup.mods.contains("basicai") ? 1 : elite ? 5 : 3);
        final Entity e = npc;
        for (int t : new int[]{5, 20, 60, 120}) later(t, () -> { if (e.isAlive()) Npcs.applySkin(e, sk); });     // re-send the skin while clients load the NPC
        return npc;
    }

    /** Which gimmicks the enemy is allowed to use this run. */
    static Set<String> enemyGimmicks(Run r) {
        Set<String> out = new HashSet<>();
        for (String g : new String[]{"mega", "zmove", "dynamax", "tera"}) if (FrontierTeams.enemyMay(r.setup, g)) out.add(g);
        return out;
    }

    static String randomType() {
        String[] t = {"normal", "fire", "water", "grass", "electric", "ice", "fighting", "poison", "ground", "flying", "psychic", "bug", "rock", "ghost", "dragon", "dark", "steel", "fairy"};
        return t[RNG.nextInt(t.length)];
    }

    static String randomChampion(Run r) {
        List<String> ids = new ArrayList<>();
        for (var en : FrontierData.teams.champions.entrySet()) if (!en.getValue().secret) ids.add(en.getKey());
        if (r.floor >= 50 && RNG.nextDouble() < 0.02) for (var en : FrontierData.teams.champions.entrySet()) if (en.getValue().secret) return en.getKey();
        return ids.isEmpty() ? null : ids.get(RNG.nextInt(ids.size()));
    }

    static void beginBattle(Run r, ServerPlayer p) {
        if (r.npc == null || !r.npc.isAlive()) { msg(p, "§cThe trainer couldn't be set up, so this room is skipped."); r.floor++; afterRoom(r); return; }
        List<String> rules = new ArrayList<>();
        if (r.setup.mods.contains("inverse")) rules.add("Inverse Mod");
        if (gimmickBanned(r, "tera")) rules.add("Terastal Clause");
        if (gimmickBanned(r, "dynamax")) rules.add("Dynamax Clause");
        if (gimmickBanned(r, "zmove")) rules.add("Z-Move Clause");
        r.faintThisBattle = false;
        String err;
        List<Object[]> players = new ArrayList<>();
        List<Object> mine = originals(p, r.origIds);
        if (mine == null) { finish(r, p, false, "§cA Pokémon from your run team left your party, so the run ended."); return; }
        players.add(new Object[]{p, mine, r.team});
        if (r.partner != null) {
            ServerPlayer mate = server.getPlayerList().getPlayer(r.partner);
            if (mate == null) { finish(r, p, false, "§7Your co-op partner left, so the run ended."); return; }
            List<Object> theirs = originals(mate, r.partnerOrigIds);
            if (theirs == null) { finish(r, p, false, "§cA Pokémon from your partner's run team left their party, so the run ended."); return; }
            players.add(new Object[]{mate, theirs, r.partnerTeam});
        }
        List<Entity> enemies = new ArrayList<>();
        enemies.add(r.npc);
        if (r.coop()) {
            if (r.npc2 == null || !r.npc2.isAlive() || (r.ally != null && r.allyNpc == null)) { msg(p, "§cThe trainers couldn't be set up, so this room is skipped."); r.floor++; afterRoom(r); return; }
            enemies.add(r.npc2);
        }
        int skill = r.setup.mods.contains("basicai") ? 1 : (r.setup.mods.contains("elite") || r.room.equals("elite") || r.room.equals("champion") || r.room.equals("brain")) ? 5 : 3;
        r.pairs.clear();
        Cob.STRIP_ITEMS = r.setup.mods.contains("noitems");
        try {
        err = Cob.startRun(players, r.ally != null ? r.allyNpc : null, enemies, r.coop() ? "multi" : battleKind(r), rules, skill, r.pairs);
        } finally { Cob.STRIP_ITEMS = false; }
        if (err != null) {
            for (ServerPlayer pl : online(r)) msg(pl, "§cThe battle couldn't start: " + err + ". Your run has been ended and your rewards paid.");
            finish(r, p, false, null);
            return;
        }
        r.state = State.BATTLE;
        Object battle = Cob.battleOf(p);
        r.battleId = Cob.battleId(battle);
        Cob.enableEnemyGimmicks(battle, enemyGimmicks(r), !r.coop() && "singles".equals(battleKind(r)));
    }

    /** True when a gimmick is fully off for both sides (so a Showdown rule can ban it). */
    static boolean gimmickBanned(Run r, String id) { return r.setup.gimmicks.getOrDefault(id, "off").equals("off"); }

    // ------------------------------------------------------------------ battle results

    static void hookBattles() {
        try {
            Class<?> ev = Cob.cls("api.events.CobblemonEvents");
            Leaderboards.subscribe(ev.getField("BATTLE_VICTORY").get(null), (Consumer<Object>) Frontier::onVictory);
            Leaderboards.subscribe(ev.getField("BATTLE_FLED").get(null), (Consumer<Object>) Frontier::onFled);
            Leaderboards.subscribe(ev.getField("BATTLE_FAINTED").get(null), (Consumer<Object>) Frontier::onFainted);
        } catch (Throwable t) { GtsMod.LOG.error("[JCL] Could not hook battle events for the Frontier", t); }
    }

    static Run runFor(Object battle) {
        UUID id = Cob.battleId(battle);
        if (id == null) return null;
        for (Run r : RUNS.values()) if (id.equals(r.battleId)) return r;
        return null;
    }

    @SuppressWarnings("unchecked")
    static void onVictory(Object event) {
        try {
            Object battle = Cob.call(event, "getBattle");
            Run r = runFor(battle);
            if (r == null) return;
            boolean won = false;
            for (Object a : (List<Object>) Cob.call(event, "getWinners"))
                if (a.getClass().getSimpleName().equals("PlayerBattleActor") && r.members().contains(Cob.call(a, "getUuid"))) won = true;
            final boolean w = won;
            Cob.writeBack(r.pairs); r.pairs.clear();
            server.execute(() -> battleOver(r, w));
        } catch (Throwable t) { Cob.warn("read a Frontier battle result", t); }
    }

    static void onFled(Object event) {
        try { Run r = runFor(Cob.call(event, "getBattle")); if (r != null) { Cob.writeBack(r.pairs); r.pairs.clear(); server.execute(() -> battleOver(r, false)); } }
        catch (Throwable t) { Cob.warn("read a fled Frontier battle", t); }
    }

    static void onFainted(Object event) {
        try {
            Object killed = Cob.call(event, "getKilled");
            Object actor = Cob.call(killed, "getActor");
            Run r = runFor(Cob.call(event, "getBattle"));
            if (r != null && actor.getClass().getSimpleName().equals("PlayerBattleActor")) {
                r.faintThisBattle = true; r.anyFaint = true;
                if (r.setup.mods.contains("suddendeath") && r.state == State.BATTLE) {
                    Object battle = Cob.call(event, "getBattle");
                    Cob.writeBack(r.pairs); r.pairs.clear();
                    server.execute(() -> {
                        if (r.state != State.BATTLE) return;
                        Cob.endBattle(battle);
                        for (ServerPlayer pl : online(r)) msg(pl, "§c§lSudden Death! §7A Pokémon fainted.");
                        battleOver(r, false);
                    });
                }
            }
        } catch (Throwable ignored) {}
    }

    static void battleOver(Run r, boolean won) {
        if (r.state != State.BATTLE) return;
        r.state = State.BUSY;
        r.missing = 0;
        Cob.writeBack(r.pairs);
        r.pairs.clear();
        ServerPlayer p = server.getPlayerList().getPlayer(r.player);
        r.battleId = null;
        if (r.npc != null) { r.npc.discard(); r.npc = null; }
        if (r.npc2 != null) { r.npc2.discard(); r.npc2 = null; }
        if (r.allyNpc != null) { r.allyNpc.discard(); r.allyNpc = null; }
        if (p == null || (r.partner != null && server.getPlayerList().getPlayer(r.partner) == null)) { finish(r, p, false, "§7Your co-op partner left, so the run ended."); return; }
        List<ServerPlayer> all = online(r);
        if (!won) {
            if (r.setup.mods.contains("secondchance") && !r.secondChanceUsed) {
                r.secondChanceUsed = true;
                for (Object pk : partyList(r)) Cob.heal(pk);
                for (ServerPlayer pl : all) msg(pl, "§eSecond Chance! §7Your team is healed. Try this room again.");
                Door d = new Door(); d.room = r.room; d.type = r.roomType; d.champion = r.champion;
                later(40, () -> { ServerPlayer pl = server.getPlayerList().getPlayer(r.player); if (pl != null && RUNS.containsValue(r)) enterRoom(r, pl, d.room, d); });
                return;
            }
            for (ServerPlayer pl : all) {
                FrontierData.addCounter(pl.getUUID().toString(), "battlesLost", 1);
                title(pl, "§c§lDefeated", "§7Room " + (r.floor + 1) + " §8· §7Heading Back to the Lobby...");
                pl.playNotifySound(SoundEvents.ELDER_GUARDIAN_CURSE, SoundSource.MASTER, 0.7f, 1.0f);
                pl.playNotifySound(SoundEvents.NOTE_BLOCK_BASS.value(), SoundSource.MASTER, 1.0f, 0.5f);
                msg(pl, "§cDefeated on room " + (r.floor + 1) + "! §7Your rewards are being paid out...");
            }
            later(40, () -> finish(r, server.getPlayerList().getPlayer(r.player), false, null));
            return;
        }
        // a win
        r.floor++;
        payRoom(r, p);
        for (ServerPlayer pl : all) {
            String id = pl.getUUID().toString();
            FrontierData.addCounter(id, "battlesWon", 1);
            if (r.room.equals("brain")) FrontierData.addCounter(id, "brainsBeaten", 1);
            FrontierQuests.progress(pl, "rooms", 1);
            if (r.setup.rental && r.floor >= 3) FrontierQuests.complete(pl, "first_rental");
            for (String m : r.setup.mods) { var md = FrontierData.modifier(m); if (md != null && md.bonus > 0) { FrontierQuests.complete(pl, "first_modifier"); break; } }
            if (r.room.equals("champion")) {
                int total = FrontierData.addCounter(id, "championWins", 1);
                FrontierQuests.progress(pl, "champion", 1);
                if (total >= 10) award(pl, "f_champion_slayer", "feat_champion_slayer");
            }
            if (r.room.equals("type") && r.roomType != null && r.roomType.equals(FrontierQuests.featuredType())) FrontierQuests.progress(pl, "type", 1);
            if (r.room.equals("brain")) brainBeaten(r, pl);
        }
        if (r.room.equals("champion")) r.championWins++;
        if (r.curse > 0) r.curse--;
        if (r.clover > 0) r.clover--;
        if (r.setup.mods.contains("suddendeath") && r.faintThisBattle) { finish(r, p, false, "§cSudden Death: a Pokémon fainted. The run is over."); return; }
        if (!r.faintThisBattle) {
            r.cleanRooms++;
            if (r.cleanRooms % FrontierData.cfg.streakEvery == 0) { r.streak++; for (ServerPlayer pl : all) msg(pl, "§a§lPerfect streak! §7+" + (int) (FrontierData.cfg.streakBonus * 100) + "% rewards for the rest of the run."); }
        } else r.cleanRooms = 0;
        boolean nohealing = r.setup.mods.contains("nohealing");
        for (Object pk : partyList(r)) {
            if (!nohealing || r.floor % 7 == 0) Cob.heal(pk);
            else if (r.boons.contains("secondwind")) Cob.healPercent(pk, 0.25);
        }
        if (r.floor % 7 == 0) for (ServerPlayer pl : all) supply(r, pl);
        afterRoom(r);
    }

    /** Every Pokémon in the run, both co-op players' included. */
    static List<Object> partyList(Run r) {
        List<Object> out = new ArrayList<>();
        out.addAll(r.team);
        out.addAll(r.partnerTeam);
        return out;
    }

    static void brainBeaten(Run r, ServerPlayer p) {
        String id = p.getUUID().toString();
        var len = r.setup.length;
        String grade = r.floor >= 49 && len.equals("marathon") ? "gold" : r.floor >= 21 ? "silver" : null;
        if (grade == null || len.equals("infinite")) return;
        if (grade.equals("gold") && !FrontierData.hasSymbol(id, r.brain + ":silver")) grade = "silver";
        if (FrontierData.addSymbol(id, r.brain + ":" + grade)) {
            var br = FrontierData.teams.brains.get(r.brain);
            msg(p, "§6§l✦ " + (grade.equals("gold") ? "Gold" : "Silver") + " " + br.symbol + " Symbol earned! §r§7You beat " + br.name + ".");
            DiscordBridge.announce("frontier", (grade.equals("gold") ? "🥇" : "🥈") + " **" + p.getGameProfile().getName() + "** earned the " + (grade.equals("gold") ? "Gold" : "Silver") + " " + br.symbol.replaceAll("§.", "") + " Symbol by beating " + br.name.replaceAll("§.", "") + "!");
            Titles.grant(p, "f_" + r.brain + "_" + grade);
            FrontierData.addBp(id, grade.equals("gold") ? 400 : 150);
            FrontierQuests.complete(p, "symbol_" + r.brain + "_" + grade);
            if (grade.equals("gold")) Cosmetics.give(p, "aura_starlight");
            int silvers = 0, golds = 0;
            for (String b : FrontierData.teams.brains.keySet()) { if (FrontierData.hasSymbol(id, b + ":silver")) silvers++; if (FrontierData.hasSymbol(id, b + ":gold")) golds++; }
            if (silvers >= FrontierData.teams.brains.size()) award(p, "f_challenger_seven", "seven_silvers");
            if (golds >= FrontierData.teams.brains.size()) { award(p, "f_legend", "frontier_legend"); Cosmetics.give(p, "aura_golden"); }
        }
        if (!r.anyFaint) award(p, "f_brainbuster", "feat_brainbuster");
    }

    static void award(ServerPlayer p, String title, String quest) {
        Titles.grant(p, title);
        FrontierQuests.complete(p, quest);
    }

    // ------------------------------------------------------------------ rewards

    /** Adds this room's reward to the pot. In co-op, each player earns the full amount. */
    static void payRoom(Run r, ServerPlayer p) {
        var cfg = FrontierData.cfg;
        Bracket b = FrontierData.bracket(r.setup.bracket);
        double scale = Math.min(cfg.floorCap, 1 + cfg.floorScale * (r.floor - 1));
        double m = r.mult * scale * (r.roomMult100 / 100.0) * (1 + Math.min(cfg.streakCap, r.streak * cfg.streakBonus));
        if (r.curse > 0) m *= 1.4;
        if (r.clover > 0) m *= 2;
        int today = 0;
        for (UUID u : r.members()) today = Math.max(today, FrontierData.addDaily(u.toString(), "rooms", 1));
        if (today > cfg.dailySoftCap) m *= cfg.dailySoftMult;
        double cdMult = r.boons.contains("luckycoin") ? 1.15 : 1;
        double bpMult = (r.boons.contains("focus") ? 1.10 : 1) * (r.setup.daily ? 2 : 1);
        long cd = Math.round(b.cd * m * cdMult), bp = (long) Math.ceil(b.bp * m * bpMult);
        r.potCd += cd; r.potBp += bp;
        savePot(r);
        for (ServerPlayer pl : online(r)) {
            pl.displayClientMessage(Component.literal("§a+" + Fmt.cd(cd) + " §8· §b+" + bp + " BP §8· §7Room " + r.floor + " §8· §7Pot §6" + Fmt.cd(r.potCd) + "§7/§b" + r.potBp + " BP"), true);
            pl.playNotifySound(SoundEvents.PLAYER_LEVELUP, SoundSource.MASTER, 0.5f, 1.6f);
        }
    }

    /** Mirrors the pot to disk for every player in the run, so a crash pays it on the next login instead of losing it. */
    static void savePot(Run r) {
        for (UUID u : r.members()) {
            FrontierData.Pending pd = new FrontierData.Pending(); pd.cd = r.potCd; pd.bp = r.potBp;
            FrontierData.data.pending.put(u.toString(), pd);
        }
        FrontierData.save();
    }

    static void supply(Run r, ServerPlayer p) {
        var l = roll(FrontierData.cfg.supply);
        if (l == null) return;
        giveRun(r, p, new ItemStack(Crates.resolve(l.item), l.count));
        msg(p, "§6Frontier Supply! §7You found §f" + l.count + "× " + new ItemStack(Crates.resolve(l.item)).getHoverName().getString() + "§7.");
    }

    static FrontierData.Loot roll(List<FrontierData.Loot> list) {
        double tot = 0; for (var l : list) tot += l.w;
        if (tot <= 0) return null;
        double x = RNG.nextDouble() * tot;
        for (var l : list) { x -= l.w; if (x <= 0) return l; }
        return list.get(list.size() - 1);
    }

    /** Ends a run, however it ends. Always pays every player in it and always cleans up. */
    static void finish(Run r, ServerPlayer caller, boolean cleared, String line) {
        if (r.state == State.ENDED) return;
        r.state = State.ENDED;
        for (UUID u : r.members()) RUNS.remove(u);
        ServerPlayer lead = server.getPlayerList().getPlayer(r.player);
        if (r.battleId != null) { Object battle = lead != null ? Cob.battleOf(lead) : caller != null ? Cob.battleOf(caller) : null; Cob.endBattle(battle); }
        if (r.npc != null) { r.npc.discard(); r.npc = null; }
        if (r.npc2 != null) { r.npc2.discard(); r.npc2 = null; }
        if (r.allyNpc != null) { r.allyNpc.discard(); r.allyNpc = null; }
        Length len = FrontierData.length(r.setup.length);
        long cd = r.potCd, bp = r.potBp;
        if (cleared && len.clearBonus > 0 && r.floor > 0) {
            Bracket b = FrontierData.bracket(r.setup.bracket);
            double scale = Math.min(FrontierData.cfg.floorCap, 1 + FrontierData.cfg.floorScale * (r.floor - 1));
            cd += Math.round(b.cd * r.mult * scale * len.clearBonus);
            bp += (long) Math.ceil(b.bp * r.mult * scale * len.clearBonus);
        }
        for (UUID u : r.members()) {
            String id = u.toString();
            // the player logging out right now is still "online" for this tick, so use the caller when it matches
            ServerPlayer p = caller != null && caller.getUUID().equals(u) ? caller : server.getPlayerList().getPlayer(u);
            if (len.id.equals("infinite") && r.floor > 0) {
                String board = r.setup.bracket + ":" + r.setup.format;
                FrontierData.data.best.computeIfAbsent(board, k -> new HashMap<>()).merge(id, r.floor, Math::max);
                FrontierData.data.season.computeIfAbsent(board, k -> new HashMap<>()).merge(id, r.floor, Math::max);
                later(5, () -> FrontierBoards.refresh(board));
            }
            if (r.setup.daily) FrontierData.data.dailyChallengeBest.merge(id, r.floor, Math::max);
            FrontierData.addCounter(id, "runs", 1);
            if (cleared) FrontierData.addCounter(id, "runsCleared", 1);
            if (r.setup.rental) FrontierData.addCounter(id, "rentalRuns", 1);
            if (r.coop()) FrontierData.addCounter(id, "coopRuns", 1);
            FrontierData.addCounter(id, "roomsTotal", r.floor);
            FrontierData.addCounter(id, "cdEarnedK", (int) Math.min(Integer.MAX_VALUE, cd / 1000));
            FrontierData.addCounter(id, "bpEarned", (int) Math.min(Integer.MAX_VALUE, bp));
            if (r.floor > FrontierData.counter(id, "bestRun")) FrontierData.setCounter(id, "bestRun", r.floor);
            if (r.cleanRooms > FrontierData.counter(id, "bestStreak")) {
                FrontierData.setCounter(id, "bestStreak", r.cleanRooms);
                if (r.cleanRooms >= 7 && p != null) DiscordBridge.announce("frontier", "🔥 **" + p.getGameProfile().getName() + "** set a new personal best: **" + r.cleanRooms + " rooms** in a row at the Battle Frontier!");
            }
            FrontierData.data.pending.remove(id);
            List<ItemStack> held = r.overflow.remove(id.equals(r.player.toString()) ? r.player : UUID.fromString(id));
            if (held != null && p != null) {
                final ServerPlayer who = p;
                later(80, () -> { for (ItemStack st : held) Crates.giveStack(who, st); if (!held.isEmpty()) msg(who, "§aThe loot that didn't fit in your bag has been handed over."); });
            }
            if (p != null && r.setup.rental) {
                Cob.removeRentals(p);
                List<Object[]> snaps = DEX_SNAP.remove(p.getUUID());
                if (snaps != null) { final ServerPlayer who = p; later(5, () -> Cob.dexRestore(who, snaps)); }
            }
            if (p != null) {
                if (cd > 0) Economy.give(p, cd);
                FrontierData.addBp(id, bp);
                if (cleared) clearRewards(r, p);
                infiniteMilestones(r, p);
                if (r.setup.daily && r.floor >= 7) FrontierQuests.progress(p, "daily", 1);
                if (r.floor > 0 && (cleared || len.id.equals("infinite"))) FrontierQuests.progress(p, "run", 1);
                if (line != null) msg(p, line);
                msg(p, "§7Run over after §f" + r.floor + "§7 room" + (r.floor == 1 ? "" : "s") + ". You earned §6" + Fmt.cd(cd) + "§7 and §b" + bp + " BP§7. §8(BP balance: " + FrontierData.bp(id) + ")");
                toLobby(p);
            } else {
                FrontierData.Pending pd = new FrontierData.Pending(); pd.cd = cd; pd.bp = bp;
                FrontierData.data.pending.put(id, pd);
            }
        }
        clearPlot(r);
        forceChunks(r, false);
        PLOTS.remove(r.plot);
        FrontierData.save();
    }

    static void clearRewards(Run r, ServerPlayer p) {
        String id = p.getUUID().toString();
        String key = r.setup.bracket + ":" + r.setup.length;
        boolean first = FrontierData.markCleared(id, key);
        if (r.setup.length.equals("sprint")) FrontierQuests.complete(p, "first_sprint");
        if (r.setup.length.equals("standard")) {
            String title = switch (r.setup.bracket) {
                case "rookie" -> "f_rookie_climber"; case "junior" -> "f_junior_ace"; case "challenger" -> "f_rising_star"; case "ace" -> "f_ace";
                case "master" -> "f_master"; case "little" -> "f_little_legend"; case "flat50" -> "f_even_match"; default -> null;
            };
            if (title != null) award(p, title, "bracket_" + r.setup.bracket);
            boolean gauntlet = true;
            for (var g : FrontierData.cfg.gimmicks) if (!r.setup.gimmicks.getOrDefault(g.id, "off").equals("enemy")) gauntlet = false;
            if (gauntlet) award(p, "f_gauntlet_runner", "feat_gauntlet");
            if (r.setup.mods.contains("suddendeath")) award(p, "f_glass_cannon", "feat_glass_cannon");
            if (r.setup.mods.contains("nohealing") && r.setup.mods.contains("bigteams")) award(p, "f_iron_will", "feat_iron_will");
        }
        if (r.setup.length.equals("marathon")) {
            if (r.setup.bracket.equals("master")) award(p, "f_marathon_master", "marathon_master");
            if (r.setup.rental) award(p, "f_rental_royalty", "feat_rental_royalty");
        }
        if (first) msg(p, "§a§lFirst clear! §7" + FrontierData.bracket(r.setup.bracket).name + " · " + FrontierData.length(r.setup.length).name);
    }

    static void infiniteMilestones(Run r, ServerPlayer p) {
        if (!r.setup.length.equals("infinite")) return;
        int[] at = {25, 50, 100, 200};
        String[] titles = {"f_endurance", "f_unbreakable", "f_endless", "f_beyond"};
        String[] quests = {"infinite_25", "infinite_50", "infinite_100", "infinite_200"};
        for (int i = 0; i < at.length; i++) if (r.floor >= at[i]) award(p, titles[i], quests[i]);
    }

    static boolean STOPPING;

    /** Sends a player to the lobby once Cobblemon has fully finished any battle (teleporting mid-cleanup can freeze the client). */
    static void toLobby(ServerPlayer p) { toLobbyWhenFree(p, 0); }

    static void toLobbyWhenFree(ServerPlayer p, int tries) {
        if (STOPPING || p == null || p.hasDisconnected()) return;
        if (Cob.battleOf(p) != null && tries < 15) { later(20, () -> toLobbyWhenFree(p, tries + 1)); return; }
        if (tries == 0) { later(20, () -> toLobbyWhenFree(p, 1)); return; }      // always give the battle screen a second to close
        p.closeContainer();
        toLobbyNow(p);
    }

    static void toLobbyNow(ServerPlayer p) {
        if (STOPPING) return;
        var s = FrontierData.data.lobby;
        ServerLevel lvl = s == null ? null : Cob.level(server, s.dim);
        if (lvl == null) { lvl = server.overworld(); BlockPos sp = lvl.getSharedSpawnPos(); p.teleportTo(lvl, sp.getX() + 0.5, sp.getY(), sp.getZ() + 0.5, 0, 0); return; }
        p.teleportTo(lvl, s.x, s.y, s.z, s.yaw, 0);
    }

    // ------------------------------------------------------------------ between rooms

    static void afterRoom(Run r) {
        ServerPlayer p = server.getPlayerList().getPlayer(r.player);
        if (p == null || r.state == State.ENDED) return;
        Length len = FrontierData.length(r.setup.length);
        if (len.rooms > 0 && r.floor >= len.rooms) { finish(r, p, true, "§a§lRun cleared! §r§7Clear bonus added to your rewards."); return; }
        // Brains guard rooms 21 and 49 of Standard and Marathon runs, and every 25th room of Infinite runs.
        int next = r.floor + 1;
        boolean brainRoom = (!len.id.equals("infinite") && (next == 21 || next == 49) && len.rooms >= next) || (len.id.equals("infinite") && next % 25 == 0);
        if (brainRoom) {
            r.brain = FrontierTeams.pickBrain(r);
            for (ServerPlayer pl : online(r)) msg(pl, "§c§lA Frontier Brain awaits: §r§f" + FrontierData.teams.brains.get(r.brain).name + "§7!");
            later(40, () -> { ServerPlayer pl = server.getPlayerList().getPlayer(r.player); if (pl != null && RUNS.containsValue(r)) enterRoom(r, pl, "brain", null); });
            return;
        }
        String[] forced = FORCE_NEXT.remove(r.player);
        if (forced != null) {
            if (forced[0].equals("brain")) {
                r.brain = forced[1] != null && FrontierData.teams.brains.containsKey(forced[1]) ? forced[1] : FrontierTeams.pickBrain(r);
                later(10, () -> { ServerPlayer pl = server.getPlayerList().getPlayer(r.player); if (pl != null && RUNS.containsValue(r)) enterRoom(r, pl, "brain", null); });
                return;
            }
            r.doors = FrontierTeams.doors(r);
            for (Door d : r.doors) {
                d.room = forced[0]; d.hidden = false;
                d.type = forced[0].equals("type") ? (forced[1] != null ? forced[1] : d.type) : null;
                d.champion = forced[0].equals("champion") ? (forced[1] != null ? forced[1] : Frontier.randomChampion(r)) : null;
                if (d.room.equals("type") && d.type == null) d.type = randomType();
                Bracket bk = FrontierData.bracket(r.setup.bracket);
                d.preview = d.room.equals("champion") && FrontierData.teams.champions.containsKey(d.champion) ? FrontierData.teams.champions.get(d.champion).team
                        : isBattle(d.room) ? FrontierTeams.randomTeam(d.room.equals("type") ? d.type : null, enemyTeamSize(r), bk) : null;
            }
            r.state = State.DOORS;
            later(10, () -> { ServerPlayer pl = server.getPlayerList().getPlayer(r.player); if (pl != null) FrontierGui.doors(pl, r); });
            return;
        }
        if (r.setup.daily) { long seed = daySeed() * 7919 + r.floor * 104729L; FrontierTeams.RNG.setSeed(seed); RNG.setSeed(seed ^ 0x5DEECE66DL); }
        r.doors = FrontierTeams.doors(r);
        if (r.setup.daily) { FrontierTeams.RNG.setSeed(System.nanoTime()); RNG.setSeed(System.nanoTime() ^ 31); }
        r.state = State.DOORS;
        later(10, () -> {
            ServerPlayer pl = server.getPlayerList().getPlayer(r.player);
            if (pl != null) {
                boolean now = r.openDoorsNow; r.openDoorsNow = false;
                if (now) { if (r.door != null) spawnDoor(r); FrontierGui.doors(pl, r); }
                else if (r.door != null && spawnDoor(r)) {
                    pl.playNotifySound(SoundEvents.PORTAL_AMBIENT, SoundSource.MASTER, 0.35f, 0.6f);
                    pl.sendSystemMessage(Component.literal("§5§l[Frontier] §r§7The dark doorway stirs. §8The doors will open in a moment, or right-click the doorway any time."));
                    // after a win, the door choice opens on its own a couple of seconds later
                    later(20, () -> {
                        ServerPlayer p2 = server.getPlayerList().getPlayer(r.player);
                        if (p2 != null && r.state == State.DOORS && RUNS.get(r.player) == r && p2.containerMenu == p2.inventoryMenu) FrontierGui.doors(p2, r);
                    });
                } else FrontierGui.doors(pl, r);
            }
            if (r.partner != null) { ServerPlayer mate = server.getPlayerList().getPlayer(r.partner); if (mate != null) msg(mate, "§7" + r.name + " is choosing the next door..."); }
        });
    }

    /** Gives an item during a run. If the bag is full, it's kept safe and handed over in the lobby (dropped items would vanish with the room). */
    static void giveRun(Run r, ServerPlayer p, ItemStack s) {
        ItemStack left = s.copy();
        p.getInventory().add(left);
        if (!left.isEmpty()) {
            r.overflow.computeIfAbsent(p.getUUID(), k -> new ArrayList<>()).add(left);
            msg(p, "§eYour bag is full! §7" + left.getCount() + "× " + left.getHoverName().getString() + " will be waiting for you in the lobby.");
        }
    }

    /** The doorway: an invisible right-click spot over the dark opening, and eerie floating text above it. */
    static boolean spawnDoor(Run r) { return spawnDoor(r, true); }

    static boolean spawnDoor(Run r, boolean open) {
        ServerLevel lvl = frontier();
        if (lvl == null || r.door == null) return false;
        try {
            for (Entity e : lvl.getEntities((Entity) null, new net.minecraft.world.phys.AABB(r.door).inflate(3), e -> e.getTags().contains("jcl_frontier_door"))) {
                if (!open || !(e instanceof net.minecraft.world.entity.Interaction)) e.discard();
            }
            boolean hasSpot = !lvl.getEntities((Entity) null, new net.minecraft.world.phys.AABB(r.door).inflate(3), e -> e instanceof net.minecraft.world.entity.Interaction && e.getTags().contains("jcl_frontier_door")).isEmpty();
            if (hasSpot && open) { spawnDoorText(lvl, r); return true; }
            var it = net.minecraft.world.entity.EntityType.INTERACTION.create(lvl);
            if (it == null) return false;
            for (String[] mm : new String[][]{{"setWidth", "3.2"}, {"setHeight", "4.2"}}) {
                var meth = it.getClass().getDeclaredMethod(mm[0], float.class); meth.setAccessible(true); meth.invoke(it, Float.parseFloat(mm[1]));
            }
            try { var resp = it.getClass().getDeclaredMethod("setResponse", boolean.class); resp.setAccessible(true); resp.invoke(it, true); } catch (Throwable ignored) {}
            double ix = r.door.getX() + 0.5, iz = r.door.getZ() + 0.5;
            if (r.doorSide == 0) ix = r.door.getX() - 0.1; else if (r.doorSide == 1) iz = r.door.getZ() + 1.1; else iz = r.door.getZ() - 0.1;
            it.moveTo(ix, r.door.getY(), iz, 0f, 0f);
            it.addTag("jcl_frontier_door");
            SPAWNING = true;
            try { lvl.addFreshEntity(it); } finally { SPAWNING = false; }
            if (open) spawnDoorText(lvl, r);
            return true;
        } catch (Throwable t) { GtsMod.LOG.error("[JCL] Could not place the Frontier door", t); return false; }
    }

    static void spawnDoorText(ServerLevel lvl, Run r) {
        double hx = r.doorSide == 0 ? r.door.getX() - 0.3 : r.door.getX() + 0.5, hz = r.doorSide == 0 ? r.door.getZ() + 0.5 : r.doorSide == 1 ? r.door.getZ() + 1.3 : r.door.getZ() - 0.3;
        Holo.spawn(lvl, hx, r.door.getY() + 4.6, hz, Component.literal("§5§l✦ Continue ✦"), "jcl_frontier_door", 1.4f, false);
        Holo.spawn(lvl, hx, r.door.getY() + 4.2, hz, Component.literal("§8§oDeeper into the dark..."), "jcl_frontier_door", 0.9f, false);
    }

    @SubscribeEvent
    public static void onDoorClick(net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.EntityInteract e) {
        if (!(e.getEntity() instanceof ServerPlayer p) || !e.getTarget().getTags().contains("jcl_frontier_door")) return;
        e.setCanceled(true);
        if (e.getHand() != net.minecraft.world.InteractionHand.MAIN_HAND) return;
        Run r = RUNS.get(p.getUUID());
        if (r == null) for (Run x : RUNS.values()) if (p.getUUID().equals(x.partner)) r = x;
        if (r == null) return;
        if (r.state != State.DOORS) {
            p.playNotifySound(SoundEvents.CHAIN_PLACE, SoundSource.MASTER, 0.7f, 0.5f);
            p.displayClientMessage(Component.literal("§8The doorway is sealed. §7Deal with what's in this room first."), true);
            return;
        }
        if (!p.getUUID().equals(r.player)) { msg(p, "§7" + r.name + " leads the way through the doors."); return; }
        p.playNotifySound(SoundEvents.WOODEN_DOOR_OPEN, SoundSource.MASTER, 0.7f, 0.6f);
        FrontierGui.doors(p, r);
    }

    static void chooseDoor(ServerPlayer p, int i) {
        Run r = RUNS.get(p.getUUID());
        if (r == null || r.state != State.DOORS || i < 0 || i >= r.doors.size()) return;
        if (!p.getUUID().equals(r.player)) { msg(p, "§7" + r.name + " chooses the doors in this run."); return; }
        Door d = r.doors.get(i);
        p.closeContainer();
        enterRoom(r, p, d.room, d);
    }

    /** Non-battle rooms. */
    static void roomEffect(Run r, ServerPlayer p) {
        r.state = State.BUSY;
        switch (r.room) {
            case "loot" -> {
                int rolls = 1 + r.extraRolls + (r.boons.contains("treasure") ? 1 : 0);
                r.extraRolls = 0;
                for (int i = 0; i < rolls; i++) {
                    var l = roll(FrontierData.cfg.loot);
                    if (l == null) continue;
                    for (ServerPlayer pl : online(r)) {
                        giveRun(r, pl, new ItemStack(Crates.resolve(l.item), l.count));
                        msg(pl, "§6Loot! §7You found §f" + l.count + "× " + new ItemStack(Crates.resolve(l.item)).getHoverName().getString() + "§7.");
                    }
                }
                for (ServerPlayer pl : online(r)) {
                    FrontierQuests.progress(pl, "loot", 1); FrontierData.addCounter(pl.getUUID().toString(), "lootRooms", 1);
                    pl.playNotifySound(SoundEvents.CHEST_OPEN, SoundSource.MASTER, 0.9f, 0.9f);
                    later(6, () -> pl.playNotifySound(SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.MASTER, 1f, 1.2f));
                    later(12, () -> pl.playNotifySound(SoundEvents.PLAYER_LEVELUP, SoundSource.MASTER, 0.7f, 1.4f));
                }
                nextAfterEffect(r);
            }
            case "heal" -> {
                for (Object pk : partyList(r)) Cob.heal(pk);
                for (ServerPlayer pl : online(r)) {
                    msg(pl, "§bThe healing spring restores your whole team.");
                    pl.playNotifySound(SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.MASTER, 1f, 1.2f);
                    later(8, () -> pl.playNotifySound(SoundEvents.PLAYER_SPLASH, SoundSource.MASTER, 0.6f, 1.4f));
                    later(14, () -> pl.playNotifySound(SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.MASTER, 0.8f, 0.8f));
                }
                nextAfterEffect(r);
            }
            case "luckyroll" -> {
                r.extraRolls++;
                for (ServerPlayer pl : online(r)) {
                    msg(pl, "§e§lLucky Roll! §7Your next Loot Room gives an extra roll.");
                    pl.playNotifySound(SoundEvents.NOTE_BLOCK_CHIME.value(), SoundSource.MASTER, 1f, 1.5f);
                    later(5, () -> pl.playNotifySound(SoundEvents.NOTE_BLOCK_CHIME.value(), SoundSource.MASTER, 1f, 1.8f));
                    later(10, () -> pl.playNotifySound(SoundEvents.PLAYER_LEVELUP, SoundSource.MASTER, 0.7f, 1.6f));
                }
                nextAfterEffect(r);
            }
            case "clover" -> {
                r.clover = 3;
                for (ServerPlayer pl : online(r)) { msg(pl, "§a§lLucky Clover! §7Double rewards for your next 3 battles."); pl.playNotifySound(SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.MASTER, 1f, 1.6f); }
                nextAfterEffect(r);
            }
            case "shrine" -> FrontierGui.shrine(p, r);
            case "merchant" -> FrontierGui.merchant(p, r);
            case "gamble" -> FrontierGui.gamble(p, r);
            case "cursed" -> FrontierGui.cursed(p, r);
            default -> nextAfterEffect(r);
        }
    }

    static void nextAfterEffect(Run r) {
        if (r.state == State.ENDED) return;
        r.floor++;
        r.openDoorsNow = true;
        later(8, () -> afterRoom(r));
    }

    // ------------------------------------------------------------------ the Daily Challenge

    /** For a Monotype rental run: one random type for the whole rental team. */
    static String monoType(Setup s) {
        String[] t = {"normal", "fire", "water", "grass", "electric", "ice", "fighting", "poison", "ground", "flying", "psychic", "bug", "rock", "ghost", "dragon", "dark", "steel", "fairy"};
        return t[RNG.nextInt(t.length)];
    }

    static long daySeed() { return LocalDate.now().toEpochDay() * 1000 + FrontierData.data.dailyReroll; }
    static int weekSeed() { LocalDate d = LocalDate.now(); return d.getYear() * 100 + d.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR); }

    static void applyDaily(Setup s) {
        Random r = new Random(daySeed() * 31 + 7);
        String[] fmts = {"singles", "doubles"};
        s.format = fmts[r.nextInt(fmts.length)];
        s.length = "sprint";
        s.mods.clear();
        List<FrontierData.Modifier> hard = new ArrayList<>();
        for (var m : FrontierData.cfg.modifiers) if (m.bonus > 0 && !m.rental && !m.id.equals("suddendeath")) hard.add(m);
        for (int i = 0; i < 2 && !hard.isEmpty(); i++) s.mods.add(hard.remove(r.nextInt(hard.size())).id);
        for (var g : FrontierData.cfg.gimmicks) s.gimmicks.put(g.id, r.nextInt(3) == 0 ? "enemy" : "both");
        s.daily = true;
    }

    static void rollDay() {
        String today = LocalDate.now().toString();
        if (today.equals(FrontierData.data.day)) return;
        // crown yesterday's Daily Challenge winner
        String best = null; int bestN = 0;
        for (var en : FrontierData.data.dailyChallengeBest.entrySet()) if (en.getValue() > bestN) { bestN = en.getValue(); best = en.getKey(); }
        if (best != null) {
            ServerPlayer w = server.getPlayerList().getPlayer(UUID.fromString(best));
            if (w != null) Titles.grant(w, "f_daily_victor");
            else FrontierData.markCleared(best, "pending:f_daily_victor");
        }
        FrontierData.data.day = today;
        FrontierData.data.daily.clear();
        FrontierData.data.dailyDone.clear();
        FrontierData.data.dailyChallengeBest.clear();
        FrontierData.save();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) FrontierQuests.resetDailies(p);
    }

    static void rollSeason() {
        LocalDate d = LocalDate.now();
        String key = d.getYear() + "-" + d.getMonthValue();
        if (key.equals(FrontierData.data.seasonKey)) return;
        if (!FrontierData.data.seasonKey.isEmpty()) {
            for (var board : FrontierData.data.season.entrySet()) {
                List<Map.Entry<String, Integer>> list = new ArrayList<>(board.getValue().entrySet());
                list.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
                for (int i = 0; i < Math.min(3, list.size()); i++) {
                    String id = list.get(i).getKey();
                    long prize = i < FrontierData.cfg.seasonPrizes.size() ? FrontierData.cfg.seasonPrizes.get(i) : 0;
                    FrontierData.addBp(id, prize);
                    FrontierData.markCleared(id, "pending:" + (i == 0 ? "f_season_champion" : "f_season_finalist"));
                }
            }
        }
        FrontierData.data.seasonKey = key;
        FrontierData.data.season.clear();
        FrontierData.save();
    }

    // ------------------------------------------------------------------ ticking

    @SubscribeEvent
    public static void onTick(ServerTickEvent.Post e) {
        if (server == null) return;
        tick++;
        for (int i = 0; i < TIMERS.size(); i++) {
            if (TIMERS_AT.get(i)[0] <= tick) {
                Runnable rr = TIMERS.remove(i); TIMERS_AT.remove(i); i--;
                try { rr.run(); } catch (Throwable t) { GtsMod.LOG.error("[JCL] Frontier task failed", t); }
            }
        }
        for (Run r : runs()) {
            if (r.state == State.ENDED) continue;
            ServerPlayer p = server.getPlayerList().getPlayer(r.player);
            ServerPlayer mate = r.partner != null ? server.getPlayerList().getPlayer(r.partner) : null;
            if (p == null || (r.partner != null && mate == null)) { finish(r, null, false, "§7Your co-op partner left, so the run ended."); continue; }
            if (mate != null && tick % 20 == 0 && r.state != State.BUSY && r.state != State.STARTING && !mate.level().dimension().location().toString().equals(DIM)) { finish(r, mate, false, "§7Your partner left the Frontier, so the run ended."); continue; }
            if (r.state == State.STARTING && r.battleStartTick > 0 && tick >= r.battleStartTick) { r.battleStartTick = -1; beginBattle(r, p); }
            // Leaving the Frontier mid-run (another mod's teleport, death, etc.) ends the run safely.
            if (tick % 20 == 0 && r.state != State.BUSY && r.state != State.STARTING && !p.level().dimension().location().toString().equals(DIM)) finish(r, p, false, "§7You left the Frontier, so your run ended.");
            // A battle that ended without telling us (rare) is treated as a loss after a short grace period.
            if (tick % 40 == 0 && r.state == State.BATTLE) {
                if (Cob.battleOf(p) == null) { if (++r.missing >= 2) battleOver(r, false); } else r.missing = 0;
            }
        }
        if (tick % 1200 == 0) { rollDay(); rollSeason(); }
        if (tick % 200 == 0) FrontierBoards.refreshAll();      // every 10 seconds, only where a board's area is loaded
    }

    // ------------------------------------------------------------------ world protection

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onSpawn(EntityJoinLevelEvent e) {
        if (e.getLevel().isClientSide() || !e.getLevel().dimension().location().toString().equals(DIM)) return;
        Entity en = e.getEntity();
        if (en instanceof Enemy || Cob.isWildPokemon(en)) { e.setCanceled(true); return; }
        String ns = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(en.getType()).getNamespace();
        if (ns.equals("rustlingspots")) { e.setCanceled(true); return; }       // no Rustling Spots in the Frontier
        // Trainers left behind by a crash are removed as soon as their chunk loads.
        if (en.getTags().contains("jcl_frontier") && !SPAWNING) {
            boolean live = false;
            for (Run r : RUNS.values()) if (r.npc == en || r.npc2 == en || r.allyNpc == en) live = true;
            if (!live) e.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onBreak(BlockEvent.BreakEvent e) {
        if (e.getLevel() instanceof ServerLevel l && l.dimension().location().toString().equals(DIM) && !e.getPlayer().hasPermissions(2)) e.setCanceled(true);
    }

    @SubscribeEvent
    public static void onPlace(BlockEvent.EntityPlaceEvent e) {
        if (e.getLevel() instanceof ServerLevel l && l.dimension().location().toString().equals(DIM) && e.getEntity() instanceof ServerPlayer p && !p.hasPermissions(2)) e.setCanceled(true);
    }

    // ------------------------------------------------------------------ rental protection

    static final Set<String> RENTAL_OK_COMMANDS = Set.of("frontier", "msg", "tell", "w", "r", "me", "help", "list", "titles", "wardrobe", "cosmetics");

    static boolean inRentalRun(ServerPlayer p) { Run r = RUNS.get(p.getUUID()); return r != null && r.setup.rental; }

    /** During a rental run, only a few harmless commands work, so rentals can't be listed, sold or traded. */
    @SubscribeEvent
    public static void onCommand(net.neoforged.neoforge.event.CommandEvent e) {
        var src = e.getParseResults().getContext().getSource();
        if (!(src.getEntity() instanceof ServerPlayer p) || !inRentalRun(p) || p.hasPermissions(2)) return;
        String cmd = e.getParseResults().getReader().getString().trim();
        if (cmd.startsWith("/")) cmd = cmd.substring(1);
        String root = cmd.split(" ")[0].toLowerCase(java.util.Locale.ROOT);
        if (root.contains(":")) root = root.substring(root.indexOf(':') + 1);
        if (!RENTAL_OK_COMMANDS.contains(root)) { e.setCanceled(true); msg(p, "§cThat command is off during a rental run. Finish or leave the run first (§f/frontier leave§c)."); }
    }

    static final Set<UUID> ADDING_RENTALS = new HashSet<>();
    /** Species a player had never registered before their rental run: forgotten again when the rentals go back. */
    static final Map<UUID, List<Object[]>> DEX_SNAP = new HashMap<>();

    static void hookRentalGuards() {
        try {
            Class<?> ev = Cob.cls("api.events.CobblemonEvents");
            Leaderboards.subscribe(ev.getField("POKEDEX_DATA_CHANGED_PRE").get(null), (Consumer<Object>) e -> {
                try {
                    UUID who = (UUID) Cob.call(e, "getPlayerUUID");
                    Run r = who == null ? null : RUNS.get(who);
                    boolean rental = ADDING_RENTALS.contains(who) || (r != null && r.setup.rental);
                    try { Object src = Cob.call(e, "getDataSource"); Object pk = src == null ? null : Cob.call(src, "getPokemon"); if (pk != null && (Cob.isRental(pk) || Cob.isLoaner(pk))) rental = true; } catch (Throwable ignored) {}
                    // Nothing that happens inside the Battle Frontier ever touches a Pokédex: not rentals (caught), not the
                    // trainers' Pokémon you face (seen), not battle copies. The same goes for Rhea's loaners anywhere.
                    // (This also stops "register N Pokémon" quests paying out for Frontier entries.)
                    if (rental || r != null) Cob.call(e, "cancel");
                } catch (Throwable ignored) {}
            });
            Leaderboards.subscribe(ev.getField("TRADE_EVENT_PRE").get(null), (Consumer<Object>) e -> {
                try {
                    for (java.lang.reflect.Method m : e.getClass().getMethods()) {
                        if (m.getParameterCount() != 0 || !m.getName().toLowerCase().contains("pokemon")) continue;
                        Object pk = m.invoke(e);
                        if (pk != null && Cob.isRental(pk)) { Cob.call(e, "cancel"); return; }
                    }
                } catch (Throwable ignored) {}
            });
            Leaderboards.subscribe(ev.getField("POKEMON_RELEASED_EVENT_PRE").get(null), (Consumer<Object>) e -> {
                try { if (Cob.isRental(Cob.call(e, "getPokemon"))) Cob.call(e, "cancel"); } catch (Throwable ignored) {}
            });
        } catch (Throwable t) { GtsMod.LOG.error("[JCL] Could not hook rental protections", t); }
    }

    /** The Receptionist and BP Clerk NPCs in the lobby. */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onInteract(PlayerInteractEvent.EntityInteract e) {
        if (!(e.getEntity() instanceof ServerPlayer p) || e.getHand() != net.minecraft.world.InteractionHand.MAIN_HAND) return;
        Set<String> tags = e.getTarget().getTags();
        if (tags.contains("jcl_npc_frontier_receptionist")) { e.setCanceled(true); FrontierGui.setup(p); }
        else if (tags.contains("jcl_npc_frontier_clerk")) { e.setCanceled(true); FrontierShop.open(p, 0); }
        else if (tags.contains("jcl_frontier")) e.setCanceled(true);
    }

    static boolean nearLobby(ServerPlayer p) {
        var s = FrontierData.data.lobby;
        if (p.hasPermissions(2)) return true;
        if (s == null) return false;
        if (!p.level().dimension().location().toString().equals(s.dim)) return false;
        return p.distanceToSqr(s.x, s.y, s.z) <= (double) FrontierData.cfg.lobbyRadius * FrontierData.cfg.lobbyRadius;
    }

    static {
        // Hooks need Cobblemon, which is ready once the server has started.
    }

    static void hookIfNeeded() {
        if (hooked) return;
        hooked = true;
        // each hook on its own, so one failing can never stop the Pokédex and trade guards from attaching
        try { hookRentalGuards(); GtsMod.LOG.info("[JCL] Frontier Pokédex and trade guards attached."); } catch (Throwable t) { GtsMod.LOG.error("[JCL] Could not attach the Frontier guards", t); }
        try { hookBattles(); } catch (Throwable t) { GtsMod.LOG.error("[JCL] Could not attach the Frontier battle hooks", t); }
    }
    static boolean hooked;

    @SubscribeEvent
    public static void onStarted2(ServerStartedEvent e) { hookIfNeeded(); }
}
