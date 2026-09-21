package dev.hostd.mcinmc;

import com.mojang.authlib.GameProfile;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.imageio.ImageIO;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerMetadata;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

public final class Status {

    private static final int ICON_SIZE = 64;

    private static Path iconPath;
    private static long iconModified;
    private static Optional<ServerMetadata.Favicon> icon = Optional.empty();

    private Status() {
    }

    public static ServerMetadata metadata(Door door) {
        MinecraftServer server = Host.server();
        String pid = "pid " + ProcessHandle.current().pid();
        MutableText description;
        List<GameProfile> sample = new ArrayList<>();
        int max = 1;
        if (server == null) {
            description = Text.literal(door.table() ? "The Table" : "A Minecraft server in Minecraft")
                    .formatted(door.table() ? Formatting.GOLD : Formatting.AQUA, Formatting.BOLD)
                    .append(Text.literal(" · waiting for " + Host.name() + " to open a world").formatted(Formatting.GRAY))
                    .append(Text.literal("\n"))
                    .append(Text.literal(pid).formatted(Formatting.DARK_GRAY));
        } else if (door.table()) {
            description = Text.literal("The Table").formatted(Formatting.GOLD, Formatting.BOLD)
                    .append(Text.literal(" · a live model of " + Host.name() + "'s world").formatted(Formatting.GRAY))
                    .append(Text.literal("\n"));
        } else {
            description = Text.literal("A Minecraft server in Minecraft").formatted(Formatting.AQUA, Formatting.BOLD)
                    .append(Text.literal(" · " + Host.name() + "'s singleplayer world").formatted(Formatting.GRAY))
                    .append(Text.literal("\n"));
        }
        if (server != null) {
            max = server.getMaxPlayerCount();
            for (ServerPlayerEntity player : List.copyOf(server.getPlayerManager().getPlayerList())) {
                if (!door.table() || Table.isOnTable(player)) {
                    sample.add(new GameProfile(player.getUuid(), player.getGameProfile().getName()));
                }
            }
            String line = door.table()
                    ? sample.size() + " on the table · " + server.getSaveProperties().getLevelName() + " · " + pid
                    : Host.worldLine(server);
            description.append(Text.literal(line).formatted(Formatting.WHITE));
        }
        return new ServerMetadata(description,
                Optional.of(new ServerMetadata.Players(max, sample.size(), sample)),
                Optional.of(ServerMetadata.Version.create()),
                favicon(server), false);
    }

    private static synchronized Optional<ServerMetadata.Favicon> favicon(MinecraftServer server) {
        if (server == null) {
            return Optional.empty();
        }
        Optional<Path> path = server.getIconFile().filter(Files::isRegularFile);
        if (path.isEmpty()) {
            return Optional.empty();
        }
        try {
            long modified = Files.getLastModifiedTime(path.get()).toMillis();
            if (path.get().equals(iconPath) && modified == iconModified) {
                return icon;
            }
            iconPath = path.get();
            iconModified = modified;
            icon = Optional.ofNullable(loadIcon(path.get()));
        } catch (IOException e) {
            icon = Optional.empty();
        }
        return icon;
    }

    private static ServerMetadata.Favicon loadIcon(Path path) throws IOException {
        BufferedImage image = ImageIO.read(path.toFile());
        if (image == null) {
            return null;
        }
        if (image.getWidth() != ICON_SIZE || image.getHeight() != ICON_SIZE) {
            BufferedImage scaled = new BufferedImage(ICON_SIZE, ICON_SIZE, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = scaled.createGraphics();
            g.drawImage(image, 0, 0, ICON_SIZE, ICON_SIZE, null);
            g.dispose();
            image = scaled;
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(image, "png", bytes);
        return new ServerMetadata.Favicon(bytes.toByteArray());
    }
}
