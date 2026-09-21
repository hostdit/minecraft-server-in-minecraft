package dev.hostd.mcinmc;

import java.util.ArrayList;
import java.util.List;

import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;

public final class Hud {

    private static final int MARGIN = 4;
    private static final int PADDING = 4;
    private static final int LINE_HEIGHT = 10;
    private static final int BACKGROUND = 0x90000000;
    private static final int TITLE = 0xFF55FFFF;
    private static final int BODY = 0xFFE0E0E0;
    private static final int MUTED = 0xFFAAAAAA;

    private Hud() {
    }

    public static void init() {
        HudRenderCallback.EVENT.register(Hud::render);
    }

    private static void render(DrawContext context, net.minecraft.client.render.RenderTickCounter tickCounter) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null || mc.options.hudHidden || mc.getDebugHud().shouldShowDebugHud()) {
            return;
        }
        List<String> lines = new ArrayList<>();
        List<Integer> colours = new ArrayList<>();
        lines.add("A Minecraft server in Minecraft · pid " + ProcessHandle.current().pid());
        colours.add(TITLE);
        if (Listener.bound(Door.WORLD)) {
            lines.add("Join at " + Listener.lanAddress() + ":" + Door.WORLD.port());
            colours.add(BODY);
        } else {
            lines.add("Port " + Door.WORLD.port() + " is busy, nobody can join");
            colours.add(0xFFFF5555);
        }
        if (Listener.bound(Door.TABLE)) {
            lines.add("The Table at " + Listener.lanAddress() + ":" + Door.TABLE.port() + " · showing " + Table.shownName());
            colours.add(0xFFFFAA00);
        }
        for (String server : Fleet.describe()) {
            lines.add("Server Sign: " + server);
            colours.add(0xFF80FF80);
        }
        List<String> guests = Host.guestNames();
        lines.add(guests.isEmpty() ? "No guests yet" : "Guests: " + String.join(", ", guests));
        colours.add(guests.isEmpty() ? MUTED : BODY);

        TextRenderer font = mc.textRenderer;
        int width = 0;
        for (String line : lines) {
            width = Math.max(width, font.getWidth(line));
        }
        int x = MARGIN;
        int y = MARGIN;
        context.fill(x, y, x + width + PADDING * 2, y + lines.size() * LINE_HEIGHT + PADDING * 2 - 2, BACKGROUND);
        for (int i = 0; i < lines.size(); i++) {
            context.drawTextWithShadow(font, lines.get(i), x + PADDING, y + PADDING + i * LINE_HEIGHT, colours.get(i));
        }
    }
}
