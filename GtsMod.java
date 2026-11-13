package net.joeyscobblemon.gts;

import com.mojang.logging.LogUtils;
import net.minecraft.ChatFormatting;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

/** JCL Core: GTS, Wonder Trade, shop, crates, tab list, announcements and spawn alerts. */
@Mod(GtsMod.MODID)
public class GtsMod {
    public static final String MODID = "jcl_gts";
    public static final String VERSION = "2.4.41";
    public static final String BUILD = "2026-11-13 spawn-snapshot";
    public static final Logger LOG = LogUtils.getLogger();

    public GtsMod(IEventBus modBus) {
        GtsConfig.load();
        Keys.ITEMS.register(modBus);
        Badges.MENUS.register(modBus); Badges.TABS.register(modBus);
        Locked.register(modBus);
        StyleTickets.register(modBus);
        LOG.info("[JCL] JCL Core loaded. Version " + VERSION + " (build " + BUILD + ")");
    }

    @EventBusSubscriber(modid = MODID)
    public static class Events {
        @SubscribeEvent
        public static void commands(RegisterCommandsEvent e) { GtsCommands.register(e.getDispatcher()); }

        @SubscribeEvent
        public static void started(ServerStartedEvent e) { Market.start(e.getServer()); }

        @SubscribeEvent
        public static void stopping(ServerStoppingEvent e) { Market.stop(); }

        @SubscribeEvent
        public static void tick(ServerTickEvent.Post e) { Market.onTick(); }

        @SubscribeEvent
        public static void login(PlayerEvent.PlayerLoggedInEvent e) {
            if (!(e.getEntity() instanceof ServerPlayer p) || Market.server == null) return;
            int n = Market.claimCount(p.getUUID());
            if (n > 0) p.sendSystemMessage(Fmt.chat("[GTS] ", ChatFormatting.GOLD).append(Fmt.chat("You have " + n + " thing(s) waiting. Use /gts claims to collect them.", ChatFormatting.YELLOW)));
        }
    }
}
