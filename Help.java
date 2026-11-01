package net.joeyscobblemon.gts;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.Gson;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;

import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

/**
 * /help with categories and pages, written for players (not admins).
 * /help shows the categories; /help <category> [page] shows its commands.
 * Everything is in config/jcl_help.json, so new commands can be added without a rebuild.
 */
@EventBusSubscriber(modid = "jcl_gts")
public final class Help {
    private Help() {}

    public static final class Category { public String id, name, icon; public List<List<String>> commands = new ArrayList<>(); }
    public static final class Config { public int version; public List<Category> categories = new ArrayList<>(); }

    static Config cfg = new Config();
    private static final int PER_PAGE = 6;

    static void load() {
        try { String json = Defaults.load("jcl_help.json"); Config c = json == null ? null : new Gson().fromJson(json, Config.class); if (c != null) cfg = c; }
        catch (Exception e) { GtsMod.LOG.error("[JCL] Could not read config/jcl_help.json", e); }
    }

    @SubscribeEvent public static void onStart(ServerStartedEvent e) { load(); }

    @SubscribeEvent
    public static void onCommands(RegisterCommandsEvent e) {
        e.getDispatcher().register(Commands.literal("help")
                .executes(c -> { index(c.getSource().getPlayerOrException()); return 1; })
                .then(Commands.argument("category", StringArgumentType.word())
                        .suggests((c, b) -> SharedSuggestionProvider.suggest(cfg.categories.stream().map(x -> x.id), b))
                        .executes(c -> { page(c.getSource().getPlayerOrException(), StringArgumentType.getString(c, "category"), 1); return 1; })
                        .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                .executes(c -> { page(c.getSource().getPlayerOrException(), StringArgumentType.getString(c, "category"), IntegerArgumentType.getInteger(c, "page")); return 1; }))));
    }

    static MutableComponent button(String text, String cmd, String hover) {
        return Component.literal(text).withStyle(st -> st.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, cmd))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(hover))));
    }

    static void index(ServerPlayer p) {
        p.sendSystemMessage(Component.literal(" "));
        p.sendSystemMessage(Component.literal("§6§l✦ Joey's Cobblemon League §7· Help"));
        p.sendSystemMessage(Component.literal("§7Click a category:"));
        for (Category c : cfg.categories) {
            p.sendSystemMessage(Component.literal(" §8» ").append(button("§e" + c.name, "/help " + c.id, "§7Show " + c.name + " commands")).append(Component.literal(" §8(" + c.commands.size() + ")")));
        }
        p.sendSystemMessage(Component.literal("§8Still stuck? Ask in chat or on our Discord."));
    }

    static void page(ServerPlayer p, String id, int page) {
        Category cat = null;
        for (Category c : cfg.categories) if (c.id.equalsIgnoreCase(id)) cat = c;
        if (cat == null) { index(p); return; }
        int pages = Math.max(1, (cat.commands.size() + PER_PAGE - 1) / PER_PAGE);
        int pg = Math.max(1, Math.min(page, pages));
        p.sendSystemMessage(Component.literal(" "));
        p.sendSystemMessage(Component.literal("§6§l✦ " + cat.name + " §7· page " + pg + "/" + pages));
        for (int i = (pg - 1) * PER_PAGE; i < Math.min(cat.commands.size(), pg * PER_PAGE); i++) {
            List<String> row = cat.commands.get(i);
            String cmd = row.get(0), desc = row.size() > 1 ? row.get(1) : "";
            MutableComponent name = cmd.startsWith("/")
                    ? Component.literal("§f" + cmd).withStyle(st -> st.withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, cmd.replaceAll(" <.*", " ")))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("§7Click to type it"))))
                    : Component.literal("§f" + cmd);
            p.sendSystemMessage(Component.literal(" §8• ").append(name).append(Component.literal(" §7" + desc)));
        }
        MutableComponent nav = Component.literal(" ");
        if (pg > 1) nav.append(button("§e« Prev ", "/help " + cat.id + " " + (pg - 1), "§7Previous page"));
        nav.append(button("§7[Back to categories]", "/help", "§7All categories"));
        if (pg < pages) nav.append(button(" §eNext »", "/help " + cat.id + " " + (pg + 1), "§7Next page"));
        p.sendSystemMessage(nav);
    }
}
