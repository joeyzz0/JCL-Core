package net.joeyscobblemon.gts;

import com.google.gson.JsonObject;

/** Readable names for quest tasks (plain Java, so it can be tested without the game). */
final class QuestText {
    private QuestText() {}
    static String pretty(String id) {
        String s = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
        s = s.replaceAll("_[0-9a-f]{4}$", "");
        StringBuilder b = new StringBuilder();
        for (String w : s.split("_")) if (!w.isEmpty()) b.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1)).append(' ');
        return b.toString().trim();
    }

    static String taskName(JsonObject t) {
        String title = t.has("title") ? t.get("title").getAsString() : "";
        if (!title.isEmpty()) return title;
        String type = t.get("type").getAsString();
        long n = QuestClient.target(t);
        switch (type) {
            case "item": return pretty(t.getAsJsonObject("item").get("id").getAsString()) + (n > 1 ? " ×" + n : "");
            case "checkmark": return "Tick this off when you're ready";
            case "structure": { String s = pretty(t.get("structure").getAsString()); return "Find " + ("AEIOU".indexOf(Character.toUpperCase(s.isEmpty() ? 'x' : s.charAt(0))) >= 0 ? "an " : "a ") + s; }
            case "custom": return "Complete the task";
            default:
        }
        String a = t.has("action") ? t.get("action").getAsString() : "";
        String num = n > 1 ? n + " " : "a ";
        return switch (a) {
            case "defeat_trainer" -> "Defeat " + (t.get("trainers").getAsString().isBlank() ? num + "trainer" + (n > 1 ? "s" : "") : pretty(t.get("trainers").getAsString().split(",")[0].trim()));
            case "catch" -> "Catch " + num + (t.get("shiny").getAsBoolean() ? "shiny " : "") + "Pokémon";
            case "have_registered" -> "Register " + n + " Pokémon in your Pokédex" + ("seen".equals(t.get("dex_progress").getAsString()) ? " (seen)" : "");
            case "evolve" -> "Evolve " + num + "Pokémon";
            case "hatch_egg" -> "Hatch " + (n > 1 ? n + " eggs" : "an egg");
            case "level_up" -> "Level up your Pokémon " + n + " time" + (n > 1 ? "s" : "");
            case "level_up_to" -> "Raise a Pokémon to level " + n;
            case "defeat" -> "Defeat " + num + "wild Pokémon";
            case "defeat_player" -> "Win " + num + "battle" + (n > 1 ? "s" : "") + " against another player";
            case "scan" -> "Scan " + num + "Pokémon with your Pokédex";
            case "fishing" -> "Fish up " + num + "Pokémon";
            case "mega_evolve" -> "Mega Evolve in battle" + (n > 1 ? " " + n + " times" : "");
            case "dynamax" -> "Dynamax in battle" + (n > 1 ? " " + n + " times" : "");
            case "use_z_move" -> "Use a Z-Move" + (n > 1 ? " " + n + " times" : "");
            case "terastallize" -> "Terastallize in battle" + (n > 1 ? " " + n + " times" : "");
            case "throw_ball" -> "Throw " + n + " Poké Ball" + (n > 1 ? "s" : "");
            case "win_raid" -> "Win " + num + "raid" + (n > 1 ? "s" : "");
            case "join_raid" -> "Join a raid";
            case "open_raid_pouch" -> "Open a raid pouch";
            case "revive_fossil" -> "Revive a fossil";
            case "select_starter" -> "Choose your starter Pokémon";
            default -> pretty(a);
        };
    }

}
