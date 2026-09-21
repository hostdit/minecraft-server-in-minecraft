package dev.hostd.mcinmc;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.network.packet.s2c.common.ServerTransferS2CPacket;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import net.minecraft.world.Heightmap;
import net.minecraft.world.World;
import net.minecraft.world.chunk.WorldChunk;

public final class Table {

    public static final RegistryKey<World> WORLD = RegistryKey.of(RegistryKeys.WORLD, Identifier.of("mcinmc", "table"));
    private static final int CHUNKS_PER_TICK = 1;
    private static final int CUBES_PER_TICK = 256;
    private static final int PLACEMENTS_PER_TICK = 4096;
    private static final int LOCK_CHECKS_PER_TICK = 512;
    private static final int LOCK_REACH = 2;
    private static final float BUSY_TICK_MS = 40.0F;
    private static final int TABLETOP_MARGIN = 3;
    private static final int TABLETOP_Y = Cells.FIRST_CELL_Y - 1;
    private static final int FLOOR_MARGIN = 24;
    private static final int FLOOR_Y = TABLETOP_Y - 16;
    private static final int HOVER = 24;
    private static final float FLY_SPEED = 0.05F;
    private static final float LOOK_DOWN = 60.0F;
    private static final int LAMP_SPACING = 8;
    private static final int FLOOR_GRID = 16;

    private record Placement(BlockPos pos, BlockState state) {
    }

    private record Drop(MinecraftServer server, double x, double z) {
    }

    private static MinecraftServer outer;
    private static ServerWorld table;
    private static int centreX;
    private static int centreZ;
    private static volatile Source source;
    private static volatile Cells.Frame frame;
    private static final ArrayDeque<ChunkPos> spiral = new ArrayDeque<>();
    private static final Set<Long> converted = ConcurrentHashMap.newKeySet();
    private static final Set<Long> dirty = ConcurrentHashMap.newKeySet();
    private static final ConcurrentLinkedQueue<Placement> sink = new ConcurrentLinkedQueue<>();
    private static final ConcurrentLinkedQueue<Long> lockQueue = new ConcurrentLinkedQueue<>();
    private static final Set<Long> LOCKED = ConcurrentHashMap.newKeySet();
    private static final Map<UUID, Vec3d> returnPoints = new HashMap<>();
    private static final Map<String, Drop> PENDING_DROPS = new ConcurrentHashMap<>();
    private static final ThreadLocal<Boolean> SYNCING = ThreadLocal.withInitial(() -> false);

    private Table() {
    }

    public static void init() {
        ServerLifecycleEvents.SERVER_STARTED.register(s -> {
            if (!Nested.isNested(s)) {
                start(s);
            }
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(s -> {
            if (!Nested.isNested(s)) {
                stop();
            }
        });
        ServerChunkEvents.CHUNK_LOAD.register((world, chunk) -> {
            Source shown = source;
            if (shown != null && world == shown.world() && !converted.contains(chunk.getPos().toLong())) {
                convert(chunk);
            }
        });
        ServerTickEvents.END_SERVER_TICK.register(Table::tick);
        ServerPlayConnectionEvents.JOIN.register((handler, sender, s) -> {
            if (s == outer && isOnTable(handler.player)) {
                equip(handler.player);
            } else if (Nested.isNested(s)) {
                land(handler.player, s);
            }
        });
        Hands.init();
    }

    public static boolean ready() {
        return table != null;
    }

    public static ServerWorld world() {
        return table;
    }

    public static Source source() {
        return source;
    }

    public static Cells.Frame frame() {
        return frame;
    }

    public static String shownName() {
        Source shown = source;
        return shown == null ? Source.WORLD : shown.name();
    }

    public static ServerWorld realWorld() {
        Source shown = source;
        return shown == null ? null : shown.world();
    }

    public static boolean isOnTable(ServerPlayerEntity player) {
        return table != null && player.getServerWorld() == table;
    }

    private static void start(MinecraftServer s) {
        outer = s;
        table = s.getWorld(WORLD);
        if (table == null) {
            System.out.println("[mcinmc] table dimension missing, the model is off");
            return;
        }
        BlockPos spawn = s.getOverworld().getSpawnPos();
        centreX = Cells.toTable(spawn.getX());
        centreZ = Cells.toTable(spawn.getZ());
        int cells = Cells.cellRadius() + FLOOR_MARGIN;
        for (int chunkX = (centreX - cells) >> 4; chunkX <= (centreX + cells) >> 4; chunkX++) {
            for (int chunkZ = (centreZ - cells) >> 4; chunkZ <= (centreZ + cells) >> 4; chunkZ++) {
                table.setChunkForced(chunkX, chunkZ, true);
            }
        }
        Figures.start(table);
        show(Source.world(s));
        furnish();
        int radius = Cells.cellRadius();
        for (int x = centreX - radius; x <= centreX + radius; x++) {
            for (int z = centreZ - radius; z <= centreZ + radius; z++) {
                lockQueue.add(ChunkPos.toLong(x, z));
            }
        }
        System.out.println("[mcinmc] table ready, modelling " + (2 * Cells.RADIUS) + " blocks around " + spawn.toShortString());
    }

    public static void show(Source next) {
        source = next;
        BlockPos spawn = next.world().getSpawnPos();
        frame = Cells.Frame.around(spawn.getX(), spawn.getZ(), centreX, centreZ);
        spiral.clear();
        converted.clear();
        dirty.clear();
        sink.clear();
        int chunkRadius = (Cells.RADIUS >> 4) + 1;
        ChunkPos centre = new ChunkPos(spawn);
        for (int ring = 0; ring <= chunkRadius; ring++) {
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) == ring) {
                        spiral.add(new ChunkPos(centre.x + dx, centre.z + dz));
                    }
                }
            }
        }
        Figures.reset();
        next.refresh();
    }

    private static void furnish() {
        int cx = centreX;
        int cz = centreZ;
        int top = Cells.cellRadius() + TABLETOP_MARGIN;
        int floor = Cells.cellRadius() + FLOOR_MARGIN;
        BlockState tiles = Blocks.DEEPSLATE_TILES.getDefaultState();
        BlockState grid = Blocks.POLISHED_DEEPSLATE.getDefaultState();
        BlockState glow = Blocks.SEA_LANTERN.getDefaultState();
        BlockState wood = Blocks.SMOOTH_QUARTZ.getDefaultState();
        BlockState rim = Blocks.DARK_OAK_PLANKS.getDefaultState();
        BlockState lamp = Blocks.END_ROD.getDefaultState();
        BlockState leg = Blocks.DARK_OAK_LOG.getDefaultState();
        for (int x = cx - floor; x <= cx + floor; x++) {
            for (int z = cz - floor; z <= cz + floor; z++) {
                boolean line = Math.floorMod(x - cx, FLOOR_GRID) == 0 || Math.floorMod(z - cz, FLOOR_GRID) == 0;
                boolean cross = Math.floorMod(x - cx, FLOOR_GRID) == 0 && Math.floorMod(z - cz, FLOOR_GRID) == 0;
                sink.add(new Placement(new BlockPos(x, FLOOR_Y, z), cross ? glow : line ? grid : tiles));
            }
        }
        for (int x = cx - top; x <= cx + top; x++) {
            for (int z = cz - top; z <= cz + top; z++) {
                boolean edge = Math.abs(x - cx) == top || Math.abs(z - cz) == top;
                sink.add(new Placement(new BlockPos(x, TABLETOP_Y, z), edge ? rim : wood));
                if (edge) {
                    sink.add(new Placement(new BlockPos(x, TABLETOP_Y + 1, z), rim));
                    boolean corner = Math.abs(x - cx) == top && Math.abs(z - cz) == top;
                    boolean post = Math.floorMod(x - cx, LAMP_SPACING) == 0 && Math.floorMod(z - cz, LAMP_SPACING) == 0;
                    if (corner || post) {
                        sink.add(new Placement(new BlockPos(x, TABLETOP_Y + 2, z), rim));
                        sink.add(new Placement(new BlockPos(x, TABLETOP_Y + 3, z), lamp));
                    }
                }
            }
        }
        int inset = top - 2;
        for (int sx = -1; sx <= 1; sx += 2) {
            for (int sz = -1; sz <= 1; sz += 2) {
                for (int y = FLOOR_Y + 1; y < TABLETOP_Y; y++) {
                    sink.add(new Placement(new BlockPos(cx + sx * inset, y, cz + sz * inset), leg));
                    sink.add(new Placement(new BlockPos(cx + sx * inset - sx, y, cz + sz * inset), leg));
                    sink.add(new Placement(new BlockPos(cx + sx * inset, y, cz + sz * inset - sz), leg));
                    sink.add(new Placement(new BlockPos(cx + sx * inset - sx, y, cz + sz * inset - sz), leg));
                }
            }
        }
    }

    private static void stop() {
        Figures.stop();
        spiral.clear();
        converted.clear();
        dirty.clear();
        sink.clear();
        lockQueue.clear();
        LOCKED.clear();
        returnPoints.clear();
        PENDING_DROPS.clear();
        source = null;
        frame = null;
        outer = null;
        table = null;
    }

    private static void tick(MinecraftServer s) {
        if (table == null || s != outer) {
            return;
        }
        Source shown = source;
        if (!shown.alive()) {
            show(Source.world(outer));
            for (ServerPlayerEntity player : playersOnTable()) {
                Hands.tell(player, shown.name() + " went down, the table shows the world again");
            }
            shown = source;
        }
        Cells.Frame f = frame;
        boolean busy = s.getAverageTickTime() > BUSY_TICK_MS;
        for (int i = 0; i < CHUNKS_PER_TICK && !busy && !spiral.isEmpty(); i++) {
            ChunkPos next = spiral.poll();
            if (!converted.contains(next.toLong())) {
                Source captured = shown;
                shown.run(() -> {
                    if (source == captured && !converted.contains(next.toLong())) {
                        convert(captured.world().getChunk(next.x, next.z));
                    }
                });
            }
        }
        List<Long> batch = new ArrayList<>(CUBES_PER_TICK);
        var it = dirty.iterator();
        while (it.hasNext() && batch.size() < CUBES_PER_TICK) {
            batch.add(it.next());
            it.remove();
        }
        if (!batch.isEmpty()) {
            Source captured = shown;
            shown.run(() -> {
                if (source != captured) {
                    return;
                }
                for (long key : batch) {
                    recompute(captured.world(), f, BlockPos.fromLong(key));
                }
            });
        }
        SYNCING.set(true);
        try {
            for (int i = 0; i < PLACEMENTS_PER_TICK && !sink.isEmpty(); i++) {
                Placement p = sink.poll();
                BlockState current = table.getBlockState(p.pos());
                if (current != p.state() && Model.isModelBlock(current)) {
                    table.setBlockState(p.pos(), p.state(), Block.NOTIFY_LISTENERS);
                }
            }
        } finally {
            SYNCING.set(false);
        }
        checkLocks();
        Figures.tick(shown.seen(), f, table, outer);
        shown.refresh();
        Hands.tick(s);
    }

    private static void convert(WorldChunk chunk) {
        Cells.Frame f = frame;
        ChunkPos pos = chunk.getPos();
        converted.add(pos.toLong());
        BlockPos.Mutable cursor = new BlockPos.Mutable();
        Model.Reader reader = (x, y, z) -> chunk.getBlockState(cursor.set(x, y, z));
        for (int rx = pos.getStartX(); rx < pos.getStartX() + 16; rx += Cells.SCALE) {
            for (int rz = pos.getStartZ(); rz < pos.getStartZ() + 16; rz += Cells.SCALE) {
                if (!f.inRange(rx, rz)) {
                    continue;
                }
                for (int cellY = Cells.FIRST_CELL_Y; cellY < Cells.TABLE_HEIGHT; cellY++) {
                    BlockState state = Model.cube(reader, rx, Cells.toRealY(cellY), rz);
                    sink.add(new Placement(new BlockPos(f.cellX(rx), cellY, f.cellZ(rz)), state));
                }
            }
        }
    }

    private static void recompute(ServerWorld world, Cells.Frame f, BlockPos cell) {
        int rx = f.realX(cell.getX());
        int rz = f.realZ(cell.getZ());
        if (!world.isChunkLoaded(ChunkPos.toLong(rx >> 4, rz >> 4))) {
            return;
        }
        BlockPos.Mutable cursor = new BlockPos.Mutable();
        BlockState state = Model.cube((x, y, z) -> world.getBlockState(cursor.set(x, y, z)),
                rx, Cells.toRealY(cell.getY()), rz);
        sink.add(new Placement(cell, state));
    }

    public static void onBlockChanged(ServerWorld world, BlockPos pos, BlockState oldState, BlockState newState) {
        if (table == null || oldState == newState) {
            return;
        }
        Source shown = source;
        Cells.Frame f = frame;
        if (shown != null && world == shown.world()) {
            if (SYNCING.get() || !f.inRange(pos.getX(), pos.getZ())) {
                return;
            }
            int cellY = Cells.toTableY(pos.getY());
            if (cellY < Cells.FIRST_CELL_Y || cellY >= Cells.TABLE_HEIGHT) {
                return;
            }
            dirty.add(new BlockPos(f.cellX(pos.getX()), cellY, f.cellZ(pos.getZ())).asLong());
        } else if (world == table) {
            boolean syncing = SYNCING.get();
            boolean plain = mirrors(oldState) && mirrors(newState);
            if (!syncing || !plain) {
                for (int dx = -LOCK_REACH; dx <= LOCK_REACH; dx++) {
                    for (int dz = -LOCK_REACH; dz <= LOCK_REACH; dz++) {
                        lockQueue.add(ChunkPos.toLong(pos.getX() + dx, pos.getZ() + dz));
                    }
                }
            }
            if (!syncing && plain && shown != null && Hands.wasPlayerEdit(pos)) {
                mirror(shown, f, pos, newState);
            }
        }
    }

    private static boolean mirrors(BlockState state) {
        return state.isAir() || state.isOpaque();
    }

    private static void mirror(Source shown, Cells.Frame f, BlockPos cell, BlockState state) {
        if (cell.getY() < Cells.FIRST_CELL_Y || cell.getY() >= Cells.TABLE_HEIGHT || !f.cellInRange(cell.getX(), cell.getZ())) {
            return;
        }
        BlockState fill = state.isAir() ? Blocks.AIR.getDefaultState() : state.getBlock().getDefaultState();
        int rx0 = f.realX(cell.getX());
        int ry0 = Cells.toRealY(cell.getY());
        int rz0 = f.realZ(cell.getZ());
        shown.run(() -> {
            ServerWorld world = shown.world();
            SYNCING.set(true);
            try {
                BlockPos.Mutable cursor = new BlockPos.Mutable();
                for (int x = rx0; x < rx0 + Cells.SCALE; x++) {
                    for (int y = ry0; y < ry0 + Cells.SCALE; y++) {
                        for (int z = rz0; z < rz0 + Cells.SCALE; z++) {
                            world.setBlockState(cursor.set(x, y, z), fill, Block.NOTIFY_ALL);
                        }
                    }
                }
            } finally {
                SYNCING.set(false);
            }
        });
    }

    private static void checkLocks() {
        BlockPos.Mutable cell = new BlockPos.Mutable();
        for (int i = 0; i < LOCK_CHECKS_PER_TICK && !lockQueue.isEmpty(); i++) {
            long column = lockQueue.poll();
            int x = ChunkPos.getPackedX(column);
            int z = ChunkPos.getPackedZ(column);
            boolean powered = false;
            for (int y = Cells.FIRST_CELL_Y; y < Cells.TABLE_HEIGHT && !powered; y++) {
                powered = table.isReceivingRedstonePower(cell.set(x, y, z));
            }
            if (powered) {
                LOCKED.add(column);
            } else {
                LOCKED.remove(column);
            }
        }
    }

    public static boolean locked(int realX, int realZ) {
        Cells.Frame f = frame;
        return f != null && LOCKED.contains(ChunkPos.toLong(f.cellX(realX), f.cellZ(realZ)));
    }

    public static int rimTop() {
        return TABLETOP_Y + 2;
    }

    public static Vec3d tableSpawn() {
        int y = table.getTopY(Heightmap.Type.MOTION_BLOCKING, centreX, centreZ);
        return new Vec3d(centreX + 0.5, Math.max(y, TABLETOP_Y) + HOVER, centreZ + 0.5);
    }

    public static void arrive(ServerPlayerEntity player) {
        if (table == null) {
            return;
        }
        if (!isOnTable(player)) {
            returnPoints.put(player.getUuid(), player.getPos());
            Vec3d at = tableSpawn();
            player.teleport(table, at.x, at.y, at.z, Set.of(), player.getYaw(), LOOK_DOWN);
        }
        equip(player);
        Hands.tell(player, "/table help lists what you can do here");
    }

    public static void equip(ServerPlayerEntity player) {
        if (!isOnTable(player)) {
            return;
        }
        player.changeGameMode(Hands.isOp(player) ? GameMode.CREATIVE : GameMode.ADVENTURE);
        player.getAbilities().allowFlying = true;
        player.getAbilities().flying = true;
        player.getAbilities().setFlySpeed(FLY_SPEED);
        player.sendAbilitiesUpdate();
    }

    private static void restoreMode(ServerPlayerEntity player) {
        GameMode previous = player.interactionManager.getPreviousGameMode();
        if (previous == null || previous == GameMode.CREATIVE || previous == GameMode.ADVENTURE) {
            previous = player.getServer().getDefaultGameMode();
        }
        player.changeGameMode(previous);
        player.interactionManager.getGameMode().setAbilities(player.getAbilities());
        player.sendAbilitiesUpdate();
    }

    public static void leave(ServerPlayerEntity player) {
        if (table == null || !isOnTable(player)) {
            return;
        }
        Vec3d back = returnPoints.remove(player.getUuid());
        if (back == null) {
            BlockPos spawn = outer.getOverworld().getSpawnPos();
            back = new Vec3d(spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5);
        }
        dropIn(player, back.x, back.z);
    }

    public static void dropIn(ServerPlayerEntity player, double realX, double realZ) {
        ServerWorld world = outer.getOverworld();
        int x = (int) Math.floor(realX);
        int z = (int) Math.floor(realZ);
        int y = landingY(world, x, z);
        player.teleport(world, x + 0.5, y, z + 0.5, Set.of(), player.getYaw(), player.getPitch());
        restoreMode(player);
    }

    private static int landingY(ServerWorld world, int x, int z) {
        world.getChunk(x >> 4, z >> 4);
        int y = world.getTopY(Heightmap.Type.MOTION_BLOCKING, x, z);
        return y > world.getBottomY() ? y : world.getSeaLevel() + 1;
    }

    public static void transfer(ServerPlayerEntity player, Source into, double realX, double realZ) {
        PENDING_DROPS.put(player.getGameProfile().getName().toLowerCase(Locale.ROOT), new Drop(into.server(), realX, realZ));
        player.networkHandler.sendPacket(new ServerTransferS2CPacket(Listener.lanAddress(), into.port()));
    }

    private static void land(ServerPlayerEntity player, MinecraftServer nested) {
        Drop drop = PENDING_DROPS.remove(player.getGameProfile().getName().toLowerCase(Locale.ROOT));
        if (drop == null || drop.server() != nested) {
            return;
        }
        ServerWorld world = nested.getOverworld();
        int x = (int) Math.floor(drop.x());
        int z = (int) Math.floor(drop.z());
        int y = landingY(world, x, z);
        player.teleport(world, x + 0.5, y, z + 0.5, Set.of(), player.getYaw(), player.getPitch());
    }

    public static void move(UUID id, double realX, double realZ) {
        Source shown = source;
        shown.run(() -> {
            ServerPlayerEntity player = shown.player(id);
            if (player == null) {
                return;
            }
            ServerWorld world = shown.world();
            int x = (int) Math.floor(realX);
            int z = (int) Math.floor(realZ);
            int y = landingY(world, x, z);
            player.teleport(world, x + 0.5, y, z + 0.5, Set.of(), player.getYaw(), player.getPitch());
        });
    }

    public static void kick(UUID id, String reason) {
        Source shown = source;
        shown.run(() -> {
            ServerPlayerEntity player = shown.player(id);
            if (player != null) {
                player.networkHandler.disconnect(Text.literal(reason));
            }
        });
    }

    public static List<ServerPlayerEntity> playersOnTable() {
        List<ServerPlayerEntity> out = new ArrayList<>();
        if (outer == null) {
            return out;
        }
        for (ServerPlayerEntity player : outer.getPlayerManager().getPlayerList()) {
            if (isOnTable(player)) {
                out.add(player);
            }
        }
        return out;
    }
}
