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
 * The server shop, paid in CobbleDollars. Open with /shop. /sell sells items at the bank price list.
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

    public static final class Config { public int version; public List<Category> categories = new ArrayList<>(); }

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
            String json = Defaults.load("jcl_shop.json");
            Config c = json == null ? null : GSON.fromJson(json, Config.class);
            if (c != null) cfg = c;
        } catch (Exception e) { GtsMod.LOG.error("[JCL] Could not read config/jcl_shop.json", e); }
        loadPrices();
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
        e.getDispatcher().register(Commands.literal("sell")
                .executes(c -> { sell(c.getSource().getPlayerOrException(), false); return 1; })
                .then(Commands.literal("hand").executes(c -> { sell(c.getSource().getPlayerOrException(), false); return 1; }))
                .then(Commands.literal("all").executes(c -> { sell(c.getSource().getPlayerOrException(), true); return 1; }))
                .then(Commands.literal("price").executes(c -> { price(c.getSource().getPlayerOrException()); return 1; })));
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
        s.set(30, Gui.icon(Items.ANVIL, "§d§lMerge crate keys", "§7Upgrade your keys for a fee."), pl -> Crates.mergeMenu(pl));
        s.set(32, Gui.icon(Items.HOPPER, "§a§lSell items", "§7Hold an item and type:", "§f/sell §7sells the stack in your hand", "§f/sell all §7sells every one you carry", "§f/sell price §7shows what it's worth"));
        Gui.open(p, s);
    }

    static void category(ServerPlayer p, int cat) { category(p, cat, 0); }

    static void category(ServerPlayer p, int cat, int page) {
        Category c = cfg.categories.get(cat);
        Gui.Screen s = new Gui.Screen("§6§l🛒 " + c.name.replaceAll("§.", ""), 6);
        s.frame(Items.ORANGE_STAINED_GLASS_PANE, Items.YELLOW_STAINED_GLASS_PANE);
        long bal = Economy.balance(p);
        s.set(4, Gui.icon(Crates.resolve(c.icon), c.name, "§7Your balance: §6" + (bal >= 0 ? Fmt.cd(bal) : "?")));
        int pages = Math.max(1, (c.items.size() + 27) / 28);
        int pg = Math.max(0, Math.min(page, pages - 1));
        int n = pg * 28;
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
            s.set(slot, armed ? Gui.glow(icon) : icon, pl -> buy(pl, cat, e, pg));
        }
        if (pg > 0) s.set(48, Gui.icon(Items.ARROW, "§e« Previous page", "§7Page " + pg + " of " + pages), pl -> category(pl, cat, pg - 1));
        if (pg < pages - 1) s.set(50, Gui.icon(Items.ARROW, "§eNext page »", "§7Page " + (pg + 2) + " of " + pages), pl -> category(pl, cat, pg + 1));
        s.set(45, Gui.icon(Items.ARROW, "§e« Back"), pl -> { CONFIRM.remove(pl.getUUID().toString()); main(pl); });
        Gui.open(p, s);
    }

    static void buy(ServerPlayer p, int cat, Entry e, int page) {
        String id = p.getUUID().toString();
        String tag = cat + "/" + e.name;
        if (!tag.equals(CONFIRM.get(id))) { CONFIRM.put(id, tag); p.playNotifySound(SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.MASTER, 0.6f, 1.4f); category(p, cat, page); return; }
        CONFIRM.remove(id);
        if (e.limit > 0 && bought(p, cat, e) >= e.limit) { fail(p, "You've reached the limit for " + e.name + " " + periodWord(e.period) + "."); category(p, cat, page); return; }
        long bal = Economy.balance(p);
        if (bal < e.price) { fail(p, "You need " + Fmt.cd(e.price) + " but have " + Fmt.cd(Math.max(0, bal)) + "."); category(p, cat, page); return; }
        if (!Economy.take(p, e.price)) { fail(p, "The payment didn't go through. You weren't charged."); category(p, cat, page); return; }
        if (e.item != null && !e.item.isEmpty()) Crates.giveStack(p, new ItemStack(Crates.resolve(e.item), e.count));
        for (String cmd : e.commands) server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), cmd.replace("{player}", p.getGameProfile().getName()));
        if (e.limit > 0) { data.bought.computeIfAbsent(id, k -> new HashMap<>()).merge(key(cat, e), 1, Integer::sum); save(); }
        p.playNotifySound(SoundEvents.PLAYER_LEVELUP, SoundSource.MASTER, 0.6f, 1.3f);
        p.sendSystemMessage(Component.literal("§6§l[Shop] §r§aYou bought §f" + e.name + "§a for §6" + Fmt.cd(e.price) + "§a."));
        category(p, cat, page);
    }

    private static void fail(ServerPlayer p, String msg) {
        p.playNotifySound(SoundEvents.VILLAGER_NO, SoundSource.MASTER, 0.6f, 1f);
        p.sendSystemMessage(Component.literal("§6§l[Shop] §r§c" + msg));
    }

    // ================================================================== selling (uses the CobbleDollars bank price list)

    static final Map<String, Long> PRICES = new HashMap<>();

    static void loadPrices() {
        PRICES.clear();
        try {
            Path f = Path.of("config", "cobbledollars", "default_bank.json");
            if (!Files.exists(f)) return;
            com.google.gson.JsonArray arr = com.google.gson.JsonParser.parseString(Files.readString(f)).getAsJsonArray();
            for (var el : arr) {
                var o = el.getAsJsonObject();
                if (!o.has("item") || !o.has("price")) continue;
                try { PRICES.put(o.get("item").getAsString(), Long.parseLong(o.get("price").getAsString().trim())); } catch (Exception ignored) {}
            }
            GtsMod.LOG.info("[JCL] Loaded {} sell prices.", PRICES.size());
        } catch (Exception ex) { GtsMod.LOG.error("[JCL] Could not read the bank price list", ex); }
    }

    static long priceOf(ItemStack s) {
        if (s.isEmpty() || s.isDamaged() || s.has(net.minecraft.core.component.DataComponents.CUSTOM_NAME)) return -1;
        Long v = PRICES.get(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(s.getItem()).toString());
        return v == null ? -1 : v;
    }

    static void price(ServerPlayer p) {
        ItemStack s = p.getMainHandItem();
        long v = priceOf(s);
        if (v < 0) { fail(p, "That item can't be sold."); return; }
        p.sendSystemMessage(Component.literal("§6§l[Shop] §r§f" + s.getHoverName().getString() + " §7sells for §6" + Fmt.cd(v) + "§7 each (§6" + Fmt.cd(v * s.getCount()) + "§7 for this stack)."));
    }

    static void sell(ServerPlayer p, boolean all) {
        ItemStack hand = p.getMainHandItem();
        long each = priceOf(hand);
        if (each < 0) { fail(p, hand.isEmpty() ? "Hold the item you want to sell." : "That item can't be sold."); return; }
        int count = 0;
        if (all) {
            var item = hand.getItem();
            for (ItemStack st : p.getInventory().items) if (st.is(item) && priceOf(st) == each) { count += st.getCount(); st.setCount(0); }
        } else { count = hand.getCount(); hand.setCount(0); }
        long total = each * count;
        Economy.give(p, total);
        p.playNotifySound(SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.MASTER, 0.6f, 1.1f);
        p.sendSystemMessage(Component.literal("§6§l[Shop] §r§aSold §f" + count + "x " + hand.getHoverName().getString() + "§a for §6" + Fmt.cd(total) + "§a."));
    }
}
