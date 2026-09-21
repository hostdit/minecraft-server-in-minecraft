package dev.hostd.mcinmc;

public record Door(int port, String label, boolean table) {

    public static final Door WORLD = new Door(Integer.getInteger("mcinmc.port", 25565), "world", false);
    public static final Door TABLE = new Door(Integer.getInteger("mcinmc.tablePort", 25566), "table", true);
}
