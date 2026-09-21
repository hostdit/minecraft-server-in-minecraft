package dev.hostd.mcinmc;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.AbstractSignBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.WallSignBlock;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.block.entity.SignText;
import net.minecraft.network.packet.s2c.common.ServerTransferS2CPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.DyeColor;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;

public final class ServerSigns {

    public static final String TAG = "[server]";
    private static final int CHECK_EVERY = 20;
    private static final int LINES = 4;
    private static final int MAX_SIGNAL = 15;

    private static final Set<Fleet.Key> CANDIDATES = ConcurrentHashMap.newKeySet();
    private static final Set<Fleet.Key> RUNNING = ConcurrentHashMap.newKeySet();
    private static final Map<Fleet.Key, Integer> LAST_OUTPUT = new ConcurrentHashMap<>();

    private ServerSigns() {
    }

    public static void init() {
        ServerChunkEvents.CHUNK_LOAD.register((world, chunk) -> {
            if (Nested.isNested(world.getServer())) {
                return;
            }
            for (BlockPos pos : chunk.getBlockEntityPositions()) {
                if (chunk.getBlockState(pos).getBlock() instanceof AbstractSignBlock) {
                    CANDIDATES.add(Fleet.key(world, pos));
                }
            }
        });
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            if (!Nested.isNested(server)) {
                CANDIDATES.addAll(Fleet.knownKeys(server));
            }
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            if (!Nested.isNested(server)) {
                CANDIDATES.clear();
                RUNNING.clear();
                LAST_OUTPUT.clear();
            }
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (Nested.isNested(server) || server.getTicks() % CHECK_EVERY != 0) {
                return;
            }
            for (Fleet.Key key : CANDIDATES) {
                reconcile(server, key);
            }
        });
    }

    public static void onBlockChanged(ServerWorld world, BlockPos pos, BlockState oldState, BlockState newState) {
        if (Nested.isNested(world.getServer())) {
            return;
        }
        boolean was = oldState.getBlock() instanceof AbstractSignBlock;
        boolean is = newState.getBlock() instanceof AbstractSignBlock;
        Fleet.Key key = Fleet.key(world, pos);
        if (is) {
            CANDIDATES.add(key);
        } else if (was) {
            CANDIDATES.remove(key);
            RUNNING.remove(key);
            LAST_OUTPUT.remove(key);
            Fleet.stop(key, "Server unplugged", false);
        }
    }

    private static void reconcile(MinecraftServer server, Fleet.Key key) {
        ServerWorld world = server.getWorld(key.world());
        if (world == null || !world.isChunkLoaded(ChunkPos.toLong(key.pos().getX() >> 4, key.pos().getZ() >> 4))) {
            return;
        }
        if (!(world.getBlockState(key.pos()).getBlock() instanceof AbstractSignBlock)) {
            CANDIDATES.remove(key);
            RUNNING.remove(key);
            LAST_OUTPUT.remove(key);
            Fleet.stop(key, "Server unplugged", false);
            return;
        }
        if (!(world.getBlockEntity(key.pos()) instanceof SignBlockEntity sign)) {
            return;
        }
        if (!isServerSign(sign)) {
            if (RUNNING.remove(key)) {
                Fleet.stop(key, "Server unplugged", false);
                mark(sign, false);
            }
            return;
        }
        BlockPos rack = rackOf(world.getBlockState(key.pos()), key.pos());
        boolean powered = world.isReceivingRedstonePower(key.pos()) || world.isReceivingRedstonePower(rack);
        if (powered && !Fleet.isUp(key)) {
            int cap = Math.max(1, Math.max(world.getReceivedRedstonePower(key.pos()), world.getReceivedRedstonePower(rack)));
            String name = nameOf(sign, key.pos());
            Fleet.start(world, key.pos(), name, Fleet.portFor(server, key), Math.min(cap, MAX_SIGNAL));
            RUNNING.add(key);
            mark(sign, true);
        } else if (!powered && Fleet.isUp(key)) {
            Fleet.stop(key, "Server powered down", false);
            RUNNING.remove(key);
            mark(sign, false);
        }
        int output = Math.max(0, comparatorOutput(world, key.pos()));
        Integer previous = LAST_OUTPUT.put(key, output);
        if (previous == null || previous != output) {
            world.updateComparators(key.pos(), world.getBlockState(key.pos()).getBlock());
            world.updateComparators(rack, world.getBlockState(rack).getBlock());
        }
    }

    private static void mark(SignBlockEntity sign, boolean on) {
        SignText text = sign.getFrontText();
        SignText wanted = text.withGlowing(on).withColor(on ? DyeColor.LIME : DyeColor.BLACK);
        if (wanted.isGlowing() != text.isGlowing() || wanted.getColor() != text.getColor()) {
            sign.setText(wanted, true);
        }
    }

    public static BlockPos rackOf(BlockState state, BlockPos pos) {
        if (state.getBlock() instanceof WallSignBlock) {
            return pos.offset(state.get(WallSignBlock.FACING).getOpposite());
        }
        return pos.down();
    }

    public static boolean isServerSign(SignBlockEntity sign) {
        return sign.getFrontText().getMessage(0, false).getString().trim().toLowerCase(Locale.ROOT).equals(TAG);
    }

    public static boolean isServerSign(ServerWorld world, BlockPos pos) {
        return world.getBlockEntity(pos) instanceof SignBlockEntity sign && isServerSign(sign);
    }

    public static String nameOf(SignBlockEntity sign, BlockPos pos) {
        StringBuilder name = new StringBuilder();
        for (int line = 1; line < LINES; line++) {
            String part = sign.getFrontText().getMessage(line, false).getString().trim();
            if (!part.isEmpty()) {
                name.append(name.isEmpty() ? "" : " ").append(part);
            }
        }
        return name.isEmpty() ? "Server at " + pos.getX() + " " + pos.getY() + " " + pos.getZ() : name.toString();
    }

    public static int comparatorOutput(ServerWorld world, BlockPos pos) {
        Fleet.Key key = Fleet.key(world, pos);
        if (!RUNNING.contains(key)) {
            return -1;
        }
        return Math.min(MAX_SIGNAL, Fleet.playerCount(key));
    }

    public static int comparatorOutputBehind(ServerWorld world, BlockPos behind) {
        int direct = comparatorOutput(world, behind);
        if (direct >= 0) {
            return direct;
        }
        for (Fleet.Key key : RUNNING) {
            if (key.world() == world.getRegistryKey() && rackOf(world.getBlockState(key.pos()), key.pos()).equals(behind)) {
                return Math.min(MAX_SIGNAL, Fleet.playerCount(key));
            }
        }
        return -1;
    }

    public static boolean use(ServerPlayerEntity user, ServerWorld world, BlockPos pos, boolean onTable) {
        if (!isServerSign(world, pos)) {
            return false;
        }
        if (user.isSneaking()) {
            return false;
        }
        Fleet.Key key = Fleet.key(world, pos);
        Nested.Spec spec = Fleet.spec(key);
        if (spec == null) {
            tell(user, "Power the sign or the block it's on to start this server");
        } else if (!Fleet.isRunning(key)) {
            tell(user, spec.name() + " is still starting");
        } else if (onTable && Hands.isOp(user)) {
            Hands.show(user, spec.name());
        } else if (world.getServer().isHost(user.getGameProfile())) {
            user.sendMessage(Text.literal(spec.name() + " is on " + Listener.lanAddress() + ":" + spec.port()
                    + ", join it from another client").formatted(Formatting.GOLD), false);
            Hands.show(user, spec.name());
        } else {
            tell(user, "Sending you to " + spec.name());
            user.networkHandler.sendPacket(new ServerTransferS2CPacket(Listener.lanAddress(), spec.port()));
        }
        return true;
    }

    private static void tell(ServerPlayerEntity player, String message) {
        player.sendMessage(Text.literal(message).formatted(Formatting.GOLD), true);
    }
}
