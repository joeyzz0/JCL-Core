package net.joeyscobblemon.gts;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Rotating chat announcements (Twitch, Discord, voting, tips). Config: config/jcl_announcer.json.
 * Links written as [text](https://url) become clickable. Messages with enabled=false are skipped,
 * so the voting message can sit ready until you set it up.
 */
@EventBusSubscriber(modid = "jcl_gts")
public final class Announcer {
    private Announcer() {}

    public static final class Message {
        public boolean enabled = true;
        public List<String> lines = new ArrayList<>();
        Message() {}
        Message(boolean enabled, String... lines) { this.enabled = enabled; this.lines = List.of(lines); }
    }

    public static final class Config {
        public int version = 4;
        public boolean enabled = true;
        public int intervalMinutes = 8;
        public int minPlayersOnline = 1;
        public boolean randomOrder = false;
        public String prefix = "§6✦ ";
        public List<Message> messages = List.of(
                new Message(true,
                        "§5§lLive on Twitch",
                        "§7Legendary summons, shiny hunts and server events happen on stream.",
                        "§d▶ [twitch.tv/joeyzz0](https://twitch.tv/joeyzz0)"),
                new Message(true,
                        "§9§lJoin the Discord",
                        "§7Live auctions, update news, events and the community all live there.",
                        "§b▶ [Click to join](https://discord.gg/4hHNvcJagV)"),
                new Message(false,
                        "§a§lVote for the server",
                        "§7Every vote helps new trainers find us, and earns you rewards.",
                        "§a▶ [Vote here](https://example.com/vote)"),
                new Message(true,
                        "§e§lTip: Sweeps pay keys",
                        "§7Finish every daily chore for §e5 Poké Ball Keys§7.",
                        "§7All weeklies pay §b3 Great Ball Keys§7, and all monthlies §d2 Ultra Ball Keys§7."),
                new Message(true,
                        "§6§lTip: Merge your keys",
                        "§7Five keys of one tier merge into a key of the next tier, for a small fee.",
                        "§7Open the merge menu from any crate or from §f/shop§7."),
                new Message(true,
                        "§6§lTip: Check the odds",
                        "§7Left-click any crate to see every reward, its exact chance and your pity progress."),
                new Message(true,
                        "§a§lTip: The shop",
                        "§7Poké Balls, healing, training items, keys, titles and name colours are all in §f/shop§7."),
                new Message(true,
                        "§a§lTip: Selling",
                        "§7Hold an item and type §f/sell§7 to sell it. §f/sell all§7 sells every one you're carrying."),
                new Message(true,
                        "§d§lTip: Wonder Trade",
                        "§7Send a Pokémon into the unknown with §f/wondertrade§7 and see what comes back."),
                new Message(true,
                        "§b§lTip: The GTS",
                        "§7Buy, sell and auction with other trainers in §f/gts§7.",
                        "§7Earnings from offline sales wait for you in §f/gts claims§7."),
                new Message(true,
                        "§c§lTip: Can't level up?",
                        "§7Your Level Cap rises as you beat trainers. Pick a series at any Trainer Association."),
                new Message(true,
                        "§6§lTip: Titles",
                        "§7Show off a title in chat and the tab list. Pick one with §f/titles§7."),
                new Message(true,
                        "§e§lTip: Lost?",
                        "§7Type §f/help§7 for every command, or §f/jobs§7 to see what to do next."),
                new Message(true,
                        "§e§lTip: Shiny alerts",
                        "§7You get a ping when something rare spawns near you. Keep a Poké Ball ready!"),
                new Message(true,
                        "§0§l◆ §8§lThe Black Sharestone",
                        "§7The black sharestone at spawn links every §fofficial§7 safe zone on the server.",
                        "§7Craft your own, or use the one in Port Azure to travel."),
                new Message(true,
                        "§c§l◆ Red Sharestones",
                        "§7Red sharestones mark §fcommunity-built§7 safe zones. Both kinds can take you back to spawn."),
                new Message(true,
                        "§b§lTip: Getting around",
                        "§7Use §f/warp§7 for spawn, EV training, crates and the shop, §f/rtp§7 for the wild,",
                        "§7and §f/tpa <player>§7 to visit a friend."),
                new Message(true,
                        "§e§lTip: Jobs",
                        "§7Townsfolk with jobs for you will tell you where to go next. Check your progress with §f/jobs§7."),
                new Message(true,
                        "§6§lTip: Your first steps",
                        "§7Pick a trainer series at a Trainer Association, then beat gym leaders to earn badges",
                        "§7and raise your Level Cap."),
                new Message(true,
                        "§d§lTip: The Master Ribbon",
                        "§7Beat every trainer series on the server to earn the legendary §6Master Ribbon§7."),
                new Message(true,
                        "§a§lTip: Shiny hunting",
                        "§7Season Poké Bait with a Starf Berry or a Golden Apple to boost your shiny odds.",
                        "§7The Pokémon Cooking chapter in the quest book has the best recipes."),
                new Message(true,
                        "§9§lTip: EV training",
                        "§7Head to the EV Training gate at spawn and hold a Power item to train twice as fast."),
                new Message(true,
                        "§6§lNever pay-to-win",
                        "§7Every rank, title and cosmetic here is earned in game. Always."));
    }

    private static Config cfg = new Config();
    private static MinecraftServer server;
    private static long ticks;
    private static int next;

    @SubscribeEvent
    public static void onStart(ServerStartedEvent e) {
        server = e.getServer();
        cfg = Tablist.loadConfig("jcl_announcer.json", Config.class, new Config());
    }

    @SubscribeEvent
    public static void onTick(ServerTickEvent.Post e) {
        if (server == null || !cfg.enabled || cfg.messages.isEmpty()) return;
        if (++ticks % (Math.max(1, cfg.intervalMinutes) * 1200L) != 0) return;
        if (server.getPlayerCount() < cfg.minPlayersOnline) return;
        List<Message> live = cfg.messages.stream().filter(m -> m.enabled && m.lines != null && !m.lines.isEmpty()).toList();
        if (live.isEmpty()) return;
        Message m = cfg.randomOrder ? live.get((int) (Math.random() * live.size())) : live.get(next++ % live.size());
        server.getPlayerList().broadcastSystemMessage(Component.literal(" "), false);
        boolean first = true;
        for (String line : m.lines) {
            MutableComponent c = first ? Component.literal(cfg.prefix) : Component.literal("");
            server.getPlayerList().broadcastSystemMessage(c.append(withLinks(line)), false);
            first = false;
        }
        server.getPlayerList().broadcastSystemMessage(Component.literal(" "), false);
    }

    private static final Pattern LINK = Pattern.compile("\\[([^\\]]+)]\\((https?://[^)]+)\\)");

    /** Turns [text](url) into clickable, underlined text with a hover hint. */
    static MutableComponent withLinks(String line) {
        MutableComponent out = Component.literal("");
        Matcher m = LINK.matcher(line);
        int last = 0;
        String colour = "";
        while (m.find()) {
            String before = line.substring(last, m.start());
            out.append(Component.literal(before));
            Matcher c = Pattern.compile("§[0-9a-fk-or]").matcher(before);
            while (c.find()) colour = c.group().equals("§r") ? "" : colour + c.group();
            String url = m.group(2);
            out.append(Component.literal(colour + "§n" + m.group(1)).withStyle(st -> st
                    .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, url))
                    .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("§7Open " + url)))));
            last = m.end();
        }
        out.append(Component.literal(line.substring(last)));
        return out;
    }
}
