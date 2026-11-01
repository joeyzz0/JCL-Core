package net.joeyscobblemon.gts;

import java.lang.reflect.Method;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.IntegerArgumentType;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.Commands;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Wonder Trade: send a Pokémon into a server-wide pool and get someone else's random Pokémon back.
 * Nobody ever sees who traded what. If the pool only holds your own Pokémon (or is empty),
 * the server hands you a random non-legendary Pokémon at the same level instead.
 *
 * /wondertrade opens the menu: pick a party Pokémon, confirm, then watch the roulette reveal.
 * /wondertrade <slot> trades straight from chat.
 *
 * Self-contained: registers its own command, menu, tick handler and save file.
 */
@EventBusSubscriber(modid = "jcl_gts")
public final class WonderTrade {
    private WonderTrade() {}

    // ================================================================== data

    public static final class Entry {
        public String owner;        // UUID of the sender. Only used so you never get your own Pokémon back. Never shown.
        public String pokemonSnbt;
        public long addedAt;
    }

    public static final class Data {
        public List<Entry> pool = new ArrayList<>();
        public Map<String, Long> lastTrade = new HashMap<>();
    }

    public static final class Config {
        public int cooldownSeconds = 600;
        public int maxPoolSize = 500;
        public boolean announceShinies = true;
        public boolean announceLegendaries = true;
        public boolean stripOriginalTrainer = true;
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Random RNG = new Random();
    private static final Set<String> SPECIAL = Set.of("legendary", "mythical", "ultra_beast", "paradox");
    private static Data data = new Data();
    private static Config cfg = new Config();
    private static MinecraftServer server;

    private static Path dataFile() {
        return server.getWorldPath(LevelResource.ROOT).resolve("data").resolve("jcl_gts").resolve("wondertrade.json");
    }

    private static Path configFile() { return Path.of("config", "jcl_wondertrade.json"); }

    @SubscribeEvent
    public static void onStart(ServerStartedEvent e) {
        server = e.getServer();
        try {
            Path c = configFile();
            if (Files.exists(c)) cfg = GSON.fromJson(Files.readString(c), Config.class);
            if (cfg == null) cfg = new Config();
            Files.createDirectories(c.getParent());
            Files.writeString(c, GSON.toJson(cfg));
        } catch (Exception ex) { GtsMod.LOG.error("[GTS] Could not read config/jcl_wondertrade.json, using defaults", ex); }
        try {
            Path f = dataFile();
            if (Files.exists(f)) {
                Data d = GSON.fromJson(Files.readString(f), Data.class);
                if (d != null) data = d;
            }
        } catch (Exception ex) { GtsMod.LOG.error("[GTS] Could not read the Wonder Trade pool", ex); }
        if (data.pool == null) data.pool = new ArrayList<>();
        if (data.lastTrade == null) data.lastTrade = new HashMap<>();
        GtsMod.LOG.info("[GTS] Wonder Trade pool holds {} Pokémon.", data.pool.size());
    }

    @SubscribeEvent
    public static void onStop(ServerStoppingEvent e) { save(); ANIMS.clear(); }

    private static void save() {
        if (server == null) return;
        try {
            Path f = dataFile();
            Files.createDirectories(f.getParent());
            Path tmp = f.resolveSibling("wondertrade.json.tmp");
            Files.writeString(tmp, GSON.toJson(data));
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception ex) { GtsMod.LOG.error("[GTS] Could not save the Wonder Trade pool", ex); }
    }

    // ================================================================== command

    @SubscribeEvent
    public static void onCommands(RegisterCommandsEvent e) {
        e.getDispatcher().register(Commands.literal("wondertrade")
                .executes(ctx -> { openMain(ctx.getSource().getPlayerOrException()); return 1; })
                .then(Commands.argument("slot", IntegerArgumentType.integer(1, 6))
                        .executes(ctx -> {
                            ServerPlayer p = ctx.getSource().getPlayerOrException();
                            Result r = trade(p, IntegerArgumentType.getInteger(ctx, "slot") - 1);
                            if (r.error != null) { ctx.getSource().sendFailure(Component.literal(r.error)); return 0; }
                            chatResult(p, r);
                            return 1;
                        })));
    }

    private static long cooldownLeft(ServerPlayer p) {
        Long last = data.lastTrade.get(p.getUUID().toString());
        if (last == null) return 0;
        return Math.max(0, last + cfg.cooldownSeconds * 1000L - System.currentTimeMillis());
    }

    private static String fmtTime(long ms) {
        long s = (ms + 999) / 1000;
        return s >= 60 ? (s / 60) + "m " + (s % 60) + "s" : s + "s";
    }

    // ================================================================== the trade

    public static final class Result {
        String error;
        String sentName;
        CobblemonBridge.Snap got;
        boolean shiny, legend, toClaims;
        int level;
    }

    private static Result fail(String msg) { Result r = new Result(); r.error = msg; return r; }

    private static Result trade(ServerPlayer p, int slotIndex) {
        if (!CobblemonBridge.available()) return fail("Cobblemon is not available.");
        long left = cooldownLeft(p);
        if (left > 0) return fail("Wonder Trade is recharging. Try again in " + fmtTime(left) + ".");
        try {
            Object party = CobblemonBridge.party(p);
            Object mine = CobblemonBridge.partyGet(party, slotIndex);
            if (mine == null) return fail("There is no Pokémon in party slot " + (slotIndex + 1) + ".");
            if (GtsConfig.INSTANCE.blockLastPokemon && CobblemonBridge.partyCount(party) <= 1) return fail("You can't wonder trade your only Pokémon.");

            Result r = new Result();
            r.sentName = CobblemonBridge.snapshot(mine, server.registryAccess()).title;
            r.level = intOf(mine, "getLevel", 5);
            CompoundTag sentNbt = CobblemonBridge.toNbt(mine, server.registryAccess());
            if (cfg.stripOriginalTrainer) stripTrainer(sentNbt);
            if (CobblemonBridge.fromNbt(server.registryAccess(), sentNbt.copy()) == null) return fail("This Pokémon can't be traded right now.");

            // Find a Pokémon from someone else, and build it before anything leaves your party.
            String me = p.getUUID().toString();
            List<Entry> candidates = new ArrayList<>();
            for (Entry en : data.pool) if (!me.equals(en.owner)) candidates.add(en);
            Entry pick = null;
            Object received;
            if (!candidates.isEmpty()) {
                pick = candidates.get(RNG.nextInt(candidates.size()));
                received = CobblemonBridge.fromNbt(server.registryAccess(), TagParser.parseTag(pick.pokemonSnbt));
            } else {
                received = randomWild(r.level, true);
            }
            if (received == null) return fail("Wonder Trade couldn't find a partner right now. Nothing was traded.");

            if (!CobblemonBridge.removeFromParty(party, mine)) return fail("Could not take that Pokémon out of your party.");
            if (pick != null) data.pool.remove(pick);

            Entry e = new Entry();
            e.owner = me;
            e.pokemonSnbt = sentNbt.toString();
            e.addedAt = System.currentTimeMillis();
            data.pool.add(e);
            while (data.pool.size() > cfg.maxPoolSize) data.pool.remove(0);
            data.lastTrade.put(me, System.currentTimeMillis());

            r.got = CobblemonBridge.snapshot(received, server.registryAccess());
            r.shiny = boolOf(received, "getShiny");
            r.legend = isSpecial(received);
            if (!CobblemonBridge.give(p, received)) {
                ClaimEntry c = new ClaimEntry();
                c.type = ClaimEntry.Type.POKEMON;
                c.pokemonSnbt = CobblemonBridge.toNbt(received, server.registryAccess()).toString();
                c.itemSnbt = Market.encode(r.got.icon);
                c.title = r.got.title;
                c.reason = "Wonder Trade";
                Market.data.claimsOf(p.getUUID()).add(c);
                Market.markDirty();
                r.toClaims = true;
            }
            save();
            if ((r.shiny && cfg.announceShinies) || (r.legend && cfg.announceLegendaries)) announce(r.got.title, r.shiny, r.legend);
            return r;
        } catch (Throwable t) {
            GtsMod.LOG.error("[GTS] Wonder Trade failed", t);
            return fail("Something went wrong with Wonder Trade. Tell an admin if your Pokémon is missing.");
        }
    }

    private static void chatResult(ServerPlayer p, Result r) {
        p.sendSystemMessage(tag().append(Component.literal("§7You sent " + r.sentName + "§7 into the unknown...")));
        p.sendSystemMessage(tag().append(Component.literal("§7...and received " + r.got.title + "§7!")));
        if (r.toClaims) p.sendSystemMessage(tag().append(Fmt.chat("Your party and PC are full, so it's waiting in /gts claims.", ChatFormatting.YELLOW)));
    }

    private static MutableComponent tag() { return Fmt.chat("[Wonder Trade] ", ChatFormatting.LIGHT_PURPLE); }

    private static void stripTrainer(CompoundTag tag) {
        for (String k : new ArrayList<>(tag.getAllKeys())) {
            if (k.toLowerCase().contains("originaltrainer")) tag.remove(k);
        }
    }

    /** A random Pokémon at the given level. With noSpecial, legendaries, mythicals, Ultra Beasts and paradoxes are skipped. */
    private static Object randomWild(int level, boolean noSpecial) {
        try {
            Class<?> ps = Class.forName("com.cobblemon.mod.common.api.pokemon.PokemonSpecies");
            Object inst = ps.getField("INSTANCE").get(null);
            Object implemented = call(inst, "getImplemented");
            List<Object> pool = new ArrayList<>();
            for (Object sp : (Iterable<?>) implemented) {
                if (noSpecial && hasSpecialLabel(sp)) continue;
                pool.add(sp);
            }
            if (pool.isEmpty()) return null;
            Object species = pool.get(RNG.nextInt(pool.size()));
            return call(species, "create", Math.max(1, Math.min(100, level)));
        } catch (Throwable t) {
            GtsMod.LOG.error("[GTS] Could not create a random Pokémon for Wonder Trade", t);
            return null;
        }
    }

    private static boolean hasSpecialLabel(Object species) {
        try {
            Object labels = call(species, "getLabels");
            if (labels instanceof Iterable<?> it) for (Object l : it) if (SPECIAL.contains(String.valueOf(l))) return true;
        } catch (Throwable ignored) {}
        return false;
    }

    private static boolean isSpecial(Object pk) {
        try { return hasSpecialLabel(call(pk, "getSpecies")); } catch (Throwable t) { return false; }
    }

    private static void announce(String title, boolean shiny, boolean legend) {
        String what = shiny && legend ? "a shiny legend" : shiny ? "a shiny" : "a legend";
        server.getPlayerList().broadcastSystemMessage(tag()
                .append(Component.literal("§eSomeone just pulled " + what + ": " + title + "§e!")), false);
        String url = GtsConfig.INSTANCE.webhookUrl;
        if (url == null || url.isBlank()) return;
        try {
            JsonObject body = new JsonObject();
            body.addProperty("username", GtsConfig.INSTANCE.webhookName);
            body.addProperty("content", "🎁 **Wonder Trade:** someone just pulled " + what + ": **" + title.replaceAll("§.", "") + "**!");
            HttpClient.newHttpClient().sendAsync(HttpRequest.newBuilder(URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(), HttpResponse.BodyHandlers.discarding());
        } catch (Throwable t) { GtsMod.LOG.warn("[GTS] Wonder Trade Discord post failed", t); }
    }

    // ================================================================== GUI
    //
    // A 5-row chest menu, drawn and handled entirely on the server. Layout (slot numbers):
    //   row 0  0- 8  border, title in 4
    //   row 1  9-17  border, info in 11, pool in 13, cooldown in 15
    //   row 2 18-26  border, party in 19 20 21 | 23 24 25, centre 22
    //   row 3 27-35  border, confirm 30, cancel 32 (confirm screen) / again 30, close 32 (result screen)
    //   row 4 36-44  border

    private enum Screen { MAIN, CONFIRM, ROLLING, RESULT }

    private static final int[] PARTY_SLOTS = {19, 20, 21, 23, 24, 25};
    private static final int CENTER = 22;
    private static final Item[] RAINBOW = {Items.MAGENTA_STAINED_GLASS_PANE, Items.PURPLE_STAINED_GLASS_PANE, Items.BLUE_STAINED_GLASS_PANE,
            Items.LIGHT_BLUE_STAINED_GLASS_PANE, Items.CYAN_STAINED_GLASS_PANE, Items.LIME_STAINED_GLASS_PANE,
            Items.YELLOW_STAINED_GLASS_PANE, Items.ORANGE_STAINED_GLASS_PANE, Items.PINK_STAINED_GLASS_PANE};

    private static final class WTMenu extends ChestMenu {
        final SimpleContainer box;
        final ServerPlayer viewer;
        Screen screen = Screen.MAIN;
        int selected = -1;

        WTMenu(int id, Inventory inv, SimpleContainer box, ServerPlayer viewer) {
            super(MenuType.GENERIC_9x5, id, inv, box, 5);
            this.box = box;
            this.viewer = viewer;
        }

        @Override
        public void clicked(int slot, int button, ClickType type, Player player) {
            // Nothing in this menu can be picked up or moved; every click is a button press.
            if (slot >= 0 && slot < box.getContainerSize()) onClick(this, slot);
            broadcastFullState();
        }

        @Override public ItemStack quickMoveStack(Player player, int index) { return ItemStack.EMPTY; }
        @Override public boolean stillValid(Player player) { return true; }
    }

    static void openMain(ServerPlayer p) {
        SimpleContainer box = new SimpleContainer(45);
        p.openMenu(new SimpleMenuProvider((id, inv, pl) -> {
            WTMenu m = new WTMenu(id, inv, box, p);
            drawMain(m);
            return m;
        }, Component.literal("§5§l✦ Wonder Trade ✦")));
    }

    private static ItemStack named(Item item, String name, String... lore) {
        ItemStack s = new ItemStack(item);
        s.set(DataComponents.CUSTOM_NAME, Component.literal(name).withStyle(st -> st.withItalic(false)));
        if (lore.length > 0) {
            List<Component> lines = new ArrayList<>();
            for (String l : lore) lines.add(Component.literal(l).withStyle(st -> st.withItalic(false)));
            s.set(DataComponents.LORE, new ItemLore(lines));
        }
        return s;
    }

    private static ItemStack pokemonIcon(CobblemonBridge.Snap snap, String... extra) {
        ItemStack s = snap.icon.copy();
        s.set(DataComponents.CUSTOM_NAME, Component.literal(snap.title).withStyle(st -> st.withItalic(false)));
        List<Component> lines = new ArrayList<>();
        for (String l : snap.lines) lines.add(Component.literal(l).withStyle(st -> st.withItalic(false)));
        for (String l : extra) lines.add(Component.literal(l).withStyle(st -> st.withItalic(false)));
        s.set(DataComponents.LORE, new ItemLore(lines));
        return s;
    }

    private static void border(WTMenu m, Item pane) {
        ItemStack glass = named(pane, " ");
        for (int i = 0; i < 45; i++) m.box.setItem(i, glass.copy());
    }

    private static void drawMain(WTMenu m) {
        m.screen = Screen.MAIN;
        border(m, Items.PURPLE_STAINED_GLASS_PANE);
        long left = cooldownLeft(m.viewer);
        m.box.setItem(4, named(Items.NETHER_STAR, "§d§lWonder Trade",
                "§7Send a Pokémon into the unknown,", "§7and get a random one back.", "",
                "§7Nobody knows who sent what.", "§7Not even you."));
        m.box.setItem(11, named(Items.BOOK, "§e§lHow it works",
                "§71. Click a Pokémon from your party.", "§72. Confirm the trade.", "§73. See who you get!", "",
                "§7Anything can come out: even shinies", "§7and legends, if someone sent one."));
        m.box.setItem(13, named(Items.ENDER_CHEST, "§b§lThe Pool", "§7Pokémon waiting to be traded: §f" + data.pool.size()));
        m.box.setItem(15, left > 0
                ? named(Items.CLOCK, "§c§lRecharging", "§7You can trade again in §f" + fmtTime(left) + "§7.")
                : named(Items.CLOCK, "§a§lReady!", "§7You can wonder trade now."));
        m.box.setItem(CENTER, named(Items.ENDER_EYE, "§5?", "§7Pick a Pokémon to send."));
        try {
            Object party = CobblemonBridge.party(m.viewer);
            for (int i = 0; i < 6; i++) {
                Object pk = CobblemonBridge.partyGet(party, i);
                if (pk == null) { m.box.setItem(PARTY_SLOTS[i], named(Items.GRAY_STAINED_GLASS_PANE, "§8Empty slot")); continue; }
                CobblemonBridge.Snap snap = CobblemonBridge.snapshot(pk, server.registryAccess());
                m.box.setItem(PARTY_SLOTS[i], pokemonIcon(snap, "", left > 0 ? "§cRecharging..." : "§a▶ Click to wonder trade"));
            }
        } catch (Throwable t) { GtsMod.LOG.error("[GTS] Could not draw the Wonder Trade party", t); }
        m.box.setItem(40, named(Items.BARRIER, "§cClose"));
    }

    private static void drawConfirm(WTMenu m, int partyIndex) {
        m.screen = Screen.CONFIRM;
        m.selected = partyIndex;
        border(m, Items.MAGENTA_STAINED_GLASS_PANE);
        try {
            Object pk = CobblemonBridge.partyGet(CobblemonBridge.party(m.viewer), partyIndex);
            if (pk == null) { drawMain(m); return; }
            m.box.setItem(13, pokemonIcon(CobblemonBridge.snapshot(pk, server.registryAccess()), "", "§d§lThis Pokémon will be sent away."));
        } catch (Throwable t) { drawMain(m); return; }
        m.box.setItem(4, named(Items.NETHER_STAR, "§d§lAre you sure?", "§7Once it's gone, it's gone.", "§7You won't know who receives it."));
        m.box.setItem(30, named(Items.LIME_CONCRETE, "§a§lSend it!", "§7Trade this Pokémon into the unknown."));
        m.box.setItem(32, named(Items.RED_CONCRETE, "§c§lCancel", "§7Go back."));
    }

    private static void onClick(WTMenu m, int slot) {
        ServerPlayer p = m.viewer;
        switch (m.screen) {
            case MAIN -> {
                if (slot == 40) { p.closeContainer(); return; }
                for (int i = 0; i < 6; i++) {
                    if (PARTY_SLOTS[i] != slot) continue;
                    long left = cooldownLeft(p);
                    if (left > 0) { sound(p, SoundEvents.VILLAGER_NO, 1f); p.sendSystemMessage(tag().append(Fmt.chat("Recharging. Try again in " + fmtTime(left) + ".", ChatFormatting.RED))); return; }
                    try { if (CobblemonBridge.partyGet(CobblemonBridge.party(p), i) == null) return; } catch (Throwable t) { return; }
                    sound(p, SoundEvents.EXPERIENCE_ORB_PICKUP, 1.2f);
                    drawConfirm(m, i);
                }
            }
            case CONFIRM -> {
                if (slot == 32) { sound(p, SoundEvents.EXPERIENCE_ORB_PICKUP, 0.8f); drawMain(m); }
                if (slot == 30) startRoll(m);
            }
            case ROLLING -> {}
            case RESULT -> {
                if (slot == 30) { sound(p, SoundEvents.EXPERIENCE_ORB_PICKUP, 1.2f); drawMain(m); }
                if (slot == 32) p.closeContainer();
            }
        }
    }

    // ------------------------------------------------------------------ the reveal

    private static final class Anim {
        WTMenu menu;
        Result result;
        List<ItemStack> frames = new ArrayList<>();
        int tick, frame, nextChange;
    }

    private static final Map<UUID, Anim> ANIMS = new HashMap<>();
    // Ticks at which the centre icon changes: fast at first, then slowing down before the reveal.
    private static final int[] SCHEDULE = {0, 2, 4, 6, 8, 10, 12, 14, 17, 20, 23, 27, 31, 36, 42, 49, 57, 66};

    private static void startRoll(WTMenu m) {
        // The trade happens right now. The animation is only for show, so closing the menu loses nothing.
        Result r = trade(m.viewer, m.selected);
        if (r.error != null) {
            sound(m.viewer, SoundEvents.VILLAGER_NO, 1f);
            m.viewer.sendSystemMessage(tag().append(Fmt.chat(r.error, ChatFormatting.RED)));
            drawMain(m);
            return;
        }
        Anim a = new Anim();
        a.menu = m;
        a.result = r;
        for (int i = 0; i < 6; i++) {
            Object pk = randomWild(r.level, false);
            if (pk != null) a.frames.add(pokemonIcon(CobblemonBridge.snapshot(pk, server.registryAccess())));
        }
        if (a.frames.isEmpty()) a.frames.add(named(Items.ENDER_EYE, "§5?"));
        m.screen = Screen.ROLLING;
        border(m, Items.BLACK_STAINED_GLASS_PANE);
        m.box.setItem(4, named(Items.NETHER_STAR, "§d§lSearching the world...", "§7Someone, somewhere, is sending you a Pokémon."));
        ANIMS.put(m.viewer.getUUID(), a);
        // If they close the menu mid-roll, they still get the Pokémon: tell them in chat too.
        chatResult(m.viewer, r);
    }

    @SubscribeEvent
    public static void onTick(ServerTickEvent.Post e) {
        if (ANIMS.isEmpty()) return;
        Iterator<Map.Entry<UUID, Anim>> it = ANIMS.entrySet().iterator();
        while (it.hasNext()) {
            Anim a = it.next().getValue();
            ServerPlayer p = a.menu.viewer;
            if (p.hasDisconnected() || p.containerMenu != a.menu) { it.remove(); continue; }
            if (a.frame < SCHEDULE.length && a.tick == SCHEDULE[a.frame]) {
                a.menu.box.setItem(CENTER, a.frames.get(a.frame % a.frames.size()).copy());
                // Spinning ring of colour around the centre.
                int[] ring = {12, 13, 14, 23, 32, 31, 30, 21};
                for (int i = 0; i < ring.length; i++) {
                    Item pane = RAINBOW[(i + a.frame) % RAINBOW.length];
                    a.menu.box.setItem(ring[i], named(pane, " "));
                }
                sound(p, SoundEvents.NOTE_BLOCK_HAT.value(), 0.8f + a.frame * 0.06f);
                a.frame++;
                a.menu.broadcastFullState();
            }
            if (a.frame >= SCHEDULE.length && a.tick >= SCHEDULE[SCHEDULE.length - 1] + 12) {
                reveal(a);
                it.remove();
                continue;
            }
            a.tick++;
        }
    }

    private static void reveal(Anim a) {
        WTMenu m = a.menu;
        Result r = a.result;
        ServerPlayer p = m.viewer;
        m.screen = Screen.RESULT;
        boolean special = r.shiny || r.legend;
        border(m, special ? Items.YELLOW_STAINED_GLASS_PANE : Items.LIME_STAINED_GLASS_PANE);
        String headline = r.shiny && r.legend ? "§6§l★ A SHINY LEGEND! ★" : r.shiny ? "§e§l★ IT'S SHINY! ★" : r.legend ? "§6§l✦ A LEGEND! ✦" : "§a§lYou received...";
        m.box.setItem(4, named(special ? Items.TOTEM_OF_UNDYING : Items.NETHER_STAR, headline,
                "§7You sent " + r.sentName + "§7.", r.toClaims ? "§eIt's waiting in /gts claims." : "§7It's been added to your party or PC."));
        m.box.setItem(CENTER, pokemonIcon(r.got));
        m.box.setItem(30, named(Items.ENDER_PEARL, "§d§lTrade again", "§7Back to your party."));
        m.box.setItem(32, named(Items.BARRIER, "§cClose"));
        m.broadcastFullState();
        if (special) { sound(p, SoundEvents.TOTEM_USE, 1f); sound(p, SoundEvents.PLAYER_LEVELUP, 1.2f); }
        else sound(p, SoundEvents.PLAYER_LEVELUP, 1f);
    }

    private static void sound(ServerPlayer p, SoundEvent s, float pitch) {
        p.playNotifySound(s, SoundSource.MASTER, 0.7f, pitch);
    }

    // ================================================================== small reflection helpers

    private static Object call(Object target, String name, Object... args) throws Exception {
        for (Method m : target.getClass().getMethods()) {
            if (!m.getName().equals(name) || m.getParameterCount() != args.length) continue;
            try { return m.invoke(target, args); } catch (IllegalArgumentException ignored) {}
        }
        throw new NoSuchMethodException(target.getClass().getName() + "." + name);
    }

    private static int intOf(Object o, String getter, int dflt) {
        try { return ((Number) call(o, getter)).intValue(); } catch (Throwable t) { return dflt; }
    }

    private static boolean boolOf(Object o, String getter) {
        try { return Boolean.TRUE.equals(call(o, getter)); } catch (Throwable t) { return false; }
    }
}
