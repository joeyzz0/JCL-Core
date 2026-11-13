package net.joeyscobblemon.gts;

import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/** Staff commands for testing daily resets without waiting a day. */
@EventBusSubscriber(modid = GtsMod.MODID)
public final class DailyDebug {
    private DailyDebug() {}
    @SubscribeEvent
    public static void onCommands(RegisterCommandsEvent e) {
        e.getDispatcher().register(Commands.literal("jcl").requires(s -> s.hasPermission(2)).then(Commands.literal("daily")
                .then(Commands.literal("reset").then(Commands.argument("player", EntityArgument.player()).executes(c -> {
                    ServerPlayer p = EntityArgument.getPlayer(c, "player");
                    int quests = QuestBook.BOOK == null ? 0 : QuestBook.expireRepeats(p);
                    FrontierQuests.forceDailies(p);
                    c.getSource().sendSuccess(() -> Component.literal("§aDaily reset for " + p.getGameProfile().getName() + ": " + quests + " repeatable quests and the Frontier dailies start over."), true);
                    return 1; })))
                .then(Commands.literal("newday").executes(c -> {
                    FrontierData.data.dailyReroll++; FrontierData.data.dailyChallengeBest.clear(); FrontierData.save();
                    for (ServerPlayer p : c.getSource().getServer().getPlayerList().getPlayers()) FrontierQuests.forceDailies(p);
                    c.getSource().sendSuccess(() -> Component.literal("§aIt's a new day: the featured type and daily challenge changed, and everyone's Frontier dailies reset. Today's type: §f" + Frontier.cap(FrontierQuests.featuredType())), true);
                    return 1; }))));
    }
}
