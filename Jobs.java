package net.joeyscobblemon.gts;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.StringArgumentType;

import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

/**
 * Jobs: short quest chains run by NPCs around town (and later, events).
 *
 * NPCs are villagers that stand still and can't be hurt or traded with. Spawn one where you're
 * standing with /jobs npc spawn <id>. Talking to the first NPC of a job starts it; chat tells
 * the player the job name, their progress and exactly where to go next.
 *
 * Players: /jobs (active jobs and current task), /jobs all (every job)
 * Admins:  /jobs npc spawn <id>, /jobs npc remove, /jobs reset <player> <job>, /jobs reload
 * Config:  config/jcl_jobs.json
 */
@EventBusSubscriber(modid = "jcl_gts")
public final class Jobs {
    private Jobs() {}

    public static final class Npc { public String id, name, profession = "minecraft:none", type = "minecraft:plains"; public List<String> idle = new ArrayList<>(); }
    public static final class Step { public String npc, where; public List<String> lines = new ArrayList<>(); }
    public static final class PokemonReward { public String species; public int level = 25; public boolean shiny, maxRideStats; }
    public static final class Reward { public long money; public List<String> commands = new ArrayList<>(); public PokemonReward pokemon; public String message; }
    public static final class Job { public String id, name, description; public boolean repeatable; public List<Step> steps = new ArrayList<>(); public Reward reward = new Reward(); }
    public static final class Config { public int version; public List<Npc> npcs = new ArrayList<>(); public List<Job> jobs = new ArrayList<>(); }

    /** player -> job -> next step index (steps.size() = finished) */
    public static final class Data { public Map<String, Map<String, Integer>> progress = new HashMap<>(); }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    static Config cfg = new Config();
    static Data data = new Data();
    static MinecraftServer server;
    private static final Map<String, Long> COOLDOWN = new HashMap<>();

    private static Path dataFile() { return server.getWorldPath(LevelResource.ROOT).resolve("data").resolve("jcl_gts").resolve("jobs.json"); }

    static void load() {
        try {
            String json = Defaults.load("jcl_jobs.json");
            Config c = json == null ? null : GSON.fromJson(json, Config.class);
            if (c != null) cfg = c;
        } catch (Exception e) { GtsMod.LOG.error("[JCL] Could not read config/jcl_jobs.json", e); }
        try {
            if (Files.exists(dataFile())) { Data d = GSON.fromJson(Files.readString(dataFile()), Data.class); if (d != null) data = d; }
        } catch (Exception e) { GtsMod.LOG.error("[JCL] Could not read job progress", e); }
        if (data.progress == null) data.progress = new HashMap<>();
    }

    static void save() {
        try { Files.createDirectories(dataFile().getParent()); Files.writeString(dataFile(), GSON.toJson(data)); }
        catch (Exception e) { GtsMod.LOG.error("[JCL] Could not save job progress", e); }
    }

    @SubscribeEvent public static void onStart(ServerStartedEvent e) { server = e.getServer(); load(); }
    @SubscribeEvent public static void onStop(ServerStoppingEvent e) { save(); }

    static Npc npc(String id) { for (Npc n : cfg.npcs) if (n.id.equals(id)) return n; return null; }
    static Job job(String id) { for (Job j : cfg.jobs) if (j.id.equals(id)) return j; return null; }
    static String c(String s) { return s == null ? "" : s.replace('&', '§'); }

    static Map<String, Integer> progress(ServerPlayer p) { return data.progress.computeIfAbsent(p.getUUID().toString(), k -> new HashMap<>()); }

    // ------------------------------------------------------------------ talking to NPCs

    @SubscribeEvent
    public static void onInteract(PlayerInteractEvent.EntityInteract e) {
        if (e.getLevel().isClientSide() || !(e.getEntity() instanceof ServerPlayer p)) return;
        String id = null;
        for (String tag : e.getTarget().getTags()) if (tag.startsWith("jcl_npc_")) id = tag.substring(8);
        if (id == null) return;
        e.setCanceled(true);
        if (e.getHand() != InteractionHand.MAIN_HAND) return;
        String key = p.getUUID() + "|" + id;
        long now = System.currentTimeMillis();
        if (COOLDOWN.getOrDefault(key, 0L) > now) return;
        COOLDOWN.put(key, now + 1200);
        talk(p, id);
    }

    static void say(ServerPlayer p, Npc n, String line) {
        p.sendSystemMessage(Component.literal("§e§l" + c(n.name) + "§r§7: §f" + c(line).replace("{player}", p.getGameProfile().getName())));
    }

    static void talk(ServerPlayer p, String npcId) {
        Npc n = npc(npcId);
        if (n == null) return;
        Map<String, Integer> prog = progress(p);
        for (Job j : cfg.jobs) {
            if (j.steps.isEmpty()) continue;
            int at = prog.getOrDefault(j.id, -1);
            boolean done = at >= j.steps.size();
            if (done && !j.repeatable) continue;
            if (done) at = -1;
            int next = Math.max(at, 0);
            if (at == -1 && !j.steps.get(0).npc.equals(npcId)) continue;     // jobs start at their first NPC
            Step s = j.steps.get(next);
            if (!s.npc.equals(npcId)) continue;
            p.playNotifySound(SoundEvents.VILLAGER_AMBIENT, SoundSource.MASTER, 0.6f, 1.1f);
            for (String line : s.lines) say(p, n, line);
            if (at == -1) p.sendSystemMessage(Component.literal("§6§l✦ New job: §e" + c(j.name) + "§7 · " + c(j.description)));
            prog.put(j.id, next + 1);
            save();
            if (next + 1 >= j.steps.size()) finish(p, j);
            else update(p, j);
            return;
        }
        // Nothing for this NPC right now: idle chatter, plus a nudge toward the player's current task.
        if (!n.idle.isEmpty()) say(p, n, n.idle.get((int) (Math.random() * n.idle.size())));
        for (Job j : cfg.jobs) {
            int at = prog.getOrDefault(j.id, -1);
            if (at >= 0 && at < j.steps.size()) { update(p, j); break; }
        }
    }

    static void update(ServerPlayer p, Job j) {
        int at = progress(p).getOrDefault(j.id, 0);
        Step s = j.steps.get(at);
        Npc n = npc(s.npc);
        p.sendSystemMessage(Component.literal("§6§l[" + c(j.name) + "] §r§7Step §f" + (at + 1) + "/" + j.steps.size()
                + " §8» §7Talk to §e" + (n == null ? s.npc : c(n.name)) + (s.where == null ? "" : " §7" + c(s.where)) + "§7."));
    }

    static void finish(ServerPlayer p, Job j) {
        Reward r = j.reward;
        if (r.money > 0) Economy.give(p, r.money);
        for (String cmd : r.commands) server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), cmd.replace("{player}", p.getGameProfile().getName()));
        if (r.pokemon != null) givePokemon(p, r.pokemon);
        p.playNotifySound(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundSource.MASTER, 0.8f, 1f);
        p.sendSystemMessage(Component.literal("§6§l✦ Job complete: §e" + c(j.name) + "§6§l ✦"));
        if (r.message != null) p.sendSystemMessage(Component.literal("§7" + c(r.message)));
    }

    static void givePokemon(ServerPlayer p, PokemonReward r) {
        try {
            Object inst = Class.forName("com.cobblemon.mod.common.api.pokemon.PokemonSpecies").getField("INSTANCE").get(null);
            Object species = call(inst, "getByName", r.species);
            if (species == null) { GtsMod.LOG.error("[JCL] Unknown species for job reward: {}", r.species); return; }
            Object pk = call(species, "create", r.level);
            if (r.shiny) call(pk, "setShiny", true);
            if (r.maxRideStats) {
                Class<?> stat = Class.forName("com.cobblemon.mod.common.api.riding.stats.RidingStat");
                for (Object st : stat.getEnumConstants()) {
                    float max = ((Number) call(pk, "getMaxRideBoost", st)).floatValue();
                    call(pk, "setRideBoost", st, max);
                }
            }
            if (!CobblemonBridge.give(p, pk)) {
                ClaimEntry ce = new ClaimEntry();
                ce.type = ClaimEntry.Type.POKEMON;
                ce.pokemonSnbt = CobblemonBridge.toNbt(pk, server.registryAccess()).toString();
                ce.itemSnbt = Market.encode(CobblemonBridge.snapshot(pk, server.registryAccess()).icon);
                ce.title = CobblemonBridge.snapshot(pk, server.registryAccess()).title; ce.reason = "Job reward";
                Market.data.claimsOf(p.getUUID()).add(ce); Market.markDirty();
                p.sendSystemMessage(Component.literal("§eYour party and PC are full, so your Pokémon is waiting in /gts claims."));
            }
        } catch (Throwable t) { GtsMod.LOG.error("[JCL] Could not give the job Pokémon", t); }
    }

    static Object call(Object target, String name, Object... args) throws Exception {
        for (Method m : target.getClass().getMethods()) {
            if (!m.getName().equals(name) || m.getParameterCount() != args.length) continue;
            try { return m.invoke(target, args); } catch (IllegalArgumentException ignored) {}
        }
        throw new NoSuchMethodException(name);
    }

    // ------------------------------------------------------------------ commands

    @SubscribeEvent
    public static void onCommands(RegisterCommandsEvent e) {
        e.getDispatcher().register(Commands.literal("jobs")
                .executes(c -> { show(c.getSource().getPlayerOrException(), false); return 1; })
                .then(Commands.literal("all").executes(c -> { show(c.getSource().getPlayerOrException(), true); return 1; }))
                .then(Commands.literal("npc").requires(s -> s.hasPermission(2))
                        .then(Commands.literal("spawn").then(Commands.argument("id", StringArgumentType.word())
                                .suggests((c, b) -> SharedSuggestionProvider.suggest(cfg.npcs.stream().map(n -> n.id), b))
                                .executes(c -> { spawnNpc(c.getSource().getPlayerOrException(), StringArgumentType.getString(c, "id")); return 1; })))
                        .then(Commands.literal("remove").executes(c -> {
                            ServerPlayer p = c.getSource().getPlayerOrException();
                            server.getCommands().performPrefixedCommand(p.createCommandSourceStack().withSuppressedOutput().withPermission(4), "kill @e[tag=jcl_npc,distance=..3,limit=1,sort=nearest]");
                            p.sendSystemMessage(Component.literal("§aRemoved the nearest NPC (within 3 blocks)."));
                            return 1;
                        })))
                .then(Commands.literal("reset").requires(s -> s.hasPermission(2))
                        .then(Commands.argument("player", EntityArgument.player()).then(Commands.argument("job", StringArgumentType.word())
                                .suggests((c, b) -> SharedSuggestionProvider.suggest(cfg.jobs.stream().map(j -> j.id), b))
                                .executes(c -> {
                                    ServerPlayer t = EntityArgument.getPlayer(c, "player"); progress(t).remove(StringArgumentType.getString(c, "job")); save();
                                    c.getSource().sendSuccess(() -> Component.literal("§aJob reset."), true); return 1;
                                }))))
                .then(Commands.literal("reload").requires(s -> s.hasPermission(2)).executes(c -> { load(); c.getSource().sendSuccess(() -> Component.literal("§aJobs reloaded."), true); return 1; })));
    }

    static void spawnNpc(ServerPlayer p, String id) {
        Npc n = npc(id);
        if (n == null) { p.sendSystemMessage(Component.literal("§cNo NPC called " + id + " in config/jcl_jobs.json.")); return; }
        JsonObject name = new JsonObject(); name.addProperty("text", c(n.name));
        String cmd = String.format(java.util.Locale.ROOT,
                "summon minecraft:villager %.2f %.2f %.2f {NoAI:1b,Invulnerable:1b,Silent:1b,PersistenceRequired:1b,CustomNameVisible:1b,Rotation:[%.1ff,0f],CustomName:'%s',VillagerData:{profession:\"%s\",type:\"%s\",level:5},Offers:{Recipes:[]},Tags:[\"jcl_npc\",\"jcl_npc_%s\"]}",
                p.getX(), p.getY(), p.getZ(), p.getYRot(), name.toString().replace("'", "\\'"), n.profession, n.type, n.id);
        server.getCommands().performPrefixedCommand(p.createCommandSourceStack().withSuppressedOutput().withPermission(4), cmd);
        p.sendSystemMessage(Component.literal("§aSpawned " + c(n.name) + "§a here. Remove it with §f/jobs npc remove§a while standing next to it."));
    }

    static void show(ServerPlayer p, boolean all) {
        Map<String, Integer> prog = progress(p);
        p.sendSystemMessage(Component.literal("§6§l✦ Jobs"));
        int shown = 0;
        for (Job j : cfg.jobs) {
            int at = prog.getOrDefault(j.id, -1);
            boolean done = at >= j.steps.size();
            if (!all && at < 0) continue;
            if (!all && done) continue;
            shown++;
            if (done) { p.sendSystemMessage(Component.literal(" §a✔ §f" + c(j.name) + " §8· complete")); continue; }
            if (at < 0) {
                Npc first = npc(j.steps.get(0).npc);
                p.sendSystemMessage(Component.literal(" §7○ §f" + c(j.name) + " §8· §7" + c(j.description)));
                p.sendSystemMessage(Component.literal("   §7Start it by talking to §e" + (first == null ? "?" : c(first.name)) + (j.steps.get(0).where == null ? "" : " §7" + c(j.steps.get(0).where))));
                continue;
            }
            Step s = j.steps.get(at); Npc n = npc(s.npc);
            p.sendSystemMessage(Component.literal(" §e▶ §f" + c(j.name) + " §8· §7step " + (at + 1) + "/" + j.steps.size()));
            p.sendSystemMessage(Component.literal("   §7Next: talk to §e" + (n == null ? s.npc : c(n.name)) + (s.where == null ? "" : " §7" + c(s.where))));
        }
        if (shown == 0) p.sendSystemMessage(Component.literal(" §7No active jobs. ").append(Component.literal("§e§n[See every job]").withStyle(st -> st.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/jobs all")))));
    }
}
