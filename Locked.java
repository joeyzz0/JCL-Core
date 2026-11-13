package net.joeyscobblemon.gts;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.loot.IGlobalLootModifier;
import net.neoforged.neoforge.common.loot.LootModifier;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/**
 * Crate-exclusive items: every epic and legendary crate prize, plus every Legendary Monuments item.
 * They're removed from all world loot (chests, structures, mobs, blocks, ores, fishing, NPC rewards,
 * loot balls), from Pokémon drops, and from wild Pokémon's held items when caught. Recipes are removed
 * by the KubeJS script. The list lives in config/jcl_locked_items.json.
 */
@EventBusSubscriber(modid = "jcl_gts")
public final class Locked {
    private Locked() {}

    static final Set<String> IDS = new HashSet<>();

    // ------------------------------------------------------------------ the global loot modifier

    public static final DeferredRegister<MapCodec<? extends IGlobalLootModifier>> GLM =
            DeferredRegister.create(NeoForgeRegistries.Keys.GLOBAL_LOOT_MODIFIER_SERIALIZERS, "jcl_gts");

    public static final class StripLocked extends LootModifier {
        public static final MapCodec<StripLocked> CODEC = RecordCodecBuilder.mapCodec(inst -> codecStart(inst).apply(inst, StripLocked::new));
        public StripLocked(LootItemCondition[] conditions) { super(conditions); }
        @Override
        protected ObjectArrayList<ItemStack> doApply(ObjectArrayList<ItemStack> loot, LootContext ctx) {
            if (IDS.isEmpty()) return loot;
            // A block always keeps dropping itself (so a placed Poké Doll can be picked back up).
            net.minecraft.world.level.block.state.BlockState st = ctx.getParamOrNull(net.minecraft.world.level.storage.loot.parameters.LootContextParams.BLOCK_STATE);
            net.minecraft.world.item.Item self = st == null ? null : st.getBlock().asItem();
            loot.removeIf(s -> isLocked(s) && (self == null || !s.is(self)));
            return loot;
        }
        @Override public MapCodec<? extends IGlobalLootModifier> codec() { return CODEC; }
    }

    static { GLM.register("strip_locked", () -> StripLocked.CODEC); }

    static void register(IEventBus modBus) { GLM.register(modBus); }

    static boolean isLocked(ItemStack s) { return !s.isEmpty() && IDS.contains(BuiltInRegistries.ITEM.getKey(s.getItem()).toString()); }

    // ------------------------------------------------------------------ config + Cobblemon hooks

    @SubscribeEvent
    public static void onStart(ServerStartedEvent e) {
        IDS.clear();
        try {
            String json = Defaults.load("jcl_locked_items.json");
            if (json != null) {
                JsonObject cfg = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
                if (cfg.has("items")) for (JsonElement el : cfg.getAsJsonArray("items")) IDS.add(el.getAsString());
                BLOCK_WILD_LEGENDS = !cfg.has("blockWildLegendaries") || cfg.get("blockWildLegendaries").getAsBoolean();
            }
        } catch (Exception ex) { GtsMod.LOG.error("[JCL] Could not read config/jcl_locked_items.json", ex); }
        GtsMod.LOG.info("[JCL] {} crate-exclusive items locked out of world loot.", IDS.size());
        stripRecipes(e.getServer());
        try {
            Class<?> ev = Class.forName("com.cobblemon.mod.common.api.events.CobblemonEvents");
            Leaderboards.subscribe(ev.getField("LOOT_DROPPED").get(null), (Consumer<Object>) Locked::onPokemonDrops);
            Leaderboards.subscribe(ev.getField("POKEMON_CAPTURED").get(null), (Consumer<Object>) Locked::onCaptured);
            Leaderboards.subscribe(ev.getField("ENTITY_SPAWN").get(null), (Consumer<Object>) Locked::onNaturalSpawn);
        } catch (Throwable t) { GtsMod.LOG.error("[JCL] Could not hook Cobblemon drops for locked items", t); }
    }

    // ------------------------------------------------------------------ recipes

    /** Removes every recipe that makes a locked item. Runs on start and after every /reload, before recipes reach players. */
    static void stripRecipes(net.minecraft.server.MinecraftServer server) {
        if (server == null || IDS.isEmpty()) return;
        try {
            var rm = server.getRecipeManager();
            List<net.minecraft.world.item.crafting.RecipeHolder<?>> keep = new java.util.ArrayList<>();
            int removed = 0;
            for (var h : rm.getRecipes()) {
                ItemStack out;
                try { out = h.value().getResultItem(server.registryAccess()); } catch (Throwable t) { keep.add(h); continue; }
                if (out != null && isLocked(out) && !(h.id().getNamespace().equals("atm_recipes") && madeFromLocked(h.value()))) removed++; else keep.add(h);
            }
            if (removed > 0) rm.replaceRecipes(keep);
            GtsMod.LOG.info("[JCL] Removed {} recipes for crate-exclusive items.", removed);
        } catch (Throwable t) { GtsMod.LOG.error("[JCL] Could not remove crate-exclusive recipes", t); }
    }

    /** True when a recipe uses a crate-only item as an ingredient. Only AllTheMons' form conversions (Adamant Orb to Adamant Crystal and back) are kept. */
    static boolean madeFromLocked(net.minecraft.world.item.crafting.Recipe<?> r) {
        try {
            for (var ing : r.getIngredients()) for (ItemStack st : ing.getItems()) if (isLocked(st)) return true;
        } catch (Throwable ignored) {}
        return false;
    }

    @SubscribeEvent
    public static void onDatapackSync(net.neoforged.neoforge.event.OnDatapackSyncEvent e) {
        if (e.getPlayer() == null) stripRecipes(e.getPlayerList().getServer());     // after /reload
    }

    // ------------------------------------------------------------------ mob drops added by mods' code (not loot tables)

    @SubscribeEvent
    public static void onMobDrops(net.neoforged.neoforge.event.entity.living.LivingDropsEvent e) {
        if (IDS.isEmpty() || e.getEntity() instanceof net.minecraft.world.entity.player.Player) return;   // never touch a player's own death drops
        e.getDrops().removeIf(ie -> isLocked(ie.getItem()));
    }

    /** Where bosses (the Wither, the Ender Dragon) died in the last few seconds: mods drop their extra loot there by code. */
    static final List<Object[]> BOSS_DEATHS = new java.util.ArrayList<>();

    @SubscribeEvent
    public static void onBossDeath(net.neoforged.neoforge.event.entity.living.LivingDeathEvent e) {
        var en = e.getEntity();
        if (en.level().isClientSide()) return;
        if (en instanceof net.minecraft.world.entity.boss.wither.WitherBoss || en instanceof net.minecraft.world.entity.boss.enderdragon.EnderDragon)
            BOSS_DEATHS.add(new Object[]{en.level(), en.position(), System.currentTimeMillis()});
    }

    /** Crate-only items that a mod drops straight into the world near a boss death (not through loot or drops) are removed. */
    @SubscribeEvent
    public static void onItemSpawn(net.neoforged.neoforge.event.entity.EntityJoinLevelEvent e) {
        if (IDS.isEmpty() || e.getLevel().isClientSide() || BOSS_DEATHS.isEmpty() || !(e.getEntity() instanceof net.minecraft.world.entity.item.ItemEntity ie)) return;
        if (!isLocked(ie.getItem()) || ie.getOwner() != null) return;
        long now = System.currentTimeMillis();
        BOSS_DEATHS.removeIf(d -> now - (long) d[2] > 15000);
        for (Object[] d : BOSS_DEATHS)
            if (d[0] == e.getLevel() && ((net.minecraft.world.phys.Vec3) d[1]).distanceToSqr(ie.position()) < 24 * 24) { e.setCanceled(true); return; }
    }

    // ------------------------------------------------------------------ no wild Ultra Beasts or Paradox Pokémon

    /** No wild legendary or mythical spawns (crates, Legendary Monuments and raids are where those come from). */
    static boolean BLOCK_WILD_LEGENDS = true;

    static void onNaturalSpawn(Object event) {
        try {
            Object ent = Leaderboards.call(event, "getEntity");
            if (!(ent instanceof net.minecraft.world.entity.Entity en)) return;
            if (en.level().dimension().location().toString().equals("cobblemon_ultrabeast:ultra_space")) return;   // Ultra Space keeps its Ultra Beasts
            if (Cob.isUltraOrParadox(en)) { Leaderboards.call(event, "cancel"); return; }
            if (BLOCK_WILD_LEGENDS && Cob.isLegendOrMythical(en)) Leaderboards.call(event, "cancel");
        } catch (Throwable ignored) {}
    }

    static void onPokemonDrops(Object event) {
        try {
            @SuppressWarnings("unchecked") List<Object> drops = (List<Object>) Leaderboards.call(event, "getDrops");
            drops.removeIf(d -> {
                try { Object id = Leaderboards.call(d, "getItem"); return id instanceof ResourceLocation rl && IDS.contains(rl.toString()); }
                catch (Throwable t) { return false; }
            });
        } catch (Throwable ignored) {}
    }

    static void onCaptured(Object event) {
        try {
            Object pokemon = Leaderboards.call(event, "getPokemon");
            Object held = Leaderboards.call(pokemon, "heldItem");
            if (held instanceof ItemStack s && isLocked(s)) Leaderboards.call(pokemon, "removeHeldItem");
        } catch (Throwable ignored) {}
    }
}
