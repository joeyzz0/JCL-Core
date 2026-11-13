package net.joeyscobblemon.gts;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.Gson;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.TooltipFlag;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Style Tickets: one item per cosmetic look from the cosmetic add-ons. The zz_jcl_style_tickets
 * datapack points every look at its ticket, so the look only works with that ticket in the
 * Pokémon's cosmetic slot. Tickets come from the Style Crate and trade on the GTS.
 */
public final class StyleTickets {
    private StyleTickets() {}

    public static final class Ticket { public String id, name, rarity; public List<String> pokemon = new ArrayList<>(); }
    public static final class Config { public int version; public List<Ticket> tickets = new ArrayList<>(); }

    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(GtsMod.MODID);
    static final Map<String, Ticket> ALL = new LinkedHashMap<>();

    static {
        try (var in = StyleTickets.class.getResourceAsStream("/jcl/tickets.json")) {
            if (in != null) {
                Config c = new Gson().fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), Config.class);
                for (Ticket t : c.tickets) {
                    ALL.put(t.id, t);
                    Rarity r = switch (t.rarity) { case "legendary" -> Rarity.EPIC; case "epic" -> Rarity.RARE; case "rare" -> Rarity.UNCOMMON; default -> Rarity.COMMON; };
                    ITEMS.register(t.id, () -> new TicketItem(t, new Item.Properties().stacksTo(16).rarity(r)));
                }
            }
        } catch (Exception e) { GtsMod.LOG.error("[JCL] Could not load style tickets", e); }
    }

    static void register(IEventBus bus) { ITEMS.register(bus); }

    public static final class TicketItem extends Item {
        final Ticket t;
        TicketItem(Ticket t, Properties p) { super(p); this.t = t; }

        @Override
        public Component getName(ItemStack stack) {
            return Component.literal(Crates.rarityColor(t.rarity) + "Style Ticket: " + t.name);
        }

        @Override
        public void appendHoverText(ItemStack stack, TooltipContext ctx, List<Component> lines, TooltipFlag flag) {
            String who = t.pokemon.size() > 6 ? t.pokemon.size() + " different Pokémon" : String.join(", ", t.pokemon.stream().map(m -> m.substring(0, 1).toUpperCase() + m.substring(1)).toList());
            lines.add(Component.literal("§7For: §f" + who));
            lines.add(Component.literal(Crates.rarityColor(t.rarity) + Crates.rarityName(t.rarity)));
            lines.add(Component.literal("§8Put it in the Pokémon's cosmetic slot to wear the look."));
        }
    }
}
