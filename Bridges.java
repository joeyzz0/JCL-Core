package net.joeyscobblemon.gts;

import java.lang.reflect.Method;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;

/**
 * Small compatibility fixes between mods.
 *
 * Fight or Flight auto-battles: when your Pokémon knocks out a wild Pokémon outside of a
 * battle, Cobblemon Quests doesn't count it. This counts it as a "defeat" for the owner, so
 * chores like "Defeat 25 wild Pokémon" progress during auto-battles too.
 */
@EventBusSubscriber(modid = "jcl_gts")
public final class Bridges {
    private Bridges() {}

    private static final String POKEMON_ENTITY = "com.cobblemon.mod.common.entity.pokemon.PokemonEntity";

    @SubscribeEvent
    public static void onDeath(LivingDeathEvent e) {
        if (e.getEntity().level().isClientSide()) return;
        Entity victim = e.getEntity(), killer = e.getSource().getEntity();
        if (killer == null || !victim.getClass().getName().equals(POKEMON_ENTITY) || !killer.getClass().getName().equals(POKEMON_ENTITY)) return;
        try {
            Object victimMon = call(victim, "getPokemon");
            if (!Boolean.TRUE.equals(call(victimMon, "isWild"))) return;
            Object owner = call(killer, "getOwner");
            if (!(owner instanceof ServerPlayer player)) return;
            Class<?> cq = Class.forName("winterwolfsv.cobblemon_quests.CobblemonQuests");
            Object handler = cq.getField("eventHandler").get(null);
            if (handler == null) return;
            for (Method m : handler.getClass().getMethods()) {
                if (!m.getName().equals("processTasksForTeam") || m.getParameterCount() != 5) continue;
                Class<?>[] t = m.getParameterTypes();
                if (t[1] != String.class || t[0] == String.class) continue;
                m.invoke(handler, victimMon, "defeat", 1L, player, 0);
                return;
            }
        } catch (Throwable ignored) {
            // Cobblemon Quests not installed or changed: nothing to do.
        }
    }

    private static Object call(Object target, String name) throws Exception {
        for (Method m : target.getClass().getMethods()) if (m.getName().equals(name) && m.getParameterCount() == 0) return m.invoke(target);
        throw new NoSuchMethodException(name);
    }
}
