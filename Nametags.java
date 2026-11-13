package net.joeyscobblemon.gts;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Titles and name colours above players' heads. Minecraft draws a player's nameplate (and chat and tab names) from
 * their scoreboard team, so each player gets a small team of their own: its prefix is their title, its colour their
 * name colour. Vanilla rules still apply: invisible players' nameplates are hidden, sneaking dims them.
 */
@EventBusSubscriber(modid = GtsMod.MODID)
public final class Nametags {
    private Nametags() {}

    static String teamName(ServerPlayer p) { return "jt" + p.getUUID().toString().replace("-", "").substring(0, 14); }

    static void apply(ServerPlayer p) {
        try {
            Scoreboard sb = p.server.getScoreboard();
            String name = teamName(p);
            PlayerTeam t = sb.getPlayerTeam(name);
            if (t == null) t = sb.addPlayerTeam(name);
            String pre = Titles.prefix(p), col = Titles.nameColor(p);
            t.setPlayerPrefix(pre == null ? Component.empty() : Component.literal(pre + " "));
            ChatFormatting cf = null;
            for (int i = 0; i + 1 < col.length(); i++) if (col.charAt(i) == '§') { ChatFormatting f = ChatFormatting.getByCode(col.charAt(i + 1)); if (f != null && f.isColor()) { cf = f; break; } }
            t.setColor(cf == null ? ChatFormatting.RESET : cf);
            if (sb.getPlayersTeam(p.getScoreboardName()) != t) sb.addPlayerToTeam(p.getScoreboardName(), t);
        } catch (Exception ex) { GtsMod.LOG.debug("[JCL] Nametag for {}: {}", p.getGameProfile().getName(), ex.toString()); }
    }

    @SubscribeEvent
    public static void onJoin(PlayerEvent.PlayerLoggedInEvent e) { if (e.getEntity() instanceof ServerPlayer p) apply(p); }

    @SubscribeEvent
    public static void onLeave(PlayerEvent.PlayerLoggedOutEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer p)) return;
        try { Scoreboard sb = p.server.getScoreboard(); PlayerTeam t = sb.getPlayerTeam(teamName(p)); if (t != null) sb.removePlayerTeam(t); } catch (Exception ignored) {}
    }

    @SubscribeEvent
    public static void onTick(ServerTickEvent.Post e) {
        if (e.getServer().getTickCount() % 1200 != 0) return;          // a minute: a safety net, titles also update the moment they change
        for (ServerPlayer p : e.getServer().getPlayerList().getPlayers()) apply(p);
    }
}
