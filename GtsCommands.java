package net.joeyscobblemon.gts;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;

public final class GtsCommands {
    private GtsCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> d) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("gts").executes(ctx -> open(ctx));

        // Buy It Now: held item
        root.then(Commands.literal("sell")
                .then(Commands.argument("price", LongArgumentType.longArg(1))
                        .executes(ctx -> sellItem(ctx, GtsConfig.INSTANCE.defaultBuyNowHours))
                        .then(Commands.argument("hours", IntegerArgumentType.integer(1))
                                .executes(ctx -> sellItem(ctx, IntegerArgumentType.getInteger(ctx, "hours"))))));

        // Buy It Now: Pokemon in a party slot
        root.then(Commands.literal("sellpokemon")
                .then(Commands.argument("slot", IntegerArgumentType.integer(1, 6))
                        .then(Commands.argument("price", LongArgumentType.longArg(1))
                                .executes(ctx -> sellPokemon(ctx, GtsConfig.INSTANCE.defaultBuyNowHours))
                                .then(Commands.argument("hours", IntegerArgumentType.integer(1))
                                        .executes(ctx -> sellPokemon(ctx, IntegerArgumentType.getInteger(ctx, "hours")))))));

        // Auction: held item
        root.then(Commands.literal("auction")
                .then(Commands.argument("start", LongArgumentType.longArg(1))
                        .then(Commands.argument("hours", IntegerArgumentType.integer(1))
                                .executes(ctx -> auctionItem(ctx)))));

        // Auction: Pokemon
        root.then(Commands.literal("auctionpokemon")
                .then(Commands.argument("slot", IntegerArgumentType.integer(1, 6))
                        .then(Commands.argument("start", LongArgumentType.longArg(1))
                                .then(Commands.argument("hours", IntegerArgumentType.integer(1))
                                        .executes(ctx -> auctionPokemon(ctx))))));

        root.then(Commands.literal("mine").executes(ctx -> { Gui.mine(player(ctx)); return 1; }));
        root.then(Commands.literal("claims").executes(ctx -> { Gui.claims(player(ctx)); return 1; }));
        root.then(Commands.literal("claim").executes(ctx -> {
            ServerPlayer p = player(ctx);
            int n = Market.claimAll(p);
            p.sendSystemMessage(Fmt.chat("[GTS] " + (n == 0 ? "Nothing to collect." : "Collected " + n + (n == 1 ? " entry." : " entries.")), n == 0 ? ChatFormatting.GRAY : ChatFormatting.GREEN));
            return 1;
        }));
        root.then(Commands.literal("help").executes(ctx -> { help(player(ctx)); return 1; }));

        // Admin
        root.then(Commands.literal("admin").requires(s -> s.hasPermission(2))
                .then(Commands.literal("remove").then(Commands.argument("id", StringArgumentType.word()).executes(ctx -> {
                    ServerPlayer p = player(ctx);
                    String id = StringArgumentType.getString(ctx, "id").replace("#", "").toUpperCase();
                    String err = Market.cancel(p, id, true);
                    p.sendSystemMessage(err != null ? Fmt.chat("[GTS] " + err, ChatFormatting.RED) : Fmt.chat("[GTS] Listing #" + id + " removed and returned to its seller's claims.", ChatFormatting.GREEN));
                    return 1;
                })))
                .then(Commands.literal("webhooktest").executes(ctx -> { Webhook.test(); ctx.getSource().sendSuccess(() -> Fmt.chat("Sent a test message to Discord."), false); return 1; }))
                .then(Commands.literal("reload").executes(ctx -> { GtsConfig.load(); ctx.getSource().sendSuccess(() -> Fmt.chat("GTS config reloaded."), false); return 1; }))
                .then(Commands.literal("debug").executes(ctx -> {
                    ServerPlayer p = player(ctx);
                    String name = p.getGameProfile().getName();
                    for (String cmd : new String[]{"cd query " + name, "cobbledollars query " + name}) {
                        Economy.Result r = Economy.runFull(p.server, cmd);
                        p.sendSystemMessage(Fmt.chat("[GTS debug] /" + cmd, ChatFormatting.AQUA));
                        p.sendSystemMessage(Fmt.chat("  text: '" + r.out.trim().replace("\n", " | ") + "'", ChatFormatting.GRAY));
                        p.sendSystemMessage(Fmt.chat("  return value: " + (r.code == Integer.MIN_VALUE ? "none" : r.code) + (r.error.isEmpty() ? "" : "   error: " + r.error), ChatFormatting.GRAY));
                    }
                    return 1;
                }))
                .then(Commands.literal("balance").executes(ctx -> {
                    ServerPlayer p = player(ctx);
                    long b = Economy.balance(p);
                    p.sendSystemMessage(Fmt.chat("[GTS] Read balance: " + b, ChatFormatting.AQUA));
                    return 1;
                })));
        d.register(root);
    }

    private static ServerPlayer player(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        return ctx.getSource().getPlayerOrException();
    }

    private static int open(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer p = player(ctx);
        Gui.view(p).page = 0;
        Gui.browse(p);
        int c = Market.claimCount(p.getUUID());
        if (c > 0) p.sendSystemMessage(Fmt.chat("[GTS] You have " + c + " thing(s) waiting. Use /gts claims.", ChatFormatting.YELLOW));
        return 1;
    }

    private static int finish(ServerPlayer p, String err) {
        if (err != null) { p.sendSystemMessage(Fmt.chat("[GTS] " + err, ChatFormatting.RED)); return 0; }
        return 1;
    }

    private static int sellItem(CommandContext<CommandSourceStack> ctx, int hours) throws CommandSyntaxException {
        ServerPlayer p = player(ctx);
        return finish(p, Market.listHeldItem(p, Listing.Mode.BUY_NOW, LongArgumentType.getLong(ctx, "price"), hours));
    }

    private static int sellPokemon(CommandContext<CommandSourceStack> ctx, int hours) throws CommandSyntaxException {
        ServerPlayer p = player(ctx);
        return finish(p, Market.listPokemon(p, IntegerArgumentType.getInteger(ctx, "slot"), Listing.Mode.BUY_NOW, LongArgumentType.getLong(ctx, "price"), hours));
    }

    private static int auctionItem(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer p = player(ctx);
        return finish(p, Market.listHeldItem(p, Listing.Mode.AUCTION, LongArgumentType.getLong(ctx, "start"), IntegerArgumentType.getInteger(ctx, "hours")));
    }

    private static int auctionPokemon(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer p = player(ctx);
        return finish(p, Market.listPokemon(p, IntegerArgumentType.getInteger(ctx, "slot"), Listing.Mode.AUCTION, LongArgumentType.getLong(ctx, "start"), IntegerArgumentType.getInteger(ctx, "hours")));
    }

    private static void help(ServerPlayer p) {
        p.sendSystemMessage(Fmt.chat("=== Global Trade System ===", ChatFormatting.GOLD));
        String[] l = {
                "/gts  - open the market",
                "/gts sell <price> [hours]  - sell the item in your hand (Buy It Now)",
                "/gts sellpokemon <slot 1-6> <price> [hours]  - sell a Pokémon",
                "/gts auction <start price> <hours>  - auction the item in your hand",
                "/gts auctionpokemon <slot 1-6> <start price> <hours>  - auction a Pokémon",
                "/gts mine  - your listings",
                "/gts claims  - collect items, Pokémon and money waiting for you"
        };
        for (String s : l) p.sendSystemMessage(Fmt.chat(s, ChatFormatting.YELLOW));
    }
}
