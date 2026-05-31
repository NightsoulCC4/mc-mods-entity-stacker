package com.example.entitystacker;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.permissions.Permissions;

/**
 * {@code /entitystacker} (alias {@code /estack}) — admin command for the per-mob stacking toggles.
 *
 * <ul>
 *   <li>{@code /entitystacker} or {@code /entitystacker list} — print the current ON/OFF state of every
 *       managed mob.</li>
 *   <li>{@code /entitystacker set <mob> <true|false>} — turn one mob's stacking on or off. Works from
 *       chat, the server console, or a command block.</li>
 * </ul>
 *
 * <p>Gated to command level 2 (ops) via the 26.x permission system: {@code source.permissions()
 * .hasPermission(Permissions.COMMANDS_GAMEMASTER)} — the replacement for the old
 * {@code source.hasPermission(2)} that no longer exists in 26.x.</p>
 */
public final class StackCommands {

    private StackCommands() {}

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, buildContext, selection) -> registerTree(dispatcher));
    }

    private static void registerTree(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("entitystacker")
                .requires(StackCommands::isOp)
                .executes(StackCommands::list);                 // bare command shows the current state

        root.then(Commands.literal("list").executes(StackCommands::list));

        LiteralArgumentBuilder<CommandSourceStack> set = Commands.literal("set");
        for (StackConfig.ManagedMob mob : StackConfig.MANAGED) {
            set.then(Commands.literal(mob.label())
                    .then(Commands.argument("enabled", BoolArgumentType.bool())
                            .executes(ctx -> setMob(ctx, mob))));
        }
        root.then(set);

        LiteralCommandNode<CommandSourceStack> node = dispatcher.register(root);
        // A Brigadier redirect only forwards to the target's CHILDREN, not its own executes — so the alias
        // needs its own executes for the bare "/estack" case; the redirect still routes "/estack list" and
        // "/estack set ..." to the real subcommands.
        dispatcher.register(Commands.literal("estack")
                .requires(StackCommands::isOp)
                .executes(StackCommands::list)
                .redirect(node));
    }

    /** Op gate (command level 2). 26.x replaced {@code hasPermission(int)} with the PermissionSet API. */
    private static boolean isOp(CommandSourceStack source) {
        return source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
    }

    private static int list(CommandContext<CommandSourceStack> ctx) {
        ctx.getSource().sendSuccess(() -> {
            MutableComponent msg = Component.literal("Stackable mobs:").withStyle(ChatFormatting.GOLD);
            for (StackConfig.ManagedMob mob : StackConfig.MANAGED) {
                boolean on = StackConfig.isMobStackingEnabled(mob.type());
                msg.append(Component.literal("\n  - "))
                        .append(mob.type().getDescription())
                        .append(Component.literal(on ? ": ON" : ": OFF")
                                .withStyle(on ? ChatFormatting.GREEN : ChatFormatting.RED));
            }
            return msg;
        }, false);
        return 1;
    }

    private static int setMob(CommandContext<CommandSourceStack> ctx, StackConfig.ManagedMob mob) {
        boolean value = BoolArgumentType.getBool(ctx, "enabled");
        StackConfig.setMobStacking(mob.type(), value);
        ctx.getSource().sendSuccess(() -> Component.empty()
                .append(mob.type().getDescription())
                .append(Component.literal(value ? " stacking enabled." : " stacking disabled.")
                        .withStyle(value ? ChatFormatting.GREEN : ChatFormatting.RED)), true);
        return 1;
    }
}
