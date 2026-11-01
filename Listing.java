package net.joeyscobblemon.gts;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** One entry on the market. Everything is stored as plain strings so it saves cleanly to JSON. */
public class Listing {
    public enum Kind { ITEM, POKEMON }
    public enum Mode { BUY_NOW, AUCTION }

    public String id;
    public UUID seller;
    public String sellerName;
    public Kind kind;
    public Mode mode;

    /** ITEM: the real item stack (SNBT). POKEMON: the icon stack used in menus. */
    public String itemSnbt;
    /** POKEMON only: the saved Pokemon data (SNBT). */
    public String pokemonSnbt;
    public String title;
    public List<String> details = new ArrayList<>();

    /** Buy It Now price, or the starting bid for auctions. */
    public long price;
    public long currentBid;
    public UUID bidder;
    public String bidderName;
    public int bidCount;

    public long createdAt;
    public long endsAt;
    public int extensions;
    public boolean endingWarned;
}
