package net.joeyscobblemon.gts;

import java.lang.reflect.Method;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.google.gson.JsonObject;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

/**
 * Spawn alerts for shiny, legendary, mythical, Ultra Beast, paradox and hand-picked rare Pokémon.
 * Nearby players get a personal alert with distance, direction and biome; big spawns can also be
 * announced server-wide and on Discord. Config: config/jcl_spawnalerts.json.
 */
@EventBusSubscriber(modid = "jcl_gts")
public final class SpawnAlerts {
    private SpawnAlerts() {}

    public static final class Config {
        public boolean enabled = true;
        public int nearbyRadius = 128;
        public boolean showCoordinates = true;
        public boolean playSound = true;
        /** Which kinds are announced to the whole server (nearby players always get an alert). */
        public List<String> broadcast = List.of("legendary", "mythical", "ultra_beast", "shiny");
        /** Which kinds are posted to Discord, using the GTS webhook. */
        public List<String> discord = List.of("legendary", "mythical", "ultra_beast");
        /** Extra species that count as rare, by Cobblemon name. */
        public List<String> rareSpecies = List.of("dratini", "larvitar", "bagon", "beldum", "gible", "deino", "goomy", "jangmoo", "dreepy", "frigibax", "riolu", "axew", "eevee", "ditto");
        public boolean broadcastRare = false;
    }

    private static Config cfg = new Config();
    private static MinecraftServer server;
    private static final Set<String> SPECIAL = Set.of("legendary", "mythical", "ultra_beast", "paradox");

    @SubscribeEvent
    public static void onStart(ServerStartedEvent e) {
        server = e.getServer();
        cfg = Tablist.loadConfig("jcl_spawnalerts.json", Config.class, new Config());
    }

    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent e) {
        if (!cfg.enabled || e.loadedFromDisk() || !(e.getLevel() instanceof ServerLevel level)) return;
        Entity ent = e.getEntity();
        if (!ent.getClass().getName().equals("com.cobblemon.mod.common.entity.pokemon.PokemonEntity")) return;
        try {
            Object pk = call(ent, "getPokemon");
            if (!Boolean.TRUE.equals(call(pk, "isWild"))) return;
            Object species = call(pk, "getSpecies");
            String id = String.valueOf(call(species, "getName")).toLowerCase();
            boolean shiny = Boolean.TRUE.equals(call(pk, "getShiny"));
            String kind = null;
            Object labels = call(species, "getLabels");
            if (labels instanceof Iterable<?> it) for (Object l : it) if (SPECIAL.contains(String.valueOf(l))) kind = String.valueOf(l);
            boolean rare = cfg.rareSpecies.contains(id);
            if (kind == null && !shiny && !rare) return;
            int lvl = ((Number) call(pk, "getLevel")).intValue();
            String name = displayName(species, id);
            // Defer one tick so the Pokémon is fully placed before anyone is told about it.
            String fKind = kind;
            server.execute(() -> alert(level, ent.blockPosition(), name, lvl, shiny, fKind, rare));
        } catch (Throwable t) {
            GtsMod.LOG.debug("[JCL] Spawn alert skipped", t);
        }
    }

    private static void alert(ServerLevel level, BlockPos pos, String name, int lvl, boolean shiny, String kind, boolean rare) {
        String label = labelColoured(shiny, kind, rare);
        String biome = "";
        // Personal alerts for nearby players, with distance and direction.
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p.serverLevel() != level) continue;
            double dist = Math.sqrt(p.blockPosition().distSqr(pos));
            if (dist > cfg.nearbyRadius) continue;
            if (biome.isEmpty()) biome = biomeName(level, pos);
            String where = (int) dist + " blocks " + direction(p.blockPosition(), pos) + (biome.isEmpty() ? "" : " §8(" + biome + "§8)");
            MutableComponent msg = Component.literal("§d§l✦ §r" + label + " §f" + name + " §7(Lv. " + lvl + ") spawned near you! §f" + where);
            if (cfg.showCoordinates) msg.append(Component.literal(" §8[" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + "]"));
            p.sendSystemMessage(msg);
            if (cfg.playSound) p.playNotifySound(kind != null ? SoundEvents.ENDER_DRAGON_GROWL : SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.MASTER, kind != null ? 0.4f : 1f, 1.2f);
        }
        String k = kind != null ? kind : shiny ? "shiny" : "rare";
        boolean wide = cfg.broadcast.contains(k) || (shiny && cfg.broadcast.contains("shiny")) || (rare && cfg.broadcastRare);
        if (wide) {
            MutableComponent all = Component.literal("§d§l✦ §r§7A wild " + label + " §f" + name + " §7(Lv. " + lvl + ") has appeared somewhere in the world!");
            all.withStyle(st -> st.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("§7Keep your eyes open, trainer."))));
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                if (p.serverLevel() != level || Math.sqrt(p.blockPosition().distSqr(pos)) > cfg.nearbyRadius) p.sendSystemMessage(all);
            }
        }
        if (cfg.discord.contains(k)) discord("✨ A wild **" + label.replaceAll("§.", "") + " " + name + "** (Lv. " + lvl + ") just appeared on the server!");
    }

    /** Each kind gets its own colour; the Pokémon's name itself stays white. */
    private static String labelColoured(boolean shiny, String kind, boolean rare) {
        StringBuilder b = new StringBuilder();
        if (shiny) b.append("§e§l✦ Shiny§r ");
        if (kind != null) b.append(switch (kind) {
            case "legendary" -> "§6§lLegendary"; case "mythical" -> "§d§lMythical"; case "ultra_beast" -> "§b§lUltra Beast"; case "paradox" -> "§5§lParadox"; default -> "";
        }).append("§r ");
        if (kind == null && rare && !shiny) b.append("§9§lRare§r ");
        return b.toString().trim();
    }

    private static String label(boolean shiny, String kind, boolean rare) {
        String s = shiny ? "Shiny" : "";
        String k = kind == null ? (rare && !shiny ? "Rare" : "") : switch (kind) {
            case "legendary" -> "Legendary"; case "mythical" -> "Mythical"; case "ultra_beast" -> "Ultra Beast"; case "paradox" -> "Paradox"; default -> "";
        };
        return (s + " " + k).trim();
    }

    private static String displayName(Object species, String fallback) {
        try { Object c = call(species, "getTranslatedName"); if (c instanceof Component comp) return comp.getString(); } catch (Throwable ignored) {}
        return fallback.substring(0, 1).toUpperCase() + fallback.substring(1);
    }

    private static String biomeName(ServerLevel level, BlockPos pos) {
        try {
            return level.getBiome(pos).unwrapKey().map(k -> {
                String p = k.location().getPath();
                StringBuilder b = new StringBuilder();
                for (String w : p.replace('/', ' ').split("_")) if (!w.isEmpty()) b.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1)).append(' ');
                return b.toString().trim();
            }).orElse("");
        } catch (Throwable t) { return ""; }
    }

    private static String direction(BlockPos from, BlockPos to) {
        double dx = to.getX() - from.getX(), dz = to.getZ() - from.getZ();
        double angle = (Math.toDegrees(Math.atan2(dx, -dz)) + 360) % 360;
        String[] d = {"north", "north-east", "east", "south-east", "south", "south-west", "west", "north-west"};
        return d[(int) Math.round(angle / 45) % 8];
    }

    private static void discord(String text) {
        String url = GtsConfig.INSTANCE.webhookUrl;
        if (url == null || url.isBlank()) return;
        try {
            JsonObject body = new JsonObject();
            body.addProperty("username", GtsConfig.INSTANCE.webhookName);
            body.addProperty("content", text);
            HttpClient.newHttpClient().sendAsync(HttpRequest.newBuilder(URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(), HttpResponse.BodyHandlers.discarding());
        } catch (Throwable t) { GtsMod.LOG.warn("[JCL] Spawn alert Discord post failed", t); }
    }

    private static Object call(Object target, String name, Object... args) throws Exception {
        for (Method m : target.getClass().getMethods()) {
            if (!m.getName().equals(name) || m.getParameterCount() != args.length) continue;
            return m.invoke(target, args);
        }
        throw new NoSuchMethodException(name);
    }
}
