package net.joeyscobblemon.gts;

import com.google.gson.JsonParser;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Hides items that were removed from the game (jcl/jei_hidden.json) from JEI's item list and search. Client only. */
@JeiPlugin
public class JeiHide implements IModPlugin {
    @Override public ResourceLocation getPluginUid() { return ResourceLocation.fromNamespaceAndPath(GtsMod.MODID, "hidden_items"); }

    @Override
    public void onRuntimeAvailable(IJeiRuntime runtime) {
        try (var in = JeiHide.class.getResourceAsStream("/jcl/jei_hidden.json")) {
            if (in == null) return;
            var items = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonArray("items");
            List<ItemStack> hide = new ArrayList<>();
            for (var e : items) {
                var it = BuiltInRegistries.ITEM.get(ResourceLocation.parse(e.getAsString()));
                if (it != Items.AIR) hide.add(new ItemStack(it));
            }
            if (!hide.isEmpty()) runtime.getIngredientManager().removeIngredientsAtRuntime(VanillaTypes.ITEM_STACK, hide);
            GtsMod.LOG.info("[JCL] Hid {} removed items from JEI", hide.size());
        } catch (Throwable t) { GtsMod.LOG.warn("[JCL] Couldn't hide removed items from JEI: {}", t.toString()); }
    }
}
