package net.joeyscobblemon.gts;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

/** Keeps wandering Radical Cobblemon Trainers out of the Battle Frontier (its own trainers carry JCL tags). */
@EventBusSubscriber(modid = GtsMod.MODID)
public final class FrontierGuard {
    private FrontierGuard() {}
    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent e) {
        if (e.getLevel().isClientSide() || !e.getLevel().dimension().location().getPath().contains("frontier")) return;
        var ent = e.getEntity();
        if (!ent.getClass().getName().startsWith("com.gitlab.srcmc.rctmod")) return;
        for (String t : ent.getTags()) if (t.startsWith("jcl")) return;
        e.setCanceled(true);
    }
}
