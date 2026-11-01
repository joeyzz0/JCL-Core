package net.joeyscobblemon.gts;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Posts to Discord. Everything is asynchronous so it can never lag the server. */
public final class Webhook {
    private Webhook() {}

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    private static final int GOLD = 0xF5B301, GREEN = 0x2ECC71, BLUE = 0x3498DB, ORANGE = 0xE67E22;

    private static boolean on() { return !GtsConfig.INSTANCE.webhookUrl.isBlank(); }

    private static String clean(String s) {
        return s == null ? "" : s.replaceAll("§.", "").replace("@", "@\u200b");
    }

    private static JsonObject body(String title, String description, int color) {
        JsonObject embed = new JsonObject();
        embed.addProperty("title", title);
        embed.addProperty("description", description);
        embed.addProperty("color", color);
        embed.addProperty("timestamp", Instant.now().toString());
        JsonObject footer = new JsonObject();
        footer.addProperty("text", "Global Trade System");
        embed.add("footer", footer);
        return wrap(embed);
    }

    private static JsonObject wrap(JsonObject embed) {
        JsonObject o = new JsonObject();
        o.addProperty("username", GtsConfig.INSTANCE.webhookName);
        JsonArray arr = new JsonArray();
        arr.add(embed);
        o.add("embeds", arr);
        JsonObject am = new JsonObject();
        am.add("parse", new JsonArray());
        o.add("allowed_mentions", am);
        return o;
    }

    private static void post(JsonObject json) {
        if (!on()) return;
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(GtsConfig.INSTANCE.webhookUrl))
                    .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.toString())).build();
            HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString()).thenAccept(r -> {
                if (r.statusCode() >= 300) GtsMod.LOG.warn("[GTS] Discord answered {}: {}", r.statusCode(), r.body());
            }).exceptionally(e -> { GtsMod.LOG.warn("[GTS] Discord post failed: {}", e.toString()); return null; });
        } catch (Exception e) {
            GtsMod.LOG.warn("[GTS] Discord post failed: {}", e.toString());
        }
    }

    private static String ts(long ms) { return "<t:" + (ms / 1000) + ":R>"; }

    private static String how(Listing l) {
        return l.mode == Listing.Mode.AUCTION ? "Auction" : "Buy It Now";
    }

    public static void listed(Listing l) {
        if (!on()) return;
        String d = "**" + clean(l.title) + "**\n"
                + (l.kind == Listing.Kind.POKEMON ? "Pokémon\n" : "Item\n")
                + (l.mode == Listing.Mode.AUCTION ? "Starting bid: **" + Fmt.cd(l.price) + "**\nEnds " + ts(l.endsAt) : "Price: **" + Fmt.cd(l.price) + "**\nExpires " + ts(l.endsAt))
                + "\nSeller: **" + clean(l.sellerName) + "**\nListing ID: `#" + l.id + "`";
        if (l.kind == Listing.Kind.POKEMON && !l.details.isEmpty()) {
            StringBuilder sb = new StringBuilder("\n\n");
            for (String s : l.details) sb.append(clean(s)).append('\n');
            d += sb;
        }
        post(body("🆕 New " + how(l) + " listing", d, l.mode == Listing.Mode.AUCTION ? ORANGE : BLUE));
    }

    public static void sold(Listing l, String buyer) {
        post(body("💰 Sold!", "**" + clean(l.title) + "**\nSold for **" + Fmt.cd(l.price) + "**\nSeller: " + clean(l.sellerName) + "\nBuyer: " + clean(buyer), GREEN));
    }

    public static void auctionWon(Listing l) {
        post(body("🏆 Auction ended", "**" + clean(l.title) + "**\nWinning bid: **" + Fmt.cd(l.currentBid) + "**\nWinner: " + clean(l.bidderName)
                + "\nSeller: " + clean(l.sellerName) + "\nBids: " + l.bidCount, GREEN));
    }

    public static void endingSoon(Listing l) {
        String bid = l.bidder == null ? "No bids yet. Starting at **" + Fmt.cd(l.price) + "**" : "Top bid: **" + Fmt.cd(l.currentBid) + "** by " + clean(l.bidderName);
        post(body("⏰ Auction ending soon", "**" + clean(l.title) + "**\n" + bid + "\nEnds " + ts(l.endsAt) + "\nSeller: " + clean(l.sellerName) + "\nListing ID: `#" + l.id + "`", GOLD));
    }

    /** Keeps one message up to date with the live auctions. */
    public static void updateBoard() {
        if (!on()) return;
        List<Listing> auctions = new ArrayList<>(Market.query(Market.Filter.AUCTIONS, Market.Sort.ENDING_SOON, null));
        int buyNow = Market.query(Market.Filter.BUY_NOW, Market.Sort.NEWEST, null).size();
        StringBuilder sb = new StringBuilder();
        if (auctions.isEmpty()) sb.append("No live auctions right now.\n");
        int shown = 0;
        for (Listing l : auctions) {
            if (shown++ >= 15) break;
            sb.append("• **").append(clean(l.title)).append("** — ")
                    .append(l.bidder == null ? "start " + Fmt.cd(l.price) : "bid " + Fmt.cd(l.currentBid))
                    .append(" — ends ").append(ts(l.endsAt)).append('\n');
        }
        if (auctions.size() > 15) sb.append("…and ").append(auctions.size() - 15).append(" more\n");
        sb.append("\n🛒 **").append(buyNow).append("** Buy It Now listings. Use `/gts` in game.");
        JsonObject json = body("🔨 Live Auctions", sb.toString(), GOLD);
        String id = Market.data.boardMessageId;
        try {
            String base = GtsConfig.INSTANCE.webhookUrl;
            HttpRequest.Builder b;
            if (id == null) {
                b = HttpRequest.newBuilder(URI.create(base + (base.contains("?") ? "&" : "?") + "wait=true")).POST(HttpRequest.BodyPublishers.ofString(json.toString()));
            } else {
                String root = base.contains("?") ? base.substring(0, base.indexOf('?')) : base;
                b = HttpRequest.newBuilder(URI.create(root + "/messages/" + id)).method("PATCH", HttpRequest.BodyPublishers.ofString(json.toString()));
            }
            HttpRequest req = b.timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json").build();
            final boolean creating = id == null;
            HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString()).thenAccept(r -> {
                if (r.statusCode() == 404 && !creating) {
                    Market.server.execute(() -> { Market.data.boardMessageId = null; Market.markDirty(); });
                } else if (r.statusCode() < 300 && creating) {
                    try {
                        String newId = JsonParser.parseString(r.body()).getAsJsonObject().get("id").getAsString();
                        Market.server.execute(() -> { Market.data.boardMessageId = newId; Market.markDirty(); });
                    } catch (Exception e) { GtsMod.LOG.warn("[GTS] Could not read the Discord message id", e); }
                } else if (r.statusCode() >= 300) {
                    GtsMod.LOG.warn("[GTS] Discord board update answered {}: {}", r.statusCode(), r.body());
                }
            }).exceptionally(e -> { GtsMod.LOG.warn("[GTS] Discord board update failed: {}", e.toString()); return null; });
        } catch (Exception e) {
            GtsMod.LOG.warn("[GTS] Discord board update failed: {}", e.toString());
        }
    }

    public static void test() {
        post(body("✅ GTS webhook connected", "The Global Trade System can post to this channel.", GREEN));
    }
}
