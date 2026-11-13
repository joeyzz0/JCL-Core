package net.joeyscobblemon.gts;

import java.time.LocalDate;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** The BP Exchange. Three "Spotlight" items each week are 25% off. */
final class FrontierShop {
    private FrontierShop() {}

    static Set<Integer> spotlight() {
        Set<Integer> out = new HashSet<>();
        int n = FrontierData.cfg.shop.size();
        if (n == 0) return out;
        Random r = new Random(Frontier.weekSeed() * 13L + 5);
        for (int tries = 0; out.size() < Math.min(3, n) && tries < 50; tries++) out.add(r.nextInt(n));
        return out;
    }

    static long price(int i, FrontierData.ShopEntry e) { return spotlight().contains(i) ? Math.round(e.bp * 0.75) : e.bp; }

    static String periodKey(String period) {
        LocalDate d = LocalDate.now();
        return switch (period) {
            case "week" -> d.getYear() + "w" + d.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
            case "month" -> d.getYear() + "m" + d.getMonthValue();
            case "once" -> "once";
            default -> "";
        };
    }

    static int bought(String uuid, int i, FrontierData.ShopEntry e) {
        return FrontierData.data.bought.getOrDefault(uuid, new HashMap<>()).getOrDefault(e.name + "@" + periodKey(e.period), 0);
    }

    static void open(ServerPlayer p, int page) { open(p, page, false); }

    static void open(ServerPlayer p, int page, boolean fromFrontier) {
        String id = p.getUUID().toString();
        var list = FrontierData.cfg.shop;
        int per = 28, pages = Math.max(1, (list.size() + per - 1) / per), pg = Math.max(0, Math.min(page, pages - 1));
        Gui.Screen g = new Gui.Screen("§a§lBP Exchange §8· §b" + FrontierData.bp(id) + " BP", 6);
        g.frame(Items.LIME_STAINED_GLASS_PANE, Items.GREEN_STAINED_GLASS_PANE);
        Set<Integer> spot = spotlight();
        int k = 0;
        for (int r = 1; r <= 4; r++) for (int c = 1; c <= 7; c++) {
            int i = pg * per + k++;
            if (i >= list.size()) break;
            var e = list.get(i);
            long cost = price(i, e);
            List<String> lore = new ArrayList<>();
            if (spot.contains(i)) lore.add("§e§l★ Spotlight: 25% off this week!");
            lore.add("§7Price: §b" + cost + " BP" + (spot.contains(i) ? " §8(was " + e.bp + ")" : ""));
            if (e.limit > 0) lore.add("§7Limit: §f" + bought(id, i, e) + "§7/§f" + e.limit + (e.period.equals("once") ? " ever" : " per " + e.period));
            lore.add("");
            lore.add(FrontierData.bp(id) >= cost ? "§a▶ Click to buy" : "§cNot enough BP");
            ItemStack icon = new ItemStack(Crates.resolve(e.item), Math.max(1, Math.min(64, e.count)));
            final int idx = i;
            ItemStack named = Gui.named(icon, "§f" + e.name + (e.count > 1 ? " §7×" + e.count : ""), lore.toArray(new String[0]));
            g.set(r * 9 + c, spot.contains(i) ? Gui.glow(named) : named, pl -> buy(pl, idx, pg, fromFrontier));
        }
        if (pg > 0) g.set(48, Gui.icon(Items.ARROW, "§e« Previous page"), pl -> open(pl, pg - 1, fromFrontier));
        if (pg < pages - 1) g.set(50, Gui.icon(Items.ARROW, "§eNext page »"), pl -> open(pl, pg + 1, fromFrontier));
        g.set(49, Gui.icon(Items.EMERALD, "§b§l" + FrontierData.bp(id) + " BP", "§7Earn BP by clearing Frontier rooms."));
        if (fromFrontier) g.set(45, Gui.icon(Items.ARROW, "§e« Back"), FrontierGui::setup);
        Gui.open(p, g);
    }

    static void buy(ServerPlayer p, int i, int page, boolean fromFrontier) {
        var list = FrontierData.cfg.shop;
        if (i < 0 || i >= list.size()) return;
        var e = list.get(i);
        String id = p.getUUID().toString();
        if (e.limit > 0 && bought(id, i, e) >= e.limit) { Frontier.msg(p, "§cYou've reached the limit for " + e.name + "."); open(p, page, fromFrontier); return; }
        long cost = price(i, e);
        if (!FrontierData.takeBp(id, cost)) { Frontier.msg(p, "§cNot enough BP."); open(p, page, fromFrontier); return; }
        if (e.commands != null && !e.commands.isEmpty()) {
            for (String cmd : e.commands)
                Frontier.server.getCommands().performPrefixedCommand(Frontier.server.createCommandSourceStack().withSuppressedOutput(), cmd.replace("{player}", p.getGameProfile().getName()));
        } else {
            Crates.giveStack(p, new ItemStack(Crates.resolve(e.item), Math.max(1, e.count)));
        }
        if (e.limit > 0) FrontierData.data.bought.computeIfAbsent(id, x -> new HashMap<>()).merge(e.name + "@" + periodKey(e.period), 1, Integer::sum);
        FrontierData.save();
        FrontierQuests.complete(p, "first_shop");
        p.playNotifySound(SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.MASTER, 0.7f, 1.2f);
        Frontier.msg(p, "§aBought §f" + e.name + "§a for §b" + cost + " BP§a.");
        open(p, page, fromFrontier);
    }
}
