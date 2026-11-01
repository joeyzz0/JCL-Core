package net.joeyscobblemon.gts;

import java.util.List;
import java.util.Map;

import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.TooltipFlag;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/** The four crate keys. They are real items, so they can be traded on the GTS. */
public final class Keys {
    private Keys() {}

    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(GtsMod.MODID);

    public static final DeferredItem<Item> POKE = ITEMS.register("poke_key", () -> new KeyItem("poke", new Item.Properties().rarity(Rarity.COMMON)));
    public static final DeferredItem<Item> GREAT = ITEMS.register("great_key", () -> new KeyItem("great", new Item.Properties().rarity(Rarity.UNCOMMON)));
    public static final DeferredItem<Item> ULTRA = ITEMS.register("ultra_key", () -> new KeyItem("ultra", new Item.Properties().rarity(Rarity.RARE)));
    public static final DeferredItem<Item> MASTER = ITEMS.register("master_key", () -> new KeyItem("master", new Item.Properties().rarity(Rarity.EPIC)
            .component(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true)));

    static Item item(String tier) {
        return switch (tier) {
            case "poke" -> POKE.get();
            case "great" -> GREAT.get();
            case "ultra" -> ULTRA.get();
            case "master" -> MASTER.get();
            default -> null;
        };
    }

    static String tierOf(ItemStack s) {
        return s.getItem() instanceof KeyItem k ? k.tier : null;
    }

    static final Map<String, String> NAMES = Map.of("poke", "Poké Crate", "great", "Great Crate", "ultra", "Ultra Crate", "master", "Master Crate");

    public static final class KeyItem extends Item {
        final String tier;
        KeyItem(String tier, Properties p) { super(p); this.tier = tier; }

        @Override
        public void appendHoverText(ItemStack stack, TooltipContext ctx, List<Component> lines, TooltipFlag flag) {
            lines.add(Component.literal("§7Opens a §f" + NAMES.get(tier) + "§7 at spawn."));
            lines.add(Component.literal("§8Left-click a crate to see its rewards and odds."));
        }
    }
}
