package net.joeyscobblemon.gts;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

/**
 * The server shop, paid in CobbleDollars. Open with /shop (point /trigger shop at it too).
 * Everything is in config/jcl_shop.json: categories, items, prices, purchase limits, and
 * command rewards for ranks and cosmetics ({player} is replaced with the buyer's name).
 */
@EventBusSubscriber(modid = "jcl_gts")
public final class Shop {
    private Shop() {}

    public static final class Entry {
        public String name, icon, description = "";
        public long price;
        public String item;
        public int count = 1;
        public List<String> commands = new ArrayList<>();
        public int limit;              // 0 = no limit
        public String period = "none"; // none, week, month, once
    }

    public static final class Category {
        public String name, icon;
        public List<Entry> items = new ArrayList<>();
    }

    public static final class Config { public List<Category> categories = new ArrayList<>(); }

    /** player -> "category/entry@period" -> times bought */
    public static final class Data { public Map<String, Map<String, Integer>> bought = new HashMap<>(); }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    static Config cfg = new Config();
    static Data data = new Data();
    static MinecraftServer server;
    private static final Map<String, String> CONFIRM = new HashMap<>();

    private static Path dataFile() { return server.getWorldPath(LevelResource.ROOT).resolve("data").resolve("jcl_gts").resolve("shop.json"); }

    static void load() {
        try {
            Path f = Path.of("config", "jcl_shop.json");
            if (!Files.exists(f)) {
                String d = Crates.readDefault("shop.json");
                Files.createDirectories(f.getParent());
                if (d != null) Files.writeString(f, d);
            }
            Config c = GSON.fromJson(Files.readString(f), Config.class);
            if (c != null) cfg = c;
        } catch (Exception e) { GtsMod.LOG.error("[JCL] Could not read config/jcl_shop.json", e); }
        try {
            Path f = dataFile();
            if (Files.exists(f)) { Data d = GSON.fromJson(Files.readString(f), Data.class); if (d != null) data = d; }
        } catch (Exception e) { GtsMod.LOG.error("[JCL] Could not read shop data", e); }
        if (data.bought == null) data.bought = new HashMap<>();
    }

    static void save() {
        try { Files.createDirectories(dataFile().getParent()); Files.writeString(dataFile(), GSON.toJson(data)); }
        catch (Exception e) { GtsMod.LOG.error("[JCL] Could not save shop data", e); }
    }

    @SubscribeEvent public static void onStart(ServerStartedEvent e) { server = e.getServer(); load(); }
    @SubscribeEvent public static void onStop(ServerStoppingEvent e) { save(); }

    @SubscribeEvent
    public static void onCommands(RegisterCommandsEvent e) {
        e.getDispatcher().register(Commands.literal("shop").executes(c -> { main(c.getSource().getPlayerOrException()); return 1; })
                .then(Commands.literal("reload").requires(s -> s.hasPermission(2)).executes(c -> { load(); c.getSource().sendSuccess(() -> Component.literal("§aShop reloaded."), true); return 1; })));
        e.getDispatcher().register(Commands.literal("jclshop").executes(c -> { main(c.getSource().getPlayerOrException()); return 1; }));
    }

    // ================================================================== limits

    private static String periodTag(String period) {
        LocalDate d = LocalDate.now();
        return switch (period) {
            case "week" -> d.get(IsoFields.WEEK_BASED_YEAR) + "W" + d.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
            case "month" -> d.getYear() + "M" + d.getMonthValue();
            case "once" -> "once";
            default -> "";
        };
    }

    private static String key(int cat, Entry e) { return cat + "/" + e.name + "@" + periodTag(e.period); }

    private static int bought(ServerPlayer p, int cat, Entry e) {
        return data.bought.getOrDefault(p.getUUID().toString(), Map.of()).getOrDefault(key(cat, e), 0);
    }

    private static String periodWord(String p) {
        return switch (p) { case "week" -> "this week"; case "month" -> "this month"; case "once" -> "ever"; default -> ""; };
    }

    // ================================================================== screens

    static void main(ServerPlayer p) {
        Gui.Screen s = new Gui.Screen("§6§l🛒 League Shop", 4);
        s.frame(Items.ORANGE_STAINED_GLASS_PANE, Items.YELLOW_STAINED_GLASS_PANE);
        long bal = Economy.balance(p);
        s.set(4, Gui.glow(Gui.icon(Items.GOLD_INGOT, "§6§lLeague Shop", "§7Your balance: §6" + (bal >= 0 ? Fmt.cd(bal) : "?"), "",
                "§7Everything here costs CobbleDollars", "§7you earn by playing. §lNever pay-to-win.")));
        int[] slots = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25};
        for (int i = 0; i < Math.min(slots.length, cfg.categories.size()); i++) {
            Category c = cfg.categories.get(i);
            int idx = i;
            s.set(slots[i], Gui.icon(Crates.resolve(c.icon), c.name, "§7" + c.items.size() + " items", "", "§e▶ Click to browse"), pl -> category(pl, idx));
        }
        s.set(31, Gui.icon(Items.ANVIL, "§d§lMerge crate keys", "§7Upgrade your keys for a fee."), pl -> Crates.mergeMenu(pl));
        Gui.open(p, s);
    }

    static void category(ServerPlayer p, int cat) {
        Category c = cfg.categories.get(cat);
        Gui.Screen s = new Gui.Screen("§6§l🛒 " + c.name.replaceAll("§.", ""), 6);
        s.frame(Items.ORANGE_STAINED_GLASS_PANE, Items.YELLOW_STAINED_GLASS_PANE);
        long bal = Economy.balance(p);
        s.set(4, Gui.icon(Crates.resolve(c.icon), c.name, "§7Your balance: §6" + (bal >= 0 ? Fmt.cd(bal) : "?")));
        int n = 0;
        for (int r = 1; r <= 4; r++) for (int col = 1; col <= 7; col++) {
            if (n >= c.items.size()) break;
            Entry e = c.items.get(n++);
            int slot = r * 9 + col;
            List<String> lore = new ArrayList<>();
            if (!e.description.isEmpty()) { lore.add("§7" + e.description); lore.add(""); }
            lore.add("§7Price: §6" + Fmt.cd(e.price));
            if (e.limit > 0) {
                int b = bought(p, cat, e);
                lore.add("§7Limit: §f" + b + " / " + e.limit + " " + periodWord(e.period));
            }
            boolean armed = (cat + "/" + e.name).equals(CONFIRM.get(p.getUUID().toString()));
            lore.add("");
            lore.add(armed ? "§6§l⚠ Click again to confirm" : bal >= e.price ? "§a▶ Click to buy" : "§cYou can't afford this yet.");
            ItemStack icon = Gui.named(new ItemStack(Crates.resolve(e.icon), Math.max(1, Math.min(64, e.count))), "§f" + e.name, lore.toArray(new String[0]));
            s.set(slot, armed ? Gui.glow(icon) : icon, pl -> buy(pl, cat, e));
        }
        s.set(45, Gui.icon(Items.ARROW, "§e« Back"), pl -> { CONFIRM.remove(pl.getUUID().toString()); main(pl); });
        Gui.open(p, s);
    }

    static void buy(ServerPlayer p, int cat, Entry e) {
        String id = p.getUUID().toString();
        String tag = cat + "/" + e.name;
        if (!tag.equals(CONFIRM.get(id))) { CONFIRM.put(id, tag); p.playNotifySound(SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.MASTER, 0.6f, 1.4f); category(p, cat); return; }
        CONFIRM.remove(id);
        if (e.limit > 0 && bought(p, cat, e) >= e.limit) { fail(p, "You've reached the limit for " + e.name + " " + periodWord(e.period) + "."); category(p, cat); return; }
        long bal = Economy.balance(p);
        if (bal < e.price) { fail(p, "You need " + Fmt.cd(e.price) + " but have " + Fmt.cd(Math.max(0, bal)) + "."); category(p, cat); return; }
        if (!Economy.take(p, e.price)) { fail(p, "The payment didn't go through. You weren't charged."); category(p, cat); return; }
        if (e.item != null && !e.item.isEmpty()) Crates.giveStack(p, new ItemStack(Crates.resolve(e.item), e.count));
        for (String cmd : e.commands) server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), cmd.replace("{player}", p.getGameProfile().getName()));
        if (e.limit > 0) { data.bought.computeIfAbsent(id, k -> new HashMap<>()).merge(key(cat, e), 1, Integer::sum); save(); }
        p.playNotifySound(SoundEvents.PLAYER_LEVELUP, SoundSource.MASTER, 0.6f, 1.3f);
        p.sendSystemMessage(Component.literal("§6§l[Shop] §r§aYou bought §f" + e.name + "§a for §6" + Fmt.cd(e.price) + "§a."));
        category(p, cat);
    }

    private static void fail(ServerPlayer p, String msg) {
        p.playNotifySound(SoundEvents.VILLAGER_NO, SoundSource.MASTER, 0.6f, 1f);
        p.sendSystemMessage(Component.literal("§6§l[Shop] §r§c" + msg));
    }
}
