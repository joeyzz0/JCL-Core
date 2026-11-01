package net.joeyscobblemon.gts;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Everything that touches Cobblemon lives here, reached through reflection. If Cobblemon changes a method name,
 * the mod keeps running and logs which step failed.
 */
public final class CobblemonBridge {
    private CobblemonBridge() {}

    public static final class Snap {
        public String title = "Pokémon";
        public List<String> lines = new ArrayList<>();
        public ItemStack icon = new ItemStack(Items.PAPER);
    }

    private static Object storage;

    private static Object storage() throws Exception {
        if (storage == null) {
            Class<?> c = Class.forName("com.cobblemon.mod.common.Cobblemon");
            Object inst = c.getField("INSTANCE").get(null);
            storage = Refl.call(inst, "getStorage");
        }
        return storage;
    }

    public static boolean available() {
        try { storage(); return true; } catch (Throwable t) { return false; }
    }

    public static Object party(ServerPlayer p) throws Exception { return Refl.call(storage(), "getParty", p); }
    public static Object pc(ServerPlayer p) throws Exception { return Refl.call(storage(), "getPC", p); }

    public static Object partyGet(Object party, int slot) {
        try { return Refl.call(party, "get", slot); } catch (Throwable t) { return null; }
    }

    public static int partyCount(Object party) {
        int n = 0;
        for (int i = 0; i < 6; i++) if (partyGet(party, i) != null) n++;
        return n;
    }

    public static boolean removeFromParty(Object party, Object pokemon) {
        try {
            Object r = Refl.call(party, "remove", pokemon);
            return !(r instanceof Boolean b) || b;
        } catch (Throwable t) {
            GtsMod.LOG.error("[GTS] Could not remove a Pokémon from the party", t);
            return false;
        }
    }

    public static CompoundTag toNbt(Object pokemon, RegistryAccess ra) throws Exception {
        Object r = Refl.call(pokemon, "saveToNBT", ra, new CompoundTag());
        return (CompoundTag) r;
    }

    public static Object fromNbt(RegistryAccess ra, CompoundTag tag) throws Exception {
        Class<?> pk = Class.forName("com.cobblemon.mod.common.pokemon.Pokemon");
        Object p = pk.getDeclaredConstructor().newInstance();
        Object r = Refl.call(p, "loadFromNBT", ra, tag);
        return r != null ? r : p;
    }

    /** Puts the Pokémon in the party, or the PC if the party is full. */
    public static boolean give(ServerPlayer p, Object pokemon) {
        try {
            Object r = Refl.call(party(p), "add", pokemon);
            if (r instanceof Boolean b && b) return true;
            r = Refl.call(pc(p), "add", pokemon);
            return r instanceof Boolean b2 && b2;
        } catch (Throwable t) {
            GtsMod.LOG.error("[GTS] Could not give a Pokémon to {}", p.getGameProfile().getName(), t);
            return false;
        }
    }

    // ---------------------------------------------------------------- snapshot

    private static String str(Object o) { return o == null ? "" : String.valueOf(o); }

    public static Snap snapshot(Object pk, RegistryAccess ra) {
        Snap s = new Snap();
        String species = "Pokémon";
        boolean shiny = false;
        int level = 0;
        String gender = "";
        try {
            Object sp = Refl.call(pk, "getSpecies");
            species = Fmt.pretty(str(Refl.call(sp, "getName")));
        } catch (Throwable t) { warn("species", t); }
        try { level = (Integer) Refl.call(pk, "getLevel"); } catch (Throwable t) { warn("level", t); }
        try { shiny = (Boolean) Refl.call(pk, "getShiny"); } catch (Throwable t) { warn("shiny", t); }
        try {
            Object g = Refl.call(pk, "getGender");
            gender = Fmt.pretty(str(g));
        } catch (Throwable ignored) {}

        s.title = (shiny ? "§e★ §6" : "§f") + species + " §7Lv." + level;
        s.lines.add("§7Species: §f" + species + (shiny ? " §e(Shiny)" : ""));
        s.lines.add("§7Level: §f" + level + (gender.isEmpty() ? "" : "   §7Gender: §f" + gender));

        try {
            Object n = Refl.call(pk, "getNature");
            Object name = Refl.call(n, "getName");
            String nature = name instanceof ResourceLocation rl ? rl.getPath() : str(name);
            s.lines.add("§7Nature: §d" + Fmt.pretty(nature));
        } catch (Throwable t) { warn("nature", t); }
        try {
            Object ab = Refl.call(pk, "getAbility");
            s.lines.add("§7Ability: §b" + Fmt.pretty(str(Refl.call(ab, "getName"))));
        } catch (Throwable t) { warn("ability", t); }

        statLine(s, pk, "getIvs", "IVs");
        statLine(s, pk, "getEvs", "EVs");

        try {
            Object ms = Refl.call(pk, "getMoveSet");
            Object moves = Refl.call(ms, "getMoves");
            List<String> names = new ArrayList<>();
            for (Object m : (Iterable<?>) moves) names.add(Fmt.pretty(str(Refl.call(m, "getName"))));
            if (!names.isEmpty()) s.lines.add("§7Moves: §f" + String.join("§7, §f", names));
        } catch (Throwable t) { warn("moves", t); }
        try {
            Object held = Refl.call(pk, "heldItem");
            if (held instanceof ItemStack is && !is.isEmpty()) s.lines.add("§7Held item: §a" + is.getHoverName().getString());
        } catch (Throwable ignored) {}

        ItemStack ball = null;
        try {
            Object b = Refl.call(pk, "getCaughtBall");
            Object bn = Refl.call(b, "getName");
            if (bn instanceof ResourceLocation rl) {
                s.lines.add("§7Caught in: §f" + Fmt.pretty(rl.getPath()));
                ball = new ItemStack(BuiltInRegistries.ITEM.get(rl));
            }
        } catch (Throwable ignored) {}

        s.icon = modelIcon(pk);
        if (s.icon == null || s.icon.isEmpty()) s.icon = (ball != null && !ball.isEmpty()) ? ball : new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse("cobblemon:poke_ball")));
        return s;
    }

    private static void statLine(Snap s, Object pk, String getter, String label) {
        try {
            Object stats = Refl.call(pk, getter);
            Class<?> st = Class.forName("com.cobblemon.mod.common.api.pokemon.stats.Stats");
            String[][] order = {{"HP"}, {"ATTACK"}, {"DEFENCE", "DEFENSE"}, {"SPECIAL_ATTACK"}, {"SPECIAL_DEFENCE", "SPECIAL_DEFENSE"}, {"SPEED"}};
            String[] short_ = {"HP", "Atk", "Def", "SpA", "SpD", "Spe"};
            StringBuilder sb = new StringBuilder();
            int total = 0;
            for (int i = 0; i < order.length; i++) {
                Object stat = null;
                for (String nm : order[i]) {
                    for (Object c : st.getEnumConstants()) if (((Enum<?>) c).name().equals(nm)) stat = c;
                    if (stat != null) break;
                }
                int v = 0;
                if (stat != null) {
                    Object r = Refl.call(stats, "get", stat);
                    if (r instanceof Number num) v = num.intValue();
                }
                total += v;
                if (i > 0) sb.append("§7 / ");
                boolean perfect = label.equals("IVs") && v >= 31;
                sb.append(perfect ? "§a" : "§f").append(v);
            }
            s.lines.add("§7" + label + ": " + sb + " §8(HP/Atk/Def/SpA/SpD/Spe)");
            if (label.equals("IVs")) s.lines.add("§7IV total: §f" + total + "§7/186");
            else s.lines.add("§7EV total: §f" + total + "§7/510");
        } catch (Throwable t) { warn(label, t); }
    }

    /** Tries Cobblemon's own Pokémon model item. Returns null if it is not available. */
    private static ItemStack modelIcon(Object pk) {
        try {
            Class<?> pi = Class.forName("com.cobblemon.mod.common.item.PokemonItem");
            Object comp = pi.getField("Companion").get(null);
            for (Method m : comp.getClass().getMethods()) {
                if (!m.getName().equals("from")) continue;
                Class<?>[] pt = m.getParameterTypes();
                if (pt.length < 1 || pt.length > 3 || !pt[0].isInstance(pk)) continue;
                Object[] args = new Object[pt.length];
                args[0] = pk;
                if (pt.length > 1) args[1] = 1;
                try {
                    Object r = m.invoke(comp, args);
                    if (r instanceof ItemStack is && !is.isEmpty()) return is;
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static boolean warned;
    private static void warn(String part, Throwable t) {
        if (!warned) {
            warned = true;
            GtsMod.LOG.warn("[GTS] Could not read '{}' from a Pokémon (Cobblemon API may have changed). Further errors of this kind are hidden.", part, t);
        }
    }
}
