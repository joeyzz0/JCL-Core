package net.joeyscobblemon.gts;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.Commands;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;
import java.util.zip.*;

/**
 * The whole-world sweep. Scheduled with /jcl sweep, it runs at the next server start, before any world or player
 * data is loaded, so it can safely edit the save files directly:
 *  - every chunk in every dimension (block entities like chests, and entities like item frames and minecarts),
 *  - every player file (inventories, ender chests, Accessories/Curios slots),
 *  - every saved-data file in world/data (shared mod storage: Refined Storage disks, backpacks, AE2 cells...).
 * It removes the chosen items wherever they're stored (nested containers included) and can strip data components
 * and enchantments belonging to chosen mods (used to make Apotheosis gear safe before removing Apotheosis).
 * A report is written to logs/jcl_sweep_report.txt.
 */
@EventBusSubscriber(modid = GtsMod.MODID)
public final class Sweep {
    private Sweep() {}

    public static final class Job { public List<String> items = new ArrayList<>(); public List<String> strip = new ArrayList<>(); public boolean dryRun = false; }
    static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    static Path jobFile() { return Paths.get("config", "jcl_sweep.json"); }

    // ------------------------------------------------------------------ matching

    static List<String> exact = new ArrayList<>(), mods = new ArrayList<>(), stripNs = new ArrayList<>(), reimburseNs = new ArrayList<>();
    static long removedItems, strippedTags, filesChanged, chunksChanged, reimbursed;

    // ------------------------------------------------------------------ reimbursement (replace an item with materials, in the same slot)

    /** config/jcl_sweep_reimburse.json: which tier each item is, and what each tier pays out. */
    public static final class Reimburse {
        public String note = "Items from a mod listed with @reimburse:<mod> are swapped, in place, for the material of their tier. Unlisted items use the default tier.";
        public String defaultTier = "common";
        public Map<String, String[]> tiers = new LinkedHashMap<>();     // tier -> {item id, count}
        public Map<String, String> items = new LinkedHashMap<>();       // item id -> tier
    }
    static Reimburse RB;
    static PrintWriter REP; static String CUR = "";      // the report, and the file being swept (every change is listed)
    static Path rbFile() { return Paths.get("config", "jcl_sweep_reimburse.json"); }
    static Reimburse loadReimburse() {
        try { if (Files.exists(rbFile())) return GSON.fromJson(Files.readString(rbFile()), Reimburse.class); } catch (Exception ex) { GtsMod.LOG.warn("[JCL] Sweep: bad reimburse file, using defaults: {}", ex.toString()); }
        Reimburse r = new Reimburse();
        r.tiers.put("common", new String[]{"minecraft:echo_shard", "4"});
        r.tiers.put("rare", new String[]{"minecraft:echo_shard", "10"});
        r.tiers.put("legendary", new String[]{"minecraft:echo_shard", "24"});
        String[] legendary = {"artifacts:crystal_heart", "artifacts:cloud_in_a_bottle", "artifacts:power_glove", "artifacts:fire_gauntlet", "artifacts:vampiric_glove",
                "artifacts:feral_claws", "artifacts:flame_pendant", "artifacts:shock_pendant", "artifacts:thorn_pendant", "artifacts:warp_drive", "artifacts:universal_attractor",
                "artifacts:chorus_totem", "relics:magic_mirror", "relics:elytra_booster", "relics:enders_hand", "relics:infinity_ham", "relics:holy_locket", "relics:bastion_ring",
                "relics:spatial_sign", "relics:space_dissector", "relics:shadow_glaive", "relics:wool_mitten", "relics:rage_glove", "relics:midnight_robe", "relics:phantom_boot"};
        String[] rare = {"artifacts:aqua_dashers", "artifacts:bunny_hoppers", "artifacts:running_shoes", "artifacts:golden_hook", "artifacts:lucky_scarf", "artifacts:panic_necklace",
                "artifacts:night_vision_goggles", "artifacts:obsidian_skull", "artifacts:scarf_of_invisibility", "artifacts:steadfast_spikes", "artifacts:strider_shoes",
                "artifacts:snorkel", "artifacts:flippers", "artifacts:digging_claws", "artifacts:pickaxe_heater", "artifacts:rooted_boots", "artifacts:antidote_vessel",
                "artifacts:charm_of_sinking", "artifacts:charm_of_shrinking", "artifacts:helium_flamingo", "artifacts:withered_bracelet", "artifacts:cross_necklace",
                "relics:aqua_walker", "relics:arrow_quiver", "relics:blazing_flask", "relics:chorus_inhibitor", "relics:delay_ring", "relics:drowned_belt", "relics:hunter_belt",
                "relics:ice_breaker", "relics:ice_skates", "relics:jellyfish_necklace", "relics:lucky_horseshoe", "relics:magma_walker", "relics:ghost_skin_talisman", "relics:amphibian_boot"};
        for (String i : legendary) r.items.put(i, "legendary");
        for (String i : rare) r.items.put(i, "rare");
        try { Files.writeString(rbFile(), GSON.toJson(r)); } catch (Exception ignored) {}
        return r;
    }
    static boolean reimburses(String id) {
        if (id == null || reimburseNs.isEmpty()) return false;
        id = id.toLowerCase(Locale.ROOT);
        for (String n : reimburseNs) if (id.startsWith(n)) return true;
        return false;
    }
    /** Turns this stack (in place) into its tier's material. */
    static void reimburse(CompoundTag c) {
        String id = itemId(c).toLowerCase(Locale.ROOT);
        String tier = RB.items.getOrDefault(id, RB.defaultTier);
        String[] pay = RB.tiers.getOrDefault(tier, RB.tiers.get(RB.defaultTier));
        int n = Integer.parseInt(pay[1]);
        // storage mods keep the item one level down (Refined Storage "resource", AE2 "what")
        CompoundTag holder = c.contains("resource", Tag.TAG_COMPOUND) ? c.getCompound("resource") : c.contains("what", Tag.TAG_COMPOUND) ? c.getCompound("what") : c;
        for (String k : new String[]{"id", "item", "Item"}) if (holder.contains(k, Tag.TAG_STRING)) holder.putString(k, pay[0]);
        holder.remove("components"); holder.remove("tag"); c.remove("components"); c.remove("tag");
        if (c.contains("amount")) c.putLong("amount", n);
        else if (c.contains("Amount")) c.putLong("Amount", n);
        else if (c.contains("#")) c.putLong("#", n);
        else if (c.contains("Count")) c.putByte("Count", (byte) n);
        else c.putInt("count", n);
        reimbursed++;
        if (REP != null) REP.println("  reimbursed " + id + " (" + tier + ") -> " + n + " x " + pay[0] + "   in " + CUR);
    }

    static boolean matches(String id) {
        if (id == null) return false;
        id = id.toLowerCase(Locale.ROOT);
        if (exact.contains(id)) return true;
        for (String m : mods) if (id.startsWith(m)) return true;
        return false;
    }

    static String itemId(CompoundTag c) {
        for (String k : new String[]{"id", "item", "Item"}) if (c.contains(k, Tag.TAG_STRING)) return c.getString(k);
        if (c.contains("resource", Tag.TAG_COMPOUND)) return itemId(c.getCompound("resource"));     // Refined Storage
        if (c.contains("what", Tag.TAG_COMPOUND)) return itemId(c.getCompound("what"));             // AE2 keys
        return null;
    }

    static boolean isStack(CompoundTag c) {
        // never a block entity (has x/y/z) or an entity (has Pos/UUID): only the item stacks they hold
        if ((c.contains("x") && c.contains("y") && c.contains("z")) || c.contains("Pos") || c.contains("UUID")) return false;
        return (c.contains("count") || c.contains("Count") || c.contains("amount") || c.contains("Amount") || c.contains("#") || c.contains("components")) && itemId(c) != null;   // "#" = AE2 cells
    }

    /** Walks any NBT, removing matching stacks from lists and stripping chosen mods' data. Returns true if it changed anything. */
    static boolean walk(Tag t) {
        boolean changed = false;
        if (t instanceof CompoundTag c) {
            if (!stripNs.isEmpty()) {
                if (c.contains("components", Tag.TAG_COMPOUND)) {
                    CompoundTag comps = c.getCompound("components");
                    for (String k : new ArrayList<>(comps.getAllKeys())) if (stripped(k)) { comps.remove(k); strippedTags++; changed = true; }
                    for (String ek : new String[]{"minecraft:enchantments", "minecraft:stored_enchantments"}) {
                        if (!comps.contains(ek, Tag.TAG_COMPOUND)) continue;
                        CompoundTag en = comps.getCompound(ek);
                        CompoundTag levels = en.contains("levels", Tag.TAG_COMPOUND) ? en.getCompound("levels") : en;
                        for (String k : new ArrayList<>(levels.getAllKeys())) if (stripped(k)) { levels.remove(k); strippedTags++; changed = true; }
                    }
                }
                for (String k : new ArrayList<>(c.getAllKeys())) if (stripped(k)) { c.remove(k); strippedTags++; changed = true; }    // attachments like "apotheosis:..."
            }
            if (isStack(c) && reimburses(itemId(c))) { reimburse(c); return true; }
            for (String k : new ArrayList<>(c.getAllKeys())) {
                Tag v = c.get(k);
                if (v instanceof CompoundTag vc && isStack(vc) && matches(itemId(vc))) {
                    if (REP != null) REP.println("  removed " + itemId(vc) + "   in " + CUR);
                    c.remove(k); removedItems += Math.max(1, vc.getInt("count") + vc.getInt("Count") + vc.getInt("amount") + vc.getInt("Amount")); changed = true; continue;
                }
                if (walk(v)) changed = true;
            }
        } else if (t instanceof ListTag l) {
            for (int i = l.size() - 1; i >= 0; i--) {
                Tag e = l.get(i);
                if (e instanceof CompoundTag ec && isStack(ec) && matches(itemId(ec))) {
                    if (REP != null) REP.println("  removed " + itemId(ec) + "   in " + CUR);
                    l.remove(i); removedItems += Math.max(1, ec.getInt("count") + ec.getInt("Count") + ec.getInt("amount") + ec.getInt("Amount")); changed = true;
                } else if (e instanceof CompoundTag slot && slot.contains("item", Tag.TAG_COMPOUND) && isStack(slot.getCompound("item")) && matches(itemId(slot.getCompound("item")))) {
                    CompoundTag it = slot.getCompound("item");             // a container slot ({slot, item}): remove the whole entry, never leave it empty
                    l.remove(i); removedItems += Math.max(1, it.getInt("count") + it.getInt("Count")); changed = true;
                } else if (walk(e)) changed = true;
            }
        }
        return changed;
    }

    static boolean stripped(String key) { for (String n : stripNs) if (key.startsWith(n)) return true; return false; }

    // ------------------------------------------------------------------ files

    static void sweepDatFile(Path f, PrintWriter rep) {
        CUR = f.toString();
        try {
            CompoundTag root = NbtIo.readCompressed(f, NbtAccounter.unlimitedHeap());
            if (walk(root)) { NbtIo.writeCompressed(root, f); filesChanged++; rep.println("changed " + f); }
        } catch (Exception e) {
            try { CompoundTag root = NbtIo.read(f); if (root != null && walk(root)) { NbtIo.write(root, f); filesChanged++; rep.println("changed " + f); } }
            catch (Exception e2) { rep.println("skipped " + f + " (" + e2.getMessage() + ")"); }
        }
    }

    /** Reads, sweeps and rewrites every chunk in a region (.mca) file. */
    static void sweepRegion(Path f, PrintWriter rep) throws IOException {
        CUR = f.toString();
        byte[] all = Files.readAllBytes(f);
        if (all.length < 8192) return;
        ByteBuffer hdr = ByteBuffer.wrap(all, 0, 4096);
        List<byte[]> chunks = new ArrayList<>(Collections.nCopies(1024, (byte[]) null));
        Map<Integer, byte[]> raw = new HashMap<>();      // chunks copied through untouched: [type][data]
        boolean any = false;
        for (int i = 0; i < 1024; i++) {
            int loc = hdr.getInt(i * 4);
            int off = (loc >>> 8) * 4096, sectors = loc & 0xFF;
            if (off == 0 || sectors == 0 || off + 5 > all.length) continue;
            int len = ByteBuffer.wrap(all, off, 4).getInt();
            byte type = all[off + 4];
            if (len <= 1 || off + 4 + len > all.length) continue;
            if (type != 2 && type != 1) {          // external (oversized) or other compression: keep exactly as it is
                raw.put(i, Arrays.copyOfRange(all, off + 4, off + 4 + len)); continue;
            }
            InputStream in = new ByteArrayInputStream(all, off + 5, len - 1);
            in = type == 2 ? new InflaterInputStream(in) : new GZIPInputStream(in);
            CompoundTag tag = NbtIo.read(new DataInputStream(new BufferedInputStream(in)), NbtAccounter.unlimitedHeap());
            boolean ch = false;
            for (String k : new String[]{"block_entities", "Entities", "entities"}) if (tag.contains(k) && walk(tag.get(k))) ch = true;
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(new DeflaterOutputStream(bo))) { NbtIo.write(tag, out); }
            chunks.set(i, bo.toByteArray());
            if (ch) { any = true; chunksChanged++; }
        }
        if (!any) return;
        // rewrite the whole region file with a fresh, packed layout
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        ByteBuffer loc = ByteBuffer.allocate(4096);
        int sector = 2;
        for (int i = 0; i < 1024; i++) {
            byte[] c = chunks.get(i), r = raw.get(i);
            if (c == null && r == null) { loc.putInt(i * 4, 0); continue; }
            int len = c != null ? c.length + 1 : r.length, total = len + 4, secs = (total + 4095) / 4096;
            loc.putInt(i * 4, (sector << 8) | secs);
            ByteBuffer cb = ByteBuffer.allocate(secs * 4096); cb.putInt(len);
            if (c != null) { cb.put((byte) 2); cb.put(c); } else cb.put(r);
            body.write(cb.array());
            sector += secs;
        }
        ByteBuffer times = ByteBuffer.wrap(all, 4096, 4096).slice();
        byte[] out = new byte[8192 + body.size()];
        System.arraycopy(loc.array(), 0, out, 0, 4096);
        System.arraycopy(all, 4096, out, 4096, 4096);
        System.arraycopy(body.toByteArray(), 0, out, 8192, body.size());
        Path tmp = f.resolveSibling(f.getFileName() + ".sweeptmp");
        Files.write(tmp, out);
        Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        filesChanged++; rep.println("changed " + f);
    }

    // ------------------------------------------------------------------ the run (before the world loads)

    @SubscribeEvent
    public static void onAboutToStart(ServerAboutToStartEvent e) {
        if (!Files.exists(jobFile())) return;
        Job job;
        try { job = GSON.fromJson(Files.readString(jobFile()), Job.class); } catch (Exception ex) { GtsMod.LOG.error("[JCL] Sweep: bad job file", ex); return; }
        exact.clear(); mods.clear(); stripNs.clear(); reimburseNs.clear(); removedItems = strippedTags = filesChanged = chunksChanged = reimbursed = 0;
        RB = loadReimburse();
        for (String s : job.items) { s = s.trim().toLowerCase(Locale.ROOT);
            if (s.startsWith("@reimburse:")) { reimburseNs.add(s.substring(11).trim() + ":"); continue; }
            if (s.startsWith("@locked:")) { exact.addAll(Purge.expandLocked(s)); continue; }
            if (s.endsWith(":*")) mods.add(s.substring(0, s.length() - 1)); else if (!s.isEmpty()) exact.add(s.contains(":") ? s : "minecraft:" + s); }
        for (String s : job.strip) { s = s.trim().toLowerCase(Locale.ROOT); if (!s.isEmpty()) stripNs.add(s.endsWith(":") ? s : s + ":"); }
        Path world = e.getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).normalize();
        long t0 = System.currentTimeMillis();
        GtsMod.LOG.info("[JCL] Sweep: starting (items {} {}, strip {}). This runs once, before the world loads.", exact, mods, stripNs);
        try (PrintWriter rep = new PrintWriter(Files.newBufferedWriter(Paths.get("logs", "jcl_sweep_report.txt")))) {
            rep.println("JCL sweep: items " + exact + " " + mods + ", strip " + stripNs + ", reimburse " + reimburseNs);
            REP = rep;
            try (Stream<Path> files = Files.walk(world)) {
                for (Path f : (Iterable<Path>) files::iterator) {
                    String n = f.getFileName().toString();
                    try {
                        if (n.endsWith(".mca") && (f.getParent().getFileName().toString().equals("region") || f.getParent().getFileName().toString().equals("entities"))) sweepRegion(f, rep);
                        else if (n.endsWith(".dat") && !n.equals("level.dat") && !n.contains("_old")) sweepDatFile(f, rep);
                    } catch (Exception ex) { rep.println("skipped " + f + " (" + ex + ")"); }
                }
            }
            rep.println("Removed items: " + removedItems + ", reimbursed items: " + reimbursed + ", stripped tags: " + strippedTags + ", chunks changed: " + chunksChanged + ", files changed: " + filesChanged);
        } catch (Exception ex) { GtsMod.LOG.error("[JCL] Sweep failed", ex); REP = null; return; }
        REP = null;
        try { Files.move(jobFile(), jobFile().resolveSibling("jcl_sweep.done.json"), StandardCopyOption.REPLACE_EXISTING); } catch (Exception ignored) {}
        GtsMod.LOG.info("[JCL] Sweep: done in {}s. Removed {} items, reimbursed {}, stripped {} mod tags, changed {} chunks in {} files. Report: logs/jcl_sweep_report.txt",
                (System.currentTimeMillis() - t0) / 1000, removedItems, reimbursed, strippedTags, chunksChanged, filesChanged);
    }

    // ------------------------------------------------------------------ scheduling it

    @SubscribeEvent
    public static void onCommands(RegisterCommandsEvent e) {
        e.getDispatcher().register(Commands.literal("jcl").requires(s -> s.hasPermission(4)).then(Commands.literal("sweep")
                .then(Commands.literal("cancel").executes(c -> {
                    try { Files.deleteIfExists(jobFile()); } catch (Exception ignored) {}
                    c.getSource().sendSuccess(() -> Component.literal("§7The scheduled sweep was cancelled."), true); return 1; }))
                .then(Commands.literal("schedule").then(Commands.argument("items", StringArgumentType.string())
                        .executes(c -> schedule(c.getSource(), StringArgumentType.getString(c, "items"), ""))
                        .then(Commands.literal("strip").then(Commands.argument("mods", StringArgumentType.greedyString())
                                .executes(c -> schedule(c.getSource(), StringArgumentType.getString(c, "items"), StringArgumentType.getString(c, "mods")))))))));
    }

    static int schedule(net.minecraft.commands.CommandSourceStack src, String items, String strip) {
        Job j = new Job();
        for (String s : items.split(",")) if (!s.isBlank() && !s.equals("none")) j.items.add(s.trim());
        for (String s : strip.split("[ ,]+")) if (!s.isBlank()) j.strip.add(s.trim());
        try { Files.writeString(jobFile(), GSON.toJson(j)); } catch (Exception ex) { src.sendFailure(Component.literal("Couldn't write config/jcl_sweep.json")); return 0; }
        src.sendSuccess(() -> Component.literal("§6[JCL] §fSweep scheduled: §eremove " + j.items + (j.strip.isEmpty() ? "" : ", strip " + j.strip)
                + "§f. §7It runs at the next server start, before the world loads. §cTake a full backup first!"), true);
        return 1;
    }
}
