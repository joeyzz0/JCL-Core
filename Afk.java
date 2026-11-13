package net.joeyscobblemon.gts;

import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.CommandEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

import java.util.*;

/**
 * /afk: everyone is told you're away, your name shows [AFK] in the tab list, and it clears the moment you move,
 * look around, chat or use a command. /fakeafk (staff): looks exactly the same to everyone, but nothing clears it
 * until you run /fakeafk again.
 */
@EventBusSubscriber(modid = GtsMod.MODID)
public final class Afk {
    private Afk() {}

    static final Set<UUID> AFK = new HashSet<>(), FAKE = new HashSet<>();
    static final Map<UUID, double[]> AT = new HashMap<>();          // where a real-AFK player was standing and looking

    static boolean isAfk(ServerPlayer p) { return AFK.contains(p.getUUID()) || FAKE.contains(p.getUUID()); }

    static void announce(ServerPlayer p, boolean away) {
        p.getServer().getPlayerList().broadcastSystemMessage(Component.literal("§7* §f" + p.getGameProfile().getName() + (away ? " §7is now AFK." : " §7is no longer AFK.")), false);
        p.refreshTabListName();
    }

    static void goAfk(ServerPlayer p) {
        AFK.add(p.getUUID());
        AT.put(p.getUUID(), new double[]{p.getX(), p.getY(), p.getZ(), p.getYRot(), p.getXRot()});
        announce(p, true);
    }
    static void back(ServerPlayer p) {
        if (!AFK.remove(p.getUUID())) return;
        AT.remove(p.getUUID());
        if (!FAKE.contains(p.getUUID())) announce(p, false);
    }

    @SubscribeEvent
    public static void onCommands(RegisterCommandsEvent e) {
        e.getDispatcher().register(Commands.literal("afk").executes(c -> {
            ServerPlayer p = c.getSource().getPlayerOrException();
            if (AFK.contains(p.getUUID())) back(p); else goAfk(p);
            return 1; }));
        e.getDispatcher().register(Commands.literal("fakeafk").requires(s -> s.hasPermission(2)).executes(c -> {
            ServerPlayer p = c.getSource().getPlayerOrException();
            if (FAKE.remove(p.getUUID())) { if (!AFK.contains(p.getUUID())) announce(p, false); }
            else { FAKE.add(p.getUUID()); if (!AFK.contains(p.getUUID())) announce(p, true); }
            p.sendSystemMessage(Component.literal(FAKE.contains(p.getUUID()) ? "§8(Fake AFK is on. Only §f/fakeafk §8turns it off.)" : "§8(Fake AFK is off.)"));
            return 1; }));
    }

    // ------------------------------------------------------------------ anything you do brings a real AFK back

    @SubscribeEvent
    public static void onTick(PlayerTickEvent.Post e) {
        if (!(e.getEntity() instanceof ServerPlayer p) || !AFK.contains(p.getUUID()) || p.tickCount % 10 != 0) return;
        double[] a = AT.get(p.getUUID());
        if (a == null) return;
        boolean moved = Math.abs(p.getX() - a[0]) > 0.2 || Math.abs(p.getZ() - a[2]) > 0.2 || Math.abs(p.getY() - a[1]) > 0.6;
        boolean looked = Math.abs(p.getYRot() - a[3]) > 4 || Math.abs(p.getXRot() - a[4]) > 4;
        if (moved || looked) back(p);
    }
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onChat(ServerChatEvent e) { if (!e.isCanceled()) back(e.getPlayer()); }
    @SubscribeEvent
    public static void onCommand(CommandEvent e) {
        var src = e.getParseResults().getContext().getSource();
        if (!(src.getEntity() instanceof ServerPlayer p) || !AFK.contains(p.getUUID())) return;
        String cmd = e.getParseResults().getReader().getString().trim();
        if (cmd.startsWith("/")) cmd = cmd.substring(1);
        if (!cmd.startsWith("afk")) back(p);                       // /afk itself toggles; everything else counts as being back
    }
    @SubscribeEvent
    public static void onUse(PlayerInteractEvent.RightClickBlock e) { if (e.getEntity() instanceof ServerPlayer p) back(p); }
    @SubscribeEvent
    public static void onHit(PlayerInteractEvent.LeftClickBlock e) { if (e.getEntity() instanceof ServerPlayer p) back(p); }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent e) { AFK.remove(e.getEntity().getUUID()); AT.remove(e.getEntity().getUUID()); }

    /** [AFK] beside the name in the tab list, kept on top of any title or colour. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onTabName(PlayerEvent.TabListNameFormat e) {
        if (!(e.getEntity() instanceof ServerPlayer p) || !isAfk(p)) return;
        Component base = e.getDisplayName() != null ? e.getDisplayName() : p.getDisplayName();
        e.setDisplayName(Component.empty().append(base).append(Component.literal(" §7[AFK]")));
    }
}
