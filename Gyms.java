package net.joeyscobblemon.gts;

import com.google.gson.*;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.AdvancementEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

import java.nio.file.*;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * JCL Player Gyms. Staff assign a leader per type (/jcl gyms leader set|remove, with a Gym Leader title). Players
 * challenge with /gym challenge <type>; the leader must accept a player's first challenge and may decline later ones;
 * the leader's whole party must share the gym's type. The leader awards the badge (/gym award) or logs a loss
 * (/gym loss). Awarded badges carry the leader, date, attempt and both teams. Every badge has a history of records.
 */
@EventBusSubscriber(modid = GtsMod.MODID)
public final class Gyms {
    private Gyms() {}

    public static final List<String> TYPES = List.of("normal", "fire", "water", "grass", "electric", "ice", "fighting", "poison", "ground",
            "flying", "psychic", "bug", "rock", "ghost", "dragon", "dark", "steel", "fairy");
    public static final class Leader { public String uuid, name; public long since; }
    public static final class Rec { public long at; public String text; public Rec() {} Rec(String t) { at = System.currentTimeMillis(); text = t; } }
    public static final class Data {
        public Map<String, Leader> leaders = new LinkedHashMap<>();                         // type -> leader
        public Map<String, Map<String, List<Rec>>> records = new HashMap<>();               // player -> badge id -> history
        public Map<String, Integer> attempts = new HashMap<>();                             // player|type -> accepted challenges
    }
    static Data data = new Data();
    static MinecraftServer server;
    static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    /** Open challenges: challenger uuid|type -> when it was sent; accepted ones: challenger uuid|type -> leader uuid. */
    static final Map<String, Long> PENDING = new HashMap<>();
    static final Map<String, String> ACCEPTED = new HashMap<>();

    static Path file() { return server.getWorldPath(LevelResource.ROOT).resolve("data").resolve("jcl_gts").resolve("gyms.json"); }
    static void save() { try { Files.createDirectories(file().getParent()); Files.writeString(file(), GSON.toJson(data)); } catch (Exception ex) { GtsMod.LOG.warn("[JCL] Couldn't save gyms: {}", ex.toString()); } }

    @SubscribeEvent
    public static void onStart(ServerStartedEvent e) {
        server = e.getServer();
        try { if (Files.exists(file())) data = GSON.fromJson(Files.readString(file()), Data.class); } catch (Exception ex) { data = new Data(); }
        if (data == null) data = new Data();
    }

    /** The badge for each type's gym (the JCL Player Gyms page). */
    static String badgeFor(String type) {
        for (Badges.Info i : Badges.ITEMS_INFO) if (i.type().equals(type) && i.from().startsWith("JCL Player Gyms")) return i.id();
        return null;
    }
    static String typeName(String t) { return t.substring(0, 1).toUpperCase() + t.substring(1); }
    static String titleId(String t) { return "gym_leader_" + t; }
    static String leaderType(ServerPlayer p) { for (var en : data.leaders.entrySet()) if (en.getValue().uuid.equals(p.getUUID().toString())) return en.getKey(); return null; }

    public static void record(UUID player, String badgeId, String text) {
        data.records.computeIfAbsent(player.toString(), k -> new HashMap<>()).computeIfAbsent(badgeId, k -> new ArrayList<>()).add(new Rec(text));
        save();
    }
    public static List<String> history(UUID player, String badgeId) {
        List<String> out = new ArrayList<>(); SimpleDateFormat f = new SimpleDateFormat("MMM d, yyyy");
        var m = data.records.get(player.toString());
        if (m == null || !m.containsKey(badgeId)) return out;
        for (Rec r : m.get(badgeId)) out.add("§8" + f.format(new Date(r.at)) + " §f" + r.text);
        return out;
    }

    // ------------------------------------------------------------------ teams
    static String mon(Object pk) {
        String held = ""; ItemStack h = Cob.heldItem(pk); if (!h.isEmpty()) held = " @" + h.getHoverName().getString();
        boolean shiny = false; try { shiny = Boolean.TRUE.equals(Cob.call(pk, "getShiny")); } catch (Throwable ignored) {}
        return (shiny ? "★" : "") + Cob.speciesName(pk) + " Lv." + Cob.level(pk) + held;
    }
    static ListTag team(ServerPlayer p) { ListTag l = new ListTag(); for (Object pk : Cob.party(p)) l.add(StringTag.valueOf(mon(pk))); return l; }
    /** The party members that don't have the gym's type (empty = the team follows the rules). */
    static List<String> offType(ServerPlayer leader, String type) {
        List<String> bad = new ArrayList<>();
        for (Object pk : Cob.party(leader)) { boolean ok = false; for (String t : Cob.types(pk)) if (t.equalsIgnoreCase(type)) ok = true; if (!ok) bad.add(Cob.speciesName(pk)); }
        return bad;
    }

    // ------------------------------------------------------------------ commands
    static int fail(CommandSourceStack s, String m) { s.sendFailure(Component.literal(m)); return 0; }

    @SubscribeEvent
    public static void onCommands(RegisterCommandsEvent e) {
        var d = e.getDispatcher();
        var typeArg = Commands.argument("type", StringArgumentType.word()).suggests((c, b) -> SharedSuggestionProvider.suggest(TYPES, b));
        d.register(Commands.literal("jcl").requires(s -> s.hasPermission(2)).then(Commands.literal("gyms")
                .then(Commands.literal("leader")
                        .then(Commands.literal("set").then(Commands.argument("player", EntityArgument.player()).then(typeArg.executes(c -> {
                            ServerPlayer p = EntityArgument.getPlayer(c, "player"); String t = StringArgumentType.getString(c, "type").toLowerCase();
                            if (!TYPES.contains(t)) return fail(c.getSource(), "Unknown type. Use one of: " + String.join(", ", TYPES));
                            String had = leaderType(p); if (had != null && !had.equals(t)) removeLeader(had);
                            Leader old = data.leaders.get(t); if (old != null && !old.uuid.equals(p.getUUID().toString())) removeLeader(t);
                            Leader l = new Leader(); l.uuid = p.getUUID().toString(); l.name = p.getGameProfile().getName(); l.since = System.currentTimeMillis();
                            data.leaders.put(t, l); save();
                            Titles.grant(p, titleId(t)); Titles.data.chosen.put(p.getUUID().toString(), titleId(t)); Titles.save(); Titles.refresh(p);
                            server.getPlayerList().broadcastSystemMessage(Component.literal("§6§l✦ §e" + l.name + " §fis now the §e" + typeName(t) + "-type Gym Leader§f! Challenge them with §e/gym challenge " + t + "§f."), false);
                            return 1; }))))
                        .then(Commands.literal("remove").then(Commands.argument("name", StringArgumentType.word()).suggests((c, b) -> SharedSuggestionProvider.suggest(data.leaders.values().stream().map(x -> x.name).toList(), b)).executes(c -> {
                            String n = StringArgumentType.getString(c, "name");
                            for (var en : new ArrayList<>(data.leaders.entrySet())) if (en.getValue().name.equalsIgnoreCase(n)) { removeLeader(en.getKey()); c.getSource().sendSuccess(() -> Component.literal("§a" + n + " is no longer a gym leader."), true); return 1; }
                            return fail(c.getSource(), n + " isn't a gym leader."); }))))
                .then(Commands.literal("leaders").executes(c -> { listLeaders(c.getSource()); return 1; }))));
        d.register(Commands.literal("gym")
                .then(Commands.literal("leaders").executes(c -> { listLeaders(c.getSource()); return 1; }))
                .then(Commands.literal("challenge").then(typeArg.executes(c -> challenge(c.getSource().getPlayerOrException(), StringArgumentType.getString(c, "type").toLowerCase()))))
                .then(Commands.literal("accept").then(Commands.argument("player", EntityArgument.player()).executes(c -> answer(c.getSource().getPlayerOrException(), EntityArgument.getPlayer(c, "player"), true))))
                .then(Commands.literal("decline").then(Commands.argument("player", EntityArgument.player()).executes(c -> answer(c.getSource().getPlayerOrException(), EntityArgument.getPlayer(c, "player"), false))))
                .then(Commands.literal("award").then(Commands.argument("player", EntityArgument.player()).executes(c -> result(c.getSource().getPlayerOrException(), EntityArgument.getPlayer(c, "player"), true))))
                .then(Commands.literal("loss").then(Commands.argument("player", EntityArgument.player()).executes(c -> result(c.getSource().getPlayerOrException(), EntityArgument.getPlayer(c, "player"), false)))));
    }

    static void removeLeader(String type) {
        Leader l = data.leaders.remove(type); save();
        if (l == null) return;
        var set = Titles.data.unlocked.get(l.uuid); if (set != null) set.remove(titleId(type));
        if (titleId(type).equals(Titles.data.chosen.get(l.uuid))) Titles.data.chosen.remove(l.uuid);
        Titles.save();
        ServerPlayer p = server.getPlayerList().getPlayer(UUID.fromString(l.uuid));
        if (p != null) { Titles.refresh(p); p.sendSystemMessage(Component.literal("§7You're no longer the " + typeName(type) + "-type Gym Leader. Thanks for leading!")); }
    }

    static void listLeaders(CommandSourceStack s) {
        s.sendSuccess(() -> Component.literal("§6§lJCL Player Gyms"), false);
        for (String t : TYPES) {
            Leader l = data.leaders.get(t); Badges.Info b = Badges.BY_ID.get(badgeFor(t));
            boolean on = l != null && server.getPlayerList().getPlayer(UUID.fromString(l.uuid)) != null;
            s.sendSuccess(() -> Component.literal("§7" + typeName(t) + " §8(" + (b == null ? "?" : b.name()) + ")§7: " + (l == null ? "§8vacant" : "§f" + l.name + (on ? " §a● online" : " §8● offline"))), false);
        }
    }

    static int challenge(ServerPlayer p, String type) {
        if (!TYPES.contains(type)) { p.sendSystemMessage(Component.literal("§cUnknown type. Try: " + String.join(", ", TYPES))); return 0; }
        Leader l = data.leaders.get(type);
        if (l == null) { p.sendSystemMessage(Component.literal("§cThe " + typeName(type) + " gym has no leader right now.")); return 0; }
        if (l.uuid.equals(p.getUUID().toString())) { p.sendSystemMessage(Component.literal("§cYou lead this gym!")); return 0; }
        ServerPlayer lead = server.getPlayerList().getPlayer(UUID.fromString(l.uuid));
        if (lead == null) { p.sendSystemMessage(Component.literal("§c" + l.name + " isn't online. Check §f/gym leaders§c.")); return 0; }
        String key = p.getUUID() + "|" + type;
        PENDING.put(key, System.currentTimeMillis());
        boolean first = data.attempts.getOrDefault(key, 0) == 0;
        record(p.getUUID(), badgeFor(type), "Challenged " + l.name + " at the " + typeName(type) + " gym");
        p.sendSystemMessage(Component.literal("§eChallenge sent to " + l.name + "."));
        Component accept = Component.literal("§a§l[Accept]").withStyle(st -> st.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/gym accept " + p.getGameProfile().getName())).withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("Accept the challenge"))));
        Component decline = first ? Component.literal("§8[Decline] (first challenge: must accept)")
                : Component.literal("§c§l[Decline]").withStyle(st -> st.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/gym decline " + p.getGameProfile().getName())));
        lead.sendSystemMessage(Component.literal("§6⚔ §e" + p.getGameProfile().getName() + " §fchallenges your " + typeName(type) + " gym! ").append(accept).append(Component.literal(" ")).append(decline));
        lead.playNotifySound(SoundEvents.NOTE_BLOCK_BELL.value(), SoundSource.MASTER, 1f, 1.2f);
        return 1;
    }

    static int answer(ServerPlayer leader, ServerPlayer p, boolean yes) {
        String type = leaderType(leader);
        if (type == null) { leader.sendSystemMessage(Component.literal("§cYou're not a gym leader.")); return 0; }
        String key = p.getUUID() + "|" + type;
        Long at = PENDING.get(key);
        if (at == null || System.currentTimeMillis() - at > 600_000) { PENDING.remove(key); leader.sendSystemMessage(Component.literal("§c" + p.getGameProfile().getName() + " has no open challenge for your gym.")); return 0; }
        boolean first = data.attempts.getOrDefault(key, 0) == 0;
        if (!yes && first) { leader.sendSystemMessage(Component.literal("§cThis is their first challenge to your gym: you have to accept it.")); return 0; }
        if (yes) {
            List<String> bad = offType(leader, type);
            if (!bad.isEmpty()) { leader.sendSystemMessage(Component.literal("§cYour team must be all " + typeName(type) + "-type. Not " + typeName(type) + ": §f" + String.join(", ", bad))); return 0; }
            PENDING.remove(key); ACCEPTED.put(key, leader.getUUID().toString());
            data.attempts.merge(key, 1, Integer::sum); save();
            record(p.getUUID(), badgeFor(type), "Accepted by " + leader.getGameProfile().getName() + " (attempt " + data.attempts.get(key) + ")");
            p.sendSystemMessage(Component.literal("§a" + leader.getGameProfile().getName() + " accepted your challenge! Battle them, then they'll record the result."));
            leader.sendSystemMessage(Component.literal("§aAccepted. When the battle's over: §f/gym award " + p.getGameProfile().getName() + " §aor §f/gym loss " + p.getGameProfile().getName()));
        } else {
            PENDING.remove(key);
            record(p.getUUID(), badgeFor(type), "Declined by " + leader.getGameProfile().getName());
            p.sendSystemMessage(Component.literal("§7" + leader.getGameProfile().getName() + " declined your challenge this time."));
            leader.sendSystemMessage(Component.literal("§7Declined."));
        }
        return 1;
    }

    static int result(ServerPlayer leader, ServerPlayer p, boolean won) {
        String type = leaderType(leader);
        if (type == null) { leader.sendSystemMessage(Component.literal("§cYou're not a gym leader.")); return 0; }
        String key = p.getUUID() + "|" + type;
        if (!leader.getUUID().toString().equals(ACCEPTED.get(key))) { leader.sendSystemMessage(Component.literal("§cAccept " + p.getGameProfile().getName() + "'s challenge first.")); return 0; }
        String badge = badgeFor(type);
        ACCEPTED.remove(key);
        if (!won) {
            record(p.getUUID(), badge, "Lost to " + leader.getGameProfile().getName());
            p.sendSystemMessage(Component.literal("§7" + leader.getGameProfile().getName() + " recorded your battle as a loss. Train up and try again!"));
            leader.sendSystemMessage(Component.literal("§7Recorded as a loss for " + p.getGameProfile().getName() + "."));
            return 1;
        }
        List<String> bad = offType(leader, type);
        if (!bad.isEmpty()) { leader.sendSystemMessage(Component.literal("§cYour team must be all " + typeName(type) + "-type to award the badge. Not " + typeName(type) + ": §f" + String.join(", ", bad))); ACCEPTED.put(key, leader.getUUID().toString()); return 0; }
        ItemStack b = new ItemStack(Badges.BADGES.get(badge).get());
        CompoundTag g = new CompoundTag();
        g.putString("leader", leader.getGameProfile().getName()); g.putString("type", type); g.putLong("at", System.currentTimeMillis());
        g.putInt("attempt", data.attempts.getOrDefault(key, 1)); g.put("yourTeam", team(p)); g.put("theirTeam", team(leader));
        CompoundTag root = new CompoundTag(); root.put("jcl_gym", g);
        b.set(DataComponents.CUSTOM_DATA, CustomData.of(root));
        if (!p.getInventory().add(b)) p.drop(b, false);
        record(p.getUUID(), badge, "§aWon the " + Badges.BY_ID.get(badge).name() + " §ffrom " + leader.getGameProfile().getName() + " (attempt " + g.getInt("attempt") + ")");
        p.playNotifySound(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundSource.MASTER, 1f, 1f);
        server.getPlayerList().broadcastSystemMessage(Component.literal("§6§l✦ §e" + p.getGameProfile().getName() + " §fearned the §e" + Badges.BY_ID.get(badge).name() + " §ffrom " + leader.getGameProfile().getName() + "!"), false);
        return 1;
    }

    // ------------------------------------------------------------------ official badges: first defeats from RCT's advancements
    static final Map<String, String> TRAINER_BADGE = new HashMap<>();
    static String badgeForTrainer(String trainer) {
        if (TRAINER_BADGE.isEmpty())
            for (Badges.Page pg : Badges.PAGES) {
                if (pg.region().startsWith("JCL")) continue;
                for (Badges.SlotDef s : pg.slots())
                    for (String nm : s.leader().toLowerCase().replace("lt. ", "lt_").split("[ ,&/]+"))
                        if (!nm.isBlank()) TRAINER_BADGE.putIfAbsent(nm, s.accepts().get(0));
            }
        String core = trainer.replaceAll("^(gym_leader_|[a-z]+leader_|leader_)", "").replaceAll("_[0-9a-f]{4}$", "");
        if (core.equals("crasherwake")) core = "wake";
        String b = TRAINER_BADGE.get(core);
        return b != null ? b : TRAINER_BADGE.get(core.substring(core.lastIndexOf('_') + 1));
    }

    @SubscribeEvent
    public static void onAdvancement(AdvancementEvent.AdvancementEarnEvent e) {
        var id = e.getAdvancement().id();
        if (!"rctmod".equals(id.getNamespace()) || !id.getPath().startsWith("trainers/defeat_")) return;
        String trainer = id.getPath().substring("trainers/defeat_".length());
        String badge = badgeForTrainer(trainer);
        if (badge == null || !(e.getEntity() instanceof ServerPlayer p)) return;
        Badges.Info i = Badges.BY_ID.get(badge);
        record(p.getUUID(), badge, "Defeated " + (i == null ? trainer : i.leader()) + " for the first time");
    }
}
