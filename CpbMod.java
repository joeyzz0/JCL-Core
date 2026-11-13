package net.joeyscobblemon.gts;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;

/** "Cobblemon Pokemon Badges", provided by JCL Core: its badge items keep their ids, so everything that names them still works. */
@Mod(Badges.CPB)
public final class CpbMod { public CpbMod(IEventBus bus) { Badges.CPB_ITEMS.register(bus); } }
