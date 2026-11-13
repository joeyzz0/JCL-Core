package net.joeyscobblemon.gts;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import net.joeyscobblemon.gts.FrontierData.Bracket;
import net.joeyscobblemon.gts.FrontierData.Set6;

/** Builds every team the Frontier uses: room trainers, Champions, Brains and rentals. Also rolls the doors. */
final class FrontierTeams {
    private FrontierTeams() {}

    static final Random RNG = new Random();

    static final String[] CLASSES = {"Ace Trainer", "Veteran", "Battle Girl", "Black Belt", "Psychic", "Ranger", "Dragon Tamer", "Scientist", "Expert", "Cooltrainer", "Hiker", "Swimmer"};
    static final String[] NAMES = {"Alex", "Bea", "Cole", "Dana", "Eli", "Faye", "Gus", "Hana", "Ivo", "Jade", "Kai", "Lena", "Milo", "Nia", "Oren", "Pia", "Quin", "Rhea", "Sol", "Tess", "Uma", "Vic", "Wren", "Xia", "Yuri", "Zed"};

    static String trainerName() { return CLASSES[RNG.nextInt(CLASSES.length)] + " " + NAMES[RNG.nextInt(NAMES.length)]; }

    /** Each trainer skin's class and whether the trainer is a woman, so names and classes always match the skin. */
    static final String[][] SKIN_CLASS = {
            {"Ace Trainer", "f"}, {"Veteran", "m"}, {"Hiker", "m"}, {"Black Belt", "m"}, {"Beauty", "f"}, {"Psychic", "f"},
            {"Swimmer", "f"}, {"Fisherman", "m"}, {"Gentleman", "m"}, {"Aroma Lady", "f"}, {"Artist", "m"}, {"Worker", "m"},
            {"Scientist", "m"}, {"Sailor", "m"}, {"Pokémon Ranger", "f"}, {"Lass", "f"}, {"Youngster", "m"}, {"Biker", "m"},
            {"Bird Keeper", "m"}, {"Channeler", "f"}, {"Ace Trainer", "f"}, {"Veteran", "m"}, {"Hiker", "m"}, {"Black Belt", "m"}};
    static final String[] NAMES_F = {"Bea", "Dana", "Faye", "Hana", "Jade", "Lena", "Nia", "Pia", "Rosa", "Tess", "Uma", "Xia", "Mira", "Ivy", "Elle", "Cora"};
    static final String[] NAMES_M = {"Cole", "Eli", "Gus", "Ivo", "Kai", "Milo", "Oren", "Quin", "Sol", "Vic", "Yuri", "Zed", "Hugo", "Ty", "Ben", "Rex"};

    /** A name that fits the skin: "ft_5" is always a Beauty with a woman's name. */
    static String trainerNameFor(String skin) {
        int i = 0;
        try { i = Integer.parseInt(skin.substring(3)) - 1; } catch (Exception ignored) {}
        if (i < 0 || i >= SKIN_CLASS.length) return trainerName();
        String[] pool = SKIN_CLASS[i][1].equals("f") ? NAMES_F : NAMES_M;
        return SKIN_CLASS[i][0] + " " + pool[RNG.nextInt(pool.length)];
    }

    static final String[] Z = {"normal:normalium_z", "fire:firium_z", "water:waterium_z", "grass:grassium_z", "electric:electrium_z", "ice:icium_z", "fighting:fightinium_z",
            "poison:poisonium_z", "ground:groundium_z", "flying:flyinium_z", "psychic:psychium_z", "bug:buginium_z", "rock:rockium_z", "ghost:ghostium_z", "dragon:dragonium_z",
            "dark:darkinium_z", "steel:steelium_z", "fairy:fairium_z"};

    /** A Z-Crystal this set can actually use: the type of one of its attacking moves (its STAB move first). */
    static String zForSet(Set6 s) {
        String fallback = null;
        for (String mv : s.m) {
            String ty = Cob.attackType(mv);
            if (ty == null || zFor(ty) == null) continue;
            if (s.t.contains(ty)) return zFor(ty);
            if (fallback == null) fallback = zFor(ty);
        }
        return fallback;
    }

    static String zFor(String type) { for (String z : Z) if (z.startsWith(type + ":")) return "mega_showdown:" + z.substring(z.indexOf(':') + 1); return null; }

    // ------------------------------------------------------------------ picking sets

    static List<Set6> sample(List<Set6> pool, int n) {
        List<Set6> out = new ArrayList<>();
        if (pool == null || pool.isEmpty()) return out;
        Set<String> species = new HashSet<>();
        Map<String, Integer> firstType = new HashMap<>();
        for (int tries = 0; tries < 400 && out.size() < n; tries++) {
            Set6 s = pool.get(RNG.nextInt(pool.size()));
            if (!species.add(s.s)) continue;
            String t = s.t.isEmpty() ? "" : s.t.get(0);
            if (firstType.getOrDefault(t, 0) >= 2 && pool.size() > 40) { species.remove(s.s); continue; }   // no more than two of one type, so teams stay balanced
            firstType.merge(t, 1, Integer::sum);
            out.add(s);
        }
        return out;
    }

    static List<Set6> randomTeam(String type, int n, Bracket b) {
        if (b.littleCup) return littleCup(n);
        List<Set6> pool = type != null ? FrontierData.BY_TYPE.get(type) : FrontierData.teams.sets;
        if (pool == null || pool.isEmpty()) pool = FrontierData.teams.sets;
        if (pool.size() >= n) return sample(pool, n);
        // a small type pool: use every one of them, then repeat, so a Ghost Room never fields a Quagsire
        List<Set6> out = new ArrayList<>(sample(pool, pool.size()));
        while (out.size() < n) out.add(pool.get(RNG.nextInt(pool.size())));
        return out;
    }

    static List<Set6> LC;

    /** Unevolved Pokémon that can still evolve, for the Little Cup, built once from Cobblemon's species list. */
    static List<Set6> littleCup(int n) {
        if (LC == null) {
            LC = new ArrayList<>();
            try {
                Object inst = Cob.cls("api.pokemon.PokemonSpecies").getField("INSTANCE").get(null);
                for (Object sp : (Iterable<?>) Cob.call(inst, "getImplemented")) {
                    Object pre = Cob.call(sp, "getPreEvolution");
                    Object evos = Cob.call(sp, "getEvolutions");
                    Object labels = Cob.call(sp, "getLabels");
                    boolean special = labels instanceof Set<?> ls && (ls.contains("legendary") || ls.contains("mythical") || ls.contains("ultra_beast") || ls.contains("paradox") || ls.contains("baby"));
                    if (pre == null && evos instanceof java.util.Collection<?> c && !c.isEmpty() && !special) {
                        Set6 s = new Set6(); s.s = String.valueOf(Cob.call(sp, "getName")).toLowerCase().replace(" ", "").replace(".", "").replace("'", "");
                        LC.add(s);
                    }
                }
            } catch (Throwable t) { Cob.warn("build the Little Cup pool", t); }
        }
        return sample(LC, n);
    }

    /**
     * A Mega Stone for a species. Pokémon with more than one (Charizard X/Y, Mewtwo X/Y, Raichu X/Y, and the Z-A
     * "_z" stones for Garchomp, Lucario, Absol and others) get one of them at random.
     */
    static final String[] FALLBACK_ITEMS = {"cobblemon:leftovers", "cobblemon:sitrus_berry", "cobblemon:lum_berry", "cobblemon:life_orb", "cobblemon:expert_belt",
            "cobblemon:shell_bell", "cobblemon:quick_claw", "cobblemon:wide_lens", "cobblemon:muscle_band", "cobblemon:wise_glasses"};

    static String megaStone(String species) {
        String base = FrontierData.teams.megaStones.get(species);
        if (base == null) return null;
        String root = base.replaceAll("_[xyz]$", "");
        List<String> opts = new ArrayList<>();
        for (String c : new String[]{root, root + "_x", root + "_y", root + "_z"})
            if (Crates.resolve(c) != net.minecraft.world.item.Items.BARRIER) opts.add(c);
        return opts.isEmpty() ? base : opts.get(RNG.nextInt(opts.size()));
    }

    static boolean playerMay(Frontier.Setup s, String gimmick) {
        String v = s.gimmicks.getOrDefault(gimmick, "off");
        return v.equals("both") || v.equals("player");
    }

    /**
     * A rental team. If the run lets the player Mega Evolve or use Z-Moves, at least one rental comes ready to:
     * a Pokémon that can Mega Evolve holding its stone, or one holding a Z-Crystal of its type.
     * (Tera and Dynamax only need the player's own Tera Orb or Dynamax Band, so every rental can use those.)
     */
    static List<Object> rental(Bracket b, int n, Frontier.Setup setup) {
        String mono = setup != null && setup.mods.contains("monotype") ? Frontier.monoType(setup) : null;
        List<Set6> sets = b.littleCup ? littleCup(n) : mono != null ? randomTeam(mono, n, b) : sample(FrontierData.teams.sets, n);
        boolean wantMega = setup != null && playerMay(setup, "mega") && !b.littleCup;
        if (wantMega && sets.stream().noneMatch(x -> FrontierData.teams.megaStones.containsKey(x.s))) {
            List<Set6> megaPool = new ArrayList<>();
            for (Set6 x : FrontierData.teams.sets) if (FrontierData.teams.megaStones.containsKey(x.s)) megaPool.add(x);
            if (!megaPool.isEmpty() && !sets.isEmpty()) sets.set(sets.size() - 1, megaPool.get(RNG.nextInt(megaPool.size())));
        }
        Frontier.Setup plain = new Frontier.Setup();
        List<Object> team = build(sets, b.level, true, plain, b, null);
        if (setup == null) return team;
        boolean noItems = setup.mods.contains("noitems");
        if (noItems) { for (Object pk : team) Cob.setHeldItem(pk, net.minecraft.world.item.ItemStack.EMPTY); return team; }
        boolean megaDone = false, zDone = false;
        for (int i = 0; i < team.size() && i < sets.size(); i++) {
            Set6 x = sets.get(i);
            String stone = megaStone(x.s);
            if (wantMega && !megaDone && stone != null) { Cob.setHeldItem(team.get(i), stackFor(stone)); megaDone = true; continue; }
            if (playerMay(setup, "zmove") && !zDone && zForSet(x) != null) { Cob.setHeldItem(team.get(i), stackFor(zForSet(x))); zDone = true; }
        }
        return team;
    }

    static List<Set6> brainTeam(String id, Bracket b) {
        var br = FrontierData.teams.brains.get(id);
        if (br == null) return randomTeam(null, 6, b);
        if (b.littleCup) return littleCup(6);
        if (br.theme.equals("rental")) return sample(FrontierData.teams.sets, 6);
        if (br.theme.equals("monotype")) {
            String type = weeklyType();
            List<Set6> pool = FrontierData.BY_TYPE.get(type);
            return pool == null ? randomTeam(null, 6, b) : sampleAny(pool, 6);
        }
        return br.team;
    }

    static List<Set6> sampleAny(List<Set6> pool, int n) {
        List<Set6> out = new ArrayList<>(); Set<String> seen = new HashSet<>();
        for (int tries = 0; tries < 300 && out.size() < n; tries++) { Set6 s = pool.get(RNG.nextInt(pool.size())); if (seen.add(s.s)) out.add(s); }
        return out;
    }

    static String weeklyType() {
        String[] t = {"normal", "fire", "water", "grass", "electric", "ice", "fighting", "poison", "ground", "flying", "psychic", "bug", "rock", "ghost", "dragon", "dark", "steel", "fairy"};
        return t[Math.floorMod(Frontier.weekSeed() * 7 + 3, t.length)];
    }

    static String pickBrain(Frontier.Run r) {
        if (r.setup.rental) return "factory";
        if (r.setup.format.equals("doubles")) return "doubles";
        if (r.setup.mods.contains("monotype")) return "mono";
        String[] rot = {"tycoon", "tera", "mega", "status"};
        int i = Math.floorMod(r.floor / 21 + FrontierData.cfg.brackets.indexOf(FrontierData.bracket(r.setup.bracket)) + (int) Frontier.daySeed(), rot.length);
        return rot[i];
    }

    // ------------------------------------------------------------------ building Pokémon

    static boolean enemyMay(Frontier.Setup s, String gimmick) {
        String v = s.gimmicks.getOrDefault(gimmick, "off");
        return v.equals("enemy") || v.equals("both");
    }

    /** Turns sets into real Pokémon at a level. Elite teams get perfect IVs and full EV spreads. */
    static List<Object> build(List<Set6> sets, int level, boolean elite, Frontier.Setup s, Bracket b, FrontierData.Brain brain) {
        List<Object> out = new ArrayList<>();
        for (int i = 0; i < sets.size(); i++) {
            Set6 set = sets.get(i);
            StringBuilder props = new StringBuilder(set.s).append(" level=").append(level);
            if (set.n != null && !set.n.isBlank()) props.append(" nature=").append(set.n);
            if (set.a != null && !set.a.isBlank()) props.append(" ability=").append(set.a);
            if (set.x != null && !set.x.isBlank()) props.append(" ").append(set.x);
            if (set.sh) props.append(" shiny=true");
            Object pk = Cob.create(props.toString());
            if (pk == null) pk = Cob.create(set.s + " level=" + level + (set.sh ? " shiny=true" : ""));       // a bad nature or ability shouldn't lose the whole Pokémon
            if (pk == null) continue;
            Cob.setMoves(pk, set.m);
            boolean ace = i == sets.size() - 1;
            String item = set.i;
            if (s.mods.contains("noitems")) item = "";
            if (!s.mods.contains("noitems")) {
                String stone = megaStone(set.s);
                if (enemyMay(s, "mega") && stone != null && (ace || (brain != null && brain.theme.equals("mega")))) item = stone;
                else if (enemyMay(s, "zmove") && ace && zForSet(set) != null) item = zForSet(set);
            }
            ItemStack held = item == null || item.isBlank() ? ItemStack.EMPTY : stackFor(item);
            if (held.isEmpty() && !s.mods.contains("noitems")) held = stackFor(FALLBACK_ITEMS[RNG.nextInt(FALLBACK_ITEMS.length)]);   // every Pokémon holds something
            Cob.setHeldItem(pk, held);
            if (enemyMay(s, "tera")) {
                String tera = brain != null && i < brain.tera.size() ? brain.tera.get(i) : (!set.t.isEmpty() ? set.t.get(RNG.nextInt(set.t.size())) : "normal");
                Cob.setTera(pk, tera);
            }
            if (enemyMay(s, "dynamax") && brain != null && set.s.equals(brain.gmax)) Cob.setGmax(pk, true);
            if (elite) { Cob.setIVs(pk, 31); spreadEVs(pk, set.n); } else Cob.setIVs(pk, 20);
            Cob.heal(pk);
            out.add(pk);
        }
        return out;
    }

    static ItemStack stackFor(String id) {
        var it = Crates.resolve(id);
        return it == Items.BARRIER ? ItemStack.EMPTY : new ItemStack(it);
    }

    static void spreadEVs(Object pk, String nature) {
        String n = nature == null ? "" : nature.toLowerCase();
        switch (n) {
            case "adamant", "jolly", "brave", "naughty", "lonely", "hasty" -> { Cob.setEV(pk, "ATTACK", 252); Cob.setEV(pk, "SPEED", 252); Cob.setEV(pk, "HP", 4); }
            case "modest", "timid", "quiet", "rash", "mild", "naive" -> { Cob.setEV(pk, "SPECIAL_ATTACK", 252); Cob.setEV(pk, "SPEED", 252); Cob.setEV(pk, "HP", 4); }
            case "bold", "impish", "relaxed", "lax" -> { Cob.setEV(pk, "HP", 252); Cob.setEV(pk, "DEFENCE", 252); Cob.setEV(pk, "SPECIAL_DEFENCE", 4); }
            case "calm", "careful", "sassy", "gentle" -> { Cob.setEV(pk, "HP", 252); Cob.setEV(pk, "SPECIAL_DEFENCE", 252); Cob.setEV(pk, "DEFENCE", 4); }
            default -> { Cob.setEV(pk, "HP", 252); Cob.setEV(pk, "ATTACK", 128); Cob.setEV(pk, "SPECIAL_ATTACK", 128); }
        }
    }

    // ------------------------------------------------------------------ doors

    static List<Frontier.Door> doors(Frontier.Run r) {
        int count = RNG.nextDouble() < 0.4 ? 3 : 2;
        List<Frontier.Door> out = new ArrayList<>();
        boolean scouting = r.setup.mods.contains("scouting") || r.boons.contains("insight");
        Set<String> used = new HashSet<>();
        var len = FrontierData.length(r.setup.length);
        boolean finalRoom = len.rooms > 0 && r.floor + 1 >= len.rooms;     // a run always ends on a battle
        for (int i = 0; i < count; i++) {
            String room = rollRoom(r, used);
            if (finalRoom && !Frontier.isBattle(room)) room = i == 0 ? "elite" : RNG.nextBoolean() ? "battle" : "type";
            used.add(room);
            Frontier.Door d = new Frontier.Door();
            d.room = room;
            if (room.equals("type")) d.type = Frontier.randomType();
            if (room.equals("champion")) d.champion = Frontier.randomChampion(r);
            d.hidden = room.equals("mystery");
            if (scouting && Frontier.isBattle(room) && !d.hidden) {
                Bracket b = FrontierData.bracket(r.setup.bracket);
                d.preview = room.equals("champion") && d.champion != null ? FrontierData.teams.champions.get(d.champion).team : randomTeam(room.equals("type") ? d.type : null, Frontier.enemyTeamSize(r), b);
            }
            out.add(d);
        }
        return out;
    }

    static String rollRoom(Frontier.Run r, Set<String> avoid) {
        double tot = 0;
        List<Object[]> opts = new ArrayList<>();
        int floor = r.floor + 1;
        for (var room : FrontierData.cfg.rooms) {
            if (floor < room.minFloor || avoid.contains(room.id) && !room.id.equals("battle") && !room.id.equals("type")) continue;
            double w = room.w;
            switch (room.id) {
                case "champion" -> { w *= (1 + floor / 20.0); if (r.setup.mods.contains("gauntlet")) w *= 3; }
                case "elite", "doubles" -> w *= (1 + floor / 25.0);
                case "heal", "luckyroll", "clover", "shrine", "gamble" -> w *= Math.max(0.3, 1 - floor / 60.0);
                default -> {}
            }
            if (w <= 0) continue;
            opts.add(new Object[]{room.id, w}); tot += w;
        }
        if (opts.isEmpty()) return "battle";
        double x = RNG.nextDouble() * tot;
        for (Object[] o : opts) { x -= (double) o[1]; if (x <= 0) return (String) o[0]; }
        return (String) opts.get(opts.size() - 1)[0];
    }
}
