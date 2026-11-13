package net.joeyscobblemon.gts;

import com.google.gson.JsonObject;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.*;
import java.util.function.Predicate;

/**
 * Tracks every Cobblemon task in the quest book (what Cobblemon Quests Reloaded used to do), straight from Cobblemon's
 * and Raid Dens' own events. Only the filters the book uses are needed: shiny (catch), Pokédex progress (have_registered)
 * and trainer IDs (defeat_trainer).
 */
@EventBusSubscriber(modid = GtsMod.MODID)
public final class QuestCobblemon {
    private QuestCobblemon() {}
    static final String T = "cobblemon_tasks:cobblemon_task";

    /** Adds progress to every unlocked, unfinished Cobblemon task of this kind that passes the filter. */
    static void bump(ServerPlayer p, String action, long by, Predicate<JsonObject> filter) {
        if (p == null || QuestBook.BOOK == null || by <= 0) return;
        var pr = QuestBook.progress(p.getUUID());
        for (var e : QuestBook.TASKS.entrySet()) {
            JsonObject t = e.getValue();
            if (!T.equals(t.get("type").getAsString()) || !action.equals(t.get("action").getAsString())) continue;
            String qid = QuestBook.TASK_QUEST.get(e.getKey());
            if (pr.done.containsKey(qid) || !QuestBook.unlocked(pr, qid)) continue;
            if (filter != null && !filter.test(t)) continue;
            QuestBook.addTask(p, e.getKey(), by);
        }
    }
    /** Sets progress to a value (only ever raising it): "reach level X", "register X Pokémon". */
    static void reach(ServerPlayer p, String action, java.util.function.ToLongFunction<JsonObject> value) {
        if (p == null || QuestBook.BOOK == null) return;
        var pr = QuestBook.progress(p.getUUID());
        for (var e : QuestBook.TASKS.entrySet()) {
            JsonObject t = e.getValue();
            if (!T.equals(t.get("type").getAsString()) || !action.equals(t.get("action").getAsString())) continue;
            String qid = QuestBook.TASK_QUEST.get(e.getKey());
            if (pr.done.containsKey(qid) || !QuestBook.unlocked(pr, qid)) continue;
            QuestBook.setTask(p, e.getKey(), value.applyAsLong(t));
        }
    }

    /** A defeat_trainer task counts this trainer if it names them (or names no one in particular). */
    static boolean trainerMatch(JsonObject t, String id) {
        String want = t.get("trainers").getAsString();
        if (want.isBlank()) return true;
        for (String s : want.split(",")) if (s.trim().equalsIgnoreCase(id)) return true;
        return false;
    }

    static Object call(Object o, String name) { try { return Leaderboards.call(o, name); } catch (Throwable t) { return null; } }
    static ServerPlayer owner(Object pokemon) { Object o = pokemon == null ? null : call(pokemon, "getOwnerPlayer"); return o instanceof ServerPlayer sp ? sp : null; }
    static ServerPlayer battleOwner(Object battlePokemon) { return owner(battlePokemon == null ? null : call(battlePokemon, "getOriginalPokemon")); }
    static boolean shiny(Object pokemon) { return Boolean.TRUE.equals(call(pokemon, "getShiny")); }

    /** Pokédex counts: species caught, or seen (seen includes caught). */
    static long dex(ServerPlayer p, boolean caughtOnly) {
        long n = 0;
        try {
            Map<?, ?> recs = (Map<?, ?>) Cob.call(Cob.pokedexOf(p), "getSpeciesRecords");
            for (Object rec : recs.values()) {
                String k = String.valueOf(Cob.call(rec, "getKnowledge"));
                if (k.equals("OWNED") || (!caughtOnly && k.equals("SEEN"))) n++;
            }
        } catch (Throwable ignored) {}
        return n;
    }
    static void dexTasks(ServerPlayer p) {
        reach(p, "have_registered", t -> dex(p, !"seen".equals(t.get("dex_progress").getAsString())));
    }

    static boolean hooked;
    @SubscribeEvent
    public static void onStart(ServerStartedEvent e) {
        if (hooked) return;
        try {
            Class<?> ev = Class.forName("com.cobblemon.mod.common.api.events.CobblemonEvents");
            sub(ev, "POKEMON_CAPTURED", x -> {
                ServerPlayer p = (ServerPlayer) call(x, "getPlayer"); Object mon = call(x, "getPokemon");
                bump(p, "catch", 1, t -> !t.get("shiny").getAsBoolean() || shiny(mon));
                dexTasks(p);
            });
            sub(ev, "BATTLE_VICTORY", x -> {
                if (Boolean.TRUE.equals(call(x, "getWasWildCapture"))) return;
                @SuppressWarnings("unchecked") List<Object> losers = (List<Object>) call(x, "getLosers");
                @SuppressWarnings("unchecked") List<Object> winners = (List<Object>) call(x, "getWinners");
                if (losers == null || winners == null) return;
                for (Object w : winners) {
                    if (!w.getClass().getSimpleName().equals("PlayerBattleActor")) continue;
                    ServerPlayer p = QuestBook.server.getPlayerList().getPlayer((UUID) call(w, "getUuid"));
                    if (p == null) continue;
                    for (Object l : losers) {
                        String n = l.getClass().getSimpleName();
                        if (n.equals("PlayerBattleActor")) { bump(p, "defeat_player", 1, null); continue; }
                        if (n.equals("PokemonBattleActor") || n.equals("MultiPokemonBattleActor")) { bump(p, "defeat", 1, null); continue; }
                        Entity npc = Npcs.npcOf(l);
                        if (npc == null || npc.getTags().contains("jcl_frontier") || Npcs.isEvTrainer(npc)) continue;
                        Object tid = call(npc, "getTrainerId");
                        String id = tid == null ? "" : tid.toString();
                        bump(p, "defeat_trainer", 1, t -> trainerMatch(t, id));
                    }
                }
            });
            sub(ev, "EVOLUTION_COMPLETE", x -> bump(owner(call(x, "getPokemon")), "evolve", 1, null));
            sub(ev, "HATCH_EGG_POST", x -> bump((ServerPlayer) call(x, "getPlayer"), "hatch_egg", 1, null));
            sub(ev, "LEVEL_UP_EVENT", x -> {
                Object mon = call(x, "getPokemon"); ServerPlayer p = owner(mon);
                Object o = call(x, "getOldLevel"), nl = call(x, "getNewLevel");
                if (p == null || !(nl instanceof Integer nv)) return;
                int ov = o instanceof Integer i ? i : nv - 1;
                bump(p, "level_up", nv - ov, null);
                reach(p, "level_up_to", t -> nv);
            });
            sub(ev, "POKEMON_SCANNED", x -> bump((ServerPlayer) call(x, "getPlayer"), "scan", 1, null));
            sub(ev, "BOBBER_SPAWN_POKEMON_POST", x -> {
                Object bob = call(x, "getBobber"); Object o = bob == null ? null : call(bob, "getOwner");
                if (o instanceof ServerPlayer p) bump(p, "fishing", 1, null);
            });
            sub(ev, "MEGA_EVOLUTION", x -> bump(battleOwner(call(x, "getPokemon")), "mega_evolve", 1, null));
            sub(ev, "TERASTALLIZATION", x -> bump(battleOwner(call(x, "getPokemon")), "terastallize", 1, null));
            sub(ev, "ZPOWER_USED", x -> bump(battleOwner(call(x, "getPokemon")), "use_z_move", 1, null));
            sub(ev, "FORME_CHANGE", x -> {                      // Mega Showdown's Dynamax shows up as a form change
                String f = String.valueOf(call(x, "getFormeName")).toLowerCase(Locale.ROOT);
                if (f.contains("dynamax") || f.contains("gmax")) bump(battleOwner(call(x, "getPokemon")), "dynamax", 1, null);
            });
            sub(ev, "THROWN_POKEBALL_HIT", x -> {
                Object ball = call(x, "getPokeBall"); Object o = ball == null ? null : call(ball, "getOwner");
                if (o instanceof ServerPlayer p) bump(p, "throw_ball", 1, null);
            });
            sub(ev, "STARTER_CHOSEN", x -> bump((ServerPlayer) call(x, "getPlayer"), "select_starter", 1, null));
            sub(ev, "FOSSIL_REVIVED", x -> { Object o = call(x, "getPlayer"); if (o instanceof ServerPlayer p) bump(p, "revive_fossil", 1, null); });
            GtsMod.LOG.info("[JCL] Quests: tracking Cobblemon tasks");
        } catch (Throwable t) { GtsMod.LOG.error("[JCL] Quests: couldn't hook Cobblemon events", t); }
        try {
            Class<?> rv = Class.forName("com.necro.raid.dens.common.events.RaidEvents");
            sub(rv, "RAID_JOIN", x -> bump((ServerPlayer) call(x, "getPlayer"), "join_raid", 1, null));
            sub(rv, "RAID_END", x -> { if (Boolean.TRUE.equals(call(x, "isWin"))) bump((ServerPlayer) call(x, "player"), "win_raid", 1, null); });
            sub(rv, "OPEN_POUCH", x -> bump((ServerPlayer) call(x, "getPlayer"), "open_raid_pouch", 1, null));
            GtsMod.LOG.info("[JCL] Quests: tracking raid tasks");
        } catch (ClassNotFoundException ignored) {
        } catch (Throwable t) { GtsMod.LOG.warn("[JCL] Quests: couldn't hook raid events: {}", t.toString()); }
        hooked = true;
    }

    static void sub(Class<?> holder, String field, java.util.function.Consumer<Object> h) throws Exception {
        Leaderboards.subscribe(holder.getField(field).get(null), x -> { try { h.accept(x); } catch (Throwable t) { GtsMod.LOG.debug("[JCL] Quests: {} handler: {}", field, t.toString()); } });
    }

    /** Pokédex tasks catch up every 10 seconds (registering can also happen through trades, gifts and so on). */
    static int tick;
    @SubscribeEvent
    public static void onTick(ServerTickEvent.Post e) {
        if (QuestBook.BOOK == null || QuestBook.server == null || ++tick % 200 != 0) return;
        for (ServerPlayer p : QuestBook.server.getPlayerList().getPlayers()) dexTasks(p);
    }
}
