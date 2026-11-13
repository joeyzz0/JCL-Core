package net.joeyscobblemon.gts;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;

/** "Badge Box", provided by JCL Core: the boxes and ribbons keep their ids, so boxes players hold and quest icons carry on. */
@Mod(Badges.BB)
public final class BadgeBoxMod { public BadgeBoxMod(IEventBus bus) { Badges.BB_ITEMS.register(bus); } }
