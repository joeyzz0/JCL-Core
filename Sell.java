package net.joeyscobblemon.gts;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * The sell box (/sell). Drop or shift-click items into the top 5 rows and the total updates live.
 * The bottom row has the buttons. The menu stays open after selling, and anything left in the
 * box is handed back when it closes, so nothing can ever be lost.
 */
public final class Sell {
    private Sell() {}

    static final int BOX = 45;

    static void open(ServerPlayer p) {
        SimpleContainer box = new SimpleContainer(54);
        p.openMenu(new SimpleMenuProvider((id, inv, pl) -> new SellMenu(id, inv, box, (ServerPlayer) pl), Component.literal("§a§l💰 Sell Box §8· drop items in")));
    }

    static final class SellMenu extends ChestMenu {
        final SimpleContainer box;
        final ServerPlayer player;

        SellMenu(int id, Inventory inv, SimpleContainer box, ServerPlayer player) {
            super(MenuType.GENERIC_9x6, id, inv, box, 6);
            this.box = box; this.player = player;
            refresh();
        }

        boolean isButton(Slot s) { return s.container == box && s.getContainerSlot() >= BOX; }

        @Override
        public void clicked(int slotId, int button, ClickType type, Player pl) {
            if (slotId >= 0 && slotId < slots.size() && isButton(slots.get(slotId))) {
                if (type == ClickType.PICKUP) press(slots.get(slotId).getContainerSlot());
                refresh();
                return;
            }
            super.clicked(slotId, button, type, pl);
            refresh();
        }

        @Override public boolean canTakeItemForPickAll(ItemStack stack, Slot slot) { return !isButton(slot) && super.canTakeItemForPickAll(stack, slot); }
        @Override public boolean canDragTo(Slot slot) { return !isButton(slot); }
        @Override public boolean stillValid(Player p) { return true; }

        @Override
        public ItemStack quickMoveStack(Player pl, int index) {
            Slot slot = slots.get(index);
            if (slot == null || !slot.hasItem() || isButton(slot)) return ItemStack.EMPTY;
            ItemStack stack = slot.getItem(), copy = stack.copy();
            if (slot.container == box) { if (!moveItemStackTo(stack, 54, slots.size(), true)) return ItemStack.EMPTY; }
            else { if (!moveItemStackTo(stack, 0, BOX, false)) return ItemStack.EMPTY; }
            if (stack.isEmpty()) slot.setByPlayer(ItemStack.EMPTY); else slot.setChanged();
            refresh();
            return copy;
        }

        @Override
        public void removed(Player pl) {
            super.removed(pl);
            for (int i = 0; i < BOX; i++) {                       // give back anything still in the box
                ItemStack s = box.removeItemNoUpdate(i);
                if (!s.isEmpty()) Crates.giveStack(player, s);
            }
            for (int i = BOX; i < 54; i++) box.setItem(i, ItemStack.EMPTY);
        }

        long total() {
            long t = 0;
            for (int i = 0; i < BOX; i++) { ItemStack s = box.getItem(i); long v = Shop.priceOf(s); if (v > 0) t += v * s.getCount(); }
            return t;
        }

        int unsellable() {
            int n = 0;
            for (int i = 0; i < BOX; i++) { ItemStack s = box.getItem(i); if (!s.isEmpty() && Shop.priceOf(s) < 0) n += s.getCount(); }
            return n;
        }

        void refresh() {
            long t = total(); int bad = unsellable();
            ItemStack pane = Gui.pane(Items.LIME_STAINED_GLASS_PANE);
            for (int i = BOX; i < 54; i++) box.setItem(i, pane.copy());
            box.setItem(45, Gui.icon(Items.BOOK, "§e§lHow it works",
                    "§7Drop items into the box, or", "§7shift-click them from your inventory.", "§7Prices are shown on each button.", "", "§7Closing the menu gives back", "§7anything you didn't sell."));
            box.setItem(47, Gui.icon(Items.HOPPER, "§b§lAdd everything sellable",
                    "§7Moves every sellable item from", "§7your inventory into the box.", "§8Your hotbar and armour are skipped."));
            box.setItem(48, Gui.icon(Items.CHEST, "§b§lAdd more of the same",
                    "§7Adds every stack from your", "§7inventory that matches an", "§7item already in the box."));
            box.setItem(49, t > 0
                    ? Gui.glow(Gui.icon(Items.EMERALD_BLOCK, "§a§lSell for " + Fmt.cd(t), "§7Sells everything sellable in the box.", bad > 0 ? "§c" + bad + " item(s) can't be sold and will stay." : "§8Click to sell."))
                    : Gui.icon(Items.GRAY_DYE, "§7Nothing to sell yet", "§8Put items in the box first."));
            box.setItem(51, Gui.icon(Items.ARROW, "§e§lGive it all back", "§7Moves everything in the box", "§7back to your inventory."));
            box.setItem(53, Gui.icon(Items.BARRIER, "§c§lClose"));
            broadcastChanges();
        }

        void press(int slot) {
            switch (slot) {
                case 47 -> moveFromInventory(null, true);
                case 48 -> {
                    List<Item> kinds = new ArrayList<>();
                    for (int i = 0; i < BOX; i++) if (!box.getItem(i).isEmpty()) kinds.add(box.getItem(i).getItem());
                    moveFromInventory(kinds, false);
                }
                case 49 -> sellAll();
                case 51 -> { for (int i = 0; i < BOX; i++) { ItemStack s = box.removeItemNoUpdate(i); if (!s.isEmpty()) Crates.giveStack(player, s); } click(); }
                case 53 -> player.closeContainer();
                default -> {}
            }
        }

        void click() { player.playNotifySound(SoundEvents.UI_BUTTON_CLICK.value(), SoundSource.MASTER, 0.4f, 1.2f); }

        void moveFromInventory(List<Item> only, boolean skipHotbar) {
            int moved = 0;
            for (int i = skipHotbar ? 9 : 0; i < 36; i++) {
                ItemStack s = player.getInventory().items.get(i);
                if (s.isEmpty() || Shop.priceOf(s) < 0) continue;
                if (only != null && !only.contains(s.getItem())) continue;
                int before = s.getCount();
                for (int b = 0; b < BOX && !s.isEmpty(); b++) {
                    ItemStack in = box.getItem(b);
                    if (in.isEmpty()) { box.setItem(b, s.split(s.getCount())); }
                    else if (ItemStack.isSameItemSameComponents(in, s) && in.getCount() < in.getMaxStackSize()) {
                        int n = Math.min(s.getCount(), in.getMaxStackSize() - in.getCount()); in.grow(n); s.shrink(n);
                    }
                }
                moved += before - s.getCount();
            }
            if (moved > 0) click();
            else player.playNotifySound(SoundEvents.VILLAGER_NO, SoundSource.MASTER, 0.5f, 1f);
        }

        void sellAll() {
            long t = 0; int n = 0;
            for (int i = 0; i < BOX; i++) {
                ItemStack s = box.getItem(i); long v = Shop.priceOf(s);
                if (v > 0) { t += v * s.getCount(); n += s.getCount(); box.setItem(i, ItemStack.EMPTY); }
            }
            if (t <= 0) { player.playNotifySound(SoundEvents.VILLAGER_NO, SoundSource.MASTER, 0.5f, 1f); return; }
            Economy.give(player, t);
            player.playNotifySound(SoundEvents.PLAYER_LEVELUP, SoundSource.MASTER, 0.6f, 1.4f);
            player.sendSystemMessage(Component.literal("§6§l[Shop] §r§aSold §f" + n + "§a item(s) for §6" + Fmt.cd(t) + "§a."));
        }
    }
}
