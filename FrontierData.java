package net.joeyscobblemon.gts;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

/** Battle Frontier: the config (config/jcl_frontier.json), the team data, and the saved data. */
final class FrontierData {
    private FrontierData() {}

    // ------------------------------------------------------------------ config

    public static final class Bracket { public String id, name, mode = "cap", unlock = "", icon = "cobblemon:poke_ball"; public int level = 50; public long cd; public int bp; public boolean littleCup; }
    public static final class Format { public String id, name, icon; public int team = 3; public double mult = 1; }
    public static final class Length { public String id, name, icon; public int rooms; public int clearBonus; }
    public static final class Gimmick { public String id, name, icon; public double enemy, player; }
    public static final class Modifier { public String id, name, icon, desc; public double bonus; public boolean rental; }
    public static final class Room { public String id, name, icon; public double w = 1, mult = 1; public int minFloor; }
    public static final class Loot { public String item; public int count = 1; public double w = 1; }
    public static final class Merchant { public String id, name, icon; public long bp; }
    public static final class Boon { public String id, name, desc, icon; }
    public static final class ShopEntry { public String name, item; public int count = 1; public long bp; public int limit; public String period = "none"; public List<String> commands = new ArrayList<>(); }

    public static final class Config {
        public int version;
        public List<Bracket> brackets = new ArrayList<>();
        public List<Format> formats = new ArrayList<>();
        public List<Length> lengths = new ArrayList<>();
        public List<Gimmick> gimmicks = new ArrayList<>();
        public List<Modifier> modifiers = new ArrayList<>();
        public List<Room> rooms = new ArrayList<>();
        public List<Loot> loot = new ArrayList<>(), supply = new ArrayList<>();
        public List<Merchant> merchant = new ArrayList<>();
        public List<Boon> boons = new ArrayList<>();
        public List<ShopEntry> shop = new ArrayList<>();
        public List<Long> seasonPrizes = new ArrayList<>();
        public double gauntletBonus = 0.15, legendaryMult = 0.2, rentalMult = 1.0, minMult = 0.1, floorScale = 0.05, floorCap = 8, streakBonus = 0.1, streakCap = 0.5, dailySoftMult = 0.5;
        public int streakEvery = 7, dailySoftCap = 150, lobbyRadius = 96;
    }

    // ------------------------------------------------------------------ teams

    public static final class Set6 { public String s, n = "hardy", a = "", i = "", x = ""; public boolean sh; public List<String> m = new ArrayList<>(), t = new ArrayList<>(); }
    public static final class Champion { public String name; public List<Set6> team = new ArrayList<>(); public boolean secret; }
    public static final class Brain { public String name, symbol, theme, mega, gmax; public List<Set6> team = new ArrayList<>(); public List<String> tera = new ArrayList<>(); }
    public static final class Teams {
        public List<Set6> sets = new ArrayList<>();
        public Map<String, Champion> champions = new LinkedHashMap<>();
        public Map<String, Brain> brains = new LinkedHashMap<>();
        public Map<String, String> megaStones = new HashMap<>();
    }

    static Config cfg = new Config();
    static Teams teams = new Teams();
    static final Map<String, List<Set6>> BY_TYPE = new HashMap<>();

    static void load() {
        try {
            String json = Defaults.load("jcl_frontier.json");
            if (json != null) { Config c = new Gson().fromJson(json, Config.class); if (c != null) cfg = c; }
        } catch (Exception e) { GtsMod.LOG.error("[JCL] Could not read config/jcl_frontier.json", e); }
        try (var in = FrontierData.class.getResourceAsStream("/jcl/frontier_teams.json")) {
            if (in != null) { Teams t = new Gson().fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), Teams.class); if (t != null) teams = t; }
        } catch (Exception e) { GtsMod.LOG.error("[JCL] Could not read the Frontier team data", e); }
        BY_TYPE.clear();
        for (Set6 s : teams.sets) for (String t : s.t) BY_TYPE.computeIfAbsent(t, k -> new ArrayList<>()).add(s);
        GtsMod.LOG.info("[JCL] Battle Frontier loaded: {} sets, {} Champions, {} Brains.", teams.sets.size(), teams.champions.size(), teams.brains.size());
    }

    static Bracket bracket(String id) { for (Bracket b : cfg.brackets) if (b.id.equals(id)) return b; return cfg.brackets.isEmpty() ? new Bracket() : cfg.brackets.get(0); }
    static Format format(String id) { for (Format f : cfg.formats) if (f.id.equals(id)) return f; return cfg.formats.isEmpty() ? new Format() : cfg.formats.get(0); }
    static Length length(String id) { for (Length l : cfg.lengths) if (l.id.equals(id)) return l; return cfg.lengths.isEmpty() ? new Length() : cfg.lengths.get(0); }
    static Modifier modifier(String id) { for (Modifier m : cfg.modifiers) if (m.id.equals(id)) return m; return null; }
    static Gimmick gimmick(String id) { for (Gimmick g : cfg.gimmicks) if (g.id.equals(id)) return g; return null; }
    static Room room(String id) { for (Room r : cfg.rooms) if (r.id.equals(id)) return r; return null; }

    // ------------------------------------------------------------------ saved data

    public static final class Spot { public String dim; public double x, y, z; public float yaw; }
    public static final class Pending { public long cd, bp; }

    public static final class Store {
        /** Bumped by /frontier debug newdaily to roll a different Daily Challenge for today. */
        public int dailyReroll = 0;
        public Map<String, Long> bp = new HashMap<>();
        public Map<String, String> names = new HashMap<>();
        public Map<String, Map<String, Integer>> best = new HashMap<>();        // board -> player -> best room (all time)
        public Map<String, Map<String, Integer>> season = new HashMap<>();      // board -> player -> best room this season
        public String seasonKey = "";
        public Map<String, Set<String>> clears = new HashMap<>();               // player -> "bracket:length", "feat:..." etc.
        public Map<String, Set<String>> symbols = new HashMap<>();              // player -> "tycoon:silver"
        public Map<String, Map<String, Integer>> counters = new HashMap<>();    // player -> counter -> value
        public String day = "";
        public Map<String, Map<String, Integer>> daily = new HashMap<>();       // player -> daily counter -> value (reset each day)
        public Map<String, Set<String>> dailyDone = new HashMap<>();            // player -> daily quests finished today
        public Map<String, Integer> dailyChallengeBest = new HashMap<>();       // player -> best rooms in today's Daily Challenge
        public Map<String, Map<String, Integer>> bought = new HashMap<>();      // player -> "entry@period" -> times
        public Map<String, Pending> pending = new HashMap<>();                  // runs cut short by a crash, paid on next login
        public Spot lobby;
        public Map<String, Spot> boards = new LinkedHashMap<>();
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    static Store data = new Store();
    static MinecraftServer server;

    static Path file() { return server.getWorldPath(LevelResource.ROOT).resolve("data").resolve("jcl_gts").resolve("frontier.json"); }

    static void read(MinecraftServer s) {
        server = s;
        try { if (Files.exists(file())) { Store d = GSON.fromJson(Files.readString(file()), Store.class); if (d != null) data = d; } }
        catch (Exception e) { GtsMod.LOG.error("[JCL] Could not read Frontier data", e); }
        if (data.bp == null) data.bp = new HashMap<>();
        if (data.names == null) data.names = new HashMap<>();
        if (data.best == null) data.best = new HashMap<>();
        if (data.season == null) data.season = new HashMap<>();
        if (data.clears == null) data.clears = new HashMap<>();
        if (data.symbols == null) data.symbols = new HashMap<>();
        if (data.counters == null) data.counters = new HashMap<>();
        if (data.daily == null) data.daily = new HashMap<>();
        if (data.dailyDone == null) data.dailyDone = new HashMap<>();
        if (data.dailyChallengeBest == null) data.dailyChallengeBest = new HashMap<>();
        if (data.bought == null) data.bought = new HashMap<>();
        if (data.pending == null) data.pending = new HashMap<>();
        if (data.boards == null) data.boards = new LinkedHashMap<>();
    }

    static void save() {
        if (server == null) return;
        try { Files.createDirectories(file().getParent()); Files.writeString(file(), GSON.toJson(data)); }
        catch (Exception e) { GtsMod.LOG.error("[JCL] Could not save Frontier data", e); }
    }

    static long bp(String uuid) { return data.bp.getOrDefault(uuid, 0L); }
    static void addBp(String uuid, long n) { data.bp.merge(uuid, n, Long::sum); if (data.bp.get(uuid) < 0) data.bp.put(uuid, 0L); }
    static boolean takeBp(String uuid, long n) { if (bp(uuid) < n) return false; data.bp.put(uuid, bp(uuid) - n); save(); return true; }

    static int counter(String uuid, String key) { return data.counters.getOrDefault(uuid, Map.of()).getOrDefault(key, 0); }
    static int addCounter(String uuid, String key, int n) { return data.counters.computeIfAbsent(uuid, k -> new HashMap<>()).merge(key, n, Integer::sum); }
    static void setCounter(String uuid, String key, int v) { data.counters.computeIfAbsent(uuid, k -> new HashMap<>()).put(key, v); }

    static int daily(String uuid, String key) { return data.daily.getOrDefault(uuid, Map.of()).getOrDefault(key, 0); }
    static int addDaily(String uuid, String key, int n) { return data.daily.computeIfAbsent(uuid, k -> new HashMap<>()).merge(key, n, Integer::sum); }

    static boolean cleared(String uuid, String key) { return data.clears.getOrDefault(uuid, Set.of()).contains(key); }
    static boolean markCleared(String uuid, String key) { return data.clears.computeIfAbsent(uuid, k -> new HashSet<>()).add(key); }

    static boolean hasSymbol(String uuid, String key) { return data.symbols.getOrDefault(uuid, Set.of()).contains(key); }
    static boolean addSymbol(String uuid, String key) { return data.symbols.computeIfAbsent(uuid, k -> new HashSet<>()).add(key); }
}
