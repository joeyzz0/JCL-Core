package net.joeyscobblemon.gts;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import net.minecraft.server.level.ServerPlayer;

/**
 * Ticks off the Battle Frontier quest chapter as players play, and resets the Daily Challenges each day.
 * Quest IDs come from /jcl/frontier_quests.json, generated together with the quest chapter.
 */
final class FrontierQuests {
    private FrontierQuests() {}

    static Map<String, String> IDS;
    static final List<String> DAILIES = List.of("daily_rooms", "daily_run", "daily_type", "daily_champion", "daily_challenge", "daily_loot");

    static Map<String, String> ids() {
        if (IDS != null) return IDS;
        IDS = new HashMap<>();
        try (var in = FrontierQuests.class.getResourceAsStream("/jcl/frontier_quests.json")) {
            if (in != null) IDS = new Gson().fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), new TypeToken<Map<String, String>>() {}.getType());
        } catch (Exception e) { GtsMod.LOG.error("[JCL] Could not read the Frontier quest IDs", e); }
        return IDS;
    }

    static void run(ServerPlayer p, String action, String quest) {
        String id = ids().get(quest);
        if (id == null || Frontier.server == null) return;
        if (QuestBook.BOOK != null) { if (action.equals("reset")) QuestBook.reset(p, id.toUpperCase(java.util.Locale.ROOT)); else QuestBook.complete(p, id); }
        if (net.neoforged.fml.ModList.get().isLoaded("ftbquests"))            // while FTB Quests is still installed, keep it in step
            Frontier.server.getCommands().performPrefixedCommand(Frontier.server.createCommandSourceStack().withSuppressedOutput(),
                    "ftbquests change_progress " + p.getGameProfile().getName() + " " + action + " " + id);
    }

    static boolean isDaily(String key) { return key.startsWith("daily_"); }

    /** Completes a quest once (dailies once per day). */
    static void complete(ServerPlayer p, String key) {
        String uuid = p.getUUID().toString();
        if (isDaily(key)) {
            if (!FrontierData.data.dailyDone.computeIfAbsent(uuid, k -> new HashSet<>()).add(key)) return;
            run(p, "complete", key);
            if (!key.equals("daily_sweep") && FrontierData.data.dailyDone.get(uuid).containsAll(DAILIES)) complete(p, "daily_sweep");
        } else {
            if (!FrontierData.markCleared(uuid, "quest:" + key)) return;
            run(p, "complete", key);
            if (!key.equals("king") && allDone(uuid)) { complete(p, "king"); Titles.grant(p, "f_king"); }
        }
        FrontierData.save();
    }

    static boolean allDone(String uuid) {
        for (String k : ids().keySet()) if (!isDaily(k) && !k.equals("king") && !FrontierData.cleared(uuid, "quest:" + k)) return false;
        return !ids().isEmpty();
    }

    static void progress(ServerPlayer p, String kind, int n) {
        String uuid = p.getUUID().toString();
        switch (kind) {
            case "rooms" -> { complete(p, "first_room"); if (FrontierData.addDaily(uuid, "q_rooms", n) >= 10) complete(p, "daily_rooms"); }
            case "run" -> complete(p, "daily_run");
            case "type" -> complete(p, "daily_type");
            case "champion" -> complete(p, "daily_champion");
            case "daily" -> complete(p, "daily_challenge");
            case "loot" -> complete(p, "daily_loot");
            default -> {}
        }
    }

    static String featuredType() {
        String[] t = {"normal", "fire", "water", "grass", "electric", "ice", "fighting", "poison", "ground", "flying", "psychic", "bug", "rock", "ghost", "dragon", "dark", "steel", "fairy"};
        return t[(int) Math.floorMod(Frontier.daySeed() * 5 + 1, (long) t.length)];
    }

    static void resetDailies(ServerPlayer p) {
        String uuid = p.getUUID().toString();
        int today = (int) LocalDate.now().toEpochDay();
        if (FrontierData.counter(uuid, "dailyResetDay") == today) return;
        FrontierData.setCounter(uuid, "dailyResetDay", today);
        for (String k : DAILIES) run(p, "reset", k);
        run(p, "reset", "daily_sweep");
        FrontierData.save();
    }

    /** Testing: this player's Frontier dailies reset now, as if it were a new day. */
    static void forceDailies(ServerPlayer p) {
        FrontierData.setCounter(p.getUUID().toString(), "dailyResetDay", -1);
        resetDailies(p);
    }

    static void onLogin(ServerPlayer p) {
        String uuid = p.getUUID().toString();
        resetDailies(p);
        Set<String> clears = FrontierData.data.clears.getOrDefault(uuid, Set.of());
        for (String c : new HashSet<>(clears)) if (c.startsWith("pending:")) { Titles.grant(p, c.substring(8)); FrontierData.data.clears.get(uuid).remove(c); }
        if (ids().containsKey("daily_type")) p.sendSystemMessage(net.minecraft.network.chat.Component.literal("§b§l[Frontier] §r§7Today's featured type is §f" + Frontier.cap(featuredType()) + "§7."));
    }
}
