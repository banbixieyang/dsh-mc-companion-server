package com.banbixieyang.dshmcserver;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Server-side companion for speedrunning.
 *
 * Every operation is executed by the server itself with its own world and
 * game-mode objects, so a block really breaks and an item really moves: no
 * client protocol emulation, no ghost blocks. The DSH plugin drives these
 * commands over RCON.
 *
 *   /dshmc dig     <player> <x> <y> <z>
 *   /dshmc place   <player> <x> <y> <z> <block>
 *   /dshmc walk    <player> <x> <y> <z>   (server-side stepping, real movement)
 *   /dshmc tp      <player> <x> <y> <z>
 *   /dshmc look    <player> <x> <y> <z>
 *   /dshmc inv     <player>
 *   /dshmc count   <player> <item>
 *   /dshmc state   <player>
 *   /dshmc players
 */
public class DshMcCompanionServer implements ModInitializer {
    public static final String MOD_ID = "dsh-mc-companion-server";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static MinecraftServer server;

    @Override
    public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTING.register(started -> server = started);
        ServerLifecycleEvents.SERVER_STOPPING.register(stopped -> server = null);

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
            Commands.literal("dshmc")
                .requires(source -> Commands.LEVEL_GAMEMASTERS.check(source.permissions()))
                .then(Commands.literal("dig").then(Commands.argument("player", StringArgumentType.word())
                    .then(Commands.argument("x", IntegerArgumentType.integer())
                        .then(Commands.argument("y", IntegerArgumentType.integer())
                            .then(Commands.argument("z", IntegerArgumentType.integer())
                                .executes(ctx -> dig(ctx.getSource(), arg(ctx, "player"), pos(ctx))))))))
                .then(Commands.literal("place").then(Commands.argument("player", StringArgumentType.word())
                    .then(Commands.argument("x", IntegerArgumentType.integer())
                        .then(Commands.argument("y", IntegerArgumentType.integer())
                            .then(Commands.argument("z", IntegerArgumentType.integer())
                                .then(Commands.argument("block", StringArgumentType.greedyString())
                                    .executes(ctx -> place(ctx.getSource(), arg(ctx, "player"), pos(ctx), arg(ctx, "block")))))))))
                .then(Commands.literal("walk").then(Commands.argument("player", StringArgumentType.word())
                    .then(Commands.argument("x", IntegerArgumentType.integer())
                        .then(Commands.argument("y", IntegerArgumentType.integer())
                            .then(Commands.argument("z", IntegerArgumentType.integer())
                                .executes(ctx -> walk(ctx.getSource(), arg(ctx, "player"), pos(ctx))))))))
                .then(Commands.literal("tp").then(Commands.argument("player", StringArgumentType.word())
                    .then(Commands.argument("x", IntegerArgumentType.integer())
                        .then(Commands.argument("y", IntegerArgumentType.integer())
                            .then(Commands.argument("z", IntegerArgumentType.integer())
                                .executes(ctx -> teleport(ctx.getSource(), arg(ctx, "player"), pos(ctx))))))))
                .then(Commands.literal("look").then(Commands.argument("player", StringArgumentType.word())
                    .then(Commands.argument("x", IntegerArgumentType.integer())
                        .then(Commands.argument("y", IntegerArgumentType.integer())
                            .then(Commands.argument("z", IntegerArgumentType.integer())
                                .executes(ctx -> look(ctx.getSource(), arg(ctx, "player"), pos(ctx))))))))
                .then(Commands.literal("inv").then(Commands.argument("player", StringArgumentType.word())
                    .executes(ctx -> inventory(ctx.getSource(), arg(ctx, "player")))))
                .then(Commands.literal("count").then(Commands.argument("player", StringArgumentType.word())
                    .then(Commands.argument("item", StringArgumentType.word())
                        .executes(ctx -> count(ctx.getSource(), arg(ctx, "player"), arg(ctx, "item"))))))
                .then(Commands.literal("state").then(Commands.argument("player", StringArgumentType.word())
                    .executes(ctx -> state(ctx.getSource(), arg(ctx, "player")))))
                .then(Commands.literal("players").executes(ctx -> players(ctx.getSource())))
        ));

        LOGGER.info("dsh-mc-companion-server ready: /dshmc dig|place|walk|tp|look|inv|count|state|players");
    }

    private static String arg(CommandContext<CommandSourceStack> ctx, String key) {
        return StringArgumentType.getString(ctx, key);
    }

    private static BlockPos pos(CommandContext<CommandSourceStack> ctx) {
        return new BlockPos(
            IntegerArgumentType.getInteger(ctx, "x"),
            IntegerArgumentType.getInteger(ctx, "y"),
            IntegerArgumentType.getInteger(ctx, "z"));
    }

    private static ServerPlayer resolve(CommandSourceStack source, String name) {
        MinecraftServer current = server != null ? server : source.getServer();
        return current.getPlayerList().getPlayerByName(name);
    }

    private static int fail(CommandSourceStack source, String message) {
        source.sendFailure(Component.literal("dshmc: " + message));
        return 0;
    }

    private static int ok(CommandSourceStack source, String message) {
        source.sendSuccess(() -> Component.literal("dshmc: " + message), true);
        return 1;
    }

    /** Break a block with the server's own logic, as the given player. */
    private static int dig(CommandSourceStack source, String playerName, BlockPos target) {
        ServerPlayer actor = resolve(source, playerName);
        if (actor == null) return fail(source, "player not found: " + playerName);
        ServerLevel level = (ServerLevel) actor.level();
        BlockState before = level.getBlockState(target);
        if (before.isAir()) return fail(source, "block is already air at " + target.toShortString());
        String blockName = before.getBlock().getName().getString();
        boolean broken = actor.gameMode.destroyBlock(target);
        if (!broken) return fail(source, "server refused to break " + blockName + " at " + target.toShortString());
        return ok(source, "broke " + blockName + " at " + target.toShortString());
    }

    private static int place(CommandSourceStack source, String playerName, BlockPos target, String blockName) {
        ServerPlayer actor = resolve(source, playerName);
        if (actor == null) return fail(source, "player not found: " + playerName);
        ServerLevel level = (ServerLevel) actor.level();
        Block block = BuiltInRegistries.BLOCK.getOptional(Identifier.parse(blockName)).orElse(null);
        if (block == null) return fail(source, "unknown block: " + blockName);
        Inventory inventory = actor.getInventory();
        if (!actor.isCreative() && inventory.getItem(inventory.getSelectedSlot()).isEmpty()) {
            return fail(source, "nothing in hand to place with");
        }
        level.setBlockAndUpdate(target, block.defaultBlockState());
        if (!actor.isCreative()) {
            inventory.getItem(inventory.getSelectedSlot()).shrink(1);
        }
        return ok(source, "placed " + blockName + " at " + target.toShortString());
    }

    /** Step the player towards a target: real server-side movement, not a teleport jump. */
    private static int walk(CommandSourceStack source, String playerName, BlockPos target) {
        ServerPlayer actor = resolve(source, playerName);
        if (actor == null) return fail(source, "player not found: " + playerName);
        ServerLevel level = (ServerLevel) actor.level();
        double goalX = target.getX() + 0.5;
        double goalZ = target.getZ() + 0.5;
        for (int step = 0; step < 160; step++) {
            double remaining = Math.sqrt(Math.pow(goalX - actor.getX(), 2) + Math.pow(goalZ - actor.getZ(), 2));
            if (remaining <= 1.0) break;
            double dx = (goalX - actor.getX()) / remaining;
            double dz = (goalZ - actor.getZ()) / remaining;
            double nextX = actor.getX() + dx * Math.min(0.5, remaining);
            double nextZ = actor.getZ() + dz * Math.min(0.5, remaining);
            double feetY = actor.getY();
            if (!level.getBlockState(BlockPos.containing(nextX, feetY, nextZ)).isAir()) {
                feetY = actor.getY() + 1.0;
            }
            actor.teleportTo(nextX, feetY, nextZ);
        }
        return ok(source, "walked " + playerName + " to " + actor.blockPosition().toShortString());
    }

    private static int teleport(CommandSourceStack source, String playerName, BlockPos target) {
        ServerPlayer actor = resolve(source, playerName);
        if (actor == null) return fail(source, "player not found: " + playerName);
        actor.teleportTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5);
        return ok(source, "teleported " + playerName + " to " + target.toShortString());
    }

    private static int look(CommandSourceStack source, String playerName, BlockPos target) {
        ServerPlayer actor = resolve(source, playerName);
        if (actor == null) return fail(source, "player not found: " + playerName);
        double dx = target.getX() + 0.5 - actor.getX();
        double dy = target.getY() + 0.5 - (actor.getY() + actor.getEyeHeight());
        double dz = target.getZ() + 0.5 - actor.getZ();
        double flat = Math.sqrt(dx * dx + dz * dz);
        actor.setYRot((float) Math.toDegrees(Math.atan2(-dx, dz)));
        actor.setXRot((float) -Math.toDegrees(Math.atan2(dy, flat)));
        return ok(source, playerName + " looks at " + target.toShortString());
    }

    private static int inventory(CommandSourceStack source, String playerName) {
        ServerPlayer actor = resolve(source, playerName);
        if (actor == null) return fail(source, "player not found: " + playerName);
        StringBuilder builder = new StringBuilder();
        Inventory inventory = actor.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty()) continue;
            if (builder.length() > 0) builder.append(',');
            builder.append(BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath()).append('x').append(stack.getCount());
        }
        String held = actor.getMainHandItem().isEmpty() ? "empty" : BuiltInRegistries.ITEM.getKey(actor.getMainHandItem().getItem()).getPath();
        String body = builder.length() == 0 ? "(empty)" : builder.toString();
        source.sendSuccess(() -> Component.literal("dshmc: inv " + playerName + " held=" + held + " items=" + body), false);
        return 1;
    }

    private static int count(CommandSourceStack source, String playerName, String itemName) {
        ServerPlayer actor = resolve(source, playerName);
        if (actor == null) return fail(source, "player not found: " + playerName);
        Item item = BuiltInRegistries.ITEM.getOptional(Identifier.parse(itemName)).orElse(null);
        if (item == null) return fail(source, "unknown item: " + itemName);
        int total = 0;
        Inventory inventory = actor.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty() && stack.getItem() == item) total += stack.getCount();
        }
        int result = total;
        source.sendSuccess(() -> Component.literal("dshmc: count " + playerName + " " + itemName + " = " + result), false);
        return 1;
    }

    private static int state(CommandSourceStack source, String playerName) {
        ServerPlayer actor = resolve(source, playerName);
        if (actor == null) return fail(source, "player not found: " + playerName);
        String text = "dshmc: state " + playerName
            + " pos=" + actor.blockPosition().toShortString()
            + " hp=" + actor.getHealth()
            + " food=" + actor.getFoodData().getFoodLevel()
            + " mode=" + (actor.isCreative() ? "creative" : "survival")
            + " dim=" + actor.level().dimension().identifier();
        source.sendSuccess(() -> Component.literal(text), false);
        return 1;
    }

    private static int players(CommandSourceStack source) {
        MinecraftServer current = server != null ? server : source.getServer();
        StringBuilder builder = new StringBuilder();
        current.getPlayerList().getPlayers().forEach(player -> {
            if (builder.length() > 0) builder.append(',');
            builder.append(player.getName().getString());
        });
        String body = builder.length() == 0 ? "(none)" : builder.toString();
        source.sendSuccess(() -> Component.literal("dshmc: players " + body), false);
        return 1;
    }
}
