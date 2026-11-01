package net.joeyscobblemon.gts;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;

import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Crates: Poké, Great, Ultra and Master. Turn any block into a crate with /crate set <tier>.
 * Left-click a crate: preview every reward, its exact chance and your pity progress.
 * Right-click while holding the matching key: open it.
 * Rewards, chances, pity and merge costs live in config/jcl_crates.json.
 */
@EventBusSubscriber(modid = "jcl_gts")
public final class Crates {
    private Crates() {}

    static final List<String> TIERS = List.of("poke", "great", "ultra", "master");

    // ================================================================== config + data

    public static final class Reward {
        public String name, rarity = "common", type = "item";
        public double weight;
        public List<String> items = new ArrayList<>();
        public int count = 1;
        public long money;
        public List<String> commands = new ArrayList<>();
        public String key;
        public String title;
        public int level = 30;
        public boolean shiny, legendary, jackpot;
    }

    public static final class Crate {
        public String name, color = "§f";
        public int pity = 50;
        public List<Reward> rewards = new ArrayList<>();
    }

    public static final class Merge {
        public String from;
        public int keys;
        public long money;
        public String item;
        public int itemCount;
    }

    public static final class Config {
        public List<String> announceRarities = List.of("epic", "legendary");
        public List<String> discordRarities = List.of("legendary");
        public Map<String, Crate> crates = new LinkedHashMap<>();
        public Map<String, Merge> merges = new LinkedHashMap<>();
    }

    public static final class Data {
        public Map<String, String> blocks = new HashMap<>();             // "dimension|x|y|z" -> tier
        public Map<String, Map<String, Integer>> pity = new HashMap<>();  // uuid -> tier -> opens since last legendary
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Random RNG = new Random();
    static Config cfg = new Config();
    static Data data = new Data();
    static MinecraftServer server;

    private static Path cfgFile() { return Path.of("config", "jcl_crates.json"); }
    private static Path dataFile() { return server.getWorldPath(LevelResource.ROOT).resolve("data").resolve("jcl_gts").resolve("crates.json"); }
    private static Path logFile() { return server.getWorldPath(LevelResource.ROOT).resolve("data").resolve("jcl_gts").resolve("crate_log.txt"); }

    /** Copies a default config out of the jar the first time, then reads it. */
    static String readDefault(String name) {
        try (InputStream in = Crates.class.getResourceAsStream("/jcl_defaults/" + name)) {
            return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) { return null; }
    }

    static void load() {
        try {
            Path f = cfgFile();
            if (!Files.exists(f)) {
                String d = readDefault("crates.json");
                Files.createDirectories(f.getParent());
                if (d != null) Files.writeString(f, d);
            }
            Config c = GSON.fromJson(Files.readString(f), Config.class);
            if (c != null) cfg = c;
        } catch (Exception e) { GtsMod.LOG.error("[JCL] Could not read config/jcl_crates.json", e); }
        try {
            Path f = dataFile();
            if (Files.exists(f)) { Data d = GSON.fromJson(Files.readString(f), Data.class); if (d != null) data = d; }
        } catch (Exception e) { GtsMod.LOG.error("[JCL] Could not read crate data", e); }
        if (data.blocks == null) data.blocks = new HashMap<>();
        if (data.pity == null) data.pity = new HashMap<>();
    }

    static void save() {
        if (server == null) return;
        try {
            Path f = dataFile();
            Files.createDirectories(f.getParent());
            Files.writeString(f, GSON.toJson(data));
        } catch (Exception e) { GtsMod.LOG.error("[JCL] Could not save crate data", e); }
    }

    @SubscribeEvent public static void onStart(ServerStartedEvent e) { server = e.getServer(); load(); }
    @SubscribeEvent public static void onStop(ServerStoppingEvent e) { save(); ANIMS.clear(); }

    static int pity(ServerPlayer p, String tier) {
        return data.pity.getOrDefault(p.getUUID().toString(), Map.of()).getOrDefault(tier, 0);
    }

    static void setPity(ServerPlayer p, String tier, int v) {
        data.pity.computeIfAbsent(p.getUUID().toString(), k -> new HashMap<>()).put(tier, v);
    }

    static String posKey(ServerPlayer p, BlockPos pos) {
        return p.level().dimension().location() + "|" + pos.getX() + "|" + pos.getY() + "|" + pos.getZ();
    }

    // ================================================================== crate blocks

    @SubscribeEvent
    public static void onLeftClick(PlayerInteractEvent.LeftClickBlock e) {
        if (e.getLevel().isClientSide() || !(e.getEntity() instanceof ServerPlayer p)) return;
        String tier = data.blocks.get(posKey(p, e.getPos()));
        if (tier == null) return;
        e.setCanceled(true);
        if (e.getAction() == PlayerInteractEvent.LeftClickBlock.Action.START) preview(p, tier);
    }

    @SubscribeEvent
    public static void onRightClick(PlayerInteractEvent.RightClickBlock e) {
        if (e.getLevel().isClientSide() || !(e.getEntity() instanceof ServerPlayer p) || e.getHand() != InteractionHand.MAIN_HAND) return;
        String tier = data.blocks.get(posKey(p, e.getPos()));
        if (tier == null) return;
        e.setCanceled(true);
        if (tier.equals(Keys.tierOf(p.getMainHandItem()))) open(p, tier);
        else preview(p, tier);
    }

    // ================================================================== commands

    @SubscribeEvent
    public static void onCommands(RegisterCommandsEvent e) {
        e.getDispatcher().register(Commands.literal("crate")
                .then(Commands.literal("preview").then(Commands.argument("tier", StringArgumentType.word()).suggests((c, b) -> SharedSuggestionProvider.suggest(TIERS, b))
                        .executes(c -> { String t = StringArgumentType.getString(c, "tier"); if (cfg.crates.containsKey(t)) preview(c.getSource().getPlayerOrException(), t); return 1; })))
                .then(Commands.literal("merge").executes(c -> { mergeMenu(c.getSource().getPlayerOrException()); return 1; }))
                .then(Commands.literal("give").requires(s -> s.hasPermission(2))
                        .then(Commands.argument("players", EntityArgument.players()).then(Commands.argument("tier", StringArgumentType.word()).suggests((c, b) -> SharedSuggestionProvider.suggest(TIERS, b))
                                .then(Commands.argument("amount", IntegerArgumentType.integer(1, 640)).executes(c -> {
                                    String t = StringArgumentType.getString(c, "tier");
                                    Item key = Keys.item(t);
                                    if (key == null) { c.getSource().sendFailure(Component.literal("Unknown tier: " + t)); return 0; }
                                    int n = IntegerArgumentType.getInteger(c, "amount");
                                    for (ServerPlayer p : EntityArgument.getPlayers(c, "players")) {
                                        giveStack(p, new ItemStack(key, n));
                                        p.sendSystemMessage(Component.literal("§6§l[Crates] §r§aYou received §f" + n + "x " + new ItemStack(key).getHoverName().getString() + "§a!"));
                                    }
                                    return 1;
                                })))))
                .then(Commands.literal("set").requires(s -> s.hasPermission(2)).then(Commands.argument("tier", StringArgumentType.word()).suggests((c, b) -> SharedSuggestionProvider.suggest(TIERS, b))
                        .executes(c -> {
                            ServerPlayer p = c.getSource().getPlayerOrException();
                            String t = StringArgumentType.getString(c, "tier");
                            BlockPos pos = lookedAt(p);
                            if (pos == null || !cfg.crates.containsKey(t)) { c.getSource().sendFailure(Component.literal("Look at a block within 6 blocks, and use poke, great, ultra or master.")); return 0; }
                            data.blocks.put(posKey(p, pos), t); save();
                            p.sendSystemMessage(Component.literal("§aThat block is now a " + cfg.crates.get(t).name + "."));
                            return 1;
                        })))
                .then(Commands.literal("remove").requires(s -> s.hasPermission(2)).executes(c -> {
                    ServerPlayer p = c.getSource().getPlayerOrException();
                    BlockPos pos = lookedAt(p);
                    if (pos == null || data.blocks.remove(posKey(p, pos)) == null) { c.getSource().sendFailure(Component.literal("You're not looking at a crate.")); return 0; }
                    save(); p.sendSystemMessage(Component.literal("§aCrate removed. The block is normal again."));
                    return 1;
                }))
                .then(Commands.literal("reload").requires(s -> s.hasPermission(2)).executes(c -> { load(); c.getSource().sendSuccess(() -> Component.literal("§aCrate config reloaded."), true); return 1; }))
                .then(Commands.literal("pity").requires(s -> s.hasPermission(2)).then(Commands.argument("player", EntityArgument.player()).executes(c -> {
                    ServerPlayer t = EntityArgument.getPlayer(c, "player");
                    StringBuilder b = new StringBuilder("§6Pity for " + t.getGameProfile().getName() + ": ");
                    for (String tier : TIERS) if (cfg.crates.containsKey(tier)) b.append("§f").append(tier).append(" §7").append(pity(t, tier)).append("/").append(cfg.crates.get(tier).pity).append("  ");
                    c.getSource().sendSuccess(() -> Component.literal(b.toString()), false);
                    return 1;
                }))));
    }

    private static BlockPos lookedAt(ServerPlayer p) {
        HitResult hit = p.level().clip(new ClipContext(p.getEyePosition(), p.getEyePosition().add(p.getLookAngle().scale(6)), ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, p));
        return hit.getType() == HitResult.Type.BLOCK ? ((BlockHitResult) hit).getBlockPos() : null;
    }

    // ================================================================== helpers

    static String rarityColor(String r) {
        return switch (r) { case "rare" -> "§9"; case "epic" -> "§5"; case "legendary" -> "§6"; default -> "§f"; };
    }

    static String rarityName(String r) { return r.substring(0, 1).toUpperCase() + r.substring(1); }

    static Item resolve(String id) {
        try {
            Item i = BuiltInRegistries.ITEM.get(ResourceLocation.parse(id));
            return i == Items.AIR ? Items.BARRIER : i;
        } catch (Exception e) { return Items.BARRIER; }
    }

    static ItemStack iconFor(Reward r) {
        return switch (r.type) {
            case "money" -> new ItemStack(r.jackpot ? Items.GOLD_BLOCK : Items.SUNFLOWER);
            case "key" -> new ItemStack(Keys.item(r.key));
            case "pokemon" -> new ItemStack(r.legendary ? Items.TOTEM_OF_UNDYING : Items.NETHER_STAR);
            case "command", "title" -> new ItemStack(Items.NAME_TAG);
            default -> r.items.isEmpty() ? new ItemStack(Items.CHEST) : new ItemStack(resolve(r.items.get(0)), Math.max(1, Math.min(64, r.count)));
        };
    }

    static double total(Crate c) { double t = 0; for (Reward r : c.rewards) t += r.weight; return t; }

    static int countKeys(ServerPlayer p, String tier) {
        Item k = Keys.item(tier); int n = 0;
        for (ItemStack s : p.getInventory().items) if (s.is(k)) n += s.getCount();
        return n;
    }

    static boolean takeItems(ServerPlayer p, Item item, int amount) {
        int have = 0;
        for (ItemStack s : p.getInventory().items) if (s.is(item)) have += s.getCount();
        if (have < amount) return false;
        for (ItemStack s : p.getInventory().items) {
            if (amount <= 0) break;
            if (!s.is(item)) continue;
            int take = Math.min(amount, s.getCount());
            s.shrink(take); amount -= take;
        }
        return true;
    }

    static void giveStack(ServerPlayer p, ItemStack s) {
        while (!s.isEmpty()) {
            ItemStack part = s.split(Math.min(s.getMaxStackSize(), s.getCount()));
            if (!p.getInventory().add(part)) p.drop(part, false);
        }
    }

    private static void sound(ServerPlayer p, SoundEvent s, float pitch) { p.playNotifySound(s, SoundSource.MASTER, 0.7f, pitch); }

    // ================================================================== preview

    static void preview(ServerPlayer p, String tier) {
        Crate c = cfg.crates.get(tier);
        if (c == null) return;
        double tot = total(c);
        Gui.Screen s = new Gui.Screen(c.color + "§l" + c.name, 6);
        Item edge = switch (tier) { case "great" -> Items.BLUE_STAINED_GLASS_PANE; case "ultra" -> Items.YELLOW_STAINED_GLASS_PANE; case "master" -> Items.PURPLE_STAINED_GLASS_PANE; default -> Items.RED_STAINED_GLASS_PANE; };
        s.frame(edge, Items.BLACK_STAINED_GLASS_PANE);

        Map<String, Double> byRarity = new LinkedHashMap<>();
        for (String r : List.of("legendary", "epic", "rare", "common")) byRarity.put(r, 0.0);
        for (Reward r : c.rewards) byRarity.merge(r.rarity, r.weight, Double::sum);
        int keys = countKeys(p, tier);
        s.set(4, Gui.glow(Gui.icon(Keys.item(tier), c.color + "§l" + c.name,
                "§7Your " + new ItemStack(Keys.item(tier)).getHoverName().getString() + "s: §f" + keys, "",
                "§6Legendary: §f" + pct(byRarity.get("legendary"), tot),
                "§5Epic: §f" + pct(byRarity.get("epic"), tot),
                "§9Rare: §f" + pct(byRarity.get("rare"), tot),
                "§fCommon: §f" + pct(byRarity.get("common"), tot), "",
                "§8Every reward and its exact chance is shown below.")));

        List<Reward> sorted = new ArrayList<>(c.rewards);
        List<String> order = List.of("legendary", "epic", "rare", "common");
        sorted.sort((a, b) -> order.indexOf(a.rarity) != order.indexOf(b.rarity) ? order.indexOf(a.rarity) - order.indexOf(b.rarity) : Double.compare(a.weight, b.weight));
        int[] grid = gridSlots();
        for (int i = 0; i < Math.min(grid.length, sorted.size()); i++) {
            Reward r = sorted.get(i);
            List<String> lore = new ArrayList<>();
            lore.add(rarityColor(r.rarity) + "§l" + rarityName(r.rarity).toUpperCase() + (r.jackpot ? " §e§l★ JACKPOT" : ""));
            lore.add("§7Chance: §f" + pct(r.weight, tot) + " §8(1 in " + Math.max(1, Math.round(tot / Math.max(r.weight, 0.0001))) + ")");
            if ("item".equals(r.type) && r.items.size() > 1) lore.add("§7One random pick from §f" + r.items.size() + "§7 items");
            if ("item".equals(r.type) || "key".equals(r.type)) lore.add("§7Amount: §f" + r.count);
            if ("title".equals(r.type)) { lore.add("§7Unlocks a chat title. Equip it with §f/titles§7."); lore.add("§8Already have it? You get " + Fmt.cd(r.money) + " instead."); }
            if ("pokemon".equals(r.type)) lore.add("§7Level §f" + r.level + (r.shiny ? " §e✦ Shiny" : "") + (r.legendary ? " §6Legendary" : ""));
            if (r.jackpot) lore.add("§8Pity never gives a jackpot. Pure luck!");
            ItemStack icon = Gui.named(iconFor(r), rarityColor(r.rarity) + r.name, lore.toArray(new String[0]));
            s.set(grid[i], "legendary".equals(r.rarity) ? Gui.glow(icon) : icon);
        }

        // Pity bar: 7 segments that fill as you open crates without a Legendary.
        int done = pity(p, tier), limit = Math.max(1, c.pity);
        int filled = (int) Math.floor(7.0 * done / limit);
        for (int i = 0; i < 7; i++) {
            Item seg = i < filled ? Items.ORANGE_STAINED_GLASS_PANE : Items.GRAY_STAINED_GLASS_PANE;
            s.set(46 + i, Gui.icon(seg, "§6§lPity: §f" + done + " / " + limit,
                    "§7Opens since your last Legendary reward.",
                    "§eLegendary guaranteed within §f" + Math.max(1, limit - done) + "§e more opens.",
                    "§8Resets when you get a Legendary."));
        }
        s.set(45, Gui.icon(Items.ANVIL, "§d§lMerge keys", "§7Upgrade lower keys into", "§7higher ones, for a fee."), pl -> mergeMenu(pl));
        s.set(53, keys > 0
                ? Gui.glow(Gui.icon(Items.LIME_CONCRETE, "§a§lOpen crate", "§7Uses 1 key.", "§7You have §f" + keys + "§7."))
                : Gui.icon(Items.RED_CONCRETE, "§c§lNo key", "§7Earn keys from chores, quests", "§7and events, or buy them in the shop."),
                pl -> { if (countKeys(pl, tier) > 0) open(pl, tier); else sound(pl, SoundEvents.VILLAGER_NO, 1f); });
        Gui.open(p, s);
    }

    private static String pct(double w, double tot) {
        double v = tot <= 0 ? 0 : 100.0 * w / tot;
        return v >= 1 ? String.format("%.1f%%", v) : String.format("%.2f%%", v);
    }

    private static int[] gridSlots() {
        List<Integer> s = new ArrayList<>();
        for (int r = 1; r <= 4; r++) for (int c = 1; c <= 7; c++) s.add(r * 9 + c);
        return s.stream().mapToInt(Integer::intValue).toArray();
    }

    // ================================================================== opening

    static void open(ServerPlayer p, String tier) {
        Crate c = cfg.crates.get(tier);
        if (c == null || c.rewards.isEmpty()) return;
        if (ANIMS.containsKey(p.getUUID())) return;
        // The key is used up before anything is rolled, so nothing can be duplicated.
        Item key = Keys.item(tier);
        ItemStack hand = p.getMainHandItem();
        if (hand.is(key)) hand.shrink(1);
        else if (!takeItems(p, key, 1)) { sound(p, SoundEvents.VILLAGER_NO, 1f); p.sendSystemMessage(Component.literal("§cYou need a " + new ItemStack(key).getHoverName().getString() + ".")); return; }

        int count = pity(p, tier);
        boolean forced = count + 1 >= c.pity;
        Reward r = forced ? pickPity(c) : pick(c.rewards);
        if (r == null) r = pick(c.rewards);
        setPity(p, tier, "legendary".equals(r.rarity) ? 0 : count + 1);
        save();

        String detail = grant(p, r);
        log(p, tier, r, detail, forced);
        if (cfg.announceRarities.contains(r.rarity)) {
            server.getPlayerList().broadcastSystemMessage(Component.literal("§6§l✦ §r§e" + p.getGameProfile().getName() + " §7pulled " + rarityColor(r.rarity) + "§l" + detail + " §7from a " + c.color + c.name + "§7!"), false);
        }
        if (cfg.discordRarities.contains(r.rarity)) discord("🎰 **" + p.getGameProfile().getName() + "** pulled **" + detail.replaceAll("§.", "") + "** from a " + c.name + "!");

        // The reward is already given; the spin is just for show.
        Anim a = new Anim();
        a.player = p; a.tier = tier; a.result = r; a.detail = detail; a.forced = forced;
        for (int i = 0; i < 8; i++) a.frames.add(pick(c.rewards));
        ANIMS.put(p.getUUID(), a);
    }

    static Reward pick(List<Reward> pool) {
        double tot = 0; for (Reward r : pool) tot += r.weight;
        double x = RNG.nextDouble() * tot;
        for (Reward r : pool) { x -= r.weight; if (x <= 0) return r; }
        return pool.get(pool.size() - 1);
    }

    static Reward pickPity(Crate c) {
        List<Reward> pool = new ArrayList<>();
        for (Reward r : c.rewards) if ("legendary".equals(r.rarity) && !r.jackpot) pool.add(r);
        return pool.isEmpty() ? null : pick(pool);
    }

    /** Gives the reward and returns a short description of what was given. */
    static String grant(ServerPlayer p, Reward r) {
        switch (r.type) {
            case "money" -> { Economy.give(p, r.money); return Fmt.cd(r.money); }
            case "key" -> { ItemStack k = new ItemStack(Keys.item(r.key), r.count); giveStack(p, k.copy()); return (r.count > 1 ? r.count + "x " : "") + k.getHoverName().getString(); }
            case "title" -> {
                if (Titles.has(p, r.title)) { Economy.give(p, r.money); return r.name + " (already owned, refunded " + Fmt.cd(r.money) + ")"; }
                Titles.grant(p, r.title);
                return r.name;
            }
            case "command" -> {
                for (String cmd : r.commands) server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), cmd.replace("{player}", p.getGameProfile().getName()));
                return r.name;
            }
            case "pokemon" -> {
                Object pk = randomPokemon(r.level, r.legendary);
                if (pk == null) { Economy.give(p, 50000); return r.name + " (refunded: 50,000)"; }
                if (r.shiny) try { call(pk, "setShiny", true); } catch (Throwable ignored) {}
                String name = CobblemonBridge.snapshot(pk, server.registryAccess()).title;
                if (!CobblemonBridge.give(p, pk)) try {
                    ClaimEntry ce = new ClaimEntry();
                    ce.type = ClaimEntry.Type.POKEMON;
                    ce.pokemonSnbt = CobblemonBridge.toNbt(pk, server.registryAccess()).toString();
                    ce.itemSnbt = Market.encode(CobblemonBridge.snapshot(pk, server.registryAccess()).icon);
                    ce.title = name; ce.reason = "Crate reward";
                    Market.data.claimsOf(p.getUUID()).add(ce); Market.markDirty();
                    p.sendSystemMessage(Component.literal("§eYour party and PC are full, so it's waiting in /gts claims."));
                } catch (Exception ex) {
                    GtsMod.LOG.error("[JCL] Could not store a crate Pokémon in claims", ex);
                    Economy.give(p, 50000);
                    p.sendSystemMessage(Component.literal("§cYour Pokémon couldn't be delivered, so you were refunded 50,000 CobbleDollars. Tell an admin."));
                }
                return name.replaceAll("§.", "");
            }
            default -> {
                if (r.items.isEmpty()) return r.name;
                Item it = resolve(r.items.get(RNG.nextInt(r.items.size())));
                ItemStack s = new ItemStack(it, r.count);
                String nm = (r.count > 1 ? r.count + "x " : "") + s.getHoverName().getString();
                giveStack(p, s);
                return nm;
            }
        }
    }

    private static final Set<String> SPECIAL = Set.of("legendary", "mythical", "ultra_beast", "paradox");

    static Object randomPokemon(int level, boolean legendary) {
        try {
            Object inst = Class.forName("com.cobblemon.mod.common.api.pokemon.PokemonSpecies").getField("INSTANCE").get(null);
            List<Object> pool = new ArrayList<>();
            for (Object sp : (Iterable<?>) call(inst, "getImplemented")) {
                boolean special = false, legend = false;
                Object labels = call(sp, "getLabels");
                if (labels instanceof Iterable<?> it) for (Object l : it) {
                    String s = String.valueOf(l);
                    if (SPECIAL.contains(s)) special = true;
                    if (s.equals("legendary")) legend = true;
                }
                if (legendary ? legend : !special) pool.add(sp);
            }
            if (pool.isEmpty()) return null;
            return call(pool.get(RNG.nextInt(pool.size())), "create", level);
        } catch (Throwable t) { GtsMod.LOG.error("[JCL] Could not create a crate Pokémon", t); return null; }
    }

    static Object call(Object target, String name, Object... args) throws Exception {
        for (Method m : target.getClass().getMethods()) {
            if (!m.getName().equals(name) || m.getParameterCount() != args.length) continue;
            try { return m.invoke(target, args); } catch (IllegalArgumentException ignored) {}
        }
        throw new NoSuchMethodException(name);
    }

    private static void log(ServerPlayer p, String tier, Reward r, String detail, boolean forced) {
        try {
            Files.writeString(logFile(), LocalDateTime.now().withNano(0) + " " + p.getGameProfile().getName() + " " + tier + " -> " + r.rarity + " " + detail.replaceAll("§.", "") + (forced ? " (pity)" : "") + "\n",
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception ignored) {}
    }

    private static void discord(String text) {
        String url = GtsConfig.INSTANCE.webhookUrl;
        if (url == null || url.isBlank()) return;
        try {
            JsonObject body = new JsonObject();
            body.addProperty("username", GtsConfig.INSTANCE.webhookName);
            body.addProperty("content", text);
            HttpClient.newHttpClient().sendAsync(HttpRequest.newBuilder(URI.create(url)).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(), HttpResponse.BodyHandlers.discarding());
        } catch (Throwable ignored) {}
    }

    // ================================================================== the roulette

    static final class Anim {
        ServerPlayer player; String tier, detail; Reward result; boolean forced;
        List<Reward> frames = new ArrayList<>();
        int tick, frame;
    }

    static final Map<UUID, Anim> ANIMS = new HashMap<>();
    private static final int[] SCHEDULE = {0, 2, 4, 6, 8, 11, 14, 18, 23, 29, 36, 44};
    private static final Item[] RAINBOW = {Items.RED_STAINED_GLASS_PANE, Items.ORANGE_STAINED_GLASS_PANE, Items.YELLOW_STAINED_GLASS_PANE, Items.LIME_STAINED_GLASS_PANE,
            Items.LIGHT_BLUE_STAINED_GLASS_PANE, Items.BLUE_STAINED_GLASS_PANE, Items.PURPLE_STAINED_GLASS_PANE, Items.MAGENTA_STAINED_GLASS_PANE};

    @SubscribeEvent
    public static void onTick(ServerTickEvent.Post e) {
        if (ANIMS.isEmpty()) return;
        Iterator<Map.Entry<UUID, Anim>> it = ANIMS.entrySet().iterator();
        while (it.hasNext()) {
            Anim a = it.next().getValue();
            if (a.player.hasDisconnected()) { it.remove(); continue; }
            Crate c = cfg.crates.get(a.tier);
            if (a.frame < SCHEDULE.length && a.tick == SCHEDULE[a.frame]) {
                Gui.Screen s = new Gui.Screen(c.color + "§l" + c.name, 5);
                s.fill(Items.BLACK_STAINED_GLASS_PANE);
                int[] ring = {12, 13, 14, 23, 32, 31, 30, 21};
                for (int i = 0; i < ring.length; i++) s.set(ring[i], Gui.pane(RAINBOW[(i + a.frame) % RAINBOW.length]));
                Reward f = a.frames.get(a.frame % a.frames.size());
                s.set(22, Gui.named(iconFor(f), rarityColor(f.rarity) + f.name));
                s.set(4, Gui.icon(Keys.item(a.tier), "§e§lRolling..."));
                Gui.open(a.player, s);
                sound(a.player, SoundEvents.NOTE_BLOCK_HAT.value(), 0.8f + a.frame * 0.08f);
                a.frame++;
            }
            if (a.frame >= SCHEDULE.length && a.tick >= SCHEDULE[SCHEDULE.length - 1] + 10) { reveal(a); it.remove(); continue; }
            a.tick++;
        }
    }

    private static void reveal(Anim a) {
        Crate c = cfg.crates.get(a.tier);
        Reward r = a.result;
        Gui.Screen s = new Gui.Screen(c.color + "§l" + c.name, 5);
        Item frame = switch (r.rarity) { case "legendary" -> Items.YELLOW_STAINED_GLASS_PANE; case "epic" -> Items.PURPLE_STAINED_GLASS_PANE; case "rare" -> Items.BLUE_STAINED_GLASS_PANE; default -> Items.WHITE_STAINED_GLASS_PANE; };
        s.fill(frame);
        String head = r.jackpot ? "§e§l★★★ JACKPOT! ★★★" : "legendary".equals(r.rarity) ? "§6§l✦ LEGENDARY! ✦" : "epic".equals(r.rarity) ? "§5§lEPIC!" : "rare".equals(r.rarity) ? "§9§lRare!" : "§fYou got...";
        s.set(4, Gui.icon(r.jackpot || "legendary".equals(r.rarity) ? Items.TOTEM_OF_UNDYING : Items.NETHER_STAR, head, a.forced ? "§8Pity kicked in. Your luck turned!" : "§8Good luck next time... or did you need it?"));
        ItemStack prize = Gui.named(iconFor(r), rarityColor(r.rarity) + "§l" + a.detail, rarityColor(r.rarity) + rarityName(r.rarity));
        s.set(22, "legendary".equals(r.rarity) || "epic".equals(r.rarity) ? Gui.glow(prize) : prize);
        int keys = countKeys(a.player, a.tier);
        s.set(39, keys > 0 ? Gui.glow(Gui.icon(Keys.item(a.tier), "§a§lOpen another", "§7You have §f" + keys + "§7 keys left.")) : Gui.icon(Items.GRAY_DYE, "§7No keys left"),
                pl -> { if (countKeys(pl, a.tier) > 0) open(pl, a.tier); });
        s.set(41, Gui.icon(Items.SPYGLASS, "§e§lBack to preview"), pl -> preview(pl, a.tier));
        Gui.open(a.player, s);
        if ("legendary".equals(r.rarity)) { sound(a.player, SoundEvents.TOTEM_USE, 1f); sound(a.player, SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1f); }
        else if ("epic".equals(r.rarity)) sound(a.player, SoundEvents.PLAYER_LEVELUP, 0.8f);
        else sound(a.player, SoundEvents.PLAYER_LEVELUP, 1.4f);
    }

    // ================================================================== merging

    static void mergeMenu(ServerPlayer p) {
        Gui.Screen s = new Gui.Screen("§d§lMerge Keys", 3);
        s.frame(Items.MAGENTA_STAINED_GLASS_PANE, Items.PURPLE_STAINED_GLASS_PANE);
        int[] slots = {11, 13, 15};
        int i = 0;
        for (Map.Entry<String, Merge> e : cfg.merges.entrySet()) {
            if (i >= slots.length) break;
            String to = e.getKey(); Merge m = e.getValue();
            String fromName = new ItemStack(Keys.item(m.from)).getHoverName().getString();
            String toName = new ItemStack(Keys.item(to)).getHoverName().getString();
            String itemName = new ItemStack(resolve(m.item)).getHoverName().getString();
            boolean ok = countKeys(p, m.from) >= m.keys && Economy.balance(p) >= m.money && countItem(p, resolve(m.item)) >= m.itemCount;
            s.set(slots[i++], Gui.glow(Gui.icon(Keys.item(to), "§d§lMake a " + toName, "",
                    "§7Costs:",
                    (countKeys(p, m.from) >= m.keys ? "§a✔ " : "§c✘ ") + "§f" + m.keys + "x " + fromName + " §8(you have " + countKeys(p, m.from) + ")",
                    (Economy.balance(p) >= m.money ? "§a✔ " : "§c✘ ") + "§6" + Fmt.cd(m.money),
                    (countItem(p, resolve(m.item)) >= m.itemCount ? "§a✔ " : "§c✘ ") + "§f" + m.itemCount + "x " + itemName, "",
                    ok ? "§a▶ Click to merge" : "§cYou're missing something.")), pl -> merge(pl, to));
        }
        Gui.open(p, s);
    }

    static int countItem(ServerPlayer p, Item item) {
        int n = 0; for (ItemStack s : p.getInventory().items) if (s.is(item)) n += s.getCount(); return n;
    }

    static void merge(ServerPlayer p, String to) {
        Merge m = cfg.merges.get(to);
        if (m == null) return;
        Item fromKey = Keys.item(m.from), item = resolve(m.item);
        if (countKeys(p, m.from) < m.keys || countItem(p, item) < m.itemCount || Economy.balance(p) < m.money) {
            sound(p, SoundEvents.VILLAGER_NO, 1f); mergeMenu(p); return;
        }
        if (!Economy.take(p, m.money)) { sound(p, SoundEvents.VILLAGER_NO, 1f); return; }
        takeItems(p, fromKey, m.keys);
        takeItems(p, item, m.itemCount);
        giveStack(p, new ItemStack(Keys.item(to)));
        sound(p, SoundEvents.ANVIL_USE, 1.2f);
        p.sendSystemMessage(Component.literal("§d§l[Crates] §r§aMerged into a §f" + new ItemStack(Keys.item(to)).getHoverName().getString() + "§a!"));
        mergeMenu(p);
    }
}
