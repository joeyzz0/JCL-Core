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
        public boolean enabled = true;
        public int intervalMinutes = 8;
        public int minPlayersOnline = 1;
        public boolean randomOrder = false;
        public String prefix = "§6§l✦ §e§lLEAGUE §6§l✦ §r";
        public List<Message> messages = List.of(
                new Message(true,
                        "§5§lWe're live on Twitch!",
                        "§7Legendary summons, shiny hunts and server events happen on stream.",
                        "§d▶ [twitch.tv/joeyzz0](https://twitch.tv/joeyzz0)"),
                new Message(true,
                        "§9§lJoin the Discord!",
                        "§7Live auctions, update news, events and the community.",
                        "§b▶ [Click to join](https://discord.gg/4hHNvcJagV)"),
                new Message(false,
                        "§a§lVote for the server!",
                        "§7Every vote helps new trainers find us, and earns you rewards.",
                        "§a▶ [Vote here](https://example.com/vote)"),
                new Message(true,
                        "§e§lTip: Daily Sweep",
                        "§7Finish every daily chore to earn §e5 Poké Ball Keys§7. Weeklies pay §b3 Great Ball Keys§7,",
                        "§7monthlies §d2 Ultra Ball Keys§7, and doing it all earns a §6Master Ball Key§7!"),
                new Message(true,
                        "§6§lTip: Upgrade your keys",
                        "§7Merge 5 Poké keys into a Great key, 5 Great into an Ultra, or 4 Ultra into a Master.",
                        "§7Each merge has a fee: CobbleDollars plus a special item. Open the merge menu from any §ecrate§7."),
                new Message(true,
                        "§6§lTip: Check before you open",
                        "§7Left-click any crate to see every reward, its exact chance and your §epity§7 progress.",
                        "§7Bad luck streak? Pity guarantees a top reward eventually."),
                new Message(true,
                        "§a§lTip: Keys in the shop",
                        "§7Keys can also be bought in §e/trigger shop§7 with CobbleDollars you earn by playing.",
                        "§7Everything here is earned in game. §lNever pay-to-win.§r"),
                new Message(true,
                        "§d§lTry Wonder Trade!",
                        "§7Send a Pokémon into the unknown with §f/wondertrade§7. Who knows what you'll get?"),
                new Message(true,
                        "§b§lThe GTS is open!",
                        "§7Buy, sell and auction Pokémon and items with §f/gts§7.",
                        "§7Selling while you're offline? Collect your earnings with §f/gts claims§7."),
                new Message(true,
                        "§c§lTip: Can't level up?",
                        "§7Your §eLevel Cap§7 rises as you beat trainers in a series.",
                        "§7Talk to the §eTrainer Association§7 in any village to pick one."),
                new Message(true,
                        "§6§lTip: Badges and the Master Ribbon",
                        "§7Gym leaders drop their badges, and the Badge Box keeps them safe.",
                        "§7Beat §levery§r§7 trainer series to earn the legendary §6Master Ribbon§7."),
                new Message(true,
                        "§a§lTip: Sell your extras",
                        "§7The bank buys every Cobblemon item, plus ores and gems. Rarer finds pay more.",
                        "§7Use §e/trigger bank§7."),
                new Message(true,
                        "§e§lTip: Shiny alerts",
                        "§7You'll get a ping when a shiny or legendary spawns near you.",
                        "§7Keep your eyes on chat and your Poké Balls ready!"),
                new Message(true,
                        "§b§lTip: Your quest book",
                        "§7Stuck or lost? Every mod has a step-by-step guide in the quest book,",
                        "§7and every quest pays CobbleDollars."));
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
