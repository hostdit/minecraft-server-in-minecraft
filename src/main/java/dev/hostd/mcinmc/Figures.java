package dev.hostd.mcinmc;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ProfileComponent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;

public final class Figures {

    public static final String TAG = "mcinmc_figure";
    private static final int ALL_SLOTS_LOCKED = 4144959;

    private static final Map<UUID, ArmorStandEntity> FIGURES = new HashMap<>();
    private static final Map<UUID, String> NAMES = new HashMap<>();
    private static final Map<UUID, UUID> HELD = new HashMap<>();

    private Figures() {
    }

    static void start(ServerWorld table) {
        List<Entity> stale = new ArrayList<>();
        for (Entity entity : table.iterateEntities()) {
            if (entity.getCommandTags().contains(TAG)) {
                stale.add(entity);
            }
        }
        stale.forEach(Entity::discard);
    }

    static void reset() {
        FIGURES.values().forEach(Entity::discard);
        stop();
    }

    static void stop() {
        FIGURES.clear();
        NAMES.clear();
        HELD.clear();
    }

    public static UUID playerOf(Entity figure) {
        if (!isFigure(figure)) {
            return null;
        }
        for (Map.Entry<UUID, ArmorStandEntity> entry : FIGURES.entrySet()) {
            if (entry.getValue() == figure) {
                return entry.getKey();
            }
        }
        return null;
    }

    public static String nameOf(UUID id) {
        return NAMES.getOrDefault(id, "someone");
    }

    public static boolean isFigure(Entity entity) {
        return entity instanceof ArmorStandEntity && entity.getCommandTags().contains(TAG);
    }

    public static void hold(ServerPlayerEntity admin, UUID target) {
        HELD.put(admin.getUuid(), target);
    }

    public static UUID release(ServerPlayerEntity admin) {
        return HELD.remove(admin.getUuid());
    }

    public static boolean isHolding(ServerPlayerEntity admin) {
        return HELD.containsKey(admin.getUuid());
    }

    static void tick(List<Source.Seen> seen, Cells.Frame frame, ServerWorld table, MinecraftServer outer) {
        List<UUID> ids = new ArrayList<>();
        int centreX = frame.centreCellX();
        int centreZ = frame.centreCellZ();
        int edge = Cells.cellRadius() + 1;
        for (Source.Seen player : seen) {
            ids.add(player.id());
            NAMES.put(player.id(), player.profile().getName());
            ArmorStandEntity figure = FIGURES.get(player.id());
            if (figure == null || figure.isRemoved()) {
                figure = spawn(table, player, frame);
                FIGURES.put(player.id(), figure);
            }
            UUID holder = holderOf(player.id());
            if (holder != null) {
                ServerPlayerEntity admin = outer.getPlayerManager().getPlayer(holder);
                if (admin != null && Table.isOnTable(admin)) {
                    Vec3d look = admin.raycast(6.0, 1.0F, false).getPos();
                    figure.refreshPositionAndAngles(look.x, look.y, look.z, admin.getYaw(), 0.0F);
                    continue;
                }
                HELD.remove(holder);
            }
            double x = frame.cellX(player.x());
            double y = Cells.standingCellY(player.y());
            double z = frame.cellZ(player.z());
            boolean off = false;
            if (x < centreX - edge || x > centreX + edge + 1 || z < centreZ - edge || z > centreZ + edge + 1) {
                off = true;
                x = Math.clamp(x, centreX - edge + 0.5, centreX + edge + 0.5);
                z = Math.clamp(z, centreZ - edge + 0.5, centreZ + edge + 0.5);
                y = Table.rimTop();
            }
            figure.setCustomName(Text.literal(player.profile().getName() + (off ? " (off the table)" : "")));
            figure.refreshPositionAndAngles(x, y, z, player.yaw(), 0.0F);
            figure.setHeadYaw(player.yaw());
        }
        FIGURES.entrySet().removeIf(entry -> {
            if (ids.contains(entry.getKey())) {
                return false;
            }
            entry.getValue().discard();
            NAMES.remove(entry.getKey());
            return true;
        });
    }

    private static UUID holderOf(UUID target) {
        for (Map.Entry<UUID, UUID> entry : HELD.entrySet()) {
            if (entry.getValue().equals(target)) {
                return entry.getKey();
            }
        }
        return null;
    }

    private static ArmorStandEntity spawn(ServerWorld table, Source.Seen player, Cells.Frame frame) {
        ArmorStandEntity figure = EntityType.ARMOR_STAND.create(table);
        NbtCompound nbt = new NbtCompound();
        nbt.putBoolean("Small", true);
        nbt.putBoolean("NoBasePlate", true);
        nbt.putBoolean("ShowArms", true);
        nbt.putInt("DisabledSlots", ALL_SLOTS_LOCKED);
        figure.readCustomDataFromNbt(nbt);
        figure.setInvulnerable(true);
        figure.setNoGravity(true);
        figure.setCustomName(Text.literal(player.profile().getName()));
        figure.setCustomNameVisible(true);
        figure.addCommandTag(TAG);
        ItemStack head = new ItemStack(Items.PLAYER_HEAD);
        head.set(DataComponentTypes.PROFILE, new ProfileComponent(player.profile()));
        figure.equipStack(EquipmentSlot.HEAD, head);
        figure.refreshPositionAndAngles(frame.cellX(player.x()),
                Cells.standingCellY(player.y()), frame.cellZ(player.z()), player.yaw(), 0.0F);
        table.spawnEntity(figure);
        return figure;
    }
}
