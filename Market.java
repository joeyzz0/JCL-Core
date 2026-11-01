package net.joeyscobblemon.gts;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import com.mojang.serialization.DataResult;

import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.storage.LevelResource;

/** All the rules of the market. Everything runs on the server thread. */
public final class Market {
    private Market() {}

    public static MinecraftServer server;
    public static MarketData data = new MarketData();
    private static boolean dirty;
    private static int ticks;
    private static long lastBoard;

    private static GtsConfig cfg() { return GtsConfig.INSTANCE; }

    // ------------------------------------------------------------------ lifecycle

    private static Path file() {
        return server.getWorldPath(LevelResource.ROOT).resolve("data").resolve("jcl_gts").resolve("market.json");
    }

    public static void start(MinecraftServer s) {
        server = s;
        data = Store.load(file());
        GtsMod.LOG.info("[GTS] Loaded {} listings.", data.listings.size());
        lastBoard = 0;
    }

    public static void stop() {
        if (server != null) Store.save(file(), data);
        server = null;
    }

    public static void markDirty() { dirty = true; }

    public static void onTick() {
        if (server == null) return;
        ticks++;
        if (ticks % 20 == 0) process();
        if (dirty && ticks % 100 == 0) { Store.save(file(), data); dirty = false; }
    }

    private static void process() {
        long now = System.currentTimeMillis();
        for (Listing l : new ArrayList<>(data.listings.values())) {
            if (l.endsAt <= now) { expire(l); continue; }
            if (l.mode == Listing.Mode.AUCTION && !l.endingWarned && l.endsAt - now <= cfg().endingSoonMinutes * 60_000L) {
                l.endingWarned = true;
                markDirty();
                if (cfg().announceAuctionEndingSoon) Webhook.endingSoon(l);
            }
        }
        if (cfg().liveBoard && !cfg().webhookUrl.isBlank() && now - lastBoard >= cfg().boardIntervalSeconds * 1000L) {
            lastBoard = now;
            Webhook.updateBoard();
        }
    }

    // ------------------------------------------------------------------ item encoding
    //
    // Items are stored as Minecraft's own binary NBT, base64 encoded, with a "b64:" prefix.
    // Older saves used SNBT text; decode() still reads those.

    private static final String B64 = "b64:";

    public static String encode(ItemStack s) {
        if (s == null || s.isEmpty()) return "";
        Tag tag = s.save(server.registryAccess());
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(bytes)) {
                NbtIo.write((CompoundTag) tag, out);
            }
            return B64 + Base64.getEncoder().encodeToString(bytes.toByteArray());
        } catch (Exception e) {
            throw new IllegalStateException("Could not write item data", e);
        }
    }

    public static ItemStack decode(String stored) {
        if (stored == null || stored.isEmpty()) return ItemStack.EMPTY;
        CompoundTag tag;
        try {
            if (stored.startsWith(B64)) {
                byte[] raw = Base64.getDecoder().decode(stored.substring(B64.length()));
                try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(raw))) {
                    tag = NbtIo.read(in, NbtAccounter.unlimitedHeap());
                }
            } else {
                tag = TagParser.parseTag(stored);
            }
        } catch (Exception e) {
            GtsMod.LOG.error("[GTS] Could not read saved item data", e);
            return ItemStack.EMPTY;
        }
        if (tag.isEmpty()) return ItemStack.EMPTY;
        DataResult<ItemStack> result = ItemStack.CODEC.parse(server.registryAccess().createSerializationContext(NbtOps.INSTANCE), tag);
        if (result.error().isPresent()) {
            GtsMod.LOG.error("[GTS] Could not rebuild a saved item: {} | data: {}", result.error().get().message(), tag);
        }
        return result.result().orElse(ItemStack.EMPTY);
    }

    /**
     * Saves the item, reads it straight back and compares. Returns the saved data,
     * or null if the item does not come back identical (then it must not be listed).
     */
    public static String encodeVerified(ItemStack s) {
        try {
            String stored = encode(s);
            ItemStack back = decode(stored);
            if (!back.isEmpty() && back.getCount() == s.getCount() && ItemStack.isSameItemSameComponents(back, s)) return stored;
            GtsMod.LOG.warn("[GTS] {} did not survive a save test, so it was not listed.", s);
        } catch (Exception e) {
            GtsMod.LOG.error("[GTS] Could not save {} for the GTS", s, e);
        }
        return null;
    }

    // ------------------------------------------------------------------ queries

    public enum Filter { ALL, POKEMON, ITEMS, AUCTIONS, BUY_NOW }
    public enum Sort { NEWEST, ENDING_SOON, PRICE_LOW, PRICE_HIGH }

    public static long shownPrice(Listing l) {
        return l.mode == Listing.Mode.AUCTION && l.bidder != null ? l.currentBid : l.price;
    }

    public static List<Listing> query(Filter f, Sort s, UUID onlySeller) {
        List<Listing> out = new ArrayList<>();
        for (Listing l : data.listings.values()) {
            if (onlySeller != null && !l.seller.equals(onlySeller)) continue;
            switch (f) {
                case POKEMON -> { if (l.kind != Listing.Kind.POKEMON) continue; }
                case ITEMS -> { if (l.kind != Listing.Kind.ITEM) continue; }
                case AUCTIONS -> { if (l.mode != Listing.Mode.AUCTION) continue; }
                case BUY_NOW -> { if (l.mode != Listing.Mode.BUY_NOW) continue; }
                default -> {}
            }
            out.add(l);
        }
        Comparator<Listing> c = switch (s) {
            case NEWEST -> Comparator.comparingLong((Listing l) -> l.createdAt).reversed();
            case ENDING_SOON -> Comparator.comparingLong(l -> l.endsAt);
            case PRICE_LOW -> Comparator.comparingLong(Market::shownPrice);
            case PRICE_HIGH -> Comparator.comparingLong(Market::shownPrice).reversed();
        };
        out.sort(c);
        return out;
    }

    public static long minNextBid(Listing l) {
        if (l.bidder == null) return l.price;
        long step = Math.max(cfg().minBidIncrement, l.currentBid * cfg().bidIncrementPercent / 100);
        return l.currentBid + step;
    }

    public static int countOf(UUID seller) {
        int n = 0;
        for (Listing l : data.listings.values()) if (l.seller.equals(seller)) n++;
        return n;
    }

    /** True if an item listing's goods can't be rebuilt right now. */
    private static boolean undeliverable(Listing l) {
        return l.kind == Listing.Kind.ITEM && decode(l.itemSnbt).isEmpty();
    }

    // ------------------------------------------------------------------ creating listings

    private static String validate(ServerPlayer p, Listing.Mode mode, long price, int hours) {
        if (price < cfg().minPrice) return "The lowest price allowed is " + Fmt.cd(cfg().minPrice) + ".";
        if (price > cfg().maxPrice) return "The highest price allowed is " + Fmt.cd(cfg().maxPrice) + ".";
        int max = mode == Listing.Mode.AUCTION ? cfg().maxAuctionHours : cfg().maxBuyNowHours;
        if (hours < 1 || hours > max) return "Choose a duration from 1 to " + max + " hours.";
        if (countOf(p.getUUID()) >= cfg().maxListingsPerPlayer) return "You already have " + cfg().maxListingsPerPlayer + " listings, the maximum.";
        return null;
    }

    private static String newId() {
        String id;
        do {
            id = Integer.toString((int) (Math.random() * 0x7fffffff), 36).toUpperCase();
            while (id.length() < 4) id = id + "0";
            id = id.substring(0, 4);
        } while (data.listings.containsKey(id));
        return id;
    }

    private static Listing base(ServerPlayer p, Listing.Kind kind, Listing.Mode mode, long price, int hours) {
        Listing l = new Listing();
        l.id = newId();
        l.seller = p.getUUID();
        l.sellerName = p.getGameProfile().getName();
        l.kind = kind;
        l.mode = mode;
        l.price = price;
        l.createdAt = System.currentTimeMillis();
        l.endsAt = l.createdAt + hours * 3_600_000L;
        return l;
    }

    public static boolean isRestricted(ItemStack s) {
        ResourceLocation key = BuiltInRegistries.ITEM.getKey(s.getItem());
        return cfg().restrictedItems.contains(key.toString());
    }

    /** Lists the stack in the player's main hand. Returns an error message, or null if it worked. */
    public static String listHeldItem(ServerPlayer p, Listing.Mode mode, long price, int hours) {
        String err = validate(p, mode, price, hours);
        if (err != null) return err;
        ItemStack held = p.getMainHandItem();
        if (held.isEmpty()) return "Hold the item you want to sell in your main hand.";
        if (isRestricted(held)) return "That item can't be sold on the GTS.";
        ResourceLocation key = BuiltInRegistries.ITEM.getKey(held.getItem());
        if (key.getNamespace().equals("brecher_breeding") && key.getPath().contains("egg")) {
            try {
                List<Component> tip = held.getTooltipLines(Item.TooltipContext.of(p.level()), p, TooltipFlag.NORMAL);
                boolean shows = false;
                for (Component c : tip) if (c.getString().contains("IVs")) shows = true;
                if (!shows) return "This egg's contents are still hidden. Reveal the egg first, so buyers can see its nature and IVs.";
            } catch (Throwable ignored) {}
        }
        ItemStack stack = held.copy();
        String stored = encodeVerified(stack);
        if (stored == null) return "This item's data can't be stored safely, so it can't be listed on the GTS. It's still in your hand.";
        Listing l = base(p, Listing.Kind.ITEM, mode, price, hours);
        l.itemSnbt = stored;
        l.title = stack.getHoverName().getString() + (stack.getCount() > 1 ? " x" + stack.getCount() : "");
        p.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        add(l);
        return null;
    }

    /** Lists the Pokémon in a party slot (1 to 6). */
    public static String listPokemon(ServerPlayer p, int slot, Listing.Mode mode, long price, int hours) {
        String err = validate(p, mode, price, hours);
        if (err != null) return err;
        if (!CobblemonBridge.available()) return "Cobblemon is not available, so Pokémon can't be listed.";
        try {
            Object party = CobblemonBridge.party(p);
            Object pk = CobblemonBridge.partyGet(party, slot - 1);
            if (pk == null) return "There is no Pokémon in party slot " + slot + ".";
            if (cfg().blockLastPokemon && CobblemonBridge.partyCount(party) <= 1) return "You can't list your only Pokémon.";
            CobblemonBridge.Snap snap = CobblemonBridge.snapshot(pk, server.registryAccess());
            CompoundTag nbt = CobblemonBridge.toNbt(pk, server.registryAccess());
            Listing l = base(p, Listing.Kind.POKEMON, mode, price, hours);
            l.title = snap.title;
            l.details = snap.lines;
            l.itemSnbt = encode(snap.icon);
            l.pokemonSnbt = nbt.toString();
            if (!CobblemonBridge.removeFromParty(party, pk)) return "Could not take that Pokémon out of your party.";
            add(l);
            return null;
        } catch (Throwable t) {
            GtsMod.LOG.error("[GTS] Listing a Pokémon failed", t);
            return "Something went wrong reading that Pokémon. Nothing was changed.";
        }
    }

    private static void add(Listing l) {
        data.listings.put(l.id, l);
        markDirty();
        Store.save(file(), data);
        ServerPlayer sp = server.getPlayerList().getPlayer(l.seller);
        if (sp != null) {
            sp.sendSystemMessage(Fmt.chat("[GTS] ", ChatFormatting.GOLD).append(Fmt.chat("Listed ", ChatFormatting.GREEN))
                    .append(Component.literal(l.title)).append(Fmt.chat(" for " + Fmt.cd(l.price) + (l.mode == Listing.Mode.AUCTION ? " (auction start)" : "") + ". ID #" + l.id, ChatFormatting.GREEN)));
        }
        if (cfg().announceNewListings) Webhook.listed(l);
    }

    // ------------------------------------------------------------------ buying and bidding

    public static String buy(ServerPlayer buyer, String id) {
        Listing l = data.listings.get(id);
        if (l == null) return "That listing is gone.";
        if (l.mode != Listing.Mode.BUY_NOW) return "That one is an auction. Place a bid instead.";
        if (l.seller.equals(buyer.getUUID())) return "You can't buy your own listing. Cancel it instead.";
        if (l.endsAt <= System.currentTimeMillis()) return "That listing has just ended.";
        if (undeliverable(l)) return "This item can't be delivered right now. You were not charged. Tell an admin (listing #" + l.id + ").";
        long bal = Economy.balance(buyer);
        if (bal < 0) return "Could not read your CobbleDollars balance. Tell an admin.";
        if (bal < l.price) return "You need " + Fmt.cd(l.price) + " but only have " + Fmt.cd(bal) + ".";
        if (!Economy.take(buyer, l.price)) return "The payment did not go through. You were not charged.";
        data.listings.remove(l.id);
        giveGoods(buyer.getUUID(), buyer.getGameProfile().getName(), l, "Bought on the GTS");
        pay(l.seller, l.sellerName, l.price, "Sold " + l.title + " on the GTS");
        tell(l.seller, Fmt.chat("[GTS] ", ChatFormatting.GOLD).append(Fmt.chat(buyer.getGameProfile().getName() + " bought your ", ChatFormatting.GREEN)).append(Component.literal(l.title)).append(Fmt.chat(" for " + Fmt.cd(l.price) + ".", ChatFormatting.GREEN)));
        markDirty();
        Store.save(file(), data);
        if (cfg().announceSales) Webhook.sold(l, buyer.getGameProfile().getName());
        return null;
    }

    public static String bid(ServerPlayer p, String id, long amount) {
        Listing l = data.listings.get(id);
        if (l == null) return "That auction is gone.";
        if (l.mode != Listing.Mode.AUCTION) return "That is a Buy It Now listing.";
        if (l.seller.equals(p.getUUID())) return "You can't bid on your own auction.";
        long now = System.currentTimeMillis();
        if (l.endsAt <= now) return "That auction has just ended.";
        if (l.bidder != null && l.bidder.equals(p.getUUID())) return "You are already the highest bidder.";
        if (undeliverable(l)) return "This item can't be delivered right now, so bidding is paused. Tell an admin (listing #" + l.id + ").";
        long min = minNextBid(l);
        if (amount < min) return "The lowest bid right now is " + Fmt.cd(min) + ".";
        if (amount > cfg().maxPrice) return "The highest bid allowed is " + Fmt.cd(cfg().maxPrice) + ".";
        long bal = Economy.balance(p);
        if (bal < 0) return "Could not read your CobbleDollars balance. Tell an admin.";
        if (bal < amount) return "You need " + Fmt.cd(amount) + " but only have " + Fmt.cd(bal) + ".";
        if (!Economy.take(p, amount)) return "The payment did not go through. You were not charged.";
        if (l.bidder != null) {
            pay(l.bidder, l.bidderName, l.currentBid, "Outbid on " + l.title);
            tell(l.bidder, Fmt.chat("[GTS] ", ChatFormatting.GOLD).append(Fmt.chat("You were outbid on ", ChatFormatting.YELLOW)).append(Component.literal(l.title)).append(Fmt.chat(". Your " + Fmt.cd(l.currentBid) + " was refunded.", ChatFormatting.YELLOW)));
        }
        l.currentBid = amount;
        l.bidder = p.getUUID();
        l.bidderName = p.getGameProfile().getName();
        l.bidCount++;
        long left = l.endsAt - now;
        if (left <= cfg().antiSnipeSeconds * 1000L && l.extensions < cfg().maxExtensions) {
            l.endsAt += cfg().antiSnipeExtendSeconds * 1000L;
            l.extensions++;
        }
        markDirty();
        Store.save(file(), data);
        tell(l.seller, Fmt.chat("[GTS] ", ChatFormatting.GOLD).append(Fmt.chat(l.bidderName + " bid " + Fmt.cd(amount) + " on your ", ChatFormatting.GREEN)).append(Component.literal(l.title)).append(Fmt.chat(".", ChatFormatting.GREEN)));
        return null;
    }

    public static String cancel(ServerPlayer actor, String id, boolean admin) {
        Listing l = data.listings.get(id);
        if (l == null) return "That listing is gone.";
        boolean owner = l.seller.equals(actor.getUUID());
        if (!owner && !admin) return "That isn't your listing.";
        if (owner && !admin && l.mode == Listing.Mode.AUCTION && l.bidder != null) return "You can't cancel an auction that already has bids.";
        data.listings.remove(id);
        if (l.bidder != null) {
            pay(l.bidder, l.bidderName, l.currentBid, "Auction cancelled: " + l.title);
            tell(l.bidder, Fmt.chat("[GTS] ", ChatFormatting.GOLD).append(Fmt.chat("An auction you bid on was cancelled. Your " + Fmt.cd(l.currentBid) + " was refunded.", ChatFormatting.YELLOW)));
        }
        returnToSeller(l, "Listing cancelled");
        markDirty();
        Store.save(file(), data);
        return null;
    }

    // ------------------------------------------------------------------ endings

    private static void expire(Listing l) {
        data.listings.remove(l.id);
        if (l.mode == Listing.Mode.AUCTION && l.bidder != null) {
            giveGoods(l.bidder, l.bidderName, l, "Won on the GTS");
            pay(l.seller, l.sellerName, l.currentBid, "Auction sold: " + l.title);
            tell(l.bidder, Fmt.chat("[GTS] ", ChatFormatting.GOLD).append(Fmt.chat("You won ", ChatFormatting.GREEN)).append(Component.literal(l.title)).append(Fmt.chat(" for " + Fmt.cd(l.currentBid) + "!", ChatFormatting.GREEN)));
            tell(l.seller, Fmt.chat("[GTS] ", ChatFormatting.GOLD).append(Fmt.chat("Your auction for ", ChatFormatting.GREEN)).append(Component.literal(l.title)).append(Fmt.chat(" sold for " + Fmt.cd(l.currentBid) + " to " + l.bidderName + ".", ChatFormatting.GREEN)));
            if (cfg().announceSales) Webhook.auctionWon(l);
        } else {
            returnToSeller(l, "Listing expired");
            tell(l.seller, Fmt.chat("[GTS] ", ChatFormatting.GOLD).append(Fmt.chat("Your listing for ", ChatFormatting.YELLOW)).append(Component.literal(l.title)).append(Fmt.chat(" ended without a sale. Collect it with /gts claims.", ChatFormatting.YELLOW)));
        }
        markDirty();
    }

    // ------------------------------------------------------------------ delivery and claims

    private static void tell(UUID id, Component c) {
        ServerPlayer p = server.getPlayerList().getPlayer(id);
        if (p != null) p.sendSystemMessage(c);
    }

    /** Pays money now if the player is online, otherwise saves it in their claims. */
    private static void pay(UUID id, String name, long amount, String reason) {
        if (amount <= 0) return;
        ServerPlayer p = server.getPlayerList().getPlayer(id);
        if (p != null && Economy.give(p, amount)) {
            p.sendSystemMessage(Fmt.chat("[GTS] ", ChatFormatting.GOLD).append(Fmt.chat("+" + Fmt.cd(amount) + " (" + reason + ")", ChatFormatting.GREEN)));
            return;
        }
        ClaimEntry c = new ClaimEntry();
        c.type = ClaimEntry.Type.MONEY;
        c.amount = amount;
        c.title = Fmt.cd(amount);
        c.reason = reason;
        data.claimsOf(id).add(c);
    }

    private static void returnToSeller(Listing l, String reason) {
        giveGoods(l.seller, l.sellerName, l, reason);
    }

    /**
     * Hands the goods to the player directly if they are online and have room, otherwise stores them in claims.
     * If an item can't be rebuilt, its saved data is kept in claims untouched, never thrown away.
     */
    private static void giveGoods(UUID id, String name, Listing l, String reason) {
        ServerPlayer p = server.getPlayerList().getPlayer(id);
        if (l.kind == Listing.Kind.ITEM) {
            ItemStack s = decode(l.itemSnbt);
            if (s.isEmpty()) {
                GtsMod.LOG.error("[GTS] Listing #{} ({}) could not be rebuilt. Its data was kept in {}'s claims.", l.id, l.title, name);
                ClaimEntry keep = new ClaimEntry();
                keep.type = ClaimEntry.Type.ITEM;
                keep.itemSnbt = l.itemSnbt;
                keep.title = l.title;
                keep.reason = reason;
                data.claimsOf(id).add(keep);
                return;
            }
            if (p != null) p.getInventory().add(s);
            if (s.isEmpty()) return;
            ClaimEntry c = new ClaimEntry();
            c.type = ClaimEntry.Type.ITEM;
            c.itemSnbt = encode(s);
            c.title = s.getHoverName().getString() + (s.getCount() > 1 ? " x" + s.getCount() : "");
            c.reason = reason;
            data.claimsOf(id).add(c);
        } else {
            if (p != null) {
                try {
                    Object pk = CobblemonBridge.fromNbt(server.registryAccess(), TagParser.parseTag(l.pokemonSnbt));
                    if (CobblemonBridge.give(p, pk)) return;
                } catch (Throwable t) {
                    GtsMod.LOG.error("[GTS] Could not hand over a Pokémon directly", t);
                }
            }
            ClaimEntry c = new ClaimEntry();
            c.type = ClaimEntry.Type.POKEMON;
            c.itemSnbt = l.itemSnbt;
            c.pokemonSnbt = l.pokemonSnbt;
            c.title = l.title;
            c.reason = reason;
            data.claimsOf(id).add(c);
        }
    }

    public static int claimCount(UUID id) {
        List<ClaimEntry> l = data.claims.get(id.toString());
        return l == null ? 0 : l.size();
    }

    /** Gives the player everything waiting for them that fits. Returns how many entries were collected. */
    public static int claimAll(ServerPlayer p) {
        List<ClaimEntry> list = data.claims.get(p.getUUID().toString());
        if (list == null || list.isEmpty()) return 0;
        int done = 0;
        int stuck = 0;
        for (ClaimEntry c : new ArrayList<>(list)) {
            boolean ok = false;
            switch (c.type) {
                case MONEY -> ok = Economy.give(p, c.amount);
                case ITEM -> {
                    ItemStack s = decode(c.itemSnbt);
                    if (s.isEmpty()) { stuck++; break; }
                    p.getInventory().add(s);
                    if (s.isEmpty()) ok = true; else c.itemSnbt = encode(s);
                }
                case POKEMON -> {
                    try {
                        Object pk = CobblemonBridge.fromNbt(server.registryAccess(), TagParser.parseTag(c.pokemonSnbt));
                        ok = CobblemonBridge.give(p, pk);
                    } catch (Throwable t) { GtsMod.LOG.error("[GTS] Could not hand over a claimed Pokémon", t); }
                }
            }
            if (ok) { list.remove(c); done++; }
        }
        if (stuck > 0) {
            p.sendSystemMessage(Fmt.chat("[GTS] ", ChatFormatting.GOLD).append(Fmt.chat(stuck + " item(s) couldn't be delivered and are being kept safe in your claims. Tell an admin.", ChatFormatting.RED)));
        }
        markDirty();
        Store.save(file(), data);
        return done;
    }
}
