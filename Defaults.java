package net.joeyscobblemon.gts;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Config files that ship with defaults inside the jar (shop, crates, titles, jobs, help).
 * Each default has a "version". If the server's copy is older, it is backed up as
 * <name>.bak-<date> and replaced, so new shop items or crate rewards actually show up.
 */
final class Defaults {
    private Defaults() {}

    static int version(String json) {
        try {
            JsonObject o = JsonParser.parseString(json).getAsJsonObject();
            return o.has("version") ? o.get("version").getAsInt() : 0;
        } catch (Exception e) { return 0; }
    }

    /** Returns the JSON text to use for config/<file>. */
    static String load(String file) {
        Path p = Path.of("config", file);
        String def = Crates.readDefault(file.replace("jcl_", ""));
        try {
            Files.createDirectories(p.getParent());
            if (!Files.exists(p)) {
                if (def != null) Files.writeString(p, def);
                return def;
            }
            String cur = Files.readString(p);
            if (def != null && version(def) > version(cur)) {
                Path bak = p.resolveSibling(file + ".bak-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")));
                Files.writeString(bak, cur);
                Files.writeString(p, def);
                GtsMod.LOG.info("[JCL] Updated config/{} to version {} (old copy saved as {}).", file, version(def), bak.getFileName());
                return def;
            }
            return cur;
        } catch (Exception e) {
            GtsMod.LOG.error("[JCL] Could not read config/" + file, e);
            return def;
        }
    }
}
