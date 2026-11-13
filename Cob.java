package net.joeyscobblemon.gts;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;

/**
 * Every Cobblemon call the Battle Frontier makes, done by reflection (JCL Core has no compile-time
 * dependency on Cobblemon). Each method fails soft and logs once, so a Cobblemon change can never
 * crash the server.
 */
final class Cob {
    private Cob() {}

    private static final String P = "com.cobblemon.mod.common.";
    private static final Set<String> WARNED = new HashSet<>();

    static void warn(String what, Throwable t) {
        if (WARNED.add(what)) GtsMod.LOG.error("[JCL] Frontier: {} failed", what, t);
    }

    static Class<?> cls(String name) throws ClassNotFoundException { return Class.forName(P + name); }

    /** Calls a method by name, matching on argument count, trying every overload until one accepts the arguments. */
    static Object call(Object target, String name, Object... args) throws Exception {
        Class<?> c = target instanceof Class<?> k ? k : target.getClass();
        Object on = target instanceof Class<?> ? null : target;
        Exception last = null;
        for (Method m : c.getMethods()) {
            if (!m.getName().equals(name) || m.getParameterCount() != args.length) continue;
            try { return m.invoke(on, args); } catch (IllegalArgumentException e) { last = e; }
        }
        throw last != null ? last : new NoSuchMethodException(c.getName() + "." + name + "/" + args.length);
    }

    static Object kobject(String name) throws Exception { return cls(name).getField("INSTANCE").get(null); }

    // ------------------------------------------------------------------ Pokémon

    /** Builds a Pokémon from text properties, e.g. "garchomp level=50 nature=jolly ability=roughskin". */
    static Object create(String props) {
        try {
            Class<?> pp = cls("api.pokemon.PokemonProperties");
            Object companion = pp.getField("Companion").get(null);
            Object parsed = call(companion, "parse", props);
            return call(parsed, "create");
        } catch (Throwable t) { warn("create Pokémon '" + props + "'", t); return null; }
    }

    static Object clone(Object pk, RegistryAccess ra) {
        try { return call(pk, "clone", false, ra); } catch (Throwable t) { warn("clone Pokémon", t); return null; }
    }

    static void setLevel(Object pk, int lvl) { try { call(pk, "setLevel", Math.max(1, Math.min(100, lvl))); } catch (Throwable t) { warn("set level", t); } }
    static int level(Object pk) { try { return (int) call(pk, "getLevel"); } catch (Throwable t) { return 1; } }
    static void heal(Object pk) { try { call(pk, "heal"); } catch (Throwable t) { warn("heal", t); } }
    static boolean fainted(Object pk) { try { return (boolean) call(pk, "isFainted"); } catch (Throwable t) { return false; } }
    static UUID uuid(Object pk) { try { return (UUID) call(pk, "getUuid"); } catch (Throwable t) { return null; } }

    static void healPercent(Object pk, double pct) {
        try {
            if (fainted(pk)) return;
            int max = (int) call(pk, "getMaxHealth"), cur = (int) call(pk, "getCurrentHealth");
            call(pk, "setCurrentHealth", Math.min(max, cur + (int) Math.ceil(max * pct)));
        } catch (Throwable t) { warn("partial heal", t); }
    }

    static void revive(Object pk) {
        try { if (fainted(pk)) { int max = (int) call(pk, "getMaxHealth"); call(pk, "setCurrentHealth", Math.max(1, max / 2)); } }
        catch (Throwable t) { warn("revive", t); }
    }

    static ItemStack heldItem(Object pk) { try { return (ItemStack) call(pk, "heldItem"); } catch (Throwable t) { return ItemStack.EMPTY; } }

    static void setHeldItem(Object pk, ItemStack s) {
        try { call(pk, "swapHeldItem", s, false, false); } catch (Throwable t) { warn("set held item", t); }
    }

    static void setMoves(Object pk, List<String> moves) {
        if (moves == null || moves.isEmpty()) return;
        try {
            Object set = call(pk, "getMoveSet");
            Class<?> movesCls = cls("api.moves.Moves");
            List<Object> made = new ArrayList<>();
            for (String m : moves) {
                Object tmpl = call(movesCls, "getByName", m.toLowerCase().replace(" ", "").replace("-", ""));
                if (tmpl != null) made.add(call(tmpl, "create"));
            }
            if (made.isEmpty()) return;              // keep its natural moves rather than leave it with none
            call(set, "clear");
            for (int i = 0; i < made.size() && i < 4; i++) call(set, "setMove", i, made.get(i));
        } catch (Throwable t) { warn("set moves", t); }
    }

    static Object stat(String name) throws Exception { return cls("api.pokemon.stats.Stats").getField(name).get(null); }

    static void setIVs(Object pk, int v) {
        try { for (String s : new String[]{"HP", "ATTACK", "DEFENCE", "SPECIAL_ATTACK", "SPECIAL_DEFENCE", "SPEED"}) call(pk, "setIV", stat(s), v); }
        catch (Throwable t) { warn("set IVs", t); }
    }

    static void setEV(Object pk, String s, int v) { try { call(pk, "setEV", stat(s), v); } catch (Throwable t) { warn("set EV", t); } }

    static void setTera(Object pk, String type) {
        try { Object tt = call(cls("api.types.tera.TeraTypes"), "get", type.toLowerCase()); if (tt != null) call(pk, "setTeraType", tt); }
        catch (Throwable t) { warn("set Tera type", t); }
    }

    static void setGmax(Object pk, boolean g) { try { call(pk, "setGmaxFactor", g); call(pk, "setDmaxLevel", 10); } catch (Throwable t) { warn("set Gigantamax", t); } }

    static List<String> types(Object pk) {
        List<String> out = new ArrayList<>();
        try { for (Object t : (Iterable<?>) call(pk, "getTypes")) out.add(String.valueOf(call(t, "getName")).toLowerCase()); } catch (Throwable ignored) {}
        return out;
    }

    static String speciesName(Object pk) {
        try { return String.valueOf(call(call(pk, "getSpecies"), "getName")); } catch (Throwable t) { return "?"; }
    }

    static Set<String> labels(Object pk) {
        Set<String> out = new HashSet<>();
        try { Object ls = call(call(pk, "getSpecies"), "getLabels"); if (ls instanceof Iterable<?> it) for (Object l : it) out.add(String.valueOf(l).toLowerCase()); }
        catch (Throwable ignored) {}
        return out;
    }

    // ------------------------------------------------------------------ parties

    static List<Object> party(ServerPlayer p) {
        List<Object> out = new ArrayList<>();
        try {
            Object store = cls("util.PlayerExtensionsKt").getMethod("party", ServerPlayer.class).invoke(null, p);
            for (int i = 0; i < 6; i++) { Object pk = call(store, "get", i); if (pk != null) out.add(pk); }
        } catch (Throwable t) { warn("read party", t); }
        return out;
    }

    /** A standalone party that lives only in memory. Run teams and rentals live here, never in the player's own party. */
    static Object newParty(List<Object> pokemon) {
        try {
            Constructor<?> c = cls("api.storage.party.PartyStore").getConstructor(UUID.class);
            Object store = c.newInstance(UUID.randomUUID());
            for (int i = 0; i < pokemon.size() && i < 6; i++) call(store, "set", i, pokemon.get(i));
            return store;
        } catch (Throwable t) { warn("create a run party", t); return null; }
    }

    // ------------------------------------------------------------------ NPC trainers and battles

    /** Gives an NPC a fresh party of the given Pokémon. */
    static boolean setNpcParty(Entity npc, List<Object> pokemon) {
        try {
            Class<?> npcCls = cls("entity.npc.NPCEntity");
            Object store = cls("api.storage.party.NPCPartyStore").getConstructor(npcCls).newInstance(npc);
            for (int i = 0; i < pokemon.size() && i < 6; i++) call(store, "set", i, pokemon.get(i));
            call(npc, "setParty", store);
            return true;
        } catch (Throwable t) { warn("give an NPC its team", t); return false; }
    }

    static void setNpcSkill(Entity npc, int skill) { try { call(npc, "setSkill", (Integer) skill); } catch (Throwable t) { warn("set NPC skill", t); } }

    /** A copy of Cobblemon's Gen 9 singles, doubles or triples format, with extra Showdown rules. */
    static Object format(String kind, List<String> extraRules) throws Exception {
        Class<?> fmt = cls("battles.BattleFormat");
        Object companion = fmt.getField("Companion").get(null);
        String getter = switch (kind) { case "doubles" -> "getGEN_9_DOUBLES"; case "triples" -> "getGEN_9_TRIPLES"; case "multi", "coop" -> "getGEN_9_MULTI"; default -> "getGEN_9_SINGLES"; };
        Object base = call(companion, getter);
        @SuppressWarnings("unchecked") Set<String> rules = new HashSet<>((Set<String>) call(base, "getRuleSet"));
        rules.addAll(extraRules);
        return call(base, "copy", call(base, "getMod"), call(base, "getBattleType"), rules, call(base, "getGen"), -1);
    }

    /** Starts a battle between a player (using a run party) and an NPC. Returns null on success, or the reason it failed. */
    static String startBattle(ServerPlayer p, Entity npc, Object party, String kind, List<String> rules) {
        try {
            Object builder = kobject("battles.BattleBuilder");
            Object fmt = format(kind, rules);
            UUID lead = null;
            for (int i = 0; i < 6 && lead == null; i++) { Object pk = call(party, "get", i); if (pk != null && !fainted(pk)) lead = uuid(pk); }
            Object result = call(builder, "pvn", p, npc, lead, fmt, false, false, party);
            if (result != null && result.getClass().getSimpleName().contains("Errored")) {
                try { return String.valueOf(call(result, "getErrors")); } catch (Throwable ignored) { return "the battle couldn't start"; }
            }
            return null;
        } catch (Throwable t) { warn("start a Frontier battle", t); return "the battle couldn't start (" + t.getClass().getSimpleName() + ")"; }
    }

    /**
     * A player-vs-player challenge. Each side is battle copies of the player's real party (Cobblemon's own
     * BattlePokemon.safeCopyOf), fully healed and set to the chosen level (0 keeps their own levels), so the real
     * Pokémon gain no experience and never faint.
     */
    static String startPvp(ServerPlayer a, ServerPlayer b, int level, String kind) {
        try {
            Object fmt = format(kind, List.of());
            Class<?> actorCls = cls("api.battles.model.actor.BattleActor");
            Class<?> playerActor = cls("battles.actor.PlayerBattleActor");
            Class<?> side = cls("battles.BattleSide");
            Object companion = cls("battles.pokemon.BattlePokemon").getField("Companion").get(null);
            Object[] sides = new Object[2];
            ServerPlayer[] ps = {a, b};
            for (int s = 0; s < 2; s++) {
                ServerPlayer p = ps[s];
                Object holder = cls("api.storage.party.PlayerPartyStore").getConstructor(UUID.class).newInstance(p.getUUID());
                List<Object> team = new ArrayList<>(); int slot = 0;
                for (Object orig : party(p)) {
                    if (orig == null) continue;
                    Object bp = call(companion, "safeCopyOf", orig);
                    Object eff = call(bp, "getEffectedPokemon");
                    if (level > 0) setLevel(eff, level);
                    try { call(eff, "setCurrentHealth", (int) call(eff, "getMaxHealth")); } catch (Throwable t) { warn("heal a challenge clone", t); }
                    if (slot < 6) { try { call(holder, "set", slot++, eff); } catch (Throwable ignored) {} }
                    team.add(bp);
                }
                if (team.isEmpty()) return p.getGameProfile().getName() + " has no Pokémon with them";
                Object arr = java.lang.reflect.Array.newInstance(actorCls, 1);
                java.lang.reflect.Array.set(arr, 0, playerActor.getConstructor(UUID.class, List.class).newInstance(p.getUUID(), team));
                sides[s] = side.getConstructor(arr.getClass()).newInstance(arr);
            }
            Object result = call(cls("battles.BattleRegistry"), "startBattle", fmt, sides[0], sides[1], false);
            if (result != null && result.getClass().getSimpleName().contains("Errored")) {
                try { return String.valueOf(call(result, "getErrors")); } catch (Throwable ignored) { return "the battle couldn't start"; }
            }
            return null;
        } catch (Throwable t) { warn("start a challenge", t); return "the battle couldn't start (" + t.getClass().getSimpleName() + ")"; }
    }

    static Object battleOf(ServerPlayer p) {
        try { return call(cls("battles.BattleRegistry"), "getBattleByParticipatingPlayer", p); } catch (Throwable t) { return null; }
    }

    static void endBattle(Object battle) { try { if (battle != null) call(battle, "end"); } catch (Throwable t) { warn("end a battle", t); } }

    static UUID battleId(Object battle) { try { return (UUID) call(battle, "getBattleId"); } catch (Throwable t) { return null; } }

    static boolean isNpcEntity(Entity e) { return e != null && e.getClass().getSimpleName().equals("NPCEntity"); }

    static boolean isWildPokemon(Entity e) {
        if (e == null || !e.getClass().getSimpleName().equals("PokemonEntity")) return false;
        try { return call(e, "getOwnerUUID") == null; } catch (Throwable t) { return false; }
    }


    // ------------------------------------------------------------------ co-op (two players against two trainers)

    /**
     * Starts a 2v2 multi battle: two players (each with a run party) against two NPC trainers.
     * Built from Cobblemon's own pieces (player actors, NPC actors, battle sides), the same way its
     * built-in battle starter does it. Returns null on success, or the reason it failed.
     */
    static String startMulti(ServerPlayer a, Object partyA, ServerPlayer b, Object partyB, Entity npc1, Entity npc2, List<String> rules, int skill) {
        return startMulti(a, partyA, b, partyB, null, npc1, npc2, rules, skill);
    }

    /** Same, but the player's teammate can be an NPC ally instead of a second player (pass b = null, ally = the NPC). */
    static String startMulti(ServerPlayer a, Object partyA, ServerPlayer b, Object partyB, Entity ally, Entity npc1, Entity npc2, List<String> rules, int skill) {
        try {
            Object fmt = format("multi", rules);
            Class<?> actorCls = cls("api.battles.model.actor.BattleActor");
            Class<?> playerActor = cls("battles.actor.PlayerBattleActor");
            Class<?> npcActor = cls("entity.npc.NPCBattleActor");
            Class<?> npcCls = cls("entity.npc.NPCEntity");
            Class<?> partyCls = cls("api.storage.party.PartyStore");
            Class<?> side = cls("battles.BattleSide");
            Object pa = playerActor.getConstructor(UUID.class, List.class).newInstance(a.getUUID(), team(partyA));
            Object pb = b != null ? playerActor.getConstructor(UUID.class, List.class).newInstance(b.getUUID(), team(partyB))
                    : npcActor.getConstructor(npcCls, partyCls, int.class).newInstance(ally, call(ally, "getParty"), 5);
            Object na = npcActor.getConstructor(npcCls, partyCls, int.class).newInstance(npc1, call(npc1, "getParty"), skill);
            Object nb = npcActor.getConstructor(npcCls, partyCls, int.class).newInstance(npc2, call(npc2, "getParty"), skill);
            Object arr1 = java.lang.reflect.Array.newInstance(actorCls, 2); java.lang.reflect.Array.set(arr1, 0, pa); java.lang.reflect.Array.set(arr1, 1, pb);
            Object arr2 = java.lang.reflect.Array.newInstance(actorCls, 2); java.lang.reflect.Array.set(arr2, 0, na); java.lang.reflect.Array.set(arr2, 1, nb);
            Object s1 = side.getConstructor(arr1.getClass()).newInstance(arr1);
            Object s2 = side.getConstructor(arr2.getClass()).newInstance(arr2);
            Object result = call(cls("battles.BattleRegistry"), "startBattle", fmt, s1, s2, false);
            if (result != null && result.getClass().getSimpleName().contains("Errored")) {
                try { return String.valueOf(call(result, "getErrors")); } catch (Throwable ignored) { return "the battle couldn't start"; }
            }
            return null;
        } catch (Throwable t) { warn("start a co-op Frontier battle", t); return "the co-op battle couldn't start (" + t.getClass().getSimpleName() + ")"; }
    }

    /** A run party as battle Pokémon, led by its first Pokémon that can still fight. */
    static Object team(Object party) throws Exception {
        UUID lead = null;
        for (int i = 0; i < 6 && lead == null; i++) { Object pk = call(party, "get", i); if (pk != null && !fainted(pk)) lead = uuid(pk); }
        return call(party, "toBattleTeam", false, false, lead);
    }

    // ------------------------------------------------------------------ enemy gimmicks

    /**
     * Cobblemon's trainer AI never chooses Mega Evolution, Z-Moves, Dynamax or Terastal on its own.
     * This wraps each NPC's AI in the battle: it still picks the move, and then adds a gimmick when
     * Showdown says one is available and the run's settings let the enemy use it.
     */
    static void enableEnemyGimmicks(Object battle, Set<String> allowed) { enableEnemyGimmicks(battle, allowed, false); }

    /**
     * Wraps each enemy trainer's AI. It adds gimmicks the run allows (once each per battle), and in Singles it drops
     * the move target: Showdown picks the only possible target itself, and a target on a move like Hypnosis (or on a
     * status move from a Dynamaxed Pokémon) is rejected and freezes the battle.
     */
    static void enableEnemyGimmicks(Object battle, Set<String> allowed, boolean singles) {
        if (battle == null) return;
        try {
            Class<?> aiCls = cls("api.battles.model.ai.BattleAI");
            java.lang.reflect.Field f = cls("api.battles.model.actor.AIBattleActor").getDeclaredField("battleAI");
            f.setAccessible(true);
            for (Object actor : (Iterable<?>) call(battle, "getActors")) {
                if (!actor.getClass().getSimpleName().equals("NPCBattleActor")) continue;
                Object orig = f.get(actor);
                if (orig == null || java.lang.reflect.Proxy.isProxyClass(orig.getClass())) continue;
                Set<String> used = new HashSet<>();      // each gimmick only once per battle, per trainer
                int[] maxTurns = {0};                      // how many more choices this trainer stays Dynamaxed
                Object proxy = java.lang.reflect.Proxy.newProxyInstance(aiCls.getClassLoader(), new Class<?>[]{aiCls}, (px, m, args) -> {
                    Object res;
                    try { res = m.invoke(orig, args); } catch (java.lang.reflect.InvocationTargetException ite) { throw ite.getCause(); }
                    if (m.getName().equals("choose") && args != null && args.length >= 4) {
                        Object active = null; boolean force = false;
                        for (Object a : args) { if (a != null && a.getClass().getSimpleName().equals("ActiveBattlePokemon")) active = a; if (a instanceof Boolean bo) force = bo; }
                        Object original = res;
                        try {
                            boolean hadMax = used.contains("max");
                            res = addGimmick(res, args[3], allowed, used);
                            if (!hadMax && used.contains("max")) maxTurns[0] = 3;
                            else if (maxTurns[0] > 0) maxTurns[0]--;
                            res = settle(res, original, active, args[3], force, maxTurns[0] > 0);
                        } catch (Throwable t) { warn("check an enemy move", t); res = original; }
                    }
                    return res;
                });
                f.set(actor, proxy);
            }
        } catch (Throwable t) { warn("let enemies use gimmicks", t); }
    }

    static final java.util.Random GR = new java.util.Random();

    /**
     * Adds a gimmick to the AI's chosen move, only when Showdown says it's available right now, the run allows it, and
     * this trainer hasn't used that gimmick yet (Showdown rejects a second Tera or Dynamax, which would freeze the battle).
     * Only one gimmick per turn, so two Pokémon on the field never both try. Z-Moves are left to the AI's normal choice,
     * since turning a status move into a Z-Move changes its targeting.
     */
    static boolean valid(Object res, Object active, Object moveset, boolean force) {
        if (active == null) return true;
        try { return Boolean.TRUE.equals(res.getClass().getMethod("isValid", active.getClass(), moveset.getClass(), boolean.class).invoke(res, active, moveset, force)); }
        catch (Throwable t) { return true; }
    }

    /**
     * Makes sure the move a trainer sends is one Showdown will accept, so a battle never stalls. Uses Cobblemon's own
     * check; if the gimmick made it invalid, the plain move is used; if the target is the problem, the right target is
     * picked (none for moves that don't take one, including any status move while Dynamaxed, which becomes Max Guard).
     */
    static Object settle(Object res, Object original, Object active, Object moveset, boolean force, boolean dynamaxed) throws Exception {
        if (res == null || !res.getClass().getSimpleName().equals("MoveActionResponse")) return res;
        String move = (String) call(res, "getMoveName");
        Object entry = null;
        for (Object mv : (List<?>) call(moveset, "getMoves")) if (move != null && move.equals(call(mv, "getId"))) entry = mv;
        String target = (String) call(res, "getTargetPnx");
        if (entry != null) {
            String tt = String.valueOf(call(entry, "getTarget")).toLowerCase(java.util.Locale.ROOT);
            boolean noTarget = tt.contains("self") || tt.contains("allyside") || tt.contains("allyteam") || tt.equals("all") || tt.contains("foeside") || tt.contains("alladjacent") || tt.contains("randomnormal") || tt.equals("allies") || tt.equals("scripted");
            if (dynamaxed && tt.contains("self")) noTarget = true;
            if (noTarget && target != null) res = res.getClass().getConstructor(String.class, String.class, String.class).newInstance(move, null, call(res, "getGimmickID"));
            else if (!noTarget && target == null && active != null) {
                try {
                    List<?> ts = (List<?>) call(entry, "getTargets", active);
                    if (ts != null && !ts.isEmpty()) res = res.getClass().getConstructor(String.class, String.class, String.class).newInstance(move, call(ts.get(0), "getPNX"), call(res, "getGimmickID"));
                } catch (Throwable ignored) {}
            }
        }
        if (valid(res, active, moveset, force)) return res;
        if (call(res, "getGimmickID") != null) {
            Object plain = res.getClass().getConstructor(String.class, String.class, String.class).newInstance(move, call(res, "getTargetPnx"), null);
            if (valid(plain, active, moveset, force)) return plain;
        }
        return original;
    }

    static Object dropTarget(Object res) throws Exception {
        if (res == null || !res.getClass().getSimpleName().equals("MoveActionResponse")) return res;
        if (call(res, "getTargetPnx") == null) return res;
        return res.getClass().getConstructor(String.class, String.class, String.class).newInstance(call(res, "getMoveName"), null, call(res, "getGimmickID"));
    }

    static Object addGimmick(Object res, Object moveset, Set<String> allowed, Set<String> used) throws Exception {
        if (res == null || moveset == null || !res.getClass().getSimpleName().equals("MoveActionResponse")) return res;
        if (call(res, "getGimmickID") != null) return res;
        String move = (String) call(res, "getMoveName");
        Object target = call(res, "getTargetPnx");
        String gimmick = null;
        if (allowed.contains("mega") && !used.contains("mega") && Boolean.TRUE.equals(call(moveset, "getCanMegaEvo"))) gimmick = "mega";
        else if (allowed.contains("tera") && !used.contains("terastal") && call(moveset, "getCanTerastallize") != null && GR.nextDouble() < 0.35) gimmick = "terastal";
        else if (allowed.contains("dynamax") && !used.contains("max") && !used.contains("terastal") && Boolean.TRUE.equals(call(moveset, "getCanDynamax")) && GR.nextDouble() < 0.35) gimmick = "max";
        if (gimmick == null) return res;
        used.add(gimmick);
        return res.getClass().getConstructor(String.class, String.class, String.class).newInstance(move, target, gimmick);
    }

    // ------------------------------------------------------------------ accessory slots

    /** True if the player has this item equipped in an Accessories slot (where Mega Showdown's gear goes). */
    static boolean equippedAccessory(ServerPlayer p, net.minecraft.world.item.Item item) {
        // armour slots
        for (ItemStack st : p.getInventory().armor) if (st.is(item)) return true;
        // Curios slots (some gear goes here through the Accessories compat layer)
        try {
            Class<?> api = Class.forName("top.theillusivec4.curios.api.CuriosApi");
            Object opt = api.getMethod("getCuriosInventory", net.minecraft.world.entity.LivingEntity.class).invoke(null, p);
            if (opt instanceof java.util.Optional<?> o && o.isPresent()) {
                Object h = o.get();
                for (Method m : h.getClass().getMethods())
                    if (m.getName().equals("isEquipped") && m.getParameterCount() == 1 && m.getParameterTypes()[0] == net.minecraft.world.item.Item.class)
                        if (Boolean.TRUE.equals(m.invoke(h, item))) return true;
            }
        } catch (ClassNotFoundException ignored) {
        } catch (Throwable t) { warn("check Curios slots", t); }
        try {
            Class<?> cap = Class.forName("io.wispforest.accessories.api.AccessoriesCapability");
            Object c = cap.getMethod("get", net.minecraft.world.entity.LivingEntity.class).invoke(null, p);
            if (c == null) return false;
            return (boolean) cap.getMethod("isEquipped", net.minecraft.world.item.Item.class).invoke(c, item);
        } catch (ClassNotFoundException e) { return false; }
        catch (Throwable t) { warn("check accessory slots", t); return false; }
    }


    // ------------------------------------------------------------------ run battles (the player's side built by hand)

    /** A Kotlin "do nothing" function, for Cobblemon constructors that want one. */
    static Object noop1() throws Exception {
        Class<?> f1 = Class.forName("kotlin.jvm.functions.Function1");
        Object unit = Class.forName("kotlin.Unit").getField("INSTANCE").get(null);
        return java.lang.reflect.Proxy.newProxyInstance(f1.getClassLoader(), new Class<?>[]{f1}, (px, m, a) -> {
            if (m.getName().equals("invoke")) return unit;
            if (m.getName().equals("hashCode")) return System.identityHashCode(px);
            if (m.getName().equals("equals")) return px == a[0];
            return "noop";
        });
    }

    /**
     * One player's battle team: each Pokémon is shown and sent out as the player's real Pokémon (so it has an
     * owner and spawns properly), but all damage, levels and items apply to the run copy only.
     */
    static List<Object> battleTeam(List<Object> originals, List<Object> copies) throws Exception {
        return battleTeam(null, originals, copies, null);
    }

    /**
     * One player's battle team, built exactly the way Cobblemon builds its own copied teams (BattlePokemon.safeCopyOf):
     * a battle-clone of each real party Pokémon, so it's sent out like a normal battle. The clone then takes the run's
     * level cap, HP and held item from the run copy. Each {clone, run copy} pair is added to "pairs" so the result
     * (HP, fainting) can be written back to the run copy after the battle. The real Pokémon is never changed.
     */
    /** Set for the length of one battle start when the No Items rule is on: nobody on either side holds anything. */
    static boolean STRIP_ITEMS;

    static List<Object> battleTeam(UUID owner, List<Object> originals, List<Object> copies, List<Object[]> pairs) throws Exception {
        Object companion = cls("battles.pokemon.BattlePokemon").getField("Companion").get(null);
        // Like Cobblemon's own level-adjusted battles: the clones sit in a throwaway party owned by the player, so the
        // game knows whose they are (that's what makes them get sent out). It's never registered or saved anywhere.
        Object holder = owner == null ? null : cls("api.storage.party.PlayerPartyStore").getConstructor(UUID.class).newInstance(owner);
        int slot = 0;
        List<Object> lead = new ArrayList<>(), rest = new ArrayList<>();
        for (int i = 0; i < copies.size() && i < originals.size(); i++) {
            Object copy = copies.get(i), orig = originals.get(i);
            Object bp = call(companion, "safeCopyOf", orig);
            Object eff = call(bp, "getEffectedPokemon");
            setLevel(eff, level(copy));
            try {
                int max = (int) call(eff, "getMaxHealth");
                int hp = fainted(copy) ? 0 : Math.max(1, Math.min(max, (int) call(copy, "getCurrentHealth")));
                call(eff, "setCurrentHealth", hp);
            } catch (Throwable t) { warn("copy HP into a battle clone", t); }
            setHeldItem(eff, STRIP_ITEMS ? ItemStack.EMPTY : heldItem(copy).copy());
            if (holder != null && slot < 6) { try { call(holder, "set", slot++, eff); } catch (Throwable t) { warn("give battle clones an owner", t); } }
            if (pairs != null) pairs.add(new Object[]{eff, copy, bp});
            (fainted(copy) || !lead.isEmpty() ? rest : lead).add(bp);
        }
        lead.addAll(rest);
        return lead;
    }

    /** After a battle: the run copies take on the battle clones' HP (and fainting), so No Healing carries over. */
    static void writeBack(List<Object[]> pairs) {
        for (Object[] pr : pairs) {
            try {
                int hp = pr.length > 2 && pr[2] != null ? (int) call(pr[2], "getHealth") : (int) call(pr[0], "getCurrentHealth");   // the battle's own HP count
                call(pr[1], "setCurrentHealth", Math.max(0, hp));
            } catch (Throwable t) { warn("copy HP back after a battle", t); }
        }
    }

    /**
     * Starts a Frontier battle. players: each entry is {ServerPlayer, originals, copies}. An optional NPC ally joins
     * the players' side. enemies: one NPC for singles/doubles/triples, two for co-op.
     */
    static String startRun(List<Object[]> players, Entity ally, List<Entity> enemies, String kind, List<String> rules, int skill) {
        return startRun(players, ally, enemies, kind, rules, skill, null);
    }

    static String startRun(List<Object[]> players, Entity ally, List<Entity> enemies, String kind, List<String> rules, int skill, List<Object[]> pairs) {
        try {
            Object fmt = format(kind, rules);
            Class<?> actorCls = cls("api.battles.model.actor.BattleActor");
            Class<?> playerActor = cls("battles.actor.PlayerBattleActor");
            Class<?> npcActor = cls("entity.npc.NPCBattleActor");
            Class<?> npcCls = cls("entity.npc.NPCEntity");
            Class<?> partyCls = cls("api.storage.party.PartyStore");
            Class<?> side = cls("battles.BattleSide");
            List<Object> a = new ArrayList<>(), b = new ArrayList<>();
            for (Object[] pl : players) {
                @SuppressWarnings("unchecked") List<Object> team = battleTeam(((ServerPlayer) pl[0]).getUUID(), (List<Object>) pl[1], (List<Object>) pl[2], pairs);
                if (team.isEmpty()) return "your team couldn't be prepared";
                a.add(playerActor.getConstructor(UUID.class, List.class).newInstance(((ServerPlayer) pl[0]).getUUID(), team));
            }
            if (ally != null) {
                if (STRIP_ITEMS) { try { for (Object pk : (Iterable<?>) call(ally, "getParty")) if (pk != null) setHeldItem(pk, ItemStack.EMPTY); } catch (Throwable ignored) {} }
                a.add(npcActor.getConstructor(npcCls, partyCls, int.class).newInstance(ally, call(ally, "getParty"), 5));
            }
            for (Entity e : enemies) {
                if (STRIP_ITEMS) { try { for (Object pk : (Iterable<?>) call(e, "getParty")) if (pk != null) setHeldItem(pk, ItemStack.EMPTY); } catch (Throwable ignored) {} }
                b.add(npcActor.getConstructor(npcCls, partyCls, int.class).newInstance(e, call(e, "getParty"), skill));
            }
            Object arr1 = java.lang.reflect.Array.newInstance(actorCls, a.size());
            for (int i = 0; i < a.size(); i++) java.lang.reflect.Array.set(arr1, i, a.get(i));
            Object arr2 = java.lang.reflect.Array.newInstance(actorCls, b.size());
            for (int i = 0; i < b.size(); i++) java.lang.reflect.Array.set(arr2, i, b.get(i));
            Object s1 = side.getConstructor(arr1.getClass()).newInstance(arr1);
            Object s2 = side.getConstructor(arr2.getClass()).newInstance(arr2);
            Object result = call(cls("battles.BattleRegistry"), "startBattle", fmt, s1, s2, false);
            GtsMod.LOG.info("[JCL] Frontier battle start ({}): {} player actor(s), ally={}, {} enemy actor(s) -> {}", kind, players.size(), ally != null, enemies.size(), result == null ? "null" : result.getClass().getSimpleName());
            if (result != null && result.getClass().getSimpleName().contains("Errored")) {
                try { return String.valueOf(call(result, "getErrors")); } catch (Throwable ignored) { return "the battle couldn't start"; }
            }
            return null;
        } catch (Throwable t) {
            GtsMod.LOG.error("[JCL] Frontier: starting a battle failed", t);
            return "the battle couldn't start (" + t.getClass().getSimpleName() + ")";
        }
    }

    // ------------------------------------------------------------------ rentals live in the (empty) real party, tagged

    static final String RENTAL_TAG = "jcl_frontier_rental";

    static boolean isLoaner(Object pk) { return Loaner.isLoaner(pk); }

    static boolean isRental(Object pk) {
        try { return ((net.minecraft.nbt.CompoundTag) call(pk, "getPersistentData")).getBoolean(RENTAL_TAG); } catch (Throwable t) { return false; }
    }

    static void markRental(Object pk) {
        try { ((net.minecraft.nbt.CompoundTag) call(pk, "getPersistentData")).putBoolean(RENTAL_TAG, true); } catch (Throwable t) { warn("tag a rental", t); }
    }

    static Object partyStore(ServerPlayer p) throws Exception { return cls("util.PlayerExtensionsKt").getMethod("party", ServerPlayer.class).invoke(null, p); }

    static boolean addToParty(ServerPlayer p, Object pk) {
        try { return Boolean.TRUE.equals(call(partyStore(p), "add", pk)); } catch (Throwable t) { warn("add a rental to the party", t); return false; }
    }

    /** Removes every rental from a player's party and PC. Returns how many were removed. */
    static int removeRentals(ServerPlayer p) {
        int n = 0;
        try {
            Object party = partyStore(p);
            for (int i = 0; i < 6; i++) { Object pk = call(party, "get", i); if (pk != null && isRental(pk)) { call(party, "remove", pk); n++; } }
        } catch (Throwable t) { warn("remove rentals from a party", t); }
        try {
            Object pc = cls("util.PlayerExtensionsKt").getMethod("pc", ServerPlayer.class).invoke(null, p);
            List<Object> found = new ArrayList<>();
            for (Object pk : (Iterable<?>) pc) if (pk != null && isRental(pk)) found.add(pk);
            for (Object pk : found) { call(pc, "remove", pk); n++; }
        } catch (Throwable t) { warn("remove rentals from a PC", t); }
        return n;
    }

    /** True if this species is a natural-spawn Ultra Beast or Paradox Pokémon. */
    static boolean isUltraOrParadox(Entity e) {
        try {
            Object pk = call(e, "getPokemon");
            Set<String> l = labels(pk);
            return l.contains("ultra_beast") || l.contains("paradox");
        } catch (Throwable t) { return false; }
    }

    // ------------------------------------------------------------------ Pokédex (so rentals don't count)

    static Object pokedexOf(ServerPlayer p) throws Exception {
        Object cobblemon = cls("Cobblemon").getField("INSTANCE").get(null);
        Object pdm = call(cobblemon, "getPlayerDataManager");
        return call(pdm, "getPokedexData", p);
    }

    static net.minecraft.resources.ResourceLocation speciesId(Object pk) {
        try { return (net.minecraft.resources.ResourceLocation) call(call(pk, "getSpecies"), "getResourceIdentifier"); } catch (Throwable t) { return null; }
    }

    /** True if the player's Pokédex has no record at all for this species yet. */
    static boolean dexHasNoRecord(ServerPlayer p, Object pk) {
        try { var id = speciesId(pk); return id != null && call(pokedexOf(p), "getSpeciesRecord", id) == null; } catch (Throwable t) { warn("read a Pokédex", t); return false; }
    }

    /** The type of a move if it's a damaging move (so a Z-Crystal of that type gives a real Z-Move), else null. */
    static String attackType(String move) {
        try {
            Object tmpl = cls("api.moves.Moves").getMethod("getByName", String.class).invoke(null, move.toLowerCase(java.util.Locale.ROOT).replace(" ", "").replace("-", ""));
            if (tmpl == null) return null;
            Object cat = call(tmpl, "getDamageCategory");
            String cn; try { cn = String.valueOf(call(cat, "getName")); } catch (Throwable t2) { cn = String.valueOf(cat); }
            if (cn.toLowerCase(java.util.Locale.ROOT).contains("status")) return null;
            return String.valueOf(call(call(tmpl, "getElementalType"), "getName")).toLowerCase(java.util.Locale.ROOT);
        } catch (Throwable t) { return null; }
    }

    static String formName(Object pk) {
        try { return String.valueOf(call(call(pk, "getForm"), "formOnlyShowdownId")); } catch (Throwable t) { return "normal"; }
    }

    /** What the player's Pokédex says about this rental's species and form right now: {species id, form, knowledge or null, species existed}. */
    static Object[] dexSnapshot(ServerPlayer p, Object pk) {
        try {
            var id = speciesId(pk); if (id == null) return null;
            Object rec = call(pokedexOf(p), "getSpeciesRecord", id);
            String form = formName(pk);
            Object fr = rec == null ? null : call(rec, "getFormRecord", form);
            return new Object[]{id, form, fr == null ? null : call(fr, "getKnowledge"), rec != null};
        } catch (Throwable t) { warn("read a Pokédex", t); return null; }
    }

    /** Puts the Pokédex back the way the snapshots found it, so rentals never count as caught. */
    static void dexRestore(ServerPlayer p, java.util.Collection<Object[]> snaps) {
        try {
            Object dex = pokedexOf(p);
            // species the player really owns right now (not loaners or rentals): their entries are earned, never reverted
            Set<net.minecraft.resources.ResourceLocation> owned = new HashSet<>();
            try { for (Object pk : party(p)) if (pk != null && !Loaner.isLoaner(pk) && !isRental(pk)) { var id = speciesId(pk); if (id != null) owned.add(id); } } catch (Throwable ignored) {}
            try {
                Object pc = cls("util.PlayerExtensionsKt").getMethod("pc", ServerPlayer.class).invoke(null, p);
                for (Object pk : (Iterable<?>) pc) if (pk != null && !Loaner.isLoaner(pk) && !isRental(pk)) { var id = speciesId(pk); if (id != null) owned.add(id); }
            } catch (Throwable ignored) {}
            for (Object[] s : snaps) {
                if (s == null) continue;
                if (owned.contains(s[0])) continue;
                if (!(boolean) s[3]) { call(dex, "deleteSpeciesRecord", s[0]); continue; }
                Object rec = call(dex, "getSpeciesRecord", s[0]);
                if (rec == null) continue;
                // the species was already in their Pokédex: never delete anything, only put a raised entry back to what it was
                if (s[2] == null) continue;
                Object fr = call(rec, "getFormRecord", s[1]);
                if (fr != null) call(fr, "setKnowledgeProgress", s[2]);
            }
        } catch (Throwable t) { warn("restore a Pokédex after a rental run", t); }
    }

    static void dexForget(ServerPlayer p, java.util.Collection<net.minecraft.resources.ResourceLocation> ids) {
        if (ids.isEmpty()) return;
        try {
            Object dex = pokedexOf(p);
            for (var id : ids) call(dex, "deleteSpeciesRecord", id);
            try { call(cls("Cobblemon").getField("INSTANCE").get(null), "getPlayerDataManager"); } catch (Throwable ignored) {}
        } catch (Throwable t) { warn("clean rentals out of a Pokédex", t); }
    }

    static boolean isLegendOrMythical(Entity e) {
        try {
            Set<String> l = labels(call(e, "getPokemon"));
            return l.contains("legendary") || l.contains("mythical");
        } catch (Throwable t) { return false; }
    }

    static ResourceLocation rl(String id) { return ResourceLocation.parse(id); }

    static ServerLevel level(net.minecraft.server.MinecraftServer s, String dim) {
        return s.getLevel(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, rl(dim)));
    }
}
