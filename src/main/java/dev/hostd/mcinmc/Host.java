package dev.hostd.mcinmc;

import com.mojang.authlib.GameProfile;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;

public final class Host {

    private static final Map<UUID, String> GUESTS = new ConcurrentHashMap<>();
    private static volatile MinecraftServer server;
    private static volatile Function<MinecraftServer, String> worldLine = Host::defaultWorldLine;

    private Host() {
    }

    public static void init() {
        ServerLifecycleEvents.SERVER_STARTING.register(s -> {
            if (!Nested.isNested(s)) {
                server = s;
            }
        });
        ServerLifecycleEvents.SERVER_STARTED.register(s -> {
            if (!Nested.isNested(s)) {
                Listener.open(Door.TABLE);
            }
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(s -> {
            if (!Nested.isNested(s)) {
                Listener.close(Door.TABLE);
                GUESTS.clear();
                Fleet.stopAll(s, "The world hosting this server closed");
            }
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(s -> {
            if (!Nested.isNested(s)) {
                server = null;
            }
        });
        ServerPlayConnectionEvents.JOIN.register((handler, sender, s) -> {
            if (!Nested.isNested(s) && !s.isHost(handler.player.getGameProfile())) {
                GUESTS.put(handler.player.getUuid(), handler.player.getGameProfile().getName());
            }
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, s) -> GUESTS.remove(handler.player.getUuid()));
    }

    public static MinecraftServer server() {
        MinecraftServer s = server;
        if (s == null || !s.isRunning() || s.getOverworld() == null) {
            return null;
        }
        return s;
    }

    private static volatile String fallbackName = "the server";

    public static void nameWhenIdle(String name) {
        fallbackName = name;
    }

    public static String name() {
        MinecraftServer s = server;
        GameProfile profile = s == null ? null : s.getHostProfile();
        return profile == null ? fallbackName : profile.getName();
    }

    public static boolean isHostName(String name) {
        MinecraftServer s = server;
        GameProfile profile = s == null ? null : s.getHostProfile();
        return profile != null && profile.getName().equalsIgnoreCase(name);
    }

    public static List<String> guestNames() {
        List<String> names = new ArrayList<>(GUESTS.values());
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    public static void describeWorldWith(Function<MinecraftServer, String> line) {
        worldLine = line;
    }

    public static String worldLine(MinecraftServer s) {
        return worldLine.apply(s);
    }

    public static String defaultWorldLine(MinecraftServer s) {
        ServerWorld world = s.getOverworld();
        String weather = world.isThundering() ? "storm" : world.isRaining() ? "rain" : "clear";
        return s.getSaveProperties().getLevelName() + " · day " + world.getTimeOfDay() / 24000
                + " · " + weather + " · pid " + ProcessHandle.current().pid();
    }
}
