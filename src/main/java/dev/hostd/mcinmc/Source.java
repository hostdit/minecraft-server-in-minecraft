package dev.hostd.mcinmc;

import com.mojang.authlib.GameProfile;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;

public final class Source {

    public static final String WORLD = "world";

    public record Seen(UUID id, GameProfile profile, double x, double y, double z, float yaw) {
    }

    private final String name;
    private final MinecraftServer server;
    private final Fleet.Key key;
    private volatile List<Seen> seen = List.of();

    private Source(String name, MinecraftServer server, Fleet.Key key) {
        this.name = name;
        this.server = server;
        this.key = key;
    }

    public static Source world(MinecraftServer outer) {
        return new Source(WORLD, outer, null);
    }

    public static Source of(Fleet.Key key) {
        Nested.NestedServer nested = Fleet.serverOf(key);
        return nested == null ? null : new Source(nested.spec().name(), nested, key);
    }

    public static Source named(MinecraftServer outer, String name) {
        if (name.trim().equalsIgnoreCase(WORLD)) {
            return world(outer);
        }
        Fleet.Key key = Fleet.keyNamed(name);
        return key == null ? null : of(key);
    }

    public String name() {
        return name;
    }

    public boolean local() {
        return key == null;
    }

    public int port() {
        return local() ? 0 : Fleet.spec(key).port();
    }

    public MinecraftServer server() {
        return server;
    }

    public ServerWorld world() {
        return server.getOverworld();
    }

    public boolean sameAs(Source other) {
        return other != null && other.server == server;
    }

    public boolean alive() {
        return local() || Fleet.serverOf(key) == server && server.isRunning();
    }

    public void run(Runnable task) {
        if (server.isOnThread()) {
            task.run();
        } else {
            server.execute(task);
        }
    }

    public ServerPlayerEntity player(UUID id) {
        return server.getPlayerManager().getPlayer(id);
    }

    public List<Seen> seen() {
        return seen;
    }

    public void refresh() {
        run(() -> {
            List<Seen> out = new ArrayList<>();
            for (ServerPlayerEntity player : world().getPlayers()) {
                out.add(new Seen(player.getUuid(), player.getGameProfile(),
                        player.getX(), player.getY(), player.getZ(), player.getYaw()));
            }
            seen = List.copyOf(out);
        });
    }
}
