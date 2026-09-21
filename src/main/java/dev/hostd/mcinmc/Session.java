package dev.hostd.mcinmc;

import com.mojang.authlib.GameProfile;
import com.mojang.serialization.DynamicOps;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.listener.ClientConfigurationPacketListener;
import net.minecraft.network.listener.ServerConfigurationPacketListener;
import net.minecraft.network.listener.ServerHandshakePacketListener;
import net.minecraft.network.listener.ServerLoginPacketListener;
import net.minecraft.network.listener.ServerQueryPacketListener;
import net.minecraft.network.packet.BrandCustomPayload;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.common.ClientOptionsC2SPacket;
import net.minecraft.network.packet.c2s.common.SyncedClientOptions;
import net.minecraft.network.packet.c2s.config.ReadyC2SPacket;
import net.minecraft.network.packet.c2s.config.SelectKnownPacksC2SPacket;
import net.minecraft.network.packet.c2s.handshake.HandshakeC2SPacket;
import net.minecraft.network.packet.c2s.login.EnterConfigurationC2SPacket;
import net.minecraft.network.packet.c2s.login.LoginHelloC2SPacket;
import net.minecraft.network.packet.c2s.query.QueryPingC2SPacket;
import net.minecraft.network.packet.c2s.query.QueryRequestC2SPacket;
import net.minecraft.network.packet.s2c.common.CustomPayloadS2CPacket;
import net.minecraft.network.packet.s2c.common.SynchronizeTagsS2CPacket;
import net.minecraft.network.packet.s2c.config.DynamicRegistriesS2CPacket;
import net.minecraft.network.packet.s2c.config.FeaturesS2CPacket;
import net.minecraft.network.packet.s2c.config.ReadyS2CPacket;
import net.minecraft.network.packet.s2c.config.SelectKnownPacksS2CPacket;
import net.minecraft.network.packet.s2c.login.LoginCompressionS2CPacket;
import net.minecraft.network.packet.s2c.login.LoginDisconnectS2CPacket;
import net.minecraft.network.packet.s2c.login.LoginSuccessS2CPacket;
import net.minecraft.network.packet.s2c.query.PingResultS2CPacket;
import net.minecraft.network.packet.s2c.query.QueryResponseS2CPacket;
import net.minecraft.network.state.ConfigurationStates;
import net.minecraft.network.state.HandshakeStates;
import net.minecraft.network.state.LoginStates;
import net.minecraft.network.state.PlayStateFactories;
import net.minecraft.network.state.QueryStates;
import net.minecraft.registry.CombinedDynamicRegistries;
import net.minecraft.registry.SerializableRegistries;
import net.minecraft.registry.ServerDynamicRegistryType;
import net.minecraft.registry.VersionedIdentifier;
import net.minecraft.registry.tag.TagPacketSerializer;
import net.minecraft.resource.featuretoggle.FeatureFlags;
import net.minecraft.server.PlayerManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ConnectedClientData;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

public final class Session implements Runnable {

    public static final int PROTOCOL = 767;
    public static final int COMPRESSION_THRESHOLD = 256;
    private static final int READ_BUFFER = 1 << 16;

    private final Socket socket;
    private final Door door;

    public Session(Socket socket, Door door) {
        this.socket = socket;
        this.door = door;
    }

    @Override
    public void run() {
        try (Socket s = socket) {
            InputStream in = new BufferedInputStream(s.getInputStream(), READ_BUFFER);
            OutputStream out = new BufferedOutputStream(s.getOutputStream(), READ_BUFFER);
            Conn conn = new Conn(in, out);
            Packet<? super ServerHandshakePacketListener> first = conn.receive(HandshakeStates.C2S);
            if (!(first instanceof HandshakeC2SPacket handshake)) {
                return;
            }
            switch (handshake.intendedState()) {
                case STATUS -> status(conn);
                case LOGIN, TRANSFER -> login(conn, in, out, handshake.protocolVersion());
                default -> {
                }
            }
        } catch (IOException e) {
            System.out.println("[mcinmc] session ended: " + e.getMessage());
        }
    }

    private void status(Conn conn) throws IOException {
        while (true) {
            Packet<? super ServerQueryPacketListener> p = conn.receive(QueryStates.C2S);
            if (p instanceof QueryRequestC2SPacket) {
                conn.send(QueryStates.S2C, new QueryResponseS2CPacket(Status.metadata(door)));
            } else if (p instanceof QueryPingC2SPacket ping) {
                conn.send(QueryStates.S2C, new PingResultS2CPacket(ping.getStartTime()));
                return;
            } else {
                return;
            }
        }
    }

    private void login(Conn conn, InputStream in, OutputStream out, int protocol) throws IOException {
        Packet<? super ServerLoginPacketListener> p = conn.receive(LoginStates.C2S);
        if (!(p instanceof LoginHelloC2SPacket hello)) {
            throw new IOException("expected login hello, got " + p);
        }
        String name = hello.name();
        System.out.println("[mcinmc] " + door.label() + " login: " + name + " from " + socket.getRemoteSocketAddress());

        if (protocol != PROTOCOL) {
            refuse(conn, "This server runs 1.21.1, you're on protocol " + protocol);
            return;
        }
        MinecraftServer server = Host.server();
        if (server == null) {
            refuse(conn, Host.name() + " hasn't opened a world yet");
            return;
        }
        if (Host.isHostName(name)) {
            refuse(conn, "That's the host's name, pick another");
            return;
        }
        GameProfile profile = Profiles.resolve(server, name);
        if (server.getPlayerManager().getPlayer(profile.getId()) != null) {
            refuse(conn, PlayerManager.DUPLICATE_LOGIN_TEXT.getString());
            return;
        }

        conn.send(LoginStates.S2C, new LoginCompressionS2CPacket(COMPRESSION_THRESHOLD));
        conn.enableCompression(COMPRESSION_THRESHOLD);
        conn.send(LoginStates.S2C, new LoginSuccessS2CPacket(profile, false));
        while (true) {
            Packet<? super ServerLoginPacketListener> q = conn.receive(LoginStates.C2S);
            if (q instanceof EnterConfigurationC2SPacket) {
                break;
            }
        }

        SyncedClientOptions options = configure(conn, server);
        GuestConnection guest = join(server, profile, options, out);
        pump(in, guest);
    }

    private static void refuse(Conn conn, String reason) throws IOException {
        conn.send(LoginStates.S2C, new LoginDisconnectS2CPacket(Text.literal(reason)));
    }

    private static SyncedClientOptions configure(Conn conn, MinecraftServer server) throws IOException {
        SyncedClientOptions options = SyncedClientOptions.createDefault();
        conn.send(ConfigurationStates.S2C,
                new CustomPayloadS2CPacket(new BrandCustomPayload(server.getServerModName())));
        conn.send(ConfigurationStates.S2C, new FeaturesS2CPacket(
                FeatureFlags.FEATURE_MANAGER.toId(server.getSaveProperties().getEnabledFeatures())));

        List<VersionedIdentifier> known = server.getResourceManager().streamResourcePacks()
                .flatMap(pack -> pack.getInfo().knownPackInfo().stream())
                .toList();
        conn.send(ConfigurationStates.S2C, new SelectKnownPacksS2CPacket(known));

        Set<VersionedIdentifier> common = Set.of();
        while (true) {
            Packet<? super ServerConfigurationPacketListener> q = conn.receive(ConfigurationStates.C2S);
            if (q instanceof ClientOptionsC2SPacket clientOptions) {
                options = clientOptions.options();
            } else if (q instanceof SelectKnownPacksC2SPacket selected) {
                if (selected.knownPacks().equals(known)) {
                    common = Set.copyOf(known);
                }
                break;
            }
        }

        CombinedDynamicRegistries<ServerDynamicRegistryType> registries = server.getCombinedDynamicRegistries();
        DynamicOps<NbtElement> ops = registries.getCombinedRegistryManager().getOps(NbtOps.INSTANCE);
        List<Packet<? super ClientConfigurationPacketListener>> sync = new ArrayList<>();
        SerializableRegistries.forEachSyncedRegistry(ops,
                registries.getSucceedingRegistryManagers(ServerDynamicRegistryType.WORLDGEN), common,
                (key, entries) -> sync.add(new DynamicRegistriesS2CPacket(key, entries)));
        for (Packet<? super ClientConfigurationPacketListener> packet : sync) {
            conn.send(ConfigurationStates.S2C, packet);
        }
        conn.send(ConfigurationStates.S2C,
                new SynchronizeTagsS2CPacket(TagPacketSerializer.serializeTags(registries)));
        conn.send(ConfigurationStates.S2C, ReadyS2CPacket.INSTANCE);

        while (true) {
            Packet<? super ServerConfigurationPacketListener> q = conn.receive(ConfigurationStates.C2S);
            if (q instanceof ClientOptionsC2SPacket clientOptions) {
                options = clientOptions.options();
            } else if (q instanceof ReadyC2SPacket) {
                return options;
            }
        }
    }

    private GuestConnection join(MinecraftServer server, GameProfile profile,
                                 SyncedClientOptions options, OutputStream out) throws IOException {
        GuestConnection guest = new GuestConnection(socket.getRemoteSocketAddress(), out);
        try {
            server.submit(() -> {
                PlayerManager players = server.getPlayerManager();
                guest.setCompressionThreshold(COMPRESSION_THRESHOLD, false);
                guest.transitionOutbound(PlayStateFactories.S2C.bind(
                        RegistryByteBuf.makeFactory(server.getRegistryManager())));
                server.getNetworkIo().getConnections().add(guest);
                ServerPlayerEntity player = players.createPlayer(profile, options);
                players.onPlayerConnect(guest, player, new ConnectedClientData(profile, 0, options, false));
                if (door.table()) {
                    Table.arrive(player);
                } else {
                    Table.leave(player);
                }
            }).get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            guest.close();
            throw new IOException("could not place " + profile.getName() + " in the world", e);
        }
        return guest;
    }

    private static void pump(InputStream in, GuestConnection guest) throws IOException {
        byte[] buffer = new byte[READ_BUFFER];
        try {
            while (guest.isOpen()) {
                int n = in.read(buffer);
                if (n < 0) {
                    break;
                }
                byte[] chunk = new byte[n];
                System.arraycopy(buffer, 0, chunk, 0, n);
                guest.feed(chunk);
            }
        } finally {
            guest.close();
        }
    }
}
