package dev.hostd.mcinmc;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

public final class Fleet {

    public static final int FIRST_PORT = Integer.getInteger("mcinmc.fleetPort", 25567);
    private static final int MAX_NAME = 32;

    public record Key(RegistryKey<World> world, BlockPos pos) {
    }

    private static final class Entry {
        final Nested.Spec spec;
        volatile Nested.NestedServer server;

        Entry(Nested.Spec spec) {
            this.spec = spec;
        }
    }

    private static final Map<Key, Entry> ENTRIES = new ConcurrentHashMap<>();

    private Fleet() {
    }

    public static Key key(ServerWorld world, BlockPos pos) {
        return new Key(world.getRegistryKey(), pos.toImmutable());
    }

    public static boolean isUp(Key key) {
        return ENTRIES.containsKey(key);
    }

    public static boolean isRunning(Key key) {
        return serverOf(key) != null;
    }

    public static Nested.NestedServer serverOf(Key key) {
        Entry entry = ENTRIES.get(key);
        Nested.NestedServer server = entry == null ? null : entry.server;
        return server != null && server.isLoading() ? server : null;
    }

    public static Key keyNamed(String name) {
        String wanted = name.trim();
        for (Map.Entry<Key, Entry> entry : ENTRIES.entrySet()) {
            if (entry.getValue().spec.name().equalsIgnoreCase(wanted) && entry.getValue().server != null) {
                return entry.getKey();
            }
        }
        return null;
    }

    public static List<String> names() {
        List<String> names = new ArrayList<>();
        names.add(Source.WORLD);
        for (Entry entry : ENTRIES.values()) {
            if (entry.server != null) {
                names.add(entry.spec.name());
            }
        }
        return names;
    }

    public static Nested.Spec spec(Key key) {
        Entry entry = ENTRIES.get(key);
        return entry == null ? null : entry.spec;
    }

    public static int playerCount(Key key) {
        Nested.NestedServer server = serverOf(key);
        return server == null ? 0 : server.getCurrentPlayerCount();
    }

    public static void start(ServerWorld world, BlockPos pos, String name, int port, int maxPlayers) {
        Key key = key(world, pos);
        Nested.Spec spec = new Nested.Spec(name, slug(name), port, maxPlayers, pos.asLong() ^ name.hashCode());
        Entry entry = new Entry(spec);
        if (ENTRIES.putIfAbsent(key, entry) != null) {
            return;
        }
        MinecraftServer outer = world.getServer();
        Thread boot = new Thread(() -> {
            try {
                Nested.NestedServer server = Nested.boot(outer, spec);
                entry.server = server;
                if (ENTRIES.get(key) != entry) {
                    server.stop(false);
                }
            } catch (Exception e) {
                ENTRIES.remove(key, entry);
                System.out.println("[mcinmc] server '" + name + "' failed to start: " + e);
            }
        }, "mcinmc-boot-" + spec.slug());
        boot.setDaemon(true);
        boot.start();
    }

    public static void stop(Key key, String reason, boolean wait) {
        Entry entry = ENTRIES.remove(key);
        if (entry == null) {
            return;
        }
        Nested.NestedServer server = entry.server;
        if (server == null) {
            return;
        }
        System.out.println("[mcinmc] server '" + entry.spec.name() + "' stopping: " + reason);
        server.unplug(reason);
        if (wait) {
            server.stop(true);
            return;
        }
        Thread stopper = new Thread(() -> server.stop(true), "mcinmc-stop-" + entry.spec.slug());
        stopper.setDaemon(true);
        stopper.start();
    }

    public static void stopAll(MinecraftServer server, String reason) {
        for (Key key : List.copyOf(ENTRIES.keySet())) {
            stop(key, reason, true);
        }
        PORTS.remove(server);
    }

    private static final Map<MinecraftServer, Map<String, Integer>> PORTS = new ConcurrentHashMap<>();

    public static synchronized int portFor(MinecraftServer server, Key key) {
        Map<String, Integer> ports = PORTS.computeIfAbsent(server, Fleet::loadPorts);
        String id = key.world().getValue() + "@" + key.pos().getX() + "," + key.pos().getY() + "," + key.pos().getZ();
        int port = allocatePort(ports.getOrDefault(id, 0));
        if (!Integer.valueOf(port).equals(ports.get(id))) {
            ports.put(id, port);
            savePorts(server, ports);
        }
        return port;
    }

    public static List<Key> knownKeys(MinecraftServer server) {
        List<Key> keys = new ArrayList<>();
        for (String id : PORTS.computeIfAbsent(server, Fleet::loadPorts).keySet()) {
            Key key = parseKey(id);
            if (key != null) {
                keys.add(key);
            }
        }
        return keys;
    }

    public static Key parseKey(String id) {
        int at = id.lastIndexOf('@');
        if (at < 0) {
            return null;
        }
        Identifier world = Identifier.tryParse(id.substring(0, at));
        String[] parts = id.substring(at + 1).split(",");
        if (world == null || parts.length != 3) {
            return null;
        }
        try {
            BlockPos pos = new BlockPos(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
            return new Key(RegistryKey.of(RegistryKeys.WORLD, world), pos);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Path portsFile(MinecraftServer server) {
        return server.getSavePath(WorldSavePath.ROOT).resolve("mcinmc-ports.json");
    }

    private static Map<String, Integer> loadPorts(MinecraftServer server) {
        Map<String, Integer> ports = new java.util.HashMap<>();
        Path file = portsFile(server);
        if (Files.isRegularFile(file)) {
            try {
                JsonObject json = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
                for (String id : json.keySet()) {
                    ports.put(id, json.get(id).getAsInt());
                }
            } catch (IOException | RuntimeException e) {
                System.out.println("[mcinmc] could not read " + file + ": " + e);
            }
        }
        return ports;
    }

    private static void savePorts(MinecraftServer server, Map<String, Integer> ports) {
        JsonObject json = new JsonObject();
        ports.forEach(json::addProperty);
        try {
            Files.writeString(portsFile(server), json.toString());
        } catch (IOException e) {
            System.out.println("[mcinmc] could not save ports: " + e);
        }
    }

    public static Set<Integer> usedPorts() {
        Set<Integer> ports = new java.util.HashSet<>();
        for (Entry entry : ENTRIES.values()) {
            ports.add(entry.spec.port());
        }
        return ports;
    }

    public static int allocatePort(int preferred) {
        Set<Integer> used = usedPorts();
        if (preferred >= FIRST_PORT && !used.contains(preferred) && free(preferred)) {
            return preferred;
        }
        return nextPort(used, FIRST_PORT, Fleet::free);
    }

    public interface PortCheck {
        boolean free(int port);
    }

    public static int nextPort(Set<Integer> used, int from, PortCheck check) {
        int port = from;
        while (used.contains(port) || !check.free(port)) {
            port++;
        }
        return port;
    }

    private static boolean free(int port) {
        try (ServerSocket socket = new ServerSocket(port)) {
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    public static String slug(String name) {
        String slug = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (slug.length() > MAX_NAME) {
            slug = slug.substring(0, MAX_NAME);
        }
        return slug.isEmpty() ? "server" : slug;
    }

    public static List<String> describe() {
        List<String> lines = new ArrayList<>();
        for (Entry entry : ENTRIES.values()) {
            Nested.NestedServer server = entry.server;
            String state = server == null || !server.isLoading() ? "starting" : server.getCurrentPlayerCount() + " online";
            lines.add(entry.spec.name() + " :" + entry.spec.port() + " · " + state);
        }
        lines.sort(String.CASE_INSENSITIVE_ORDER);
        return lines;
    }
}
