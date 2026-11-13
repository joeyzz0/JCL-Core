package net.joeyscobblemon.gts.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Fixes players being kicked mid-battle with "Failed to encode packet ... battle_update_team".
 *
 * Cobblemon's team update packet keeps a live reference to the Pokémon and only reads it later, on the
 * network thread. If another mod (here, Mega Showdown reverting a form when a Pokémon faints) changes that
 * Pokémon's data in the same instant, the read hits a half-changed tag and the player is disconnected.
 * This takes a snapshot of the Pokémon the moment the packet is created, so the network thread only ever
 * reads a copy nobody else is touching.
 */
@Mixin(targets = "com.cobblemon.mod.common.net.messages.client.battle.BattleUpdateTeamPokemonPacket", remap = false)
public abstract class BattleTeamPacketMixin {
    @Inject(method = "<init>", at = @At("RETURN"), remap = false)
    private void jcl$snapshotPokemon(CallbackInfo ci) {
        net.joeyscobblemon.gts.BattleSafety.snapshot(this);
    }
}
