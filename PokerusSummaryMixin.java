package net.joeyscobblemon.gts.mixin;

import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A small PKRS tag on Cobblemon's summary screen for a Pokémon with Pokérus: purple while infected, green once cured. */
@Mixin(targets = "com.cobblemon.mod.common.client.gui.summary.Summary", remap = false)
public abstract class PokerusSummaryMixin {
    @Shadow(remap = false) private com.cobblemon.mod.common.pokemon.Pokemon selectedPokemon;

    @Inject(method = "render(Lnet/minecraft/client/gui/GuiGraphics;IIF)V", at = @At("TAIL"), remap = false)
    private void jcl$pokerusTag(GuiGraphics g, int mouseX, int mouseY, float partial, CallbackInfo ci) {
        try {
            if (selectedPokemon == null) return;
            Integer st = net.joeyscobblemon.gts.Pokerus.CLIENT.get(selectedPokemon.getUuid().toString());
            if (st == null || st <= 0) return;
            var screen = (net.minecraft.client.gui.screens.Screen) (Object) this;
            var font = net.minecraft.client.Minecraft.getInstance().font;
            int x0 = (screen.width - 331) / 2, y0 = (screen.height - 161) / 2;
            String label = "PKRS";
            int w = font.width(label) + 6, x = x0 + 2, y = y0 - 12;
            int fill = st == 1 ? 0xFFB13FCF : 0xFF3FA65A, edge = st == 1 ? 0xFFE7A6F5 : 0xFF9BE3AE;
            var pose = g.pose(); pose.pushPose(); pose.translate(0, 0, 400);
            g.fill(x, y, x + w, y + 11, edge); g.fill(x + 1, y + 1, x + w - 1, y + 10, fill);
            g.drawString(font, label, x + 3, y + 2, 0xFFFFFFFF, false);
            if (mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + 11)
                g.renderComponentTooltip(font, java.util.List.of(net.minecraft.network.chat.Component.literal(st == 1 ? "§dPokérus §7(infected)" : "§aPokérus §7(cured)"),
                        net.minecraft.network.chat.Component.literal("§7Gains EVs twice as fast."), net.minecraft.network.chat.Component.literal(st == 1 ? "§7Can spread to its party neighbours." : "§7Can't catch it again.")), mouseX, mouseY);
            pose.popPose();
        } catch (Throwable ignored) {}
    }
}
