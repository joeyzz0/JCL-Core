package net.joeyscobblemon.gts;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.neoforged.fml.loading.FMLPaths;

/** Editable settings, saved to config/jcl_gts.json. */
public final class GtsConfig {
    public int maxListingsPerPlayer = 20;
    public long minPrice = 1;
    public long maxPrice = 1_000_000_000L;
    public int maxBuyNowHours = 168;
    public int defaultBuyNowHours = 72;
    public int maxAuctionHours = 72;
    public int bidIncrementPercent = 5;
    public long minBidIncrement = 1;
    public int antiSnipeSeconds = 120;
    public int antiSnipeExtendSeconds = 120;
    public int maxExtensions = 10;
    public boolean blockLastPokemon = true;

    /** Items that can never be listed. Add your own IDs here. */
    public List<String> restrictedItems = defaultRestricted();

    /** Discord webhook URL. Leave empty to turn Discord posts off. */
    public String webhookUrl = "";
    public String webhookName = "Joey's GTS";
    public boolean announceNewListings = true;
    public boolean announceSales = true;
    public boolean announceAuctionEndingSoon = true;
    public int endingSoonMinutes = 10;
    public boolean liveBoard = true;
    public int boardIntervalSeconds = 300;

    /** Optional. If CobbleDollars prints your balance in an odd way, put a regex here. Group 1 must be the number. */
    public String balanceRegex = "";

    public static GtsConfig INSTANCE = new GtsConfig();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static List<String> defaultRestricted() {
        List<String> l = new ArrayList<>();
        for (String s : new String[]{"cobblemon:master_ball", "cobblemon:ancient_origin_ball", "cobblemon:health_feather", "cobblemon:muscle_feather",
                "cobblemon:resist_feather", "cobblemon:genius_feather", "cobblemon:clever_feather", "cobblemon:swift_feather"}) l.add(s);
        for (String p : new String[]{"shinycard", "shiny_card", "cobblemax", "cobble_max", "mastercandy", "mastery_candy", "cobblemin", "cobble_min",
                "goldencap", "golden_cap", "hpsilvercap", "hp_silver_cap", "atksilvercap", "atk_silvercap", "attack_silver_cap", "defsilvercap",
                "def_silver_cap", "defense_silver_cap", "spatksilvercap", "spatk_silver_cap", "special_attack_silver_cap", "spdefsilvercap",
                "spdef_silver_cap", "special_defense_silver_cap", "speedsilvercap", "speed_silver_cap", "woodencap", "wooden_cap", "voidcap",
                "void_cap", "hpobsidiancap", "hp_obsidian_cap", "atkobsidiancap", "atk_obsidian_cap", "attack_obsidian_cap", "defobsidiancap",
                "def_obsidian_cap", "defense_obsidian_cap", "spatkobsidiancap", "spatk_obsidian_cap", "special_attack_obsidian_cap",
                "spdefobsidiancap", "spdef_obsidian_cap", "special_defense_obsidian_cap", "speedobsidiancap", "speed_obsidian_cap",
                "voidfeather", "void_feather"}) l.add("cobblemon_utility:" + p);
        return l;
    }

    public static void load() {
        Path p = FMLPaths.CONFIGDIR.get().resolve("jcl_gts.json");
        try {
            if (Files.exists(p)) {
                GtsConfig c = GSON.fromJson(Files.readString(p), GtsConfig.class);
                if (c != null) INSTANCE = c;
            }
            Files.writeString(p, GSON.toJson(INSTANCE));
        } catch (Exception e) {
            GtsMod.LOG.error("[GTS] Could not read or write {}", p, e);
        }
    }
}
