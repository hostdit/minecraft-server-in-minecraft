package dev.hostd.mcinmc;

import java.util.Locale;
import java.util.concurrent.TimeUnit;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.client.toast.SystemToast;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;

import dev.hostd.mcinmc.mixin.IntegratedServerAccessor;

public final class Hosting {

    private Hosting() {
    }

    public static void init() {
        Host.nameWhenIdle(MinecraftClient.getInstance().getSession().getUsername());
        Host.describeWorldWith(Hosting::worldLine);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            if (server instanceof IntegratedServer) {
                Listener.close(Door.WORLD);
            }
        });
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            if (server instanceof IntegratedServer integrated) {
                Listener.open(Door.WORLD);
                ((IntegratedServerAccessor) integrated).setLanPort(Door.WORLD.port());
            }
        });
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            if (!Nested.isNested(server) && !server.isHost(handler.player.getGameProfile())) {
                announce(handler.player, " joined your world");
            }
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            if (!Nested.isNested(server) && !server.isHost(handler.player.getGameProfile())) {
                announce(handler.player, " left your world");
            }
        });
    }

    private static void announce(ServerPlayerEntity player, String what) {
        MinecraftClient mc = MinecraftClient.getInstance();
        String name = player.getGameProfile().getName();
        mc.execute(() -> {
            SystemToast.add(mc.getToastManager(), SystemToast.Type.PERIODIC_NOTIFICATION,
                    Text.literal(name + what), Text.literal("A Minecraft server in Minecraft"));
            mc.getSoundManager().play(PositionedSoundInstance.master(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0F));
        });
    }

    private static String worldLine(MinecraftServer server) {
        String fallback = Host.defaultWorldLine(server);
        try {
            return MinecraftClient.getInstance().submit(() -> {
                MinecraftClient mc = MinecraftClient.getInstance();
                if (mc.world == null || mc.player == null) {
                    return fallback;
                }
                long day = mc.world.getTimeOfDay() / 24000;
                String biome = mc.world.getBiome(mc.player.getBlockPos()).getKey()
                        .map(key -> key.getValue().getPath().replace('_', ' '))
                        .orElse("unknown");
                String weather = mc.world.isThundering() ? "storm" : mc.world.isRaining() ? "rain" : "clear";
                String health = String.format(Locale.ROOT, "♥ %.0f/%.0f", mc.player.getHealth(), mc.player.getMaxHealth());
                return server.getSaveProperties().getLevelName() + " · day " + day + " · " + biome + " · " + weather
                        + " · " + health + " · pid " + ProcessHandle.current().pid();
            }).get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            return fallback;
        }
    }
}
