package net.joeyscobblemon.gts;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Saves the market to a JSON file inside the world folder. Writes to a temp file first so a crash never corrupts it. */
public final class Store {
    private Store() {}
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static MarketData load(Path file) {
        try {
            if (Files.exists(file)) {
                MarketData d = GSON.fromJson(Files.readString(file), MarketData.class);
                if (d != null) {
                    if (d.listings == null) d.listings = new java.util.LinkedHashMap<>();
                    if (d.claims == null) d.claims = new java.util.LinkedHashMap<>();
                    return d;
                }
            }
        } catch (Exception e) {
            GtsMod.LOG.error("[GTS] Could not read {}. Starting with an empty market, and keeping a copy of the bad file.", file, e);
            try { Files.copy(file, file.resolveSibling("market.broken." + System.currentTimeMillis() + ".json")); } catch (Exception ignored) {}
        }
        return new MarketData();
    }

    public static void save(Path file, MarketData data) {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, GSON.toJson(data));
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            GtsMod.LOG.error("[GTS] Could not save the market to {}", file, e);
        }
    }
}
