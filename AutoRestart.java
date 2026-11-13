package net.joeyscobblemon.gts;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.time.*;
import java.util.*;

/**
 * Scheduled restarts (config/jcl_restart.json): warnings counting down in chat and on screen, everything saved,
 * players sent off with a message, then the server stops. A server can't start itself again: the host brings it back.
 * In "crash" mode the server exits with an error code after a clean save, so a panel's restart-on-crash relaunches it;
 * in "stop" mode it stops normally (for hosts that restart on any stop, or a start script that loops).
 */
@EventBusSubscriber(modid = GtsMod.MODID)
public final class AutoRestart {
    private AutoRestart() {}

    public static final class Config {
        public boolean enabled = true;
        public String timezone = "America/New_York";
        public List<String> times = List.of("05:00", "17:00");        // every 12 hours, clear of prime time
        public String mode = "api";                                   // "api" (Modrinth restarts it), "crash" (exit code 1) or "stop"
        public String modrinthServerId = "";                          // from the panel's address: modrinth.com/servers/manage/<this>
        public String modrinthToken = "";                             // a Modrinth token that can manage the server (keep this secret)
        public String archonUrl = "https://archon.modrinth.com/modrinth/v0";
        public String exarotonToken = "";                             // exaroton account settings -> API key (keep this secret)
        public String exarotonServerName = "";                        // only needed if the account has more than one server
        public String exarotonUrl = "https://api.exaroton.com/v1";
        public List<Integer> warnSeconds = List.of(1800, 900, 600, 300, 60, 30, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1);
        public String kickMessage = "The server is restarting. It'll be back in a minute!";
    }
    static Config cfg = new Config();
    static MinecraftServer server;
    static long nextAt;          // epoch millis of the next restart (0 = none)
    static boolean stopping, crashExit;
    static final Set<Integer> warned = new HashSet<>();

    @SubscribeEvent
    public static void onStart(ServerStartedEvent e) {
        server = e.getServer();
        cfg = Tablist.loadConfig("jcl_restart.json", Config.class, new Config());
        schedule();
    }

    /** The next scheduled time after now, in the configured timezone. */
    static void schedule() {
        warned.clear();
        if (!cfg.enabled || cfg.times.isEmpty()) { nextAt = 0; return; }
        ZoneId zone; try { zone = ZoneId.of(cfg.timezone); } catch (Exception ex) { zone = ZoneId.systemDefault(); }
        ZonedDateTime now = ZonedDateTime.now(zone), best = null;
        for (String t : cfg.times) {
            try {
                LocalTime lt = LocalTime.parse(t.trim());
                ZonedDateTime c = now.with(lt);
                if (!c.isAfter(now.plusMinutes(2))) c = c.plusDays(1);     // never restart right after starting up
                if (best == null || c.isBefore(best)) best = c;
            } catch (Exception ex) { GtsMod.LOG.warn("[JCL] Restart: bad time '{}' in jcl_restart.json", t); }
        }
        nextAt = best == null ? 0 : best.toInstant().toEpochMilli();
        if (nextAt > 0) GtsMod.LOG.info("[JCL] Next automatic restart: {}", best);
    }

    static String left(long secs) {
        if (secs >= 60) { long m = secs / 60; return m + " minute" + (m == 1 ? "" : "s"); }
        return secs + " second" + (secs == 1 ? "" : "s");
    }

    @SubscribeEvent
    public static void onTick(ServerTickEvent.Post e) {
        if (server == null || nextAt == 0 || stopping || server.getTickCount() % 20 != 0) return;
        long secs = Math.max(0, (nextAt - System.currentTimeMillis() + 999) / 1000);
        // every threshold already passed is marked quietly; only the nearest one is announced, with the real time left
        int nearest = -1;
        for (int w : cfg.warnSeconds) if (secs <= w && warned.add(w) && (nearest < 0 || w < nearest)) nearest = w;
        if (nearest > 0) warn(nearest > 10 ? (int) secs : nearest);
        if (secs <= 0) restartNow();
    }

    static void warn(int secs) {
        if (secs == 600 || secs == 60) DiscordBridge.statusAsync("🟡 **Restarting** <t:" + (System.currentTimeMillis() / 1000 + secs) + ":R> · back in about a minute");
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (secs > 10) {
                p.sendSystemMessage(Component.literal("§6§l[Server] §fRestarting in §e" + left(secs) + "§f. Your progress is saved automatically."));
                p.playNotifySound(SoundEvents.NOTE_BLOCK_BELL.value(), SoundSource.MASTER, 0.6f, 1f);
            } else {
                p.connection.send(new ClientboundSetTitlesAnimationPacket(0, 25, 5));
                p.connection.send(new ClientboundSetTitleTextPacket(Component.literal("§c§l" + secs)));
                p.playNotifySound(SoundEvents.NOTE_BLOCK_HAT.value(), SoundSource.MASTER, 0.8f, secs <= 3 ? 1.6f : 1.1f);
                if (secs == 10) p.sendSystemMessage(Component.literal("§6§l[Server] §fRestarting in §e10 seconds§f..."));
            }
        }
    }

    /** Saves everything, sends everyone off, and stops (see the mode above). */
    static void restartNow() {
        if (stopping) return;
        stopping = true;
        GtsMod.LOG.info("[JCL] Automatic restart: saving and stopping (mode: {})", cfg.mode);
        try { server.saveEverything(true, true, true); } catch (Throwable t) { GtsMod.LOG.warn("[JCL] Restart save: {}", t.toString()); }
        for (ServerPlayer p : new ArrayList<>(server.getPlayerList().getPlayers()))
            p.connection.disconnect(Component.literal("§6" + cfg.kickMessage));
        if ("api".equalsIgnoreCase(cfg.mode) || "modrinth".equalsIgnoreCase(cfg.mode) || "exaroton".equalsIgnoreCase(cfg.mode)) {
            // ask Modrinth to restart us: the panel stops the server cleanly and starts it again
            if (askHostToRestart()) return;
            GtsMod.LOG.warn("[JCL] Restart: the Modrinth API didn't accept the restart; falling back to exit-and-recover");
            crashExit = true;
        } else crashExit = "crash".equalsIgnoreCase(cfg.mode);
        server.halt(false);
    }

    static boolean exaroton() { return "exaroton".equalsIgnoreCase(cfg.mode); }

    static java.net.http.HttpResponse<String> exGet(String path) throws Exception {
        var req = java.net.http.HttpRequest.newBuilder(java.net.URI.create(cfg.exarotonUrl + path)).timeout(java.time.Duration.ofSeconds(15))
                .header("Authorization", "Bearer " + cfg.exarotonToken).GET().build();
        return java.net.http.HttpClient.newHttpClient().send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
    }

    /** The exaroton server id: the one named in exarotonServerName, or the only server on the account. */
    static String exarotonServerId() throws Exception {
        var res = exGet("/servers/");
        if (res.statusCode() / 100 != 2) throw new IllegalStateException("exaroton answered " + res.statusCode());
        var arr = com.google.gson.JsonParser.parseString(res.body()).getAsJsonObject().getAsJsonArray("data");
        if (arr == null || arr.isEmpty()) throw new IllegalStateException("no servers on this exaroton account");
        for (var el : arr) {
            var o = el.getAsJsonObject();
            if (!cfg.exarotonServerName.isBlank() && cfg.exarotonServerName.equalsIgnoreCase(o.get("name").getAsString())) return o.get("id").getAsString();
        }
        if (arr.size() == 1) return arr.get(0).getAsJsonObject().get("id").getAsString();
        throw new IllegalStateException("several servers on this account: set exarotonServerName");
    }

    static String auth() { return cfg.modrinthToken.startsWith("Bearer ") ? cfg.modrinthToken : "Bearer " + cfg.modrinthToken; }

    /** POST {archonUrl}/servers/{id}/power {"action":"Restart"}. True if Modrinth accepted it. */
    static boolean askHostToRestart() {
        if (exaroton()) {
            if (cfg.exarotonToken.isBlank()) { GtsMod.LOG.warn("[JCL] Restart: exaroton mode needs exarotonToken in jcl_restart.json"); return false; }
            try {
                var res = exGet("/servers/" + exarotonServerId() + "/restart/");
                GtsMod.LOG.info("[JCL] Restart: exaroton answered {}", res.statusCode());
                return res.statusCode() / 100 == 2;
            } catch (Exception ex) { GtsMod.LOG.warn("[JCL] Restart: exaroton API call failed: {}", ex.toString()); return false; }
        }
        if (cfg.modrinthServerId.isBlank() || cfg.modrinthToken.isBlank()) { GtsMod.LOG.warn("[JCL] Restart: api mode needs modrinthServerId and modrinthToken in jcl_restart.json"); return false; }
        try {
            var req = java.net.http.HttpRequest.newBuilder(java.net.URI.create(cfg.archonUrl + "/servers/" + cfg.modrinthServerId + "/power"))
                    .timeout(java.time.Duration.ofSeconds(15)).header("Authorization", auth()).header("Content-Type", "application/json")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString("{\"action\":\"Restart\"}")).build();
            var res = java.net.http.HttpClient.newHttpClient().send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
            GtsMod.LOG.info("[JCL] Restart: Modrinth answered {}", res.statusCode());
            return res.statusCode() / 100 == 2;
        } catch (Exception ex) { GtsMod.LOG.warn("[JCL] Restart: Modrinth API call failed: {}", ex.toString()); return false; }
    }

    /** Staff test: can the token see this server? (Doesn't restart anything.) */
    static String testApi() {
        if (exaroton()) {
            if (cfg.exarotonToken.isBlank()) return "§cSet exarotonToken in config/jcl_restart.json first.";
            try { String id = exarotonServerId(); return "§aThe exaroton API works: found this server (" + id + "), so restarts will use it."; }
            catch (Exception ex) { return "§cexaroton: " + ex.getMessage() + ". Check the API key (and exarotonServerName if you have several servers)."; }
        }
        if (cfg.modrinthServerId.isBlank() || cfg.modrinthToken.isBlank()) return "§cSet modrinthServerId and modrinthToken in config/jcl_restart.json first.";
        try {
            var req = java.net.http.HttpRequest.newBuilder(java.net.URI.create(cfg.archonUrl + "/servers/" + cfg.modrinthServerId))
                    .timeout(java.time.Duration.ofSeconds(15)).header("Authorization", auth()).GET().build();
            var res = java.net.http.HttpClient.newHttpClient().send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
            return res.statusCode() / 100 == 2 ? "§aThe Modrinth API works: this token can manage this server." : "§cModrinth answered " + res.statusCode() + ": check the server ID and token.";
        } catch (Exception ex) { return "§cCouldn't reach the Modrinth API: " + ex.getMessage(); }
    }


    /** In crash mode, once the server has fully stopped and saved, exit with an error code so the panel relaunches it. */
    @SubscribeEvent
    public static void onStopped(ServerStoppedEvent e) {
        if (stopping && crashExit) {
            GtsMod.LOG.info("[JCL] Automatic restart: server stopped cleanly; exiting with code 1 so the host restarts it");
            new Thread(() -> { try { Thread.sleep(2000); } catch (InterruptedException ignored) {} Runtime.getRuntime().halt(1); }, "jcl-restart-exit").start();
        }
    }

    @SubscribeEvent
    public static void onCommands(RegisterCommandsEvent e) {
        e.getDispatcher().register(Commands.literal("jcl").requires(s -> s.hasPermission(2)).then(Commands.literal("restart")
                .then(Commands.literal("now").executes(c -> { nextAt = System.currentTimeMillis() + 60_000; warned.clear(); c.getSource().sendSuccess(() -> Component.literal("§6Restarting in 1 minute."), true); return 1; })
                        .then(Commands.argument("seconds", IntegerArgumentType.integer(5, 3600)).executes(c -> {
                            int s = IntegerArgumentType.getInteger(c, "seconds"); nextAt = System.currentTimeMillis() + s * 1000L; warned.clear();
                            c.getSource().sendSuccess(() -> Component.literal("§6Restarting in " + left(s) + "."), true); return 1; })))
                .then(Commands.literal("cancel").executes(c -> {
                    schedule();                                  // drop this one; the next scheduled time takes over
                    for (ServerPlayer p : server.getPlayerList().getPlayers()) p.sendSystemMessage(Component.literal("§6§l[Server] §aThe restart was cancelled."));
                    c.getSource().sendSuccess(() -> Component.literal("§7Next restart: " + status()), true); return 1; }))
                .then(Commands.literal("apitest").executes(c -> { new Thread(() -> { String r = testApi(); server.execute(() -> c.getSource().sendSuccess(() -> Component.literal(r), false)); }, "jcl-api-test").start(); return 1; }))
                .then(Commands.literal("reload").executes(c -> { cfg = Tablist.loadConfig("jcl_restart.json", Config.class, new Config()); schedule(); c.getSource().sendSuccess(() -> Component.literal("§aRestart settings reloaded. Next: " + status()), true); return 1; }))
                .then(Commands.literal("status").executes(c -> { c.getSource().sendSuccess(() -> Component.literal("§7Next restart: §f" + status() + " §7(mode: " + cfg.mode + ")"), false); return 1; }))));
    }

    static String status() {
        if (nextAt == 0) return "none scheduled";
        long s = (nextAt - System.currentTimeMillis()) / 1000;
        return "in " + (s / 3600) + "h " + (s % 3600 / 60) + "m";
    }
}
