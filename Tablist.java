package net.joeyscobblemon.gts;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundTabListPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Custom tab list: a branded header and footer that refresh every few seconds, and player names
 * with their LuckPerms prefix. Everything is in config/jcl_tablist.json; use § colour codes.
 *
 * Placeholders: {online} {max} {ping} {tps} {mspt} {balance} {time} {player}
 */
@EventBusSubscriber(modid = "jcl_gts")
public final class Tablist {
    private Tablist() {}

    public static final class Config {
        public boolean enabled = true;
        public int refreshSeconds = 3;
        public String timeZone = "America/New_York";
        public List<String> header = List.of(
                "",
                "§6§l✦ §e§lJOEY'S COBBLEMON LEAGUE §6§l✦",
                "§7Gotta catch 'em all, together.",
                "");
        public List<String> footer = List.of(
                "",
                "§7Online: §a{online}§7/§a{max}   §7Ping: §b{ping}ms   §7TPS: §a{tps}",
                "§7Balance: §6{balance}   §7Time: §f{time}",
                "",
                "§d/gts §8• §d/wondertrade §8• §e/trigger shop §8• §a/trigger bank",
                "§9discord.gg/4hHNvcJagV   §5twitch.tv/joeyzz0",
                "");
        public boolean useLuckPermsPrefix = true;
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static Config cfg = new Config();
    private static MinecraftServer server;
    private static int ticks;

    @SubscribeEvent
    public static void onStart(ServerStartedEvent e) {
        server = e.getServer();
        cfg = loadConfig("jcl_tablist.json", Config.class, new Config());
    }

    static <T> T loadConfig(String name, Class<T> type, T dflt) {
        Path p = Path.of("config", name);
        T out = dflt;
        try {
            if (Files.exists(p)) { T read = GSON.fromJson(Files.readString(p), type); if (read != null) out = read; }
            Files.createDirectories(p.getParent());
            Files.writeString(p, GSON.toJson(out));
        } catch (Exception ex) { GtsMod.LOG.error("[JCL] Could not read config/" + name + ", using defaults", ex); }
        return out;
    }

    @SubscribeEvent
    public static void onTick(ServerTickEvent.Post e) {
        if (server == null || !cfg.enabled) return;
        if (++ticks % Math.max(20, cfg.refreshSeconds * 20) != 0) return;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) send(p);
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent e) {
        if (e.getEntity() instanceof ServerPlayer p && cfg.enabled) {
            send(p);
            for (ServerPlayer other : server.getPlayerList().getPlayers()) other.refreshTabListName();
        }
    }

    /** Puts the player's LuckPerms prefix in front of their name in the tab list. */
    @SubscribeEvent
    public static void onTabName(PlayerEvent.TabListNameFormat e) {
        if (!cfg.enabled) return;
        String title = Titles.prefix(e.getEntity());
        if (title != null) { e.setDisplayName(Component.literal(title + " §r").append(e.getEntity().getName())); return; }
        if (!cfg.useLuckPermsPrefix) return;
        String prefix = luckPermsPrefix(e.getEntity().getUUID());
        if (prefix == null || prefix.isEmpty()) return;
        e.setDisplayName(Component.literal(prefix.replace('&', '§') + "§r").append(e.getEntity().getName()));
    }

    private static void send(ServerPlayer p) {
        Component header = Component.literal(fill(String.join("\n", cfg.header), p));
        Component footer = Component.literal(fill(String.join("\n", cfg.footer), p));
        p.connection.send(new ClientboundTabListPacket(header, footer));
    }

    private static String fill(String s, ServerPlayer p) {
        double mspt = server.getCurrentSmoothedTickTime();
        double tps = Math.min(20.0, 1000.0 / Math.max(mspt, 1.0));
        long bal = Economy.balance(p);
        String time;
        try { time = ZonedDateTime.now(ZoneId.of(cfg.timeZone)).format(DateTimeFormatter.ofPattern("h:mm a")); }
        catch (Exception ex) { time = ZonedDateTime.now().format(DateTimeFormatter.ofPattern("h:mm a")); }
        return s.replace("{online}", String.valueOf(server.getPlayerCount()))
                .replace("{max}", String.valueOf(server.getMaxPlayers()))
                .replace("{ping}", String.valueOf(p.connection.latency()))
                .replace("{tps}", String.format("%.1f", tps))
                .replace("{mspt}", String.format("%.1f", mspt))
                .replace("{balance}", bal >= 0 ? Fmt.cd(bal) : "?")
                .replace("{time}", time)
                .replace("{player}", p.getGameProfile().getName());
    }

    /** Reads a player's prefix from LuckPerms without a compile-time dependency on it. */
    static String luckPermsPrefix(java.util.UUID id) {
        try {
            Class<?> provider = Class.forName("net.luckperms.api.LuckPermsProvider");
            Object lp = provider.getMethod("get").invoke(null);
            Object userManager = lp.getClass().getMethod("getUserManager").invoke(lp);
            Object user = userManager.getClass().getMethod("getUser", java.util.UUID.class).invoke(userManager, id);
            if (user == null) return null;
            Object cached = user.getClass().getMethod("getCachedData").invoke(user);
            Object meta = cached.getClass().getMethod("getMetaData").invoke(cached);
            Object prefix = meta.getClass().getMethod("getPrefix").invoke(meta);
            return prefix == null ? null : prefix.toString();
        } catch (Throwable t) { return null; }
    }
}
