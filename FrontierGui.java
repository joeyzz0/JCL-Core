package net.joeyscobblemon.gts;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.HashMap;

import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import java.util.Random;
import com.mojang.brigadier.arguments.StringArgumentType;

import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import net.joeyscobblemon.gts.FrontierData.Set6;

/** Every Frontier menu, plus the /frontier commands. */
@EventBusSubscriber(modid = "jcl_gts")
public final class FrontierGui {
    private FrontierGui() {}

    static String pct(double m) { return String.format(Locale.ROOT, "×%.2f", m); }

    static Frontier.Setup setupOf(ServerPlayer p) {
        return Frontier.SETUPS.computeIfAbsent(p.getUUID(), k -> {
            Frontier.Setup s = new Frontier.Setup();
            for (var g : FrontierData.cfg.gimmicks) s.gimmicks.put(g.id, "both");
            return s;
        });
    }

    // ------------------------------------------------------------------ setup menu

    // ------------------------------------------------------------------ the run setup hub

    static final net.minecraft.world.item.Item EDGE = Items.BLACK_STAINED_GLASS_PANE, TRIM = Items.CYAN_STAINED_GLASS_PANE;

    /** A tidy frame: dark glass everywhere, a cyan trim along the top and bottom. */
    static Gui.Screen screen(String title, int rows) {
        Gui.Screen g = new Gui.Screen(title, rows);
        for (int i = 0; i < rows * 9; i++) g.set(i, Gui.icon(EDGE, " "));
        for (int i = 0; i < 9; i++) { g.set(i, Gui.icon(TRIM, " ")); g.set((rows - 1) * 9 + i, Gui.icon(TRIM, " ")); }
        return g;
    }

    static String gimmickSummary(Frontier.Setup s) {
        List<String> parts = new ArrayList<>();
        for (var gm : FrontierData.cfg.gimmicks) {
            String v = s.gimmicks.getOrDefault(gm.id, "off");
            String c = switch (v) { case "enemy" -> "§c"; case "player" -> "§a"; case "both" -> "§e"; default -> "§8"; };
            parts.add(c + gm.name);
        }
        return String.join("§7, ", parts);
    }

    // ------------------------------------------------------------------ the run setup hub

    static final String[] ROMAN = {"I", "II", "III"};

    /** Fills every empty slot with dark glass, then lines the edges in the Frontier's colours. */
    static void dress(Gui.Screen g, int rows) {
        for (int i = 0; i < rows * 9; i++) g.set(i, Gui.icon(Items.BLACK_STAINED_GLASS_PANE, " "));
        for (int c = 0; c < 9; c++) { g.set(c, Gui.icon(c % 2 == 0 ? Items.CYAN_STAINED_GLASS_PANE : Items.BLUE_STAINED_GLASS_PANE, " ")); g.set((rows - 1) * 9 + c, Gui.icon(c % 2 == 0 ? Items.CYAN_STAINED_GLASS_PANE : Items.BLUE_STAINED_GLASS_PANE, " ")); }
    }

    static void setup(ServerPlayer p) {
        if (Frontier.inRun(p)) { continueRun(p); return; }
        FrontierQuests.complete(p, "visit");
        Frontier.Setup s = setupOf(p);
        Frontier.Setup eff = s.daily ? daily(s) : s;
        double m = Frontier.multiplier(eff);
        var b = FrontierData.bracket(eff.bracket);
        var f = FrontierData.format(eff.format);
        var l = FrontierData.length(eff.length);
        Gui.Screen g = new Gui.Screen("§b§lThe Battle Frontier", 6);
        dress(g, 6);
        List<String> hl = new ArrayList<>(List.of("§7§oBehind every door, a test.", "§7§oBeyond the last, a legend.", "", "§7Reward multiplier: §e" + pct(m)));
        hl.addAll(Frontier.breakdown(eff)); hl.add(""); hl.add("§7Your BP: §b" + FrontierData.bp(p.getUUID().toString()));
        g.set(4, Gui.glow(Gui.icon(Items.NETHER_STAR, "§b§lThe Battle Frontier", hl.toArray(new String[0]))));
        // row 2: the three big choices
        g.set(20, Gui.icon(Crates.resolve(b.icon), "§f§lBracket §8» §b" + b.name, "§7" + (b.mode.equals("flat") ? "Everyone at Lv. " : "Teams capped at Lv. ") + b.level,
                "§7Base: §6" + Fmt.cd(b.cd) + " §7+ §b" + b.bp + " BP §7per room", "", "§e▶ Choose a bracket"), FrontierGui::bracketMenu);
        g.set(22, Gui.icon(Crates.resolve(f.icon), "§f§lFormat §8» §b" + f.name, "§7Bring " + f.team + " Pokémon", "§7Reward " + pct(f.mult), "", "§e▶ Choose a format"), FrontierGui::formatMenu);
        g.set(24, Gui.icon(Crates.resolve(l.icon), "§f§lLength §8» §b" + l.name, l.rooms > 0 ? "§7" + l.rooms + " rooms" : "§7Endless. §dLeaderboard mode.", "", "§e▶ Choose a length"), FrontierGui::lengthMenu);
        // row 3: rules
        int enemyG = 0, youG = 0;
        for (var gm : FrontierData.cfg.gimmicks) { String v = eff.gimmicks.getOrDefault(gm.id, "off"); if (v.equals("enemy")) enemyG++; if (v.equals("player")) youG++; }
        g.set(29, Gui.icon(Items.END_CRYSTAL, "§d§lGimmicks", "§7Mega, Z-Moves, Dynamax and Tera.", "§7Enemy only: §c" + enemyG + " §8· §7You only: §a" + youG, "", "§e▶ Set who can use them"), FrontierGui::gimmickMenu);
        int hard = 0, easy = 0;
        for (String id : eff.mods) { var md = FrontierData.modifier(id); if (md != null) { if (md.bonus > 0) hard++; else easy++; } }
        g.set(31, Gui.icon(Items.COMPARATOR, "§e§lModifiers", "§7Hard: §c" + hard + " §8· §7Easy: §a" + easy, "§7Harder rules pay more.", "", "§e▶ Choose modifiers"), FrontierGui::modifiers);
        List<String> team = new ArrayList<>();
        team.add(s.rental ? "§dRental team" : "§fYour own team");
        if (s.legendaries) team.add("§6Legendaries allowed §c(−80%)");
        if (s.format.equals("coop") && s.ally != null && FrontierData.teams.champions.containsKey(s.ally)) team.add("§bPartner: " + FrontierData.teams.champions.get(s.ally).name);
        List<String> tl = new ArrayList<>(); for (String t : team) tl.add("§7" + t); tl.add(""); tl.add("§e▶ Team options");
        g.set(33, Gui.icon(Items.TOTEM_OF_UNDYING, "§a§lTeam", tl.toArray(new String[0])), FrontierGui::teamMenu);
        // row 4: the daily challenge
        Frontier.Setup today = daily(s);
        List<String> dl = new ArrayList<>(List.of("§7Today's fixed rules for everyone.", "§bDouble BP§7, Sprint length.", "", "§f§lToday:",
                "§7Format: §f" + FrontierData.format(today.format).name));
        for (String id : today.mods) { var md = FrontierData.modifier(id); dl.add("§7Modifier: §c" + (md == null ? id : md.name)); }
        for (var gmm : FrontierData.cfg.gimmicks) dl.add("§7" + gmm.name + ": §f" + switch (today.gimmicks.getOrDefault(gmm.id, "off")) { case "enemy" -> "Enemy only"; case "both" -> "Both"; case "player" -> "You only"; default -> "Off"; });
        int best = FrontierData.data.dailyChallengeBest.getOrDefault(p.getUUID().toString(), 0);
        dl.add(""); dl.add("§7Your best today: §f" + best + " rooms");
        g.set(40, toggle(s.daily, Items.CLOCK, "§a§lDaily Challenge", dl.toArray(new String[0])),
                pl -> { var st = setupOf(pl); st.daily = !st.daily; setup(pl); });
        // bottom row
        String problem = Frontier.why(p, eff);
        List<String> sl = new ArrayList<>();
        sl.add(problem == null ? "§7Reward multiplier: §e" + pct(m) : "§c" + problem);
        sl.add(""); sl.add("§f§lWhat's affecting it:"); sl.addAll(Frontier.breakdown(eff));
        sl.add(""); sl.add("§8Your own Pokémon are never changed.");
        g.set(49, Gui.glow(Gui.icon(problem == null ? Items.LIME_CONCRETE : Items.RED_CONCRETE, problem == null ? "§a§l▶ ENTER THE FRONTIER" : "§c§lCan't start yet", sl.toArray(new String[0]))),
                pl -> { pl.closeContainer(); Frontier.start(pl, setupOf(pl)); });
        g.set(45, Gui.icon(Items.BOOK, "§f§lGuide", "§7Titles, Symbols and rewards."), FrontierGui::guide);
        g.set(47, Gui.icon(Items.EMERALD, "§a§lBP Exchange", "§7Spend your Battle Points."), pl -> FrontierShop.open(pl, 0, true));
        g.set(51, Gui.icon(Items.PAPER, "§f§lYour Record"), FrontierGui::stats);
        g.set(53, Gui.icon(Items.BARRIER, "§cClose"), pl -> pl.closeContainer());
        Gui.open(p, g);
    }

    static void back(Gui.Screen g, int slot) { g.set(slot, Gui.icon(Items.ARROW, "§e« Back"), FrontierGui::setup); }

    static void dailyLocked(ServerPlayer p, String what) {
        p.playNotifySound(net.minecraft.sounds.SoundEvents.VILLAGER_NO, net.minecraft.sounds.SoundSource.MASTER, 0.8f, 1f);
        Frontier.msg(p, "§eThe Daily Challenge sets its own " + what + " (" + (what.equals("length") ? "always a Sprint" : "today's format") + "). §7Turn the Daily Challenge off to choose your own.");
    }

    static void bracketMenu(ServerPlayer p) {
        Frontier.Setup s = setupOf(p);
        Gui.Screen g = new Gui.Screen("§b§lChoose a Bracket", 4);
        dress(g, 4);
        int[] slots = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25};
        int i = 0;
        for (var b : FrontierData.cfg.brackets) {
            if (i >= slots.length) break;
            boolean on = b.id.equals(s.bracket), open = Frontier.unlocked(p, b);
            ItemStack icon = Gui.icon(open ? Crates.resolve(b.icon) : Items.BARRIER, (on ? "§a§l▶ " : "§f§l") + b.name,
                    "§7" + (b.mode.equals("flat") ? "Everyone at Lv. " : "Teams capped at Lv. ") + b.level + (b.littleCup ? " §8(unevolved only)" : ""),
                    "§7Base: §6" + Fmt.cd(b.cd) + " §7+ §b" + b.bp + " BP §7per room", "", open ? (on ? "§a✔ Selected" : "§e▶ Choose") : "§c" + Frontier.unlockText(b));
            g.set(slots[i++], on ? Gui.glow(icon) : icon, pl -> { if (Frontier.unlocked(pl, b)) { setupOf(pl).bracket = b.id; setup(pl); } });
        }
        back(g, 31);
        Gui.open(p, g);
    }

    static void formatMenu(ServerPlayer p) {
        Frontier.Setup s = setupOf(p);
        Gui.Screen g = new Gui.Screen("§b§lChoose a Format", 3);
        dress(g, 3);
        int n = FrontierData.cfg.formats.size();
        int[] slots = n <= 2 ? new int[]{11, 15} : n == 3 ? new int[]{11, 13, 15} : new int[]{10, 12, 14, 16};
        int i = 0;
        for (var f : FrontierData.cfg.formats) {
            if (i >= slots.length) break;
            boolean on = f.id.equals(s.format);
            List<String> lore = new ArrayList<>();
            if (f.id.equals("coop")) {
                java.util.UUID mate = Frontier.COOP.get(p.getUUID());
                lore.add("§7You and a partner, 3 Pokémon each,"); lore.add("§7against one trainer's full team.");
                lore.add(mate == null ? "§7Partner: §cNone §8(invite with /frontier coop invite)" : "§7Partner: §a" + FrontierData.data.names.getOrDefault(mate.toString(), "your friend"));
            } else lore.add("§7Bring " + f.team + " Pokémon §8(the first " + f.team + " in your party)");
            lore.add("§7Reward " + pct(f.mult)); lore.add(""); lore.add(on ? "§a✔ Selected" : "§e▶ Choose");
            ItemStack icon = Gui.icon(Crates.resolve(f.icon), (on ? "§a§l▶ " : "§f§l") + f.name, lore.toArray(new String[0]));
            g.set(slots[i++], on ? Gui.glow(icon) : icon, pl -> {
                if (setupOf(pl).daily) { dailyLocked(pl, "format"); return; }
                setupOf(pl).format = f.id; setup(pl); });
        }
        back(g, 22);
        Gui.open(p, g);
    }

    static void lengthMenu(ServerPlayer p) {
        Frontier.Setup s = setupOf(p);
        Gui.Screen g = new Gui.Screen("§b§lChoose a Length", 3);
        dress(g, 3);
        int[] slots = {10, 12, 14, 16};
        int i = 0;
        for (var l : FrontierData.cfg.lengths) {
            if (i >= slots.length) break;
            boolean on = l.id.equals(s.length);
            ItemStack icon = Gui.icon(Crates.resolve(l.icon), (on ? "§a§l▶ " : "§f§l") + l.name, l.rooms > 0 ? "§7" + l.rooms + " rooms" : "§7Endless, until you fall. §dLeaderboard mode.",
                    l.clearBonus > 0 ? "§7Clear bonus: ×" + l.clearBonus : "§7Rewards keep growing.", "", on ? "§a✔ Selected" : "§e▶ Choose");
            g.set(slots[i++], on ? Gui.glow(icon) : icon, pl -> {
                if (setupOf(pl).daily && !l.id.equals("sprint")) { dailyLocked(pl, "length"); return; }
                setupOf(pl).length = l.id; setup(pl); });
        }
        back(g, 22);
        Gui.open(p, g);
    }

    static void gimmickMenu(ServerPlayer p) {
        Frontier.Setup s = setupOf(p);
        Gui.Screen g = new Gui.Screen("§d§lGimmicks", 3);
        dress(g, 3);
        int[] slots = {10, 12, 14, 16};
        int i = 0;
        for (var gm : FrontierData.cfg.gimmicks) {
            if (i >= slots.length) break;
            String v = s.gimmicks.getOrDefault(gm.id, "off");
            String label = switch (v) { case "enemy" -> "§cEnemy only §7(" + plus(gm.enemy) + ")"; case "player" -> "§aYou only §7(" + plus(gm.player) + ")"; case "both" -> "§eBoth §7(no change)"; default -> "§8Off §7(no change)"; };
            g.set(slots[i++], Gui.icon(Crates.resolve(gm.icon), "§f§l" + gm.name, "§7Who can use it: " + label, "", "§8Off → Both → Enemy only → You only", "§e▶ Click to change"), pl -> {
                var st = setupOf(pl);
                String cur = st.gimmicks.getOrDefault(gm.id, "off");
                st.gimmicks.put(gm.id, switch (cur) { case "off" -> "both"; case "both" -> "enemy"; case "enemy" -> "player"; default -> "off"; });
                gimmickMenu(pl);
            });
        }
        back(g, 22);
        Gui.open(p, g);
    }

    static void teamMenu(ServerPlayer p) {
        Frontier.Setup s = setupOf(p);
        Gui.Screen g = new Gui.Screen("§a§lTeam", 3);
        dress(g, 3);
        g.set(11, toggle(s.rental, Items.NAME_TAG, "§d§lRental Team", "§7Borrow a random team for your bracket.", "§7Needs an §fempty party§7. Rentals vanish when the run ends.", "§aRewards +30%"),
                pl -> { var st = setupOf(pl); st.rental = !st.rental; teamMenu(pl); });
        g.set(13, toggle(s.legendaries, Items.DRAGON_EGG, "§6§lAllow Legendaries", "§7Legendaries, mythicals, Ultra Beasts", "§7and Paradox Pokémon. §cRewards −80%"),
                pl -> { var st = setupOf(pl); st.legendaries = !st.legendaries; teamMenu(pl); });
        if (s.format.equals("coop") && !Frontier.COOP.containsKey(p.getUUID())) {
            List<String> ids = new ArrayList<>();
            for (var en : FrontierData.teams.champions.entrySet()) if (!en.getValue().secret) ids.add(en.getKey());
            var cur = s.ally == null ? null : FrontierData.teams.champions.get(s.ally);
            ItemStack ally = Gui.icon(cur == null ? Items.ARMOR_STAND : Items.PLAYER_HEAD, "§b§lChampion Partner", "§7No friend online? Team up with a Champion.", "",
                    "§7Partner: " + (cur == null ? "§cNone" : "§a" + cur.name), "§8Rewards −15% with a Champion partner", "", "§e▶ Click to change");
            g.set(15, cur != null ? Gui.glow(ally) : ally, pl -> {
                var st = setupOf(pl);
                int i = st.ally == null ? -1 : ids.indexOf(st.ally);
                st.ally = i + 1 >= ids.size() ? null : ids.get(i + 1);
                teamMenu(pl);
            });
        } else g.set(15, Gui.icon(Items.GRAY_DYE, "§8Champion Partner", "§7Pick §fCo-op Doubles§7 without a friend", "§7to team up with a Champion."));
        back(g, 22);
        Gui.open(p, g);
    }

    static Frontier.Setup daily(Frontier.Setup s) { Frontier.Setup d = s.copy(); Frontier.applyDaily(d); return d; }

    static String plus(double d) { return (d >= 0 ? "+" : "") + Math.round(d * 100) + "%"; }

    static ItemStack toggle(boolean on, net.minecraft.world.item.Item item, String name, String... lore) {
        String[] l = new String[lore.length + 2];
        System.arraycopy(lore, 0, l, 0, lore.length);
        l[lore.length] = ""; l[lore.length + 1] = on ? "§a✔ On §8· Click to turn off" : "§8Off §7· Click to turn on";
        ItemStack s = Gui.icon(item, name, l);
        return on ? Gui.glow(s) : s;
    }

    static void modifiers(ServerPlayer p) {
        Frontier.Setup s = setupOf(p);
        Gui.Screen g = screen("§e§lModifiers", 5);
        g.set(4, Gui.icon(Items.COMPARATOR, "§e§lModifiers", "§cRed§7 ones make runs harder and pay more.", "§aGreen§7 ones make them easier and pay less.", "", "§7Reward multiplier: §e" + pct(Frontier.multiplier(s))));
        int[] slots = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34};
        int i = 0;
        for (var md : FrontierData.cfg.modifiers) {
            if (i >= slots.length) break;
            boolean on = s.mods.contains(md.id);
            g.set(slots[i++], toggle(on, Crates.resolve(md.icon), (md.bonus > 0 ? "§c§l" : "§a§l") + md.name + " §7(" + plus(md.bonus) + ")", "§7" + md.desc),
                    pl -> { var st = setupOf(pl); if (!st.mods.remove(md.id)) st.mods.add(md.id); modifiers(pl); });
        }
        g.set(40, Gui.icon(Items.ARROW, "§e« Back"), FrontierGui::setup);
        Gui.open(p, g);
    }

    // ------------------------------------------------------------------ during a run

    static void continueRun(ServerPlayer p) {
        Frontier.Run r = Frontier.RUNS.get(p.getUUID());
        if (r == null) { Frontier.msg(p, "§7You're not in a run."); return; }
        switch (r.state) {
            case DOORS -> doors(p, r);
            case BUSY -> {
                switch (r.room) {
                    case "shrine" -> shrine(p, r); case "merchant" -> merchant(p, r); case "gamble" -> gamble(p, r); case "cursed" -> cursed(p, r);
                    default -> Frontier.msg(p, "§7Hang on, the next room is getting ready.");
                }
            }
            default -> Frontier.msg(p, "§7You're mid-battle. Finish it first!");
        }
    }

    static final String[] WHISPERS = {"A cold draught seeps from beneath it.", "Faint footsteps echo somewhere beyond.", "The handle is warm to the touch.",
            "Something growls, then falls silent.", "Light flickers through the keyhole.", "It smells of rain and old stone.", "You hear the hum of a crowd.",
            "Scratch marks run along the frame.", "A soft melody drifts through the cracks.", "The air here tastes of static.", "It is perfectly, unnervingly quiet.",
            "Petals are caught in the hinge."};
    static final net.minecraft.world.item.Item[] DOOR_ITEMS = {Items.DARK_OAK_DOOR, Items.CRIMSON_DOOR, Items.WARPED_DOOR, Items.IRON_DOOR, Items.SPRUCE_DOOR, Items.MANGROVE_DOOR, Items.CHERRY_DOOR, Items.BAMBOO_DOOR};
    static final String[] NUMERALS = {"I", "II", "III"};

    /** The door choice. Doors stay a mystery unless Scouting (or the Insight blessing) is on. */
    /**
     * What you sense behind each door. Every line hints at the room behind it, so experienced challengers can learn
     * to read the doors. Some lines are shared between two rooms, so a hint is never a guarantee.
     */
    static final Map<String, String[]> DOOR_HINTS = Map.ofEntries(
            Map.entry("battle", new String[]{"You hear a crowd roaring.", "Sand crunches underfoot beyond it.", "Someone is warming up on the other side.", "Faint cheering echoes behind it."}),
            Map.entry("doubles", new String[]{"Two voices argue about a strategy.", "You hear twin footsteps, perfectly in step.", "Faint cheering echoes behind it."}),
            Map.entry("elite", new String[]{"The air beyond feels heavy and cold.", "You hear slow, confident footsteps.", "Blood-red light leaks under the frame."}),
            Map.entry("champion", new String[]{"A fanfare plays somewhere far away.", "The handle is gilded and warm.", "You feel you are being watched by someone powerful."}),
            Map.entry("loot", new String[]{"Something glimmers through the keyhole.", "You hear coins shifting.", "It's heavier than it looks.", "A faint golden glow seeps out."}),
            Map.entry("heal", new String[]{"Running water trickles somewhere.", "A soft, sweet scent drifts out.", "It's warm to the touch, and calming."}),
            Map.entry("luckyroll", new String[]{"You hear dice rattling.", "Something glimmers through the keyhole."}),
            Map.entry("clover", new String[]{"It smells of fresh grass and luck.", "A soft, sweet scent drifts out."}),
            Map.entry("shrine", new String[]{"You hear a bell ring once.", "Incense smoke curls from the gap.", "Paper charms are tied to the handle."}),
            Map.entry("merchant", new String[]{"Someone is humming and counting.", "You hear a cart creak to a stop.", "You hear coins shifting."}),
            Map.entry("gamble", new String[]{"You hear dice rattling.", "Muffled music and laughter.", "A card slips out from under it."}),
            Map.entry("cursed", new String[]{"Silence. Total silence.", "The frame is cold and damp.", "Something whispers your name."}),
            Map.entry("mystery", new String[]{"You can't tell what's behind it.", "The door seems to shift as you look at it.", "Silence. Total silence."}),
            Map.entry("type_fire", new String[]{"The frame is scorched black.", "Heat pours through the cracks."}),
            Map.entry("type_water", new String[]{"Water drips from under the door.", "You hear waves breaking."}),
            Map.entry("type_grass", new String[]{"Vines curl around the handle.", "It smells of fresh grass and luck."}),
            Map.entry("type_electric", new String[]{"The handle crackles with static.", "Something hums with power."}),
            Map.entry("type_ice", new String[]{"Frost creeps across the frame.", "Your breath fogs in front of it."}),
            Map.entry("type_fighting", new String[]{"You hear grunts and heavy blows.", "Someone shouts a battle cry."}),
            Map.entry("type_poison", new String[]{"A sickly smell seeps out.", "The handle is sticky and purple."}),
            Map.entry("type_ground", new String[]{"Sand spills from under the door.", "The floor trembles slightly."}),
            Map.entry("type_flying", new String[]{"A strong wind whistles through the gaps.", "You hear wings beating."}),
            Map.entry("type_psychic", new String[]{"You get a sudden headache.", "The door seems to shift as you look at it."}),
            Map.entry("type_bug", new String[]{"Something skitters behind it.", "Silk threads stretch across the frame."}),
            Map.entry("type_rock", new String[]{"Pebbles rattle down the frame.", "The floor trembles slightly."}),
            Map.entry("type_ghost", new String[]{"The air turns cold.", "Something whispers your name."}),
            Map.entry("type_dragon", new String[]{"You hear a deep, ancient roar.", "Scales are scratched into the wood."}),
            Map.entry("type_dark", new String[]{"No light at all comes from the gap.", "You hear footsteps that stop when you do."}),
            Map.entry("type_steel", new String[]{"Metal clanks rhythmically behind it.", "The handle is ice-cold steel."}),
            Map.entry("type_fairy", new String[]{"Giggling. Then nothing.", "Sparkles drift out from under it."}));

    static String hintFor(Frontier.Door d, java.util.Random rnd) {
        String key = d.hidden ? "mystery" : d.room.equals("type") && d.type != null ? "type_" + d.type : d.room;
        String[] pool = DOOR_HINTS.getOrDefault(key, DOOR_HINTS.get("battle"));
        return "§7§o" + pool[rnd.nextInt(pool.length)];
    }

    static final net.minecraft.world.item.Item[] DOOR_ICONS = {Items.DARK_OAK_DOOR, Items.CRIMSON_DOOR, Items.WARPED_DOOR, Items.IRON_DOOR, Items.SPRUCE_DOOR};

    static void doors(ServerPlayer p, Frontier.Run r) {
        boolean sight = r.setup.mods.contains("scouting") || r.boons.contains("insight");
        Gui.Screen g = new Gui.Screen("§5§lChoose Your Path §8· §7Room " + (r.floor + 1), 3);
        for (int i = 0; i < 27; i++) g.set(i, Gui.icon(i % 2 == 0 ? Items.BLACK_STAINED_GLASS_PANE : Items.PURPLE_STAINED_GLASS_PANE, " "));
        int[] slots = r.doors.size() == 3 ? new int[]{11, 13, 15} : new int[]{11, 15};
        java.util.Random rnd = new java.util.Random(r.player.getMostSignificantBits() ^ (r.floor * 7919L));
        for (int i = 0; i < r.doors.size() && i < slots.length; i++) {
            Frontier.Door d = r.doors.get(i);
            var room = FrontierData.room(d.room);
            List<String> lore = new ArrayList<>();
            String title = "§5§lDoor " + ROMAN[i];
            if (sight && !d.hidden) {
                lore.add("§bYour sight reveals:");
                lore.add("§f" + (room == null ? d.room : room.name) + (d.type != null ? " §7(" + Frontier.cap(d.type) + ")" : ""));
                if (d.champion != null && FrontierData.teams.champions.containsKey(d.champion)) lore.add("§6" + FrontierData.teams.champions.get(d.champion).name + " §7waits inside.");
                if (d.preview != null) for (Set6 s6 : d.preview) lore.add("§8 • §7" + Frontier.cap(s6.s));
            } else {
                lore.add(hintFor(d, rnd));
                lore.add("");
                lore.add("§8What lies beyond is unknown.");
                if (!sight) lore.add("§8§o(Scouting would reveal it.)");
            }
            lore.add(""); lore.add("§d▶ Step through");
            final int idx = i;
            g.set(slots[i], Gui.icon(DOOR_ICONS[rnd.nextInt(DOOR_ICONS.length)], title, lore.toArray(new String[0])), pl -> Frontier.chooseDoor(pl, idx));
        }
        g.set(4, Gui.icon(Items.ENDER_EYE, "§d§lRoom " + (r.floor + 1), "§7Pot so far: §6" + Fmt.cd(r.potCd) + " §7and §b" + r.potBp + " BP", "§7Reward multiplier: §e" + pct(r.mult)));
        g.set(22, Gui.icon(Items.OAK_DOOR, "§c§lLeave the Frontier", "§7Walk away with your rewards so far:", "§6" + Fmt.cd(r.potCd) + " §7and §b" + r.potBp + " BP", "", "§c▶ Leave the run"),
                pl -> { pl.closeContainer(); Frontier.finish(r, pl, false, "§7You left the run."); });
        Gui.open(p, g);
        p.sendSystemMessage(Component.literal("§5§l[Frontier] §r§7The doors await. ").append(Component.literal("§d§n[Choose a door]").withStyle(st -> st.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/frontier continue")))));
    }

    static void shrine(ServerPlayer p, Frontier.Run r) {
        Gui.Screen g = new Gui.Screen("§6§lShrine §8· §7Pick a Blessing", 3);
        g.frame(Items.YELLOW_STAINED_GLASS_PANE, Items.WHITE_STAINED_GLASS_PANE);
        List<FrontierData.Boon> pool = new ArrayList<>();
        for (var b : FrontierData.cfg.boons) if (!r.boons.contains(b.id)) pool.add(b);
        Collections.shuffle(pool, new java.util.Random(r.player.getLeastSignificantBits() ^ r.floor));
        int[] slots = {11, 13, 15};
        for (int i = 0; i < Math.min(3, pool.size()); i++) {
            var b = pool.get(i);
            g.set(slots[i], Gui.icon(Crates.resolve(b.icon), "§6§l" + b.name, "§7" + b.desc, "", "§e▶ Click to choose"), pl -> {
                if (r.state == Frontier.State.ENDED || !r.room.equals("shrine")) return;
                r.boons.add(b.id); r.room = "shrine_done"; pl.closeContainer(); Frontier.msg(pl, "§6Blessing gained: §f" + b.name); Frontier.nextAfterEffect(r);
            });
        }
        if (pool.isEmpty()) { Frontier.msg(p, "§7You already have every blessing."); r.room = "shrine_done"; Frontier.nextAfterEffect(r); return; }
        Gui.open(p, g);
    }

    static void merchant(ServerPlayer p, Frontier.Run r) {
        Gui.Screen g = new Gui.Screen("§a§lWandering Merchant", 3);
        g.frame(Items.GREEN_STAINED_GLASS_PANE, Items.LIME_STAINED_GLASS_PANE);
        String id = p.getUUID().toString();
        int[] slots = {11, 12, 13};
        int i = 0;
        for (var m : FrontierData.cfg.merchant) {
            if (i >= slots.length) break;
            g.set(slots[i++], Gui.icon(Crates.resolve(m.icon), "§f§l" + m.name, "§7Costs §b" + m.bp + " BP §8(you have " + FrontierData.bp(id) + ")", "", "§e▶ Click to buy"), pl -> {
                if (r.state == Frontier.State.ENDED || !r.room.equals("merchant")) return;
                if (!FrontierData.takeBp(pl.getUUID().toString(), m.bp)) { Frontier.msg(pl, "§cNot enough BP."); return; }
                switch (m.id) {
                    case "heal" -> { for (Object pk : Frontier.partyList(r)) Cob.heal(pk); }
                    case "revive" -> { for (Object pk : Frontier.partyList(r)) Cob.revive(pk); }
                    case "skip" -> r.skipNext = true;
                    default -> {}
                }
                Frontier.msg(pl, "§aBought: §f" + m.name);
                merchant(pl, r);
            });
        }
        g.set(15, Gui.icon(Items.OAK_DOOR, "§e§lMove on"), pl -> { if (!r.room.equals("merchant")) return; r.room = "merchant_done"; pl.closeContainer(); Frontier.nextAfterEffect(r); });
        Gui.open(p, g);
    }

    static void gamble(ServerPlayer p, Frontier.Run r) {
        Gui.Screen g = new Gui.Screen("§6§lGamble Room", 3);
        g.frame(Items.ORANGE_STAINED_GLASS_PANE, Items.YELLOW_STAINED_GLASS_PANE);
        g.set(11, Gui.glow(Gui.icon(Items.GOLD_INGOT, "§6§lDouble or nothing", "§7Bet this run's rewards so far:", "§6" + Fmt.cd(r.potCd) + " §7and §b" + r.potBp + " BP", "", "§750/50: double them, or lose them all.", "", "§e▶ Click to bet")), pl -> {
            if (!r.room.equals("gamble")) return;
            r.room = "gamble_done"; pl.closeContainer();
            if (Frontier.RNG.nextBoolean()) {
                r.potCd *= 2; r.potBp *= 2; r.gambleStreak++; Frontier.savePot(r);
                FrontierData.addCounter(pl.getUUID().toString(), "gamblesWon", 1);
                Frontier.msg(pl, "§a§lYou won! §r§7Your rewards so far are doubled.");
                if (r.gambleStreak >= 3) Frontier.award(pl, "f_lucky_star", "feat_lucky_star");
            } else { r.potCd = 0; r.potBp = 0; r.gambleStreak = 0; Frontier.savePot(r); FrontierData.addCounter(pl.getUUID().toString(), "gamblesLost", 1); Frontier.msg(pl, "§c§lYou lost! §r§7This run's rewards so far are gone."); }
            Frontier.nextAfterEffect(r);
        });
        g.set(15, Gui.icon(Items.OAK_DOOR, "§e§lWalk away", "§7Keep what you have."), pl -> { if (!r.room.equals("gamble")) return; r.room = "gamble_done"; pl.closeContainer(); Frontier.nextAfterEffect(r); });
        Gui.open(p, g);
    }

    static void cursed(ServerPlayer p, Frontier.Run r) {
        Gui.Screen g = new Gui.Screen("§5§lCursed Room", 3);
        g.frame(Items.PURPLE_STAINED_GLASS_PANE, Items.BLACK_STAINED_GLASS_PANE);
        g.set(11, Gui.glow(Gui.icon(Items.WITHER_ROSE, "§5§lAccept the curse", "§7For the next 5 battles, opponents are", "§73 levels higher, but pay §e+40%§7.", "", "§e▶ Click to accept")), pl -> {
            if (!r.room.equals("cursed")) return; r.room = "cursed_done"; r.curse = 5; pl.closeContainer(); Frontier.msg(pl, "§5The curse takes hold..."); Frontier.nextAfterEffect(r);
        });
        g.set(15, Gui.icon(Items.OAK_DOOR, "§e§lWalk away"), pl -> { if (!r.room.equals("cursed")) return; r.room = "cursed_done"; pl.closeContainer(); Frontier.nextAfterEffect(r); });
        Gui.open(p, g);
    }

    // ------------------------------------------------------------------ guide + stats

    // ------------------------------------------------------------------ admin debugging

    static final List<String> DEBUG_ROOMS = List.of("battle", "type", "doubles", "elite", "champion", "brain", "loot", "heal", "luckyroll", "clover", "shrine", "merchant", "gamble", "cursed", "mystery");

    static List<String> debugExtras(String room) {
        return switch (room) {
            case "type" -> List.of("normal", "fire", "water", "grass", "electric", "ice", "fighting", "poison", "ground", "flying", "psychic", "bug", "rock", "ghost", "dragon", "dark", "steel", "fairy");
            case "champion" -> new ArrayList<>(FrontierData.teams.champions.keySet());
            case "brain" -> new ArrayList<>(FrontierData.teams.brains.keySet());
            default -> List.of();
        };
    }

    static void debugInfo(ServerPlayer p) {
        Frontier.Run r = Frontier.RUNS.get(p.getUUID());
        if (r == null) { Frontier.msg(p, "§e[Debug] §7You're not in a run."); return; }
        Frontier.Setup s = r.setup;
        Frontier.msg(p, "§e§l[Debug] §fRun §7state §f" + r.state + " §8· §7room §f" + (r.floor + 1) + " §8· §7now in §f" + r.room + (r.roomType != null ? " (" + r.roomType + ")" : "")
                + (r.champion != null ? " §8· §7Champion §f" + r.champion : "") + (r.brain != null ? " §8· §7Brain §f" + r.brain : ""));
        String lastFile = FrontierRooms.LAST;
        if (lastFile != null && !lastFile.startsWith("hub")) {
            String key = lastFile.substring(0, lastFile.lastIndexOf('_', lastFile.lastIndexOf('_') - 1));
            String sd = lastFile.substring(lastFile.length() - 1);
            Frontier.msg(p, "§7Design: §f" + DesignSave.friendly(key) + " §8(" + lastFile + ", " + (sd.equals("l") ? "left" : sd.equals("r") ? "right" : "center") + " door"
                    + (java.nio.file.Files.exists(DesignSave.ROOMS.resolve(lastFile + ".bin")) ? ", your saved version" : "") + ")");
        }
        Frontier.msg(p, "§7Setup: §f" + s.bracket + " / " + s.format + " / " + s.length + (s.rental ? " §d(rental)" : "") + (s.legendaries ? " §6(legendaries)" : "") + (s.daily ? " §a(daily)" : ""));
        StringBuilder mods = new StringBuilder();
        for (String id : s.mods) { var md = FrontierData.modifier(id); mods.append(md == null ? id : md.name + " " + plus(md.bonus)).append("§8, §f"); }
        Frontier.msg(p, "§7Modifiers: §f" + (mods.length() == 0 ? "none" : mods.substring(0, mods.length() - 6)));
        StringBuilder gm = new StringBuilder();
        for (var g : s.gimmicks.entrySet()) gm.append(g.getKey()).append("=").append(g.getValue()).append(" ");
        Frontier.msg(p, "§7Gimmicks: §f" + gm + "§8· §7enemy may use: §f" + Frontier.enemyGimmicks(r));
        Frontier.msg(p, "§7Reward ×§f" + String.format(Locale.ROOT, "%.2f", r.mult) + " §8(" + "format, gimmicks, modifiers, rental, legendaries) §8· §7streak §f" + r.streak + " §8· §7clean rooms §f" + r.cleanRooms
                + " §8· §7curse §f" + r.curse + " §8· §7clover §f" + r.clover + " §8· §7extra loot rolls §f" + r.extraRolls);
        Frontier.msg(p, "§7Boons: §f" + (r.boons.isEmpty() ? "none" : String.join(", ", r.boons)) + " §8· §7second chance used §f" + r.secondChanceUsed);
        Frontier.msg(p, "§7Pot §6" + Fmt.cd(r.potCd) + " §7/ §b" + r.potBp + " BP §8· §7team §f" + r.team.size() + " §8· §7enemy team size §f" + Frontier.enemyTeamSize(r)
                + " §8· §7rooms today §f" + FrontierData.daily(p.getUUID().toString(), "rooms") + "/" + FrontierData.cfg.dailySoftCap + " §8· §7plot §f" + r.plot
                + (r.partner != null ? " §8· §7partner §f" + r.partnerName : "") + (r.ally != null ? " §8· §7ally §f" + r.ally : ""));
        String[] f = Frontier.FORCE_NEXT.get(r.player);
        if (f != null) Frontier.msg(p, "§7Forced next room: §f" + f[0] + (f[1] != null ? " (" + f[1] + ")" : ""));
    }

    static final List<String> SIDE_WORDS = List.of("center", "left", "right", "front");
    static int debugNext(ServerPlayer p, String room, String extra) { return debugNext(p, room, extra, null); }
    static int debugNext(ServerPlayer p, String room, String extra, String side) {
        Frontier.Run r = Frontier.RUNS.get(p.getUUID());
        if (r == null) { Frontier.msg(p, "§cYou're not in a run."); return 0; }
        // a specific battle room (battle_port, battle_dojo...) and/or a door side
        if (DesignSave.BATTLE.containsKey(room)) { FrontierRooms.FORCE_KEY = DesignSave.BATTLE.get(room); room = "battle"; }
        if (extra != null && SIDE_WORDS.contains(extra)) { side = extra; extra = null; }
        if (side != null) FrontierRooms.FORCE_SIDE = switch (side) { case "left" -> 1; case "right" -> 2; default -> 0; };
        if (!DEBUG_ROOMS.contains(room)) { FrontierRooms.FORCE_KEY = null; FrontierRooms.FORCE_SIDE = -1; Frontier.msg(p, "§cUnknown room. Try: §f" + String.join(", ", DEBUG_ROOMS) + ", " + String.join(", ", DesignSave.BATTLE.keySet())); return 0; }
        Frontier.FORCE_NEXT.put(r.player, new String[]{room, extra});
        Frontier.msg(p, "§e[Debug] §7The next room will be §f" + room + (extra != null ? " (" + extra + ")" : "") + "§7, whichever door is picked.");
        if (r.state == Frontier.State.DOORS) { Frontier.afterRoom(r); }
        return 1;
    }

    static int debugEnd(ServerPlayer p, boolean won) {
        Frontier.Run r = Frontier.RUNS.get(p.getUUID());
        if (r == null || r.state != Frontier.State.BATTLE) { Frontier.msg(p, "§cYou're not in a Frontier battle."); return 0; }
        Object battle = Cob.battleOf(p);
        Frontier.msg(p, "§e[Debug] §7Ending the battle as a " + (won ? "§aWin" : "§cLoss") + "§7.");
        Cob.writeBack(r.pairs); r.pairs.clear();      // HP as it stands right now, before the battle is torn down
        Cob.endBattle(battle);
        Frontier.battleOver(r, won);
        return 1;
    }

    static int debugBuild(ServerPlayer p, String design, int variant) {
        ServerLevel lvl = Frontier.frontier();
        if (lvl == null) { Frontier.msg(p, "§cThe Frontier dimension isn't loaded."); return 0; }
        int n = FrontierRooms.variants().getOrDefault(design, 0);
        if (n == 0) { Frontier.msg(p, "§cUnknown design. Try: §f" + String.join(", ", FrontierRooms.variants().keySet())); return 0; }
        int v = variant < 0 ? new Random().nextInt(n) : Math.min(variant, n - 1);
        BlockPos o = new BlockPos(990 * Frontier.PLOT_SPACING, Frontier.ROOM_Y, 0);     // a plot no run ever uses
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int x = -Frontier.ROOM_PAD; x < Frontier.ROOM_SIZE + Frontier.ROOM_PAD; x++) for (int y = -12; y < Frontier.ROOM_HEIGHT; y++) for (int z = -Frontier.ROOM_PAD; z < Frontier.ROOM_SIZE + Frontier.ROOM_PAD; z++) {
            m.set(o.getX() + x, o.getY() + y, o.getZ() + z);
            if (!lvl.getBlockState(m).isAir()) lvl.setBlock(m, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), net.minecraft.world.level.block.Block.UPDATE_CLIENTS);
        }
        BlockPos[] spots = FrontierRooms.buildDesign(lvl, o, design, v);
        if (spots == null) { Frontier.msg(p, "§cThat design couldn't be built. Check the server log."); return 0; }
        p.teleportTo(lvl, spots[0].getX() + 0.5, spots[0].getY(), spots[0].getZ() + 0.5, -90f, 0f);
        Frontier.msg(p, "§e[Debug] §7Built §f" + design + " §7(variation " + (v + 1) + " of " + n + "). §8Leave with /warp frontier.");
        return 1;
    }

    /** Where the hub is built: far from every run plot. */
    static final BlockPos HUB = new BlockPos(-4096, 64, -60);

    /**
     * Builds the whole Battle Frontier hub (island, Tower, lobby, the seven facilities), then sets it up: the lobby
     * point, the /warp frontier spot, the Receptionist and BP Clerk, and the two featured leaderboards.
     */
    static int buildHub(ServerPlayer p) {
        ServerLevel lvl = Frontier.frontier();
        if (lvl == null) { Frontier.msg(p, "§cThe Frontier dimension isn't loaded."); return 0; }
        Frontier.msg(p, "§e[Frontier] §7Building the hub. This takes a few seconds...");
        FrontierRooms.clearArea(lvl, HUB, "hub_0", 12);       // removes anything from an earlier build (the old facilities included)
        for (var en : lvl.getEntities((net.minecraft.world.entity.Entity) null, new net.minecraft.world.phys.AABB(HUB).inflate(140), en -> !(en instanceof ServerPlayer))) en.discard();
        BlockPos[] spots = FrontierRooms.buildDesign(lvl, HUB, "hub", 0);
        if (spots == null) { Frontier.msg(p, "§cThe hub couldn't be built. Check the server log."); return 0; }
        Map<String, int[]> mk = FrontierRooms.markers(lvl, "hub_0");
        java.util.function.Function<String, BlockPos> at = k -> { int[] v = mk.get(k); return v == null ? null : HUB.offset(v[0], v[1], v[2]); };
        // lobby: just inside the doors, facing the desk
        BlockPos lob = at.apply("lobby");
        if (lob != null) {
            var sp = new FrontierData.Spot(); sp.dim = Frontier.DIM; sp.x = lob.getX() + 0.5; sp.y = lob.getY(); sp.z = lob.getZ() + 0.5; sp.yaw = 180f;
            FrontierData.data.lobby = sp; FrontierData.save();
        }
        // /warp frontier lands on the arrival pad, facing the Tower
        BlockPos arr = at.apply("arrival");
        if (arr != null) {
            Teleports.Warp w = Teleports.data.warps.getOrDefault("frontier", new Teleports.Warp());
            w.name = "frontier"; w.dim = Frontier.DIM; w.x = arr.getX() + 0.5; w.y = arr.getY(); w.z = arr.getZ() + 0.5; w.yaw = 180f; w.pitch = 0;
            if (w.icon == null || w.icon.equals("minecraft:ender_pearl")) w.icon = "minecraft:nether_star";
            if (w.display == null || w.display.isEmpty()) w.display = "&b&l✦ &f&lBattle Frontier &b&l✦";
            if (w.description == null || w.description.isEmpty()) w.description = "Climb the Battle Tower for BP, titles and glory.";
            Teleports.data.warps.put("frontier", w); Teleports.save();
        }
        // the two staff NPCs behind the desk
        // the staff: receptionist (facing the door) and the BP Exchange clerk (west desk). Rhea now works in Port Azure.
        for (Object[] npc : new Object[][]{{"receptionist", "frontier_receptionist", 0f}, {"clerk", "frontier_clerk", -90f}}) {
            BlockPos q = at.apply((String) npc[0]);
            if (q == null) continue;
            String cls = (String) npc[1];
            for (var old : lvl.getEntities((net.minecraft.world.entity.Entity) null, new net.minecraft.world.phys.AABB(q).inflate(3), en -> en.getTags().contains("jcl_npc_" + cls))) old.discard();
            Npcs.spawn(lvl, cls, q.getX() + 0.5, q.getY(), q.getZ() + 0.5, (float) npc[2], "jcl_npc", "jcl_npc_" + cls);
        }
        // featured leaderboards on either side of the hall
        String[] boards = {"master:singles", "master:doubles"};
        for (String b : boards) FrontierBoards.remove(b);          // rebuilding never leaves a second copy
        for (int i = 0; i < 2; i++) {
            BlockPos q = at.apply("board" + (i + 1));
            if (q == null) continue;
            p.teleportTo(lvl, q.getX() + 0.5, q.getY(), q.getZ() + 0.5, i == 0 ? -90f : 90f, 0f);
            try { FrontierBoards.place(boards[i], p); } catch (Exception ex) { GtsMod.LOG.error("[JCL] Could not place a Frontier board", ex); }
        }
        if (arr != null) p.teleportTo(lvl, arr.getX() + 0.5, arr.getY(), arr.getZ() + 0.5, 180f, 0f);
        Frontier.msg(p, "§a[Frontier] §fThe Battle Frontier is built! §7Lobby, §f/warp frontier§7, the Receptionist, the BP Clerk and two leaderboards are all set.");
        return 1;
    }

    static String tick(boolean done) { return done ? "§a✔ " : "§8✘ "; }

    /** The guide: one page per kind of reward, each title with its own icon and how to earn it. */
    static void guide(ServerPlayer p) {
        Gui.Screen g = screen("§b§lFrontier Guide", 3);
        g.set(4, Gui.icon(Items.BOOK, "§b§lFrontier Guide", "§7Everything the Frontier can award you."));
        g.set(10, Gui.icon(Items.GOLD_INGOT, "§6§lFrontier Brains", "§7Silver and Gold Symbols", "", "§e▶ Open"), FrontierGui::guideBrains);
        g.set(12, Gui.icon(Crates.resolve("cobblemon:ultra_ball"), "§b§lBracket Titles", "§7One for each bracket", "", "§e▶ Open"), FrontierGui::guideBrackets);
        g.set(14, Gui.icon(Items.NAME_TAG, "§e§lChallenge Titles", "§7Feats for the bold", "", "§e▶ Open"), FrontierGui::guideChallenges);
        g.set(16, Gui.icon(Items.CLOCK, "§a§lDaily & Seasonal", "§7Infinite milestones, seasons", "§7and the Daily Challenge", "", "§e▶ Open"), FrontierGui::guideSeasonal);
        g.set(22, Gui.icon(Items.ARROW, "§e« Back"), FrontierGui::setup);
        Gui.open(p, g);
    }

    static void guideBrains(ServerPlayer p) {
        String id = p.getUUID().toString();
        Gui.Screen g = screen("§6§lFrontier Brains", 4);
        int[] slots = {10, 11, 12, 13, 14, 15, 16};
        int i = 0;
        for (var en : FrontierData.teams.brains.entrySet()) {
            boolean s = FrontierData.hasSymbol(id, en.getKey() + ":silver"), gd = FrontierData.hasSymbol(id, en.getKey() + ":gold");
            g.set(slots[i++], Gui.icon(gd ? Items.GOLD_INGOT : s ? Items.IRON_INGOT : Items.GRAY_DYE, "§f§l" + en.getValue().name,
                    "§7The " + en.getValue().symbol + " Symbol", "",
                    tick(s) + "§7Silver Symbol: §fbeat them on room 21", "§8   of a Standard or Marathon run",
                    tick(gd) + "§7Gold Symbol: §fbeat them on room 49", "§8   of a Marathon run"));
        }
        g.set(22, Gui.icon(Items.NETHER_STAR, "§6§lAll Seven", "§7Every Silver Symbol: §fFrontier Challenger", "§7Every Gold Symbol: §6✦ Frontier Legend ✦"));
        g.set(31, Gui.icon(Items.ARROW, "§e« Back"), FrontierGui::guide);
        Gui.open(p, g);
    }

    static void guideBrackets(ServerPlayer p) {
        String id = p.getUUID().toString();
        Gui.Screen g = screen("§b§lBracket Titles", 3);
        String[][] t = {{"rookie", "Rookie Climber"}, {"junior", "Junior Ace"}, {"challenger", "Rising Star"}, {"ace", "Ace of the Frontier"}, {"master", "Frontier Master"}, {"little", "Little Legend"}, {"flat50", "Even Match"}};
        int slot = 10;
        for (String[] x : t) {
            var b = FrontierData.bracket(x[0]);
            boolean done = FrontierData.cleared(id, x[0] + ":standard");
            g.set(slot++, Gui.icon(done ? Items.LIME_DYE : Crates.resolve(b.icon), "§f§l" + x[1], "§7Clear a §fStandard§7 run in the §f" + b.name + "§7.", "", tick(done) + (done ? "§aEarned" : "§7Not yet")));
        }
        boolean mm = FrontierData.cleared(id, "master:marathon");
        g.set(22, Gui.icon(Items.ARROW, "§e« Back"), FrontierGui::guide);
        g.set(4, Gui.icon(Items.CLOCK, "§6§lMarathon Master", "§7Clear a §fMarathon§7 in the §fMaster Cup§7.", "", tick(mm) + (mm ? "§aEarned" : "§7Not yet")));
        Gui.open(p, g);
    }

    static void guideChallenges(ServerPlayer p) {
        String id = p.getUUID().toString();
        Gui.Screen g = screen("§e§lChallenge Titles", 3);
        Object[][] t = {
            {"f_gauntlet_runner", "Gauntlet Runner", Items.NETHERITE_SWORD, "Clear a Standard run with all four", "gimmicks set to Enemy Only."},
            {"f_glass_cannon", "Glass Cannon", Items.WITHER_SKELETON_SKULL, "Clear a Standard run with", "Sudden Death on."},
            {"f_iron_will", "Iron Will", Items.ANVIL, "Clear a Standard run with", "No Healing and Full Teams on."},
            {"f_brainbuster", "Brainbuster", Items.DIAMOND, "Beat a Frontier Brain without", "a single Pokémon fainting."},
            {"f_champion_slayer", "Champion Slayer", Items.GOLDEN_HELMET, "Win 10 Champion Rooms.", ""},
            {"f_lucky_star", "Lucky Star", Items.GOLD_NUGGET, "Win a Gamble Room three", "times in a row."},
            {"f_rental_royalty", "Rental Royalty", Items.NAME_TAG, "Clear a Marathon with", "a Rental Team."}};
        int slot = 10;
        for (Object[] x : t) {
            boolean done = Titles.data.unlocked.getOrDefault(id, java.util.Set.of()).contains((String) x[0]);
            g.set(slot++, Gui.icon((net.minecraft.world.item.Item) x[2], "§e§l" + x[1], "§7" + x[3], "§7" + x[4], "", tick(done) + (done ? "§aEarned" : "§7Not yet")));
        }
        g.set(22, Gui.icon(Items.ARROW, "§e« Back"), FrontierGui::guide);
        Gui.open(p, g);
    }

    static void guideSeasonal(ServerPlayer p) {
        String id = p.getUUID().toString();
        int best = 0;
        for (var board : FrontierData.data.best.values()) best = Math.max(best, board.getOrDefault(id, 0));
        Gui.Screen g = screen("§a§lDaily & Seasonal", 3);
        int[] at = {25, 50, 100, 200}; String[] names = {"Endurance Trainer", "Unbreakable", "Endless", "Beyond the Frontier"};
        for (int i = 0; i < 4; i++)
            g.set(10 + i, Gui.icon(best >= at[i] ? Items.ENDER_EYE : Items.ENDER_PEARL, "§d§l" + names[i], "§7Reach room §f" + at[i] + "§7 on an Infinite run.", "§7Your best: §f" + best, "", tick(best >= at[i]) + (best >= at[i] ? "§aEarned" : "§7Not yet")));
        g.set(14, Gui.icon(Items.CLOCK, "§a§lDaily Victor", "§7Finish the day at the top", "§7of the Daily Challenge board."));
        g.set(15, Gui.icon(Items.GOLD_BLOCK, "§6§lSeason Champion", "§7Finish a monthly season in §f1st§7", "§7on any Infinite leaderboard."));
        g.set(16, Gui.icon(Items.IRON_BLOCK, "§f§lSeason Finalist", "§7Finish a monthly season in", "§f2nd §7or §f3rd§7 on any Infinite leaderboard."));
        g.set(22, Gui.icon(Items.ARROW, "§e« Back"), FrontierGui::guide);
        Gui.open(p, g);
    }

    static String ratio(int w, int l) { return l == 0 ? (w == 0 ? "—" : w + ".00") : String.format(Locale.ROOT, "%.2f", (double) w / l); }

    /** Your Frontier record, as a menu. */
    static void stats(ServerPlayer p) {
        String id = p.getUUID().toString();
        java.util.function.ToIntFunction<String> c = k -> FrontierData.counter(id, k);
        int w = c.applyAsInt("battlesWon"), l = c.applyAsInt("battlesLost"), runs = c.applyAsInt("runs"), cl = c.applyAsInt("runsCleared");
        Gui.Screen g = screen("§f§lYour Frontier Record", 5);
        g.set(4, Gui.icon(Items.PLAYER_HEAD, "§f§l" + p.getGameProfile().getName(), "§7BP balance: §b" + FrontierData.bp(id)));
        g.set(10, Gui.icon(Items.IRON_SWORD, "§a§lBattles", "§7Won: §a" + w, "§7Lost: §c" + l, "§7Win/Loss ratio: §f" + ratio(w, l),
                "§7Win rate: §f" + (w + l == 0 ? "—" : Math.round(100.0 * w / (w + l)) + "%")));
        g.set(12, Gui.icon(Items.OAK_DOOR, "§b§lRuns", "§7Started: §f" + runs, "§7Cleared: §f" + cl, "§7Clear rate: §f" + (runs == 0 ? "—" : Math.round(100.0 * cl / runs) + "%"),
                "§7Rental runs: §f" + c.applyAsInt("rentalRuns"), "§7Co-op runs: §f" + c.applyAsInt("coopRuns")));
        g.set(14, Gui.icon(Items.ENDER_EYE, "§d§lRooms", "§7Rooms cleared: §f" + c.applyAsInt("roomsTotal"), "§7Longest run: §f" + c.applyAsInt("bestRun") + " rooms",
                "§7Longest perfect streak: §f" + c.applyAsInt("bestStreak") + " rooms", "§7Rooms today: §f" + FrontierData.daily(id, "rooms")));
        g.set(16, Gui.icon(Items.GOLD_INGOT, "§6§lEarnings", "§7CobbleDollars earned: §6" + Fmt.cd(c.applyAsInt("cdEarnedK") * 1000L), "§7BP earned: §b" + c.applyAsInt("bpEarned")));
        int sym = FrontierData.data.symbols.getOrDefault(id, java.util.Set.of()).size();
        g.set(28, Gui.icon(Items.GOLDEN_HELMET, "§6§lChampions & Brains", "§7Champion Rooms won: §f" + c.applyAsInt("championWins"), "§7Brains beaten: §f" + c.applyAsInt("brainsBeaten"),
                "§7Symbols: §f" + sym + "§7/§f" + FrontierData.teams.brains.size() * 2));
        g.set(30, Gui.icon(Items.GOLD_NUGGET, "§e§lLuck", "§7Gambles won: §a" + c.applyAsInt("gamblesWon"), "§7Gambles lost: §c" + c.applyAsInt("gamblesLost"), "§7Loot Rooms opened: §f" + c.applyAsInt("lootRooms")));
        List<String> inf = new ArrayList<>();
        for (var board : FrontierData.data.best.entrySet()) { Integer v = board.getValue().get(id); if (v != null) inf.add("§7" + FrontierBoards.title(board.getKey()) + ": §f" + v); }
        if (inf.isEmpty()) inf.add("§8No Infinite runs yet.");
        g.set(32, Gui.icon(Items.END_CRYSTAL, "§5§lInfinite Bests", inf.toArray(new String[0])));
        g.set(34, Gui.icon(Items.NAME_TAG, "§f§lTitles", "§7Open the guide to see", "§7every Frontier title."), FrontierGui::guide);
        g.set(40, Gui.icon(Items.ARROW, "§e« Back"), FrontierGui::setup);
        Gui.open(p, g);
    }

    // ------------------------------------------------------------------ commands

    @SubscribeEvent
    public static void onCommands(RegisterCommandsEvent e) {
        var boards = (com.mojang.brigadier.suggestion.SuggestionProvider<net.minecraft.commands.CommandSourceStack>) (c, b) -> {
            List<String> keys = new ArrayList<>();
            for (var br : FrontierData.cfg.brackets) for (var f : FrontierData.cfg.formats) keys.add(br.id + ":" + f.id);
            return SharedSuggestionProvider.suggest(keys, b);
        };
        e.getDispatcher().register(Commands.literal("frontier")
                .executes(c -> { ServerPlayer p = c.getSource().getPlayerOrException();
                    if (Teleports.frontierLocked(p, "frontier")) { Frontier.msg(p, "§cYou can't enter the Battle Frontier yet. Finish the §e\"Ticket to the Frontier\" §cjob first: talk to §eCaptain Marlo§c at the ferry pier."); return 0; }
                    if (!Frontier.nearLobby(p) && !Frontier.inRun(p)) { Frontier.msg(p, "§7Go to the Battle Frontier first: §f/warp frontier"); return 0; }
                    setup(p); return 1; })
                .then(Commands.literal("coop")
                        .then(Commands.literal("invite").then(Commands.argument("player", EntityArgument.player()).executes(c -> {
                            ServerPlayer p = c.getSource().getPlayerOrException(), t = EntityArgument.getPlayer(c, "player");
                            if (t == p) { Frontier.msg(p, "§cYou can't invite yourself."); return 0; }
                            if (Frontier.COOP.containsKey(p.getUUID())) { Frontier.msg(p, "§cYou already have a partner. Use §f/frontier coop leave§c first."); return 0; }
                            Frontier.INVITES.put(t.getUUID(), p.getUUID());
                            Frontier.msg(p, "§7Invited §f" + t.getGameProfile().getName() + "§7 to a co-op run.");
                            t.sendSystemMessage(Component.literal("§b§l[Frontier] §r§f" + p.getGameProfile().getName() + "§7 wants to team up for a co-op run. ")
                                    .append(Component.literal("§a§n[Accept]").withStyle(st -> st.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/frontier coop accept")))));
                            return 1; })))
                        .then(Commands.literal("accept").executes(c -> {
                            ServerPlayer p = c.getSource().getPlayerOrException();
                            java.util.UUID from = Frontier.INVITES.remove(p.getUUID());
                            ServerPlayer leader = from == null ? null : Frontier.server.getPlayerList().getPlayer(from);
                            if (leader == null) { Frontier.msg(p, "§7You don't have a co-op invite."); return 0; }
                            if (Frontier.COOP.containsKey(leader.getUUID()) || Frontier.COOP.containsKey(p.getUUID())) { Frontier.msg(p, "§cOne of you already has a partner."); return 0; }
                            Frontier.COOP.put(leader.getUUID(), p.getUUID()); Frontier.COOP.put(p.getUUID(), leader.getUUID());
                            setupOf(leader).format = "coop";
                            Frontier.msg(p, "§aYou teamed up with §f" + leader.getGameProfile().getName() + "§a. They set up the run and choose the doors.");
                            Frontier.msg(leader, "§f" + p.getGameProfile().getName() + "§a joined you! Choose §fCo-op Doubles§a in §f/frontier§a and start.");
                            return 1; }))
                        .then(Commands.literal("leave").executes(c -> {
                            ServerPlayer p = c.getSource().getPlayerOrException();
                            java.util.UUID mate = Frontier.COOP.remove(p.getUUID());
                            if (mate == null) { Frontier.msg(p, "§7You don't have a co-op partner."); return 0; }
                            Frontier.COOP.remove(mate);
                            Frontier.msg(p, "§7You left your co-op team.");
                            ServerPlayer m = Frontier.server.getPlayerList().getPlayer(mate); if (m != null) Frontier.msg(m, "§7Your co-op partner left the team.");
                            return 1; })))
                .then(Commands.literal("continue").executes(c -> { continueRun(c.getSource().getPlayerOrException()); return 1; }))
                .then(Commands.literal("leave").executes(c -> { ServerPlayer p = c.getSource().getPlayerOrException(); var r = Frontier.RUNS.get(p.getUUID());
                    if (r == null) Frontier.msg(p, "§7You're not in a run."); else Frontier.finish(r, p, false, "§7You left the run."); return 1; }))
                .then(Commands.literal("quit").executes(c -> { ServerPlayer p = c.getSource().getPlayerOrException(); var r = Frontier.RUNS.get(p.getUUID());   // the sidebar's hint
                    if (r == null) Frontier.msg(p, "§7You're not in a run."); else Frontier.finish(r, p, false, "§7You left the run."); return 1; }))
                .then(Commands.literal("shop").executes(c -> { ServerPlayer p = c.getSource().getPlayerOrException();
                    if (!Frontier.nearLobby(p)) { Frontier.msg(p, "§7The BP Exchange is in the Battle Frontier lobby."); return 0; } FrontierShop.open(p, 0); return 1; }))
                .then(Commands.literal("bp").executes(c -> { ServerPlayer p = c.getSource().getPlayerOrException(); Frontier.msg(p, "§7You have §b" + FrontierData.bp(p.getUUID().toString()) + " BP§7."); return 1; }))
                .then(Commands.literal("stats").executes(c -> { stats(c.getSource().getPlayerOrException()); return 1; }))
                .then(Commands.literal("guide").executes(c -> { guide(c.getSource().getPlayerOrException()); return 1; }))
                .then(Commands.literal("leaderboard").executes(c -> { FrontierBoards.chat(c.getSource().getPlayerOrException(), null); return 1; })
                        .then(Commands.argument("board", StringArgumentType.greedyString()).suggests(boards).executes(c -> { FrontierBoards.chat(c.getSource().getPlayerOrException(), StringArgumentType.getString(c, "board")); return 1; })))
                // ---- admin
                .then(Commands.literal("build").requires(s -> s.hasPermission(2)).then(Commands.literal("hub").executes(c -> buildHub(c.getSource().getPlayerOrException()))))
                .then(Commands.literal("setlobby").requires(s -> s.hasPermission(2)).executes(c -> {
                    ServerPlayer p = c.getSource().getPlayerOrException();
                    var sp = new FrontierData.Spot(); sp.dim = p.level().dimension().location().toString(); sp.x = p.getX(); sp.y = p.getY(); sp.z = p.getZ(); sp.yaw = p.getYRot();
                    FrontierData.data.lobby = sp; FrontierData.save(); Frontier.msg(p, "§aFrontier lobby set here. Players return here after every run."); return 1; }))
                .then(Commands.literal("board").requires(s -> s.hasPermission(2))
                        .then(Commands.literal("place").then(Commands.argument("board", StringArgumentType.greedyString()).suggests(boards).executes(c -> {
                            ServerPlayer p = c.getSource().getPlayerOrException(); String b = StringArgumentType.getString(c, "board");
                            FrontierBoards.place(b, p); Frontier.msg(p, "§aPlaced the " + b + " leaderboard."); return 1; })))
                        .then(Commands.literal("remove").then(Commands.argument("board", StringArgumentType.greedyString()).suggests(boards).executes(c -> {
                            FrontierBoards.remove(StringArgumentType.getString(c, "board")); c.getSource().sendSuccess(() -> Component.literal("§aRemoved."), false); return 1; }))))
                .then(Commands.literal("debug").requires(s -> s.hasPermission(2))
                        .then(Commands.literal("info").executes(c -> { ServerPlayer p = c.getSource().getPlayerOrException(); debugInfo(p); return 1; }))
                        .then(Commands.literal("next").then(Commands.argument("room", StringArgumentType.word()).suggests((c, b) -> { List<String> all = new ArrayList<>(DEBUG_ROOMS); all.addAll(DesignSave.BATTLE.keySet()); return SharedSuggestionProvider.suggest(all, b); })
                                .executes(c -> debugNext(c.getSource().getPlayerOrException(), StringArgumentType.getString(c, "room"), null))
                                .then(Commands.argument("extra", StringArgumentType.word()).suggests((c, b) -> { List<String> all = new ArrayList<>(debugExtras(StringArgumentType.getString(c, "room"))); all.addAll(SIDE_WORDS.subList(0, 3)); return SharedSuggestionProvider.suggest(all, b); })
                                        .executes(c -> debugNext(c.getSource().getPlayerOrException(), StringArgumentType.getString(c, "room"), StringArgumentType.getString(c, "extra")))
                                        .then(Commands.argument("side", StringArgumentType.word()).suggests((c, b) -> SharedSuggestionProvider.suggest(SIDE_WORDS.subList(0, 3), b))
                                                .executes(c -> debugNext(c.getSource().getPlayerOrException(), StringArgumentType.getString(c, "room"), StringArgumentType.getString(c, "extra"), StringArgumentType.getString(c, "side")))))))
                        .then(Commands.literal("win").executes(c -> debugEnd(c.getSource().getPlayerOrException(), true)))
                        .then(Commands.literal("lose").executes(c -> debugEnd(c.getSource().getPlayerOrException(), false)))
                        .then(Commands.literal("floor").then(Commands.argument("room", IntegerArgumentType.integer(0, 999)).executes(c -> {
                            ServerPlayer p = c.getSource().getPlayerOrException(); Frontier.Run r = Frontier.RUNS.get(p.getUUID());
                            if (r == null) { Frontier.msg(p, "§cYou're not in a run."); return 0; }
                            r.floor = IntegerArgumentType.getInteger(c, "room"); Frontier.msg(p, "§e[Debug] §7Room counter set to §f" + r.floor + "§7 (the next room is " + (r.floor + 1) + ")."); return 1; })))
                        .then(Commands.literal("newdaily").executes(c -> {
                            FrontierData.data.dailyReroll++; FrontierData.data.dailyChallengeBest.clear(); FrontierData.save();
                            c.getSource().sendSuccess(() -> Component.literal("§e[Debug] §7Rolled a new Daily Challenge for today. Open §f/frontier§7 to see it."), false); return 1; }))
                        .then(Commands.literal("boards").executes(c -> {
                            ServerPlayer p = c.getSource().getPlayerOrException();
                            FrontierBoards.refreshAll();
                            for (var en : FrontierData.data.boards.entrySet())
                                Frontier.msg(p, "§7Board §f" + en.getKey() + " §8· §7entries: §f" + FrontierData.data.season.getOrDefault(en.getKey(), Map.of()).size());
                            if (FrontierData.data.boards.isEmpty()) Frontier.msg(p, "§cNo boards placed.");
                            return 1; }))
                        .then(Commands.literal("tagteam").then(Commands.argument("pair", IntegerArgumentType.integer(1, 20)).executes(c -> {
                            ServerPlayer p = c.getSource().getPlayerOrException(); Frontier.Run r = Frontier.RUNS.get(p.getUUID());
                            if (r == null || !r.coop()) { Frontier.msg(p, "§cStart a Co-op run first (a friend or a Champion partner)."); return 0; }
                            r.forceDuo = IntegerArgumentType.getInteger(c, "pair") - 1;
                            Frontier.FORCE_NEXT.put(r.player, new String[]{"champion", null});
                            StringBuilder sb = new StringBuilder(); for (int i = 0; i < Frontier.DUOS.length; i++) sb.append("§8").append(i + 1).append(" §f").append(Frontier.DUOS[i][0]).append(" & ").append(Frontier.DUOS[i][1]).append("  ");
                            Frontier.msg(p, "§e[Debug] §7The next room is a tag-team Champion Room. Pairs: " + sb);
                            if (r.state == Frontier.State.DOORS) Frontier.afterRoom(r);
                            return 1; })))
                        .then(Commands.literal("today").then(Commands.argument("rooms", IntegerArgumentType.integer(0, 9999)).executes(c -> {
                            ServerPlayer p = c.getSource().getPlayerOrException(); String id = p.getUUID().toString();
                            int want = IntegerArgumentType.getInteger(c, "rooms");
                            FrontierData.data.daily.computeIfAbsent(id, k -> new HashMap<>()).put("rooms", want); FrontierData.save();
                            Frontier.msg(p, "§e[Debug] §7Rooms cleared today set to §f" + want + "§7 (rewards halve past " + FrontierData.cfg.dailySoftCap + ")."); return 1; })))
                        .then(Commands.literal("heal").executes(c -> {
                            ServerPlayer p = c.getSource().getPlayerOrException(); Frontier.Run r = Frontier.RUNS.get(p.getUUID());
                            if (r == null) { Frontier.msg(p, "§cYou're not in a run."); return 0; }
                            for (Object pk : Frontier.partyList(r)) Cob.heal(pk); Frontier.msg(p, "§e[Debug] §7Run team fully healed."); return 1; }))
                        .then(Commands.literal("pot").then(Commands.argument("cd", LongArgumentType.longArg(0)).then(Commands.argument("bp", LongArgumentType.longArg(0)).executes(c -> {
                            ServerPlayer p = c.getSource().getPlayerOrException(); Frontier.Run r = Frontier.RUNS.get(p.getUUID());
                            if (r == null) { Frontier.msg(p, "§cYou're not in a run."); return 0; }
                            r.potCd = LongArgumentType.getLong(c, "cd"); r.potBp = LongArgumentType.getLong(c, "bp"); Frontier.savePot(r);
                            Frontier.msg(p, "§e[Debug] §7Pot set to §6" + Fmt.cd(r.potCd) + " §7and §b" + r.potBp + " BP§7."); return 1; }))))
                        .then(Commands.literal("build").then(Commands.argument("design", StringArgumentType.word()).suggests((c, b) -> SharedSuggestionProvider.suggest(FrontierRooms.variants().keySet(), b))
                                .executes(c -> debugBuild(c.getSource().getPlayerOrException(), StringArgumentType.getString(c, "design"), -1))
                                .then(Commands.argument("variant", IntegerArgumentType.integer(0, 9))
                                        .executes(c -> debugBuild(c.getSource().getPlayerOrException(), StringArgumentType.getString(c, "design"), IntegerArgumentType.getInteger(c, "variant")))))))
                .then(Commands.literal("givebp").requires(s -> s.hasPermission(2)).then(Commands.argument("player", EntityArgument.player()).then(Commands.argument("amount", LongArgumentType.longArg()).executes(c -> {
                    ServerPlayer p = EntityArgument.getPlayer(c, "player"); long n = LongArgumentType.getLong(c, "amount");
                    FrontierData.addBp(p.getUUID().toString(), n); FrontierData.save();
                    c.getSource().sendSuccess(() -> Component.literal("§a" + p.getGameProfile().getName() + " now has " + FrontierData.bp(p.getUUID().toString()) + " BP."), true); return 1; }))))
                .then(Commands.literal("endrun").requires(s -> s.hasPermission(2)).then(Commands.argument("player", EntityArgument.player()).executes(c -> {
                    ServerPlayer p = EntityArgument.getPlayer(c, "player"); var r = Frontier.RUNS.get(p.getUUID());
                    if (r != null) Frontier.finish(r, p, false, "§7An admin ended your run. Your rewards so far were paid.");
                    c.getSource().sendSuccess(() -> Component.literal(r == null ? "§7They're not in a run." : "§aRun ended."), true); return 1; })))
                .then(Commands.literal("reload").requires(s -> s.hasPermission(2)).executes(c -> {
                    FrontierData.load(); c.getSource().sendSuccess(() -> Component.literal("§aFrontier config reloaded."), true); return 1; })));
    }
}
