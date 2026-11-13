package net.joeyscobblemon.gts;

import com.google.gson.*;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * /rules: the server's rules, from config/jcl_rules.json (edit freely; /jcl rules reload applies changes).
 * /rules shows the sections to click; /rules <section> shows one in full.
 */
@EventBusSubscriber(modid = GtsMod.MODID)
public final class Rules {
    private Rules() {}
    record Rule(String title, String text) {}
    record Section(String id, String title, String colour, List<Rule> rules) {}
    static final List<Section> SECTIONS = new ArrayList<>();
    static String intro = "", discord = "https://discord.gg/QgD4WfJ9t6";

    static void load() {
        SECTIONS.clear();
        try {
            JsonObject o = JsonParser.parseString(Defaults.load("jcl_rules.json")).getAsJsonObject();
            if (o.has("intro")) intro = o.get("intro").getAsString();
            if (o.has("discord")) discord = o.get("discord").getAsString();
            for (JsonElement e : o.getAsJsonArray("sections")) {
                JsonObject s = e.getAsJsonObject(); List<Rule> rs = new ArrayList<>();
                for (JsonElement r : s.getAsJsonArray("rules")) rs.add(new Rule(r.getAsJsonObject().get("title").getAsString(), r.getAsJsonObject().get("text").getAsString()));
                SECTIONS.add(new Section(s.get("id").getAsString(), s.get("title").getAsString(), s.has("colour") ? s.get("colour").getAsString() : "&f", rs));
            }
        } catch (Exception ex) { GtsMod.LOG.error("[JCL] Couldn't read the rules", ex); }
    }
    static String c(String s) { return s.replace('&', '§'); }
    static final String LINE = "§8§m                                                  ";

    static void index(CommandSourceStack src) {
        if (SECTIONS.isEmpty()) load();
        src.sendSuccess(() -> Component.literal(LINE), false);
        src.sendSuccess(() -> Component.literal("  §c§lJoey's Cobblemon League §f§l· Rules"), false);
        src.sendSuccess(() -> Component.literal("§7" + intro), false);
        src.sendSuccess(() -> Component.literal(""), false);
        for (Section s : SECTIONS) {
            MutableComponent row = Component.literal("  " + c(s.colour()) + "▸ §f" + s.title() + " §8(" + s.rules().size() + ")").withStyle(st -> st
                    .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/rules " + s.id()))
                    .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("§eClick to read the " + s.title()))));
            src.sendSuccess(() -> row, false);
        }
        src.sendSuccess(() -> Component.literal(""), false);
        MutableComponent dc = Component.literal("  §7Also on Discord: §9§n" + discord.replace("https://", "")).withStyle(st -> st.withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, discord)));
        src.sendSuccess(() -> dc, false);
        src.sendSuccess(() -> Component.literal(LINE), false);
    }

    static int section(CommandSourceStack src, String id) {
        if (SECTIONS.isEmpty()) load();
        for (Section s : SECTIONS) {
            if (!s.id().equalsIgnoreCase(id)) continue;
            src.sendSuccess(() -> Component.literal(LINE), false);
            src.sendSuccess(() -> Component.literal("  " + c(s.colour()) + "§l" + s.title()), false);
            int n = 1;
            for (Rule r : s.rules()) { int k = n++; src.sendSuccess(() -> Component.literal("§8" + k + ". " + c(s.colour()) + "§l" + r.title() + " §7" + r.text()), false); }
            MutableComponent back = Component.literal("  §8« §7All rules").withStyle(st -> st.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/rules")));
            src.sendSuccess(() -> back, false);
            src.sendSuccess(() -> Component.literal(LINE), false);
            return 1;
        }
        src.sendFailure(Component.literal("No rules section called " + id + ". Type /rules to see them all."));
        return 0;
    }

    @SubscribeEvent
    public static void onCommands(RegisterCommandsEvent e) {
        load();
        e.getDispatcher().register(Commands.literal("rules").executes(c -> { index(c.getSource()); return 1; })
                .then(Commands.argument("section", StringArgumentType.word()).suggests((c, b) -> SharedSuggestionProvider.suggest(SECTIONS.stream().map(Section::id).toList(), b))
                        .executes(c -> section(c.getSource(), StringArgumentType.getString(c, "section")))));
        e.getDispatcher().register(Commands.literal("jcl").requires(s -> s.hasPermission(2)).then(Commands.literal("rules").then(Commands.literal("reload").executes(c -> {
            load(); c.getSource().sendSuccess(() -> Component.literal("§aRules reloaded (" + SECTIONS.size() + " sections)."), true); return 1; }))));
    }
}
