package net.joeyscobblemon.gts;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * The quest book's network messages. The server sends the (packed) book once per session and the player's progress
 * whenever it changes; the client sends back what the player clicked. The server checks every action itself.
 */
public final class QuestNet {
    private QuestNet() {}

    /** Server -> client: the whole book (gzip bytes of the JSON) and a hash so it's only sent when it changes. */
    public record Book(String hash, byte[] data) implements CustomPacketPayload {
        public static final Type<Book> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(GtsMod.MODID, "quest_book"));
        public static final StreamCodec<ByteBuf, Book> CODEC = StreamCodec.composite(ByteBufCodecs.STRING_UTF8, Book::hash, ByteBufCodecs.byteArray(4 << 20), Book::data, Book::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    /** Server -> client: the player's progress (JSON) and whether to open the screen now. */
    public record Prog(String json, boolean open) implements CustomPacketPayload {
        public static final Type<Prog> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(GtsMod.MODID, "quest_progress"));
        public static final StreamCodec<ByteBuf, Prog> CODEC = StreamCodec.composite(ByteBufCodecs.stringUtf8(1 << 21), Prog::json, ByteBufCodecs.BOOL, Prog::open, Prog::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    /** Client -> server: something the player did ("open", "checkmark", "submit", "claim", "claimall", "choice"). */
    public record Act(String action, String id, int index) implements CustomPacketPayload {
        public static final Type<Act> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(GtsMod.MODID, "quest_action"));
        public static final StreamCodec<ByteBuf, Act> CODEC = StreamCodec.composite(ByteBufCodecs.STRING_UTF8, Act::action, ByteBufCodecs.STRING_UTF8, Act::id, ByteBufCodecs.VAR_INT, Act::index, Act::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** What the client last received (plain data: safe to reference from server code). */
    public static volatile byte[] CLIENT_BOOK; public static volatile String CLIENT_HASH = ""; public static volatile String CLIENT_PROGRESS = "{}";
    public static volatile Runnable CLIENT_OPEN; public static volatile Runnable CLIENT_REFRESH;

    @EventBusSubscriber(modid = GtsMod.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Reg {
        @SubscribeEvent
        public static void register(RegisterPayloadHandlersEvent e) {
            var r = e.registrar("1").optional();
            r.playToClient(Book.TYPE, Book.CODEC, (p, ctx) -> ctx.enqueueWork(() -> { CLIENT_BOOK = p.data(); CLIENT_HASH = p.hash(); }));
            r.playToClient(Prog.TYPE, Prog.CODEC, (p, ctx) -> ctx.enqueueWork(() -> {
                CLIENT_PROGRESS = p.json();
                if (p.open() && CLIENT_OPEN != null) CLIENT_OPEN.run(); else if (CLIENT_REFRESH != null) CLIENT_REFRESH.run();
            }));
            r.playToServer(Act.TYPE, Act.CODEC, (p, ctx) -> ctx.enqueueWork(() -> { if (ctx.player() instanceof ServerPlayer sp) act(sp, p); }));
        }
    }

    // ------------------------------------------------------------------ server side

    static byte[] PACKED; static String HASH = "";
    static final Set<UUID> HAS_BOOK = new HashSet<>();
    static final Set<UUID> OPEN = new HashSet<>();

    static void pack() {
        try {
            var bo = new java.io.ByteArrayOutputStream();
            try (var gz = new java.util.zip.GZIPOutputStream(bo)) { gz.write(QuestBook.BOOK.toString().getBytes(StandardCharsets.UTF_8)); }
            PACKED = bo.toByteArray();
            HASH = Integer.toHexString(Arrays.hashCode(PACKED));
        } catch (Exception e) { GtsMod.LOG.error("[JCL] Quests: couldn't pack the book", e); }
    }

    static boolean hasScreen(ServerPlayer p) { return p.connection.hasChannel(Prog.TYPE); }

    /** Opens the quest book for a player (sending the book first if they don't have this version). */
    static void open(ServerPlayer p) {
        if (QuestBook.BOOK == null) { p.sendSystemMessage(net.minecraft.network.chat.Component.literal("§cThe quest book isn't loaded.")); return; }
        if (!hasScreen(p)) { p.sendSystemMessage(net.minecraft.network.chat.Component.literal("§cYour game needs JCL Core to open the quest book.")); return; }
        if (PACKED == null) pack();
        if (!HAS_BOOK.contains(p.getUUID())) { p.connection.send(new Book(HASH, PACKED)); HAS_BOOK.add(p.getUUID()); }
        QuestBook.refreshRepeats(p);
        OPEN.add(p.getUUID());
        p.connection.send(new Prog(progressJson(p), true));
    }

    /** Called whenever a player's progress changes: refresh their screen if it's open. */
    static void dirty(ServerPlayer p) {
        if (OPEN.contains(p.getUUID()) && hasScreen(p)) p.connection.send(new Prog(progressJson(p), false));
    }

    static String progressJson(ServerPlayer p) {
        QuestBook.Progress pr = QuestBook.progress(p.getUUID());
        JsonObject o = new JsonObject();
        JsonObject t = new JsonObject(); pr.tasks.forEach((k, v) -> t.addProperty(k, v)); o.add("tasks", t);
        JsonArray d = new JsonArray(); pr.done.keySet().forEach(d::add); o.add("done", d);
        JsonArray c = new JsonArray(); pr.claimed.keySet().forEach(c::add); o.add("claimed", c);
        JsonObject rp = new JsonObject();
        pr.repeats.forEach((k, v) -> { var q = QuestBook.QUESTS.get(k); if (q != null) rp.addProperty(k, v + q.get("cooldown").getAsLong() * 1000L); });
        o.add("repeats", rp);   // repeatable quest -> when it resets
        // the Jobs tab: every job, where the player is in it, and what to do next
        JsonArray jobs = new JsonArray();
        try {
            var prog = Jobs.data.progress.getOrDefault(p.getUUID().toString(), java.util.Map.of());
            for (Jobs.Job j : Jobs.cfg.jobs) {
                JsonObject jo = new JsonObject();
                jo.addProperty("name", j.name == null ? j.id : j.name);
                jo.addProperty("desc", j.description == null ? "" : j.description);
                int at = prog.getOrDefault(j.id, -1), n = j.steps.size();
                boolean locked = j.requires != null && !j.requires.isEmpty() && !Jobs.done(p, j.requires);
                jo.addProperty("state", locked ? "locked" : at < 0 ? "new" : at >= n ? "done" : "active");
                jo.addProperty("step", Math.max(0, Math.min(at, n))); jo.addProperty("steps", n);
                if (locked) { Jobs.Job req = null; for (Jobs.Job r : Jobs.cfg.jobs) if (r.id.equals(j.requires)) req = r; jo.addProperty("requires", req == null ? j.requires : req.name); }
                if (at >= 0 && at < n) { Jobs.Step st = j.steps.get(at); jo.addProperty("task", st.task == null ? "" : st.task); jo.addProperty("where", st.where == null ? "" : st.where); }
                jobs.add(jo);
            }
        } catch (Throwable jx) { GtsMod.LOG.debug("[JCL] Quests: jobs tab skipped: {}", jx.toString()); }
        o.add("jobs", jobs);
        // the Stats tab
        JsonArray stats = new JsonArray();
        java.util.function.BiConsumer<String, String> stat = (k, v) -> { JsonArray a = new JsonArray(); a.add(k); a.add(v); stats.add(a); };
        try {
            int questsDone = (int) pr.done.keySet().stream().filter(QuestBook.QUESTS::containsKey).count();
            stat.accept("Quests complete", questsDone + " / " + QuestBook.QUESTS.size());
            int jd = 0; for (var je : jobs) if (je.getAsJsonObject().get("state").getAsString().equals("done")) jd++;
            stat.accept("Jobs complete", jd + " / " + jobs.size());
            stat.accept("Pokédex caught", String.valueOf(Sidebar.dexCaught(p)));
            stat.accept("Wallet", Fmt.cd(Economy.balance(p)));
            stat.accept("Battle Points", FrontierData.bp(p.getUUID().toString()) + " BP");
            stat.accept("Playtime", Sidebar.time(p.getStats().getValue(net.minecraft.stats.Stats.CUSTOM.get(net.minecraft.stats.Stats.PLAY_TIME))));
            int pk = 0; for (Object m : Cob.party(p)) if (m != null && Pokerus.state(m) > 0) pk++;
            stat.accept("Pokérus in your party", pk == 0 ? "None" : pk + " Pokémon");
            int waiting = 0; for (String q : pr.done.keySet()) { var qq = QuestBook.QUESTS.get(q); if (qq != null) for (var r : qq.getAsJsonArray("rewards")) if (!pr.claimed.containsKey(r.getAsJsonObject().get("id").getAsString())) { waiting++; break; } }
            stat.accept("Quests with rewards waiting", String.valueOf(waiting));
        } catch (Throwable sx) { GtsMod.LOG.debug("[JCL] Quests: stats tab: {}", sx.toString()); }
        o.add("stats", stats);
        return o.toString();
    }

    static void act(ServerPlayer p, Act a) {
        switch (a.action()) {
            case "open" -> open(p);
            case "close" -> OPEN.remove(p.getUUID());
            case "checkmark" -> QuestBook.checkmark(p, a.id());
            case "submit" -> QuestBook.submit(p, a.id());
            case "claim" -> QuestBook.claim(p, a.id());
            case "claimall" -> QuestBook.claimAll(p, a.id());
            case "choice" -> QuestBook.claimChoice(p, a.id(), a.index());
            case "claimeverything" -> {             // every waiting reward in the whole book (choice rewards still need a pick)
                var pr = QuestBook.progress(p.getUUID());
                for (String q : new ArrayList<>(pr.done.keySet())) QuestBook.claimAll(p, q);
            }
            default -> {}
        }
        dirty(p);
    }

    @EventBusSubscriber(modid = GtsMod.MODID)
    public static final class Life {
        @SubscribeEvent public static void out(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent e) { HAS_BOOK.remove(e.getEntity().getUUID()); OPEN.remove(e.getEntity().getUUID()); }
    }
}
