package net.joeyscobblemon.gts;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

/**
 * The GTS menus: market, listing details, your listings, claims and the sell menu.
 * Everything is a server-side chest menu, so players need nothing extra installed.
 * Public entry points used by GtsCommands: browse, mine, claims, view.
 */
public final class Gui {
    private Gui() {}

    // ================================================================== per-player browsing state

    static final class View {
        int page;
        Market.Filter filter = Market.Filter.ALL;
        Market.Sort sort = Market.Sort.NEWEST;
        String confirmBuy;   // listing id waiting for a second click on "Buy"
    }

    private static final Map<UUID, View> VIEWS = new HashMap<>();

    static View view(ServerPlayer p) { return VIEWS.computeIfAbsent(p.getUUID(), u -> new View()); }

    // ================================================================== menu plumbing

    /** One screen: its title, its rows and what each slot shows and does. */
    static final class Screen {
        final Component title;
        final int rows;
        final ItemStack[] icons;
        final Map<Integer, Consumer<ServerPlayer>> actions = new HashMap<>();

        Screen(String title, int rows) {
            this.title = Component.literal(title);
            this.rows = rows;
            this.icons = new ItemStack[rows * 9];
            for (int i = 0; i < icons.length; i++) icons[i] = ItemStack.EMPTY;
        }

        void set(int slot, ItemStack icon) { icons[slot] = icon; actions.remove(slot); }
        void set(int slot, ItemStack icon, Consumer<ServerPlayer> action) { icons[slot] = icon; actions.put(slot, action); }

        /** Fills the outer frame with a two-tone pattern. */
        void frame(Item a, Item b) {
            for (int i = 0; i < icons.length; i++) {
                int r = i / 9, c = i % 9;
                if (r == 0 || r == rows - 1 || c == 0 || c == 8) icons[i] = pane((r + c) % 2 == 0 ? a : b);
            }
        }

        void fill(Item item) { for (int i = 0; i < icons.length; i++) if (icons[i].isEmpty()) icons[i] = pane(item); }
    }

    static final class GtsMenu extends ChestMenu {
        final SimpleContainer box;
        final ServerPlayer viewer;
        Screen screen;

        GtsMenu(int id, Inventory inv, SimpleContainer box, ServerPlayer viewer, Screen screen) {
            super(typeFor(screen.rows), id, inv, box, screen.rows);
            this.box = box;
            this.viewer = viewer;
            apply(screen);
        }

        static MenuType<?> typeFor(int rows) {
            return switch (rows) {
                case 1 -> MenuType.GENERIC_9x1;
                case 2 -> MenuType.GENERIC_9x2;
                case 3 -> MenuType.GENERIC_9x3;
                case 4 -> MenuType.GENERIC_9x4;
                case 5 -> MenuType.GENERIC_9x5;
                default -> MenuType.GENERIC_9x6;
            };
        }

        void apply(Screen s) {
            screen = s;
            for (int i = 0; i < box.getContainerSize(); i++) box.setItem(i, i < s.icons.length ? s.icons[i].copy() : ItemStack.EMPTY);
            broadcastFullState();
        }

        @Override
        public void clicked(int slot, int button, ClickType type, Player player) {
            // Every slot is a button. Nothing can be picked up, moved or shift-clicked.
            if (slot >= 0 && slot < screen.icons.length) {
                Consumer<ServerPlayer> a = screen.actions.get(slot);
                if (a != null) a.accept(viewer);
            }
            broadcastFullState();
        }

        @Override public ItemStack quickMoveStack(Player player, int index) { return ItemStack.EMPTY; }
        @Override public boolean stillValid(Player player) { return true; }
    }

    /** Shows a screen, reusing the open menu when it has the same size so the cursor doesn't jump. */
    static void open(ServerPlayer p, Screen s) {
        if (p.containerMenu instanceof GtsMenu m && m.screen.rows == s.rows && m.screen.title.getString().equals(s.title.getString())) {
            m.apply(s);
            return;
        }
        SimpleContainer box = new SimpleContainer(s.rows * 9);
        p.openMenu(new SimpleMenuProvider((id, inv, pl) -> new GtsMenu(id, inv, box, p, s), s.title));
    }

    // ================================================================== item helpers

    static ItemStack pane(Item item) {
        ItemStack s = new ItemStack(item);
        s.set(DataComponents.CUSTOM_NAME, Component.literal(" "));
        s.set(DataComponents.HIDE_TOOLTIP, net.minecraft.util.Unit.INSTANCE);
        return s;
    }

    static ItemStack icon(Item item, String name, String... lore) { return named(new ItemStack(item), name, lore); }

    static ItemStack named(ItemStack s, String name, String... lore) {
        s.set(DataComponents.CUSTOM_NAME, Component.literal(name).withStyle(st -> st.withItalic(false)));
        List<Component> lines = new ArrayList<>();
        for (String l : lore) lines.add(Component.literal(l).withStyle(st -> st.withItalic(false)));
        if (!lines.isEmpty()) s.set(DataComponents.LORE, new ItemLore(lines));
        return s;
    }

    static ItemStack glow(ItemStack s) { s.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true); return s; }

    static String timeLeft(long endsAt) {
        long s = Math.max(0, (endsAt - System.currentTimeMillis()) / 1000);
        if (s >= 86400) return (s / 86400) + "d " + (s % 86400 / 3600) + "h";
        if (s >= 3600) return (s / 3600) + "h " + (s % 3600 / 60) + "m";
        if (s >= 60) return (s / 60) + "m " + (s % 60) + "s";
        return s + "s";
    }

    private static void sound(ServerPlayer p, SoundEvent s, float pitch) { p.playNotifySound(s, SoundSource.MASTER, 0.6f, pitch); }
    private static void click(ServerPlayer p) { sound(p, SoundEvents.EXPERIENCE_ORB_PICKUP, 1.4f); }
    private static void good(ServerPlayer p) { sound(p, SoundEvents.PLAYER_LEVELUP, 1.3f); }
    private static void bad(ServerPlayer p) { sound(p, SoundEvents.VILLAGER_NO, 1f); }

    private static void tell(ServerPlayer p, String msg) { p.sendSystemMessage(Component.literal("§6§l[GTS] §r" + msg)); }

    /** The card shown for a listing in the market grid. */
    static ItemStack display(Listing l, boolean mineView) {
        ItemStack base = Market.decode(l.itemSnbt);
        if (base.isEmpty()) base = new ItemStack(Items.BARRIER);
        boolean auction = l.mode == Listing.Mode.AUCTION;
        List<String> lore = new ArrayList<>();
        lore.add(auction ? "§6§l⚖ AUCTION" : "§a§l✔ BUY IT NOW");
        lore.add("");
        if (l.kind == Listing.Kind.POKEMON && l.details != null) lore.addAll(l.details);
        else if (l.kind == Listing.Kind.ITEM) lore.add("§7Amount: §f" + base.getCount());
        lore.add("");
        if (auction) {
            lore.add(l.bidder == null ? "§7Starting bid: §6" + Fmt.cd(l.price) : "§7Top bid: §6" + Fmt.cd(l.currentBid) + " §8(" + l.bidCount + " bids)");
        } else {
            lore.add("§7Price: §a" + Fmt.cd(l.price));
        }
        lore.add("§7Ends in: §f" + timeLeft(l.endsAt));
        lore.add("§7Seller: §f" + (mineView ? "You" : l.sellerName));
        lore.add("§8#" + l.id);
        lore.add("");
        lore.add("§e▶ Click for details");
        return named(base.copy(), (auction ? "§6" : "§a") + l.title, lore.toArray(new String[0]));
    }

    private static final Item[] FILTER_ICONS = {Items.NETHER_STAR, Items.TOTEM_OF_UNDYING, Items.CHEST, Items.GOLDEN_HELMET, Items.EMERALD};

    private static String filterName(Market.Filter f) {
        return switch (f) { case ALL -> "Everything"; case POKEMON -> "Pokémon"; case ITEMS -> "Items"; case AUCTIONS -> "Auctions"; case BUY_NOW -> "Buy It Now"; };
    }

    private static String sortName(Market.Sort s) {
        return switch (s) { case NEWEST -> "Newest"; case ENDING_SOON -> "Ending soon"; case PRICE_LOW -> "Price: low to high"; case PRICE_HIGH -> "Price: high to low"; };
    }

    // ================================================================== the market

    private static final int[] GRID = innerSlots(6);

    private static int[] innerSlots(int rows) {
        List<Integer> s = new ArrayList<>();
        for (int r = 1; r < rows - 1; r++) for (int c = 1; c < 8; c++) s.add(r * 9 + c);
        return s.stream().mapToInt(Integer::intValue).toArray();
    }

    public static void browse(ServerPlayer p) {
        View v = view(p);
        List<Listing> all = Market.query(v.filter, v.sort, null);
        int pages = Math.max(1, (all.size() + GRID.length - 1) / GRID.length);
        v.page = Math.max(0, Math.min(v.page, pages - 1));

        Screen s = new Screen("§1§l🌐 §9§lGlobal Trade System", 6);
        s.frame(Items.BLUE_STAINED_GLASS_PANE, Items.LIGHT_BLUE_STAINED_GLASS_PANE);

        long bal = Economy.balance(p);
        int claims = Market.claimCount(p.getUUID());
        s.set(4, glow(icon(Items.HEART_OF_THE_SEA, "§b§l🌐 Global Trade System",
                "§7Trade Pokémon and items with",
                "§7trainers across the whole server.", "",
                "§7Listings: §f" + all.size(),
                "§7Your balance: §6" + (bal >= 0 ? Fmt.cd(bal) : "?"),
                "§7Page: §f" + (v.page + 1) + "§7/§f" + pages)));

        if (all.isEmpty()) {
            s.set(22, icon(Items.ENDER_EYE, "§7Nothing here yet",
                    "§7Be the first to list something!", "", "§7Click §aSell§7 below to get started."));
        }
        for (int i = 0; i < GRID.length; i++) {
            int idx = v.page * GRID.length + i;
            if (idx >= all.size()) break;
            Listing l = all.get(idx);
            boolean own = l.seller.equals(p.getUUID());
            s.set(GRID[i], display(l, own), pl -> { click(pl); detail(pl, l.id, false); });
        }

        // Bottom bar
        if (v.page > 0) s.set(45, icon(Items.ARROW, "§e« Previous page", "§7Page " + v.page + " of " + pages), pl -> { click(pl); view(pl).page--; browse(pl); });
        s.set(46, icon(FILTER_ICONS[v.filter.ordinal()], "§b§lShowing: §f" + filterName(v.filter),
                "§7Click to change", "", list(Market.Filter.values(), v.filter.ordinal(), f -> filterName((Market.Filter) f))), pl -> {
            click(pl); View w = view(pl);
            w.filter = Market.Filter.values()[(w.filter.ordinal() + 1) % Market.Filter.values().length]; w.page = 0; browse(pl);
        });
        s.set(47, icon(Items.COMPARATOR, "§b§lSort: §f" + sortName(v.sort),
                "§7Click to change", "", list(Market.Sort.values(), v.sort.ordinal(), o -> sortName((Market.Sort) o))), pl -> {
            click(pl); View w = view(pl);
            w.sort = Market.Sort.values()[(w.sort.ordinal() + 1) % Market.Sort.values().length]; w.page = 0; browse(pl);
        });
        s.set(48, glow(icon(Items.ENDER_EYE, "§d§l✦ Wonder Trade", "§7Send a Pokémon into the unknown", "§7and get a random one back.")), pl -> { click(pl); WonderTrade.openMain(pl); });
        s.set(49, glow(icon(Items.EMERALD, "§a§l✚ Sell something", "§7List a Pokémon or the item", "§7in your hand.")), pl -> { click(pl); sellMenu(pl); });
        s.set(50, icon(Items.CHEST, "§d§lMy listings", "§7See and cancel your listings."), pl -> { click(pl); mine(pl); });
        ItemStack claimIcon = icon(claims > 0 ? Items.ENDER_CHEST : Items.BARREL, claims > 0 ? "§e§l✉ Claims §6(" + claims + ")" : "§7Claims",
                claims > 0 ? "§eThings are waiting for you!" : "§7Nothing waiting right now.");
        s.set(51, claims > 0 ? glow(claimIcon) : claimIcon, pl -> { click(pl); claims(pl); });
        s.set(52, icon(Items.SUNFLOWER, "§7Refresh"), pl -> { click(pl); browse(pl); });
        if (v.page < pages - 1) s.set(53, icon(Items.ARROW, "§eNext page »", "§7Page " + (v.page + 2) + " of " + pages), pl -> { click(pl); view(pl).page++; browse(pl); });
        open(p, s);
    }

    private interface Namer { String name(Object o); }

    /** A lore line listing the options, with the current one highlighted. */
    private static String list(Object[] options, int current, Namer n) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < options.length; i++) {
            if (i > 0) b.append("§8, ");
            b.append(i == current ? "§f§n" : "§8").append(n.name(options[i])).append("§r");
        }
        return b.toString();
    }

    // ================================================================== one listing

    static void detail(ServerPlayer p, String id, boolean fromMine) {
        Listing l = Market.data.listings.get(id);
        if (l == null) { bad(p); tell(p, "§cThat listing is gone."); if (fromMine) mine(p); else browse(p); return; }
        View v = view(p);
        boolean own = l.seller.equals(p.getUUID());
        boolean admin = p.hasPermissions(2);
        boolean auction = l.mode == Listing.Mode.AUCTION;

        Screen s = new Screen(auction ? "§6§l⚖ Auction §8#" + l.id : "§2§l✔ Buy It Now §8#" + l.id, 6);
        s.frame(auction ? Items.ORANGE_STAINED_GLASS_PANE : Items.LIME_STAINED_GLASS_PANE, auction ? Items.YELLOW_STAINED_GLASS_PANE : Items.GREEN_STAINED_GLASS_PANE);
        s.set(13, display(l, own));
        s.set(4, icon(Items.NAME_TAG, "§f" + l.title, "§7Seller: §f" + (own ? "You" : l.sellerName), "§7Ends in: §f" + timeLeft(l.endsAt)));

        Runnable back = () -> { if (fromMine) mine(p); else browse(p); };
        s.set(45, icon(Items.ARROW, "§e« Back"), pl -> { click(pl); view(pl).confirmBuy = null; back.run(); });

        if (own) {
            boolean locked = auction && l.bidder != null && !admin;
            s.set(31, locked
                    ? icon(Items.GRAY_DYE, "§7Can't cancel", "§7This auction already has bids.")
                    : icon(Items.RED_CONCRETE, "§c§lCancel listing", "§7The goods go to your §e/gts claims§7."), pl -> {
                if (locked) { bad(pl); return; }
                String err = Market.cancel(pl, l.id, admin);
                if (err != null) { bad(pl); tell(pl, "§c" + err); } else { good(pl); tell(pl, "§aListing cancelled. Collect it with §e/gts claims§a."); }
                mine(pl);
            });
        } else if (!auction) {
            boolean armed = l.id.equals(v.confirmBuy);
            s.set(31, glow(icon(armed ? Items.GOLD_BLOCK : Items.EMERALD_BLOCK, armed ? "§6§l⚠ Click again to confirm" : "§a§lBuy for " + Fmt.cd(l.price),
                    armed ? "§7You'll pay §6" + Fmt.cd(l.price) + "§7." : "§7Click to buy.")), pl -> {
                View w = view(pl);
                if (!l.id.equals(w.confirmBuy)) { click(pl); w.confirmBuy = l.id; detail(pl, l.id, fromMine); return; }
                w.confirmBuy = null;
                String err = Market.buy(pl, l.id);
                if (err != null) { bad(pl); tell(pl, "§c" + err); detail(pl, l.id, fromMine); return; }
                good(pl);
                tell(pl, "§aYou bought §f" + l.title + "§a! " + (l.kind == Listing.Kind.POKEMON ? "Check your party or PC." : "Check your inventory."));
                browse(pl);
            });
        } else {
            long min = Market.minNextBid(l);
            long[] bids = {min, raise(l, 10), raise(l, 25), raise(l, 50), raise(l, 100)};
            String[] names = {"Minimum bid", "Bid +10%", "Bid +25%", "Bid +50%", "Bid +100%"};
            Item[] items = {Items.GOLD_NUGGET, Items.GOLD_INGOT, Items.GOLD_BLOCK, Items.RAW_GOLD_BLOCK, Items.ENCHANTED_GOLDEN_APPLE};
            int[] slots = {29, 30, 31, 32, 33};
            s.set(22, icon(Items.CLOCK, "§6§lCurrent bid", l.bidder == null ? "§7No bids yet. Start: §6" + Fmt.cd(l.price) : "§6" + Fmt.cd(l.currentBid) + " §7by §f" + (l.bidder.equals(p.getUUID()) ? "You" : l.bidderName),
                    "§7Bids: §f" + l.bidCount, "§8Late bids add 2 minutes to the clock."));
            for (int i = 0; i < bids.length; i++) {
                long amount = Math.max(bids[i], min);
                s.set(slots[i], icon(items[i], "§e§l" + names[i] + ": §6" + Fmt.cd(amount), "§7Click to bid.", "", "§8Your CobbleDollars are held until", "§8you win or are outbid."), pl -> {
                    String err = Market.bid(pl, l.id, amount);
                    if (err != null) { bad(pl); tell(pl, "§c" + err); } else { good(pl); tell(pl, "§aYou bid §6" + Fmt.cd(amount) + "§a on §f" + l.title + "§a."); }
                    detail(pl, l.id, fromMine);
                });
            }
        }
        if (admin && !own) {
            s.set(53, icon(Items.TNT, "§4§lRemove listing (admin)", "§7Returns the goods to the seller's claims."), pl -> {
                String err = Market.cancel(pl, l.id, true);
                if (err != null) { bad(pl); tell(pl, "§c" + err); } else { good(pl); tell(pl, "§aListing removed."); }
                browse(pl);
            });
        }
        open(p, s);
    }

    private static long raise(Listing l, int percent) {
        long base = l.bidder == null ? l.price : l.currentBid;
        return base + Math.max(1, base * percent / 100);
    }

    // ================================================================== your listings

    public static void mine(ServerPlayer p) {
        List<Listing> mine = Market.query(Market.Filter.ALL, Market.Sort.NEWEST, p.getUUID());
        Screen s = new Screen("§5§lMy Listings", 6);
        s.frame(Items.PURPLE_STAINED_GLASS_PANE, Items.MAGENTA_STAINED_GLASS_PANE);
        s.set(4, icon(Items.CHEST, "§d§lMy listings", "§7You have §f" + mine.size() + "§7 of §f" + GtsConfig.INSTANCE.maxListingsPerPlayer + "§7 listings.", "", "§7Click one to see it or cancel it."));
        if (mine.isEmpty()) s.set(22, icon(Items.ENDER_EYE, "§7You have no listings", "§7Use §aSell something§7 in the market."));
        for (int i = 0; i < Math.min(GRID.length, mine.size()); i++) {
            Listing l = mine.get(i);
            s.set(GRID[i], display(l, true), pl -> { click(pl); detail(pl, l.id, true); });
        }
        s.set(45, icon(Items.ARROW, "§e« Back to market"), pl -> { click(pl); browse(pl); });
        open(p, s);
    }

    // ================================================================== claims

    public static void claims(ServerPlayer p) {
        List<ClaimEntry> list = Market.data.claims.get(p.getUUID().toString());
        Screen s = new Screen("§6§l✉ Claims", 6);
        s.frame(Items.ORANGE_STAINED_GLASS_PANE, Items.YELLOW_STAINED_GLASS_PANE);
        int n = list == null ? 0 : list.size();
        s.set(4, icon(Items.ENDER_CHEST, "§6§lClaims", n == 0 ? "§7Nothing is waiting for you." : "§e" + n + (n == 1 ? " thing is" : " things are") + " waiting for you!",
                "", "§7Items go to your inventory,", "§7Pokémon to your party or PC.", "§7Anything that doesn't fit stays here."));
        if (n == 0) s.set(22, icon(Items.ENDER_EYE, "§7Nothing to collect"));
        for (int i = 0; i < Math.min(GRID.length, n); i++) {
            ClaimEntry c = list.get(i);
            ItemStack shown = switch (c.type) {
                case MONEY -> icon(Items.SUNFLOWER, "§6" + Fmt.cd(c.amount));
                default -> { ItemStack it = Market.decode(c.itemSnbt); yield it.isEmpty() ? new ItemStack(Items.PAPER) : it.copy(); }
            };
            s.set(GRID[i], named(shown, "§f" + c.title, "§7" + c.reason));
        }
        if (n > 0) s.set(49, glow(icon(Items.HOPPER, "§a§lCollect everything", "§7Click to collect all you can carry.")), pl -> {
            int got = Market.claimAll(pl);
            if (got > 0) { good(pl); tell(pl, "§aCollected " + got + (got == 1 ? " entry." : " entries.")); } else { bad(pl); tell(pl, "§7Nothing could be collected. Is your inventory or PC full?"); }
            claims(pl);
        });
        s.set(45, icon(Items.ARROW, "§e« Back to market"), pl -> { click(pl); browse(pl); });
        open(p, s);
    }

    // ================================================================== sell menu

    static void sellMenu(ServerPlayer p) {
        Screen s = new Screen("§2§l✚ Sell Something", 5);
        s.frame(Items.GREEN_STAINED_GLASS_PANE, Items.LIME_STAINED_GLASS_PANE);
        s.set(4, icon(Items.EMERALD, "§a§lWhat do you want to sell?", "§7Click a Pokémon or the item in your hand,", "§7then click an option in chat."));
        try {
            Object party = CobblemonBridge.party(p);
            int[] slots = {19, 20, 21, 23, 24, 25};
            for (int i = 0; i < 6; i++) {
                Object pk = CobblemonBridge.partyGet(party, i);
                if (pk == null) { s.set(slots[i], icon(Items.GRAY_STAINED_GLASS_PANE, "§8Empty party slot")); continue; }
                CobblemonBridge.Snap snap = CobblemonBridge.snapshot(pk, Market.server.registryAccess());
                List<String> lore = new ArrayList<>(snap.lines);
                lore.add(""); lore.add("§e▶ Click to list this Pokémon");
                int slot = i + 1;
                s.set(slots[i], named(snap.icon.copy(), snap.title, lore.toArray(new String[0])), pl -> { click(pl); prompt(pl, "Party slot " + slot, "/gts sellpokemon " + slot + " ", "/gts auctionpokemon " + slot + " "); });
            }
        } catch (Throwable t) { GtsMod.LOG.error("[GTS] Could not show the party in the sell menu", t); }
        ItemStack hand = p.getMainHandItem();
        s.set(22, hand.isEmpty()
                ? icon(Items.BARRIER, "§7No item in your hand", "§7Hold the item you want to sell,", "§7then open this menu again.")
                : glow(named(hand.copy(), "§f" + hand.getHoverName().getString() + (hand.getCount() > 1 ? " x" + hand.getCount() : ""), "", "§e▶ Click to list the item in your hand")),
                pl -> { if (pl.getMainHandItem().isEmpty()) { bad(pl); return; } click(pl); prompt(pl, "the item in your hand", "/gts sell ", "/gts auction "); });
        s.set(36, icon(Items.ARROW, "§e« Back to market"), pl -> { click(pl); browse(pl); });
        open(p, s);
    }

    /** Closes the menu and puts two clickable choices in chat that fill in the command. */
    static void prompt(ServerPlayer p, String what, String buyNowCmd, String auctionCmd) {
        p.closeContainer();
        MutableComponent bin = Component.literal("§a§l[Buy It Now]").withStyle(st -> st
                .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, buyNowCmd))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("Type the price after the command: <price> [hours]"))));
        MutableComponent auc = Component.literal("§6§l[Auction]").withStyle(st -> st
                .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, auctionCmd))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("Type the start price and hours: <start> <hours>"))));
        p.sendSystemMessage(Component.literal("§6§l[GTS] §7How do you want to sell " + what + "? ").append(bin).append(Component.literal(" ")).append(auc));
    }
}
