package net.joeyscobblemon.gts;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.StringArgumentType;

import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
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

    public static final class Npc { public String id, name, service, profession = "minecraft:none", type = "minecraft:plains"; public List<String> idle = new ArrayList<>(); public List<String> done = new ArrayList<>();
        public List<Ware> shop = new ArrayList<>(); public Gift gift; }
    /** Something a vendor sells: an item, how many, and the price in CobbleDollars. */
    public static final class Ware { public String item, name; public int count = 1; public long price; }
    /** A one-time welcome gift for anyone who finds this vendor. */
    public static final class Gift { public String item, message; public int count = 1; }
    public static final class Step { public String npc, where, require, task; public boolean heal; public List<String> lines = new ArrayList<>(); public List<String> after = new ArrayList<>(); public List<String> notyet = new ArrayList<>(); }
    public static final class PokemonReward { public String species; public int level = 25; public boolean shiny, maxRideStats; }
    public static final class Reward { public long money; public List<String> commands = new ArrayList<>(); public PokemonReward pokemon; public String message; public String sailTo; }
    public static final class Job { public String id, name, description, requires; public boolean repeatable; public List<Step> steps = new ArrayList<>(); public Reward reward = new Reward(); }
    public static final class Config { public int version; public List<Npc> npcs = new ArrayList<>(); public List<Job> jobs = new ArrayList<>(); }

    /** player -> job -> next step index (steps.size() = finished) */
    public static final class Data { public Map<String, Map<String, Integer>> progress = new HashMap<>(); public Map<String, Set<String>> met = new HashMap<>(); public Map<String, Set<String>> gifts = new HashMap<>(); }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    static Config cfg = new Config();
    static Data data = new Data();
    static MinecraftServer server;
    private static final Map<String, Long> COOLDOWN = new HashMap<>();
    private static final Map<java.util.UUID, Long> REMIND = new HashMap<>();

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
        COOLDOWN.put(key, now + 500);
        talk(p, id);
    }

    static void say(ServerPlayer p, Npc n, String line) {
        String t = c(line).replace("{player}", p.getGameProfile().getName());
        if (t.startsWith("> ")) p.sendSystemMessage(Component.literal("§8┃ §7§o" + p.getGameProfile().getName() + ": " + t.substring(2)));   // the player's side
        else p.sendSystemMessage(Component.literal("§8┃ §f" + t));
    }

    /** A conversation's header: a spacer and the speaker's name, so each chat reads as its own little scene. */
    static void header(ServerPlayer p, Npc n) {
        p.sendSystemMessage(Component.literal(" "));
        p.sendSystemMessage(Component.literal("§8┃ " + (c(n.name).startsWith("§") ? "" : "§e") + "§l" + c(n.name).replaceAll("§l", "")));
    }

    /** Reading time for a line: about a second for a short one, up to 2.4 seconds for a long one. */
    static int pace(String line) { return Math.max(20, Math.min(48, 14 + line.length() / 2)); }

    /** A conversation plays one line at a time with a short pause, then runs its ending. */
    static final class Convo { ServerPlayer p; java.util.ArrayDeque<Runnable> steps = new java.util.ArrayDeque<>(); java.util.ArrayDeque<Integer> waits = new java.util.ArrayDeque<>(); int wait; }
    static final Map<java.util.UUID, Convo> TALKING = new HashMap<>();

    static void converse(ServerPlayer p, Npc n, List<String> lines, Runnable done) {
        Convo cv = new Convo(); cv.p = p;
        boolean[] first = {true};
        for (int i = 0; i < lines.size(); i++) {
            String l = lines.get(i); boolean last = i == lines.size() - 1;
            cv.steps.add(() -> { if (first[0]) { header(p, n); first[0] = false; } say(p, n, l); p.playNotifySound(SoundEvents.VILLAGER_AMBIENT, SoundSource.MASTER, 0.35f, 1.3f); });
            cv.waits.add(last && done != null ? 8 : pace(l));             // the shop or service follows the last line almost at once
        }
        if (done != null) { cv.steps.add(done); cv.waits.add(0); }
        TALKING.put(p.getUUID(), cv);
    }

    @SubscribeEvent
    public static void onTick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post e) {
        if (TALKING.isEmpty()) return;
        var it = TALKING.values().iterator();
        while (it.hasNext()) {
            Convo cv = it.next();
            if (cv.p.hasDisconnected()) { it.remove(); continue; }
            if (cv.wait-- > 0) continue;
            Runnable r = cv.steps.poll();
            if (r == null) { it.remove(); continue; }
            r.run();
            Integer w = cv.waits.poll(); cv.wait = w == null ? 20 : w;
        }
    }

    static void talk(ServerPlayer p, String npcId) {
        Npc n = npc(npcId);
        if (n == null) return;
        if (TALKING.containsKey(p.getUUID())) return;      // let the current conversation finish first
        Map<String, Integer> prog = progress(p);
        for (Job j : cfg.jobs) {
            if (j.steps.isEmpty()) continue;
            int at = prog.getOrDefault(j.id, -1);
            boolean finished = at >= j.steps.size();
            if (finished && !j.repeatable) continue;
            if (finished) at = -1;
            int next = Math.max(at, 0);
            if (at == -1 && !j.steps.get(0).npc.equals(npcId)) continue;
            if (at == -1 && j.requires != null && !done(p, j.requires)) continue;          // not unlocked yet
            Step st = j.steps.get(next);
            if (!st.npc.equals(npcId)) continue;
            if (st.require != null && !met(p, j, next)) {
                // the task isn't done yet: explain it (and remind them where they're at)
                converse(p, n, st.notyet.isEmpty() ? List.of("Come back when you've done what I asked!") : st.notyet, () -> update(p, j));
                return;
            }
            final boolean starting = at == -1;
            final int step = next;
            converse(p, n, st.lines, () -> {
                if (starting) p.sendSystemMessage(Component.literal("§6§l✦ New job: §e" + c(j.name) + "§7 · " + c(j.description)));
                progress(p).put(j.id, step + 1);
                if (st.heal) { for (Object pk : Cob.party(p)) Cob.heal(pk); p.playNotifySound(SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.MASTER, 1f, 1.2f); p.sendSystemMessage(Component.literal("§d♥ §7Your team was fully healed.")); }
                save();
                if (step + 1 >= j.steps.size()) {
                    finish(p, j);
                    if (!st.after.isEmpty()) converse(p, n, st.after, null);
                } else update(p, j);
            });
            return;
        }
        // Nothing for this NPC right now: a little chat, then a nudge toward the player's current task.
        List<String> pool = n.idle;
        boolean allDone = cfg.jobs.stream().allMatch(j -> prog.getOrDefault(j.id, -1) >= j.steps.size());
        if (allDone && !n.done.isEmpty()) pool = n.done;
        String chat = pool.isEmpty() ? "Hello there!" : pool.get((int) (Math.random() * pool.size()));
        // the first meeting gets the whole chat; after that, one line, and straight to their shop, gift or service
        boolean met = prog.containsKey("met:" + npcId);
        if (!met) { prog.put("met:" + npcId, 1); save(); }
        boolean serves = (n.shop != null && !n.shop.isEmpty()) || n.service != null;
        // someone you've met who runs a shop or a service: no chat, straight to it
        if (met && serves) { service(p, n); return; }
        List<String> lines = List.of(chat.split("\\|"));
        // (chatting NPCs always finish their whole line; only shops and services skip ahead once you've met)
        converse(p, n, lines, () -> {
            if (!serves && System.currentTimeMillis() > REMIND.getOrDefault(p.getUUID(), 0L)) {   // the job nudge: chatting NPCs only, once a minute
                REMIND.put(p.getUUID(), System.currentTimeMillis() + 60_000);
                for (Job j : cfg.jobs) {
                    int at = prog.getOrDefault(j.id, -1);
                    if (at >= 0 && at < j.steps.size()) { update(p, j); break; }
                }
            }
            service(p, n);
        });
    }

    static void update(ServerPlayer p, Job j) {
        int at = progress(p).getOrDefault(j.id, 0);
        Step s = j.steps.get(at);
        Npc n = npc(s.npc);
        String todo = s.require != null && !met(p, j, at) && s.task != null ? c(s.task) + ", then talk to §e" : "Talk to §e";
        p.sendSystemMessage(Component.literal("§6§l[" + c(j.name) + "] §r§7Step §f" + (at + 1) + "/" + j.steps.size()
                + " §8» §7" + todo + (n == null ? s.npc : c(n.name)) + (s.where == null ? "" : " §7" + c(s.where)) + "§7."));
    }

    // ------------------------------------------------------------------ job tasks and the voyage

    static boolean done(ServerPlayer p, String jobId) {
        Job j = cfg.jobs.stream().filter(x -> x.id.equals(jobId)).findFirst().orElse(null);
        return j != null && progress(p).getOrDefault(jobId, -1) >= j.steps.size();
    }
    static boolean met(ServerPlayer p, Job j, int step) { return data.met.getOrDefault(p.getUUID().toString(), Set.of()).contains(j.id + ":" + step); }
    static void markMet(ServerPlayer p, Job j, int step, String msg) {
        if (data.met.computeIfAbsent(p.getUUID().toString(), k -> new HashSet<>()).add(j.id + ":" + step)) {
            save();
            Npc n = npc(j.steps.get(step).npc);
            p.playNotifySound(SoundEvents.PLAYER_LEVELUP, SoundSource.MASTER, 0.6f, 1.6f);
            p.sendSystemMessage(Component.literal("§a✔ " + msg + " §7Go back to §e" + (n == null ? "them" : c(n.name)) + "§7."));
        }
    }
    /** Steps waiting on a requirement of this kind ("win_battle", or "visit:<spot>") for this player. */
    static void satisfy(ServerPlayer p, String kind, String msg) {
        for (Job j : cfg.jobs) {
            int at = progress(p).getOrDefault(j.id, -1);
            if (at < 0 || at >= j.steps.size()) continue;
            Step s = j.steps.get(at);
            if (kind.equals(s.require)) markMet(p, j, at, msg);
        }
    }
    /** Called when a player beats a trainer or another player. */
    public static void onBattleWin(ServerPlayer p) { satisfy(p, "win_battle", "Battle won!"); }

    @SubscribeEvent
    public static void onVisitTick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post e) {
        if (server == null || server.getTickCount() % 20 != 0) return;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            for (Job j : cfg.jobs) {
                int at = progress(p).getOrDefault(j.id, -1);
                if (at < 0 || at >= j.steps.size()) continue;
                Step s = j.steps.get(at);
                if (s.require == null || !s.require.startsWith("visit:") || met(p, j, at)) continue;
                net.minecraft.core.BlockPos spot = SpawnCity.markerWorld(p.serverLevel(), s.require.substring(6));
                if (spot != null && p.blockPosition().distSqr(spot) <= 25) markMet(p, j, at, "You found it!");
            }
        }
    }

    /** All aboard: a title, a few seconds at sea, then the ship arrives at the warp. */
    static void sail(ServerPlayer p, String warp) {
        p.connection.send(new net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket(10, 60, 20));
        p.connection.send(new net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket(Component.literal("§e§lAll aboard!")));
        p.connection.send(new net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket(Component.literal("§7The S.S. Seabreeze sets sail...")));
        p.playNotifySound(SoundEvents.BELL_BLOCK, SoundSource.MASTER, 1f, 0.6f);
        p.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.BLINDNESS, 90, 0, false, false));
        p.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.MOVEMENT_SLOWDOWN, 90, 5, false, false));
        server.execute(() -> {});
        Frontier.later(70, () -> {
            ServerPlayer pl = server.getPlayerList().getPlayer(p.getUUID());
            if (pl == null) return;
            Teleports.arrive(pl, warp);
            pl.connection.send(new net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket(Component.literal("§b§lBattle Frontier")));
            pl.connection.send(new net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket(Component.literal("§7From now on, §f/warp frontier §7brings you straight here.")));
            pl.playNotifySound(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundSource.MASTER, 0.8f, 1f);
        });
    }

    /** What an NPC does for you once they've finished talking: open the shop, the warps, heal your team... */
    /** A vendor's one-time gift (the first time each player finds them), then their little shop. */
    static void vend(ServerPlayer p, Npc n) {
        if (n.gift != null && data.gifts.computeIfAbsent(p.getUUID().toString(), k -> new HashSet<>()).add(n.id)) {
            save();
            ItemStack g = new ItemStack(Crates.resolve(n.gift.item), Math.max(1, n.gift.count));
            if (!p.getInventory().add(g)) p.drop(g, false);
            p.playNotifySound(SoundEvents.PLAYER_LEVELUP, SoundSource.MASTER, 0.6f, 1.4f);
            p.sendSystemMessage(Component.literal("§d✦ " + c(n.name) + "§7: " + (n.gift.message == null ? "Here, take this. A little welcome gift!" : c(n.gift.message))));
        }
        if (n.shop == null || n.shop.isEmpty()) return;
        Gui.Screen g = new Gui.Screen(c(n.name) + " §8· §7Shop", Math.max(1, Math.min(6, (n.shop.size() + 8) / 9)));
        for (int i = 0; i < n.shop.size() && i < 54; i++) {
            Ware w = n.shop.get(i);
            ItemStack icon = new ItemStack(Crates.resolve(w.item), Math.max(1, w.count));
            g.set(i, Gui.named(icon, "§f" + (w.name != null ? w.name : icon.getHoverName().getString()) + (w.count > 1 ? " §7×" + w.count : ""),
                    "§7Price: §6" + Fmt.cd(w.price), "", "§e▶ Click to buy"), pl -> {
                if (Economy.balance(pl) < w.price) { pl.sendSystemMessage(Component.literal("§cYou can't afford that yet.")); return; }
                if (!Economy.take(pl, w.price)) { pl.sendSystemMessage(Component.literal("§cThe payment didn't go through. You weren't charged.")); return; }
                ItemStack it = new ItemStack(Crates.resolve(w.item), Math.max(1, w.count));
                if (!pl.getInventory().add(it)) pl.drop(it, false);
                pl.playNotifySound(SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.MASTER, 0.6f, 1.2f);
                pl.sendSystemMessage(Component.literal("§aBought " + it.getHoverName().getString() + (w.count > 1 ? " ×" + w.count : "") + " §7for §6" + Fmt.cd(w.price) + "§7."));
            });
        }
        Gui.open(p, g);
    }

    static void service(ServerPlayer p, Npc n) {
        vend(p, n);
        if (n.service == null || n.service.isBlank()) return;
        if (n.service.equals("heal")) {
            for (Object pk : Cob.party(p)) Cob.heal(pk);
            p.playNotifySound(SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.MASTER, 1f, 1.2f);
            p.sendSystemMessage(Component.literal("§d♥ §7Your team is fully healed."));
            return;
        }
        server.getCommands().performPrefixedCommand(p.createCommandSourceStack().withSuppressedOutput(), n.service);
    }

    static void finish(ServerPlayer p, Job j) {
        Reward r = j.reward;
        if (r.money > 0) Economy.give(p, r.money);
        for (String cmd : r.commands) server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), cmd.replace("{player}", p.getGameProfile().getName()));
        if (r.pokemon != null) givePokemon(p, r.pokemon);
        p.playNotifySound(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundSource.MASTER, 0.8f, 1f);
        p.sendSystemMessage(Component.literal("§6§l✦ Job complete: §e" + c(j.name) + "§6§l ✦"));
        if (r.message != null) p.sendSystemMessage(Component.literal("§7" + c(r.message)));
        if (r.sailTo != null) sail(p, r.sailTo);
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
                .executes(c -> { menu(c.getSource().getPlayerOrException()); return 1; })
                .then(Commands.literal("list").executes(c -> { show(c.getSource().getPlayerOrException(), false); return 1; }))
                .then(Commands.literal("all").executes(c -> { show(c.getSource().getPlayerOrException(), true); return 1; }))
                .then(Commands.literal("npc").requires(s -> s.hasPermission(2))
                        .then(Commands.literal("spawn").then(Commands.argument("id", StringArgumentType.word())
                                .suggests((c, b) -> SharedSuggestionProvider.suggest(cfg.npcs.stream().map(n -> n.id), b))
                                .executes(c -> { spawnNpc(c.getSource().getPlayerOrException(), StringArgumentType.getString(c, "id")); return 1; })))
                        .then(Commands.literal("remove").executes(c -> {
                            ServerPlayer p = c.getSource().getPlayerOrException();
                            net.minecraft.world.entity.Entity near = null; double best = 9;
                            for (net.minecraft.world.entity.Entity en : p.serverLevel().getEntities((net.minecraft.world.entity.Entity) null, p.getBoundingBox().inflate(3), Jobs::isNpc)) {
                                double d = en.distanceToSqr(p); if (d < best) { best = d; near = en; } }
                            if (near != null) near.discard();
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
        var e = Npcs.spawn(p.serverLevel(), id, p.getX(), p.getY(), p.getZ(), p.getYRot(), "jcl_npc", "jcl_npc_" + id);
        if (e == null) { p.sendSystemMessage(Component.literal("§cCouldn't spawn " + c(n.name) + ". Check the server log.")); return; }
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
            if (done) { p.sendSystemMessage(Component.literal(" §a✔ §f" + c(j.name) + " §8· Complete")); continue; }
            if (at < 0) {
                Npc first = npc(j.steps.get(0).npc);
                p.sendSystemMessage(Component.literal(" §7○ §f" + c(j.name) + " §8· §7" + c(j.description)));
                p.sendSystemMessage(Component.literal("   §7Start it by talking to §e" + (first == null ? "?" : c(first.name)) + (j.steps.get(0).where == null ? "" : " §7" + c(j.steps.get(0).where))));
                continue;
            }
            Step s = j.steps.get(at); Npc n = npc(s.npc);
            p.sendSystemMessage(Component.literal(" §e▶ §f" + c(j.name) + " §8· §7Step " + (at + 1) + "/" + j.steps.size()));
            p.sendSystemMessage(Component.literal("   §7Next: talk to §e" + (n == null ? s.npc : c(n.name)) + (s.where == null ? "" : " §7" + c(s.where))));
        }
        if (shown == 0) p.sendSystemMessage(Component.literal(" §7No active jobs. ").append(Component.literal("§e§n[See every job]").withStyle(st -> st.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/jobs")))));
    }

    // ------------------------------------------------------------------ menus

    static void menu(ServerPlayer p) {
        Map<String, Integer> prog = progress(p);
        Gui.Screen s = new Gui.Screen("§6§l✦ Jobs", 6);
        s.frame(net.minecraft.world.item.Items.ORANGE_STAINED_GLASS_PANE, net.minecraft.world.item.Items.YELLOW_STAINED_GLASS_PANE);
        int active = 0, done = 0;
        for (Job j : cfg.jobs) { int at = prog.getOrDefault(j.id, -1); if (at >= j.steps.size()) done++; else if (at >= 0) active++; }
        s.set(4, Gui.glow(Gui.icon(net.minecraft.world.item.Items.WRITABLE_BOOK, "§6§lYour Jobs",
                "§7Active: §e" + active + "   §7Complete: §a" + done + "§7/§f" + cfg.jobs.size(), "",
                "§7Talk to townsfolk to start jobs.", "§7Click a job to see every step.")));
        int[] slots = new int[28]; int n = 0;
        for (int r = 1; r <= 4; r++) for (int c = 1; c <= 7; c++) slots[n++] = r * 9 + c;
        List<Job> list = new ArrayList<>(cfg.jobs);
        list.sort((a, b) -> Integer.compare(order(prog, a), order(prog, b)));
        for (int i = 0; i < Math.min(slots.length, list.size()); i++) {
            Job j = list.get(i);
            int at = prog.getOrDefault(j.id, -1);
            boolean finished = at >= j.steps.size();
            List<String> lore = new ArrayList<>();
            lore.add("§7" + c(j.description)); lore.add("");
            if (finished) lore.add("§a✔ Complete");
            else if (at < 0) {
                Npc first = npc(j.steps.get(0).npc);
                lore.add("§7Not started."); lore.add("§7Start by talking to §e" + (first == null ? "?" : c(first.name)));
                if (j.steps.get(0).where != null) lore.add("§8" + cap(c(j.steps.get(0).where)));
            } else {
                Step st = j.steps.get(at); Npc nn = npc(st.npc);
                lore.add("§eStep " + (at + 1) + " of " + j.steps.size()); lore.add(bar(at, j.steps.size()));
                lore.add("§7Next: talk to §e" + (nn == null ? st.npc : c(nn.name)));
                if (st.where != null) lore.add("§8" + cap(c(st.where)));
            }
            lore.add(""); lore.add("§8Click for details");
            var item = finished ? net.minecraft.world.item.Items.ENCHANTED_BOOK : at >= 0 ? net.minecraft.world.item.Items.WRITABLE_BOOK : net.minecraft.world.item.Items.BOOK;
            var icon = Gui.icon(item, (finished ? "§a" : at >= 0 ? "§e§l" : "§f") + c(j.name), lore.toArray(new String[0]));
            s.set(slots[i], at >= 0 && !finished ? Gui.glow(icon) : icon, pl -> detail(pl, j));
        }
        s.set(49, Gui.icon(net.minecraft.world.item.Items.BARRIER, "§cClose"), pl -> pl.closeContainer());
        Gui.open(p, s);
    }

    static int order(Map<String, Integer> prog, Job j) {
        int at = prog.getOrDefault(j.id, -1);
        return at >= j.steps.size() ? 2 : at >= 0 ? 0 : 1;
    }

    static String bar(int done, int total) {
        StringBuilder b = new StringBuilder("§a");
        int filled = (int) Math.round(20.0 * done / Math.max(1, total));
        for (int i = 0; i < 20; i++) { if (i == filled) b.append("§8"); b.append("|"); }
        return b.toString();
    }

    static void detail(ServerPlayer p, Job j) {
        int at = progress(p).getOrDefault(j.id, -1);
        Gui.Screen s = new Gui.Screen("§6§l✦ " + c(j.name).replaceAll("§.", ""), 6);
        s.frame(net.minecraft.world.item.Items.ORANGE_STAINED_GLASS_PANE, net.minecraft.world.item.Items.YELLOW_STAINED_GLASS_PANE);
        s.set(4, Gui.icon(net.minecraft.world.item.Items.WRITABLE_BOOK, "§6§l" + c(j.name), "§7" + c(j.description)));
        int[] slots = new int[28]; int n = 0;
        for (int r = 1; r <= 4; r++) for (int c = 1; c <= 7; c++) slots[n++] = r * 9 + c;
        for (int i = 0; i < Math.min(slots.length, j.steps.size()); i++) {
            Step st = j.steps.get(i); Npc nn = npc(st.npc);
            String who = nn == null ? st.npc : c(nn.name);
            boolean doneStep = at > i, current = at == i || (at < 0 && i == 0);
            var item = doneStep ? net.minecraft.world.item.Items.LIME_DYE : current ? net.minecraft.world.item.Items.YELLOW_DYE : net.minecraft.world.item.Items.GRAY_DYE;
            String[] lore = doneStep ? new String[]{"§a✔ Done"} : current ? new String[]{"§eYour next step", "§7Talk to " + who, "§8" + (st.where == null ? "" : cap(c(st.where)))} : new String[]{"§8Later"};
            var icon = Gui.icon(item, (doneStep ? "§a" : current ? "§e§l" : "§7") + "Step " + (i + 1) + ": " + (doneStep || current ? who : "§8???"), lore);
            s.set(slots[i], current ? Gui.glow(icon) : icon);
        }
        Reward r = j.reward;
        List<String> rl = new ArrayList<>();
        if (r.money > 0) rl.add("§6" + Fmt.cd(r.money));
        if (r.pokemon != null) rl.add("§fA Pokémon: §e" + r.pokemon.species.substring(0, 1).toUpperCase() + r.pokemon.species.substring(1) + (r.pokemon.maxRideStats ? " §7(max riding stats)" : ""));
        if (!r.commands.isEmpty()) rl.add("§fA special reward");
        s.set(49, Gui.icon(net.minecraft.world.item.Items.CHEST, "§6§lReward", rl.toArray(new String[0])));
        s.set(45, Gui.icon(net.minecraft.world.item.Items.ARROW, "§e« Back to jobs"), Jobs::menu);
        Gui.open(p, s);
    }

    // ------------------------------------------------------------------ NPCs can't be hurt (only /kill or /jobs npc remove)

    static boolean isNpc(net.minecraft.world.entity.Entity e) { return e != null && (e.getTags().contains("jcl_npc") || e.getTags().contains("jcl_ev")); }

    @SubscribeEvent
    public static void onAttack(net.neoforged.neoforge.event.entity.player.AttackEntityEvent e) {
        if (isNpc(e.getTarget())) e.setCanceled(true);
    }

    @SubscribeEvent
    public static void onHurt(net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent e) {
        if (isNpc(e.getEntity()) && !e.getSource().is(net.minecraft.world.damagesource.DamageTypes.GENERIC_KILL)) e.setCanceled(true);
    }

    @SubscribeEvent
    public static void onLightning(net.neoforged.neoforge.event.entity.EntityStruckByLightningEvent e) {
        if (isNpc(e.getEntity())) e.setCanceled(true);
    }

    /** A location hint shown on its own line starts with a capital ("At the info pavilion..."). */
    static String cap(String s) {
        if (s == null || s.isEmpty()) return s;
        int i = 0;
        while (i + 1 < s.length() && s.charAt(i) == '§') i += 2;          // skip leading colour codes
        return i < s.length() ? s.substring(0, i) + Character.toUpperCase(s.charAt(i)) + s.substring(i + 1) : s;
    }
}
