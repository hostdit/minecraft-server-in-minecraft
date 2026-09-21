package dev.hostd.mcinmc;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.yggdrasil.ServicesKeySet;
import com.mojang.serialization.Lifecycle;

import java.io.IOException;
import java.net.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import net.minecraft.datafixer.Schemas;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.SimpleRegistry;
import net.minecraft.resource.DataConfiguration;
import net.minecraft.resource.DataPackSettings;
import net.minecraft.resource.ResourcePackManager;
import net.minecraft.resource.VanillaDataPackProvider;
import net.minecraft.resource.featuretoggle.FeatureFlags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.PlayerManager;
import net.minecraft.server.SaveLoader;
import net.minecraft.server.SaveLoading;
import net.minecraft.server.WorldGenerationProgressLogger;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.ApiServices;
import net.minecraft.util.SystemDetails;
import net.minecraft.util.Util;
import net.minecraft.util.profiler.log.DebugSampleLog;
import net.minecraft.util.profiler.MultiValueDebugSampleLogImpl;
import net.minecraft.world.Difficulty;
import net.minecraft.world.GameMode;
import net.minecraft.world.GameRules;
import net.minecraft.world.dimension.DimensionOptions;
import net.minecraft.world.dimension.DimensionOptionsRegistryHolder;
import net.minecraft.world.gen.GeneratorOptions;
import net.minecraft.world.gen.WorldPresets;
import net.minecraft.world.level.LevelInfo;
import net.minecraft.world.level.LevelProperties;
import net.minecraft.world.level.storage.LevelStorage;

public final class Nested {

    public record Spec(String name, String slug, int port, int maxPlayers, long seed) {
    }

    private Nested() {
    }

    public static boolean isNested(MinecraftServer server) {
        return server instanceof NestedServer;
    }

    public static NestedServer boot(MinecraftServer outer, Spec spec) throws Exception {
        Path root = outer.getRunDirectory().resolve("servers").resolve(spec.slug());
        LevelStorage storage = LevelStorage.create(root);
        LevelStorage.Session session = storage.createSession("world");
        ResourcePackManager packs = VanillaDataPackProvider.createManager(session);
        packs.scanPacks();
        DataConfiguration dataConfiguration = new DataConfiguration(
                new DataPackSettings(new ArrayList<>(packs.getIds()), List.of()),
                FeatureFlags.FEATURE_MANAGER.getFeatureSet());
        LevelInfo info = new LevelInfo(spec.name(), GameMode.SURVIVAL, false, Difficulty.NORMAL, false, new GameRules(), dataConfiguration);
        GeneratorOptions generator = new GeneratorOptions(spec.seed(), true, false);
        SaveLoading.DataPacks dataPacks = new SaveLoading.DataPacks(packs, dataConfiguration, false, true);
        SaveLoading.ServerConfig config = new SaveLoading.ServerConfig(dataPacks, CommandManager.RegistrationEnvironment.DEDICATED, 4);
        SaveLoader loader = Util.<SaveLoader>waitAndApply(executor -> SaveLoading.load(
                config,
                context -> {
                    Registry<DimensionOptions> registry = new SimpleRegistry<>(RegistryKeys.DIMENSION, Lifecycle.stable()).freeze();
                    DimensionOptionsRegistryHolder.DimensionsConfig dimensions = context.worldGenRegistryManager()
                            .get(RegistryKeys.WORLD_PRESET)
                            .entryOf(WorldPresets.DEFAULT)
                            .value()
                            .createDimensionsRegistryHolder()
                            .toConfig(registry);
                    return new SaveLoading.LoadContext<>(
                            new LevelProperties(info, generator, dimensions.specialWorldProperty(), dimensions.getLifecycle()),
                            dimensions.toDynamicRegistryManager());
                },
                SaveLoader::new,
                Util.getMainWorkerExecutor(),
                executor)).get(120, TimeUnit.SECONDS);
        ApiServices services = new ApiServices(outer.getSessionService(), ServicesKeySet.EMPTY, outer.getGameProfileRepo(), null);
        String motd = spec.name() + " · a Server Sign in " + outer.getSaveProperties().getLevelName();
        return MinecraftServer.startServer(thread -> new NestedServer(thread, session, packs, loader, services, spec, motd));
    }

    public static final class NestedServer extends MinecraftServer {

        private final MultiValueDebugSampleLogImpl debugLog = new MultiValueDebugSampleLogImpl(4);
        private final Spec spec;
        private final String motd;

        NestedServer(Thread thread, LevelStorage.Session session, ResourcePackManager packs, SaveLoader loader,
                     ApiServices services, Spec spec, String motd) {
            super(thread, session, packs, loader, Proxy.NO_PROXY, Schemas.getFixer(), services, WorldGenerationProgressLogger::create);
            this.spec = spec;
            this.motd = motd;
        }

        public Spec spec() {
            return spec;
        }

        public void unplug(String reason) {
            submitAndJoin(() -> {
                for (ServerPlayerEntity player : List.copyOf(getPlayerManager().getPlayerList())) {
                    player.networkHandler.disconnect(Text.literal(reason));
                }
            });
        }

        @Override
        protected boolean setupServer() throws IOException {
            setPlayerManager(new PlayerManager(this, getCombinedDynamicRegistries(), saveHandler, spec.maxPlayers()) {
            });
            setOnlineMode(false);
            setFlightEnabled(true);
            setServerPort(spec.port());
            setMotd(motd);
            getNetworkIo().bind(null, spec.port());
            loadWorld();
            System.out.println("[mcinmc] server '" + spec.name() + "' up on " + spec.port() + " in pid " + ProcessHandle.current().pid());
            return true;
        }

        @Override
        public int getOpPermissionLevel() {
            return 4;
        }

        @Override
        public int getFunctionPermissionLevel() {
            return 2;
        }

        @Override
        public boolean shouldBroadcastRconToOps() {
            return false;
        }

        @Override
        protected DebugSampleLog getDebugSampleLog() {
            return debugLog;
        }

        @Override
        public boolean shouldPushTickTimeLog() {
            return false;
        }

        @Override
        public SystemDetails addExtraSystemDetails(SystemDetails details) {
            details.addSection("Type", "Server Sign '" + spec.name() + "'");
            return details;
        }

        @Override
        public boolean isDedicated() {
            return true;
        }

        @Override
        public int getRateLimit() {
            return 0;
        }

        @Override
        public boolean isUsingNativeTransport() {
            return false;
        }

        @Override
        public boolean areCommandBlocksEnabled() {
            return false;
        }

        @Override
        public boolean isRemote() {
            return true;
        }

        @Override
        public boolean shouldBroadcastConsoleToOps() {
            return false;
        }

        @Override
        public boolean isHost(GameProfile profile) {
            return false;
        }

        @Override
        public boolean acceptsTransfers() {
            return true;
        }
    }
}
