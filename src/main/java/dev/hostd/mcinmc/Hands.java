package dev.hostd.mcinmc;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;

import java.util.HashMap;
import java.util.HashSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.message.v1.ServerMessageDecoratorEvent;
import net.minecraft.command.CommandSource;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.item.Items;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;

public final class Hands {

    private static final int OP_LEVEL = 2;
    private static final double JUMP_RISE = 0.05;

    private record Footing(boolean onGround, double y) {
    }

    private static final Set<Long> PENDING_EDITS = ConcurrentHashMap.newKeySet();
    private static final Set<String> TRUSTED = new HashSet<>();
    private static final Map<UUID, Footing> FOOTING = new HashMap<>();

    private Hands() {
    }

    static void init() {
        UseEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
            if (!(player instanceof ServerPlayerEntity admin) || !Table.isOnTable(admin) || !Figures.isFigure(entity)) {
                return ActionResult.PASS;
            }
            if (!isOp(admin)) {
                tell(admin, "Only ops can move figures");
                return ActionResult.FAIL;
            }
            UUID target = Figures.playerOf(entity);
            if (target == null) {
                return ActionResult.FAIL;
            }
            Figures.hold(admin, target);
            tell(admin, "Holding " + Figures.nameOf(target) + ". Right click the table to put them down");
            return ActionResult.SUCCESS;
        });
        AttackEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
            if (!(player instanceof ServerPlayerEntity admin) || !Figures.isFigure(entity)) {
                return ActionResult.PASS;
            }
            UUID target = Figures.playerOf(entity);
            if (isOp(admin) && target != null && admin.getMainHandStack().isOf(Items.STICK)) {
                Table.kick(target, "Flicked off the table by " + admin.getGameProfile().getName());
                tell(admin, Figures.nameOf(target) + " flicked off the table");
            }
            return ActionResult.FAIL;
        });
        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (!(player instanceof ServerPlayerEntity user)) {
                return ActionResult.PASS;
            }
            if (Table.isOnTable(user)) {
                if (Figures.isHolding(user)) {
                    putDown(user, hit.getBlockPos());
                    return ActionResult.FAIL;
                }
                if (world instanceof ServerWorld tableWorld && ServerSigns.use(user, tableWorld, hit.getBlockPos(), true)) {
                    return ActionResult.SUCCESS;
                }
                if (!isOp(user)) {
                    tell(user, "Only ops can edit the table");
                    return ActionResult.FAIL;
                }
                PENDING_EDITS.add(hit.getBlockPos().asLong());
                PENDING_EDITS.add(hit.getBlockPos().offset(hit.getSide()).asLong());
                return ActionResult.PASS;
            }
            if (world instanceof ServerWorld serverWorld && ServerSigns.use(user, serverWorld, hit.getBlockPos(), false)) {
                return ActionResult.SUCCESS;
            }
            BlockPos placed = hit.getBlockPos().offset(hit.getSide());
            if (world == Table.realWorld() && !isOp(user) && Table.locked(placed.getX(), placed.getZ())) {
                tell(user, "This land is locked from the table");
                return ActionResult.FAIL;
            }
            return ActionResult.PASS;
        });
        PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, blockEntity) -> {
            if (!(player instanceof ServerPlayerEntity user)) {
                return true;
            }
            if (Table.isOnTable(user)) {
                if (!isOp(user)) {
                    tell(user, "Only ops can edit the table");
                    return false;
                }
                PENDING_EDITS.add(pos.asLong());
                return true;
            }
            if (world == Table.realWorld() && !isOp(user) && Table.locked(pos.getX(), pos.getZ())) {
                tell(user, "This land is locked from the table");
                return false;
            }
            return true;
        });
        ServerMessageDecoratorEvent.EVENT.register(ServerMessageDecoratorEvent.CONTENT_PHASE, (sender, message) ->
                sender != null && Table.isOnTable(sender)
                        ? Text.literal("[Table] ").formatted(Formatting.GOLD).append(message)
                        : message);
        CommandRegistrationCallback.EVENT.register((dispatcher, access, environment) -> dispatcher.register(
                CommandManager.literal("table")
                        .requires(source -> source.hasPermissionLevel(OP_LEVEL)
                                || source.getPlayer() != null && isOp(source.getPlayer()))
                        .executes(context -> {
                            ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
                            if (!Table.ready()) {
                                tell(player, "The table isn't set up in this world");
                                return 0;
                            }
                            Table.arrive(player);
                            return Command.SINGLE_SUCCESS;
                        })
                        .then(CommandManager.literal("help").executes(context -> {
                            help(context.getSource().getPlayerOrThrow());
                            return Command.SINGLE_SUCCESS;
                        }))
                        .then(CommandManager.literal("back").executes(context -> {
                            Table.leave(context.getSource().getPlayerOrThrow());
                            return Command.SINGLE_SUCCESS;
                        }))
                        .then(CommandManager.literal("list").executes(context -> {
                            list(context.getSource().getPlayerOrThrow());
                            return Command.SINGLE_SUCCESS;
                        }))
                        .then(CommandManager.literal("show")
                                .then(CommandManager.argument("server", StringArgumentType.greedyString())
                                        .suggests((context, builder) -> CommandSource.suggestMatching(Fleet.names(), builder))
                                        .executes(context -> {
                                            show(context.getSource().getPlayerOrThrow(), StringArgumentType.getString(context, "server"));
                                            return Command.SINGLE_SUCCESS;
                                        })))
                        .then(CommandManager.literal("trust")
                                .then(CommandManager.argument("player", EntityArgumentType.player()).executes(context -> {
                                    ServerPlayerEntity who = EntityArgumentType.getPlayer(context, "player");
                                    TRUSTED.add(who.getGameProfile().getName().toLowerCase(Locale.ROOT));
                                    who.getServer().getPlayerManager().sendCommandTree(who);
                                    Table.equip(who);
                                    tell(who, "You can now run the table");
                                    context.getSource().sendFeedback(() -> Text.literal(who.getGameProfile().getName() + " trusted on the table"), true);
                                    return Command.SINGLE_SUCCESS;
                                })))
                        .then(CommandManager.literal("untrust")
                                .then(CommandManager.argument("player", EntityArgumentType.player()).executes(context -> {
                                    ServerPlayerEntity who = EntityArgumentType.getPlayer(context, "player");
                                    TRUSTED.remove(who.getGameProfile().getName().toLowerCase(Locale.ROOT));
                                    who.getServer().getPlayerManager().sendCommandTree(who);
                                    Table.equip(who);
                                    context.getSource().sendFeedback(() -> Text.literal(who.getGameProfile().getName() + " no longer runs the table"), true);
                                    return Command.SINGLE_SUCCESS;
                                })))));
    }

    private static final String[] HELP = {
            "Fly: double tap space, shift to descend",
            "Right click a figure to pick that player up, right click the table to put them down there",
            "Hit a figure with a stick to kick them",
            "Place or break a block to edit the shown world in 4x4x4 cubes",
            "Sneak and jump on a cell to drop in to the shown world there",
            "/table brings you back, /table back returns you to where you were",
            "A lever on a cell locks that column of the shown world",
            "Tripwire and pressure plates fire when a figure crosses them",
            "Right click a powered Server Sign to show that server on the table, or /table show <name>",
            "/table show world brings the model of this world back, /table list names every server up",
            "/table trust <name> lets a guest run the table, /table help repeats this",
    };

    static void help(ServerPlayerEntity player) {
        player.sendMessage(Text.literal("The Table").formatted(Formatting.GOLD, Formatting.BOLD), false);
        for (String line : HELP) {
            player.sendMessage(Text.literal("· " + line).formatted(Formatting.GRAY), false);
        }
    }

    private static void list(ServerPlayerEntity player) {
        player.sendMessage(Text.literal("The table shows " + Table.shownName()).formatted(Formatting.GOLD), false);
        for (String line : Fleet.describe()) {
            player.sendMessage(Text.literal("· " + line).formatted(Formatting.GRAY), false);
        }
        if (Fleet.describe().isEmpty()) {
            player.sendMessage(Text.literal("· no Server Signs are powered").formatted(Formatting.GRAY), false);
        }
    }

    static void show(ServerPlayerEntity player, String name) {
        if (!Table.ready()) {
            tell(player, "The table isn't set up in this world");
            return;
        }
        Source next = Source.named(player.getServer(), name);
        if (next == null) {
            tell(player, "No server called " + name + " is up, /table list names them");
            return;
        }
        if (next.sameAs(Table.source())) {
            tell(player, "The table already shows " + next.name());
            return;
        }
        Table.show(next);
        for (ServerPlayerEntity watcher : Table.playersOnTable()) {
            tell(watcher, "The table now shows " + next.name());
        }
        if (!Table.isOnTable(player)) {
            tell(player, "The table now shows " + next.name());
        }
    }

    static boolean wasPlayerEdit(BlockPos pos) {
        return PENDING_EDITS.contains(pos.asLong());
    }

    static void tick(MinecraftServer server) {
        PENDING_EDITS.clear();
        for (ServerPlayerEntity player : Table.playersOnTable()) {
            Footing before = FOOTING.get(player.getUuid());
            Footing now = new Footing(player.isOnGround(), player.getY());
            FOOTING.put(player.getUuid(), now);
            if (before != null && before.onGround() && !now.onGround() && player.isSneaking()
                    && now.y() > before.y() + JUMP_RISE) {
                dropIn(player);
            }
        }
        FOOTING.keySet().removeIf(id -> server.getPlayerManager().getPlayer(id) == null);
    }

    private static void dropIn(ServerPlayerEntity player) {
        Cells.Frame frame = Table.frame();
        int cellX = player.getBlockX();
        int cellZ = player.getBlockZ();
        if (!frame.cellInRange(cellX, cellZ)) {
            tell(player, "Jump on the model to drop in to the world");
            return;
        }
        double realX = frame.realX(cellX) + Cells.SCALE / 2.0;
        double realZ = frame.realZ(cellZ) + Cells.SCALE / 2.0;
        Source shown = Table.source();
        if (shown.local()) {
            Table.dropIn(player, realX, realZ);
            tell(player, "Dropped in. /table brings you back");
        } else if (player.getServer().isHost(player.getGameProfile())) {
            tell(player, shown.name() + " is on " + Listener.lanAddress() + ":" + shown.port() + ", join it from another client");
        } else {
            tell(player, "Dropping in to " + shown.name());
            Table.transfer(player, shown, realX, realZ);
        }
    }

    private static void putDown(ServerPlayerEntity admin, BlockPos cell) {
        UUID target = Figures.release(admin);
        if (target == null) {
            return;
        }
        Cells.Frame frame = Table.frame();
        if (!frame.cellInRange(cell.getX(), cell.getZ())) {
            tell(admin, "That's off the model");
            return;
        }
        int realX = frame.realX(cell.getX());
        int realZ = frame.realZ(cell.getZ());
        Table.move(target, realX + Cells.SCALE / 2.0, realZ + Cells.SCALE / 2.0);
        tell(admin, "Put " + Figures.nameOf(target) + " down at " + realX + ", " + realZ);
    }

    static boolean isOp(ServerPlayerEntity player) {
        return player.hasPermissionLevel(OP_LEVEL)
                || player.getServer().isHost(player.getGameProfile())
                || TRUSTED.contains(player.getGameProfile().getName().toLowerCase(Locale.ROOT));
    }

    static void tell(ServerPlayerEntity player, String message) {
        player.sendMessage(Text.literal(message).formatted(Formatting.GOLD), true);
    }
}
