package dev.hostd.mcinmc;

public final class Cells {

    public static final int SHIFT = Integer.getInteger("mcinmc.tableShift", 2);
    public static final int SCALE = 1 << SHIFT;
    public static final int REAL_MIN_Y = -64;
    public static final int REAL_HEIGHT = 384;
    public static final int TABLE_HEIGHT = REAL_HEIGHT >> SHIFT;
    public static final int TABLE_FLOOR_REAL_Y = 32;
    public static final int FIRST_CELL_Y = (TABLE_FLOOR_REAL_Y - REAL_MIN_Y) >> SHIFT;
    public static final int RADIUS = Integer.getInteger("mcinmc.tableRadius", 256);

    private Cells() {
    }

    public record Frame(int spawnX, int spawnZ, int shiftX, int shiftZ) {

        public static Frame around(int spawnX, int spawnZ, int centreCellX, int centreCellZ) {
            return new Frame(spawnX, spawnZ, centreCellX - toTable(spawnX), centreCellZ - toTable(spawnZ));
        }

        public int cellX(int realX) {
            return toTable(realX) + shiftX;
        }

        public int cellZ(int realZ) {
            return toTable(realZ) + shiftZ;
        }

        public double cellX(double realX) {
            return realX / SCALE + shiftX;
        }

        public double cellZ(double realZ) {
            return realZ / SCALE + shiftZ;
        }

        public int realX(int cellX) {
            return toReal(cellX - shiftX);
        }

        public int realZ(int cellZ) {
            return toReal(cellZ - shiftZ);
        }

        public int centreCellX() {
            return cellX(spawnX);
        }

        public int centreCellZ() {
            return cellZ(spawnZ);
        }

        public boolean inRange(int realX, int realZ) {
            return Cells.inRange(realX, realZ, spawnX, spawnZ);
        }

        public boolean cellInRange(int cellX, int cellZ) {
            return inRange(realX(cellX) + SCALE / 2, realZ(cellZ) + SCALE / 2);
        }
    }

    public static int toTable(int real) {
        return real >> SHIFT;
    }

    public static int toTableY(int realY) {
        return (realY - REAL_MIN_Y) >> SHIFT;
    }

    public static int standingCellY(double realFeetY) {
        return toTableY((int) Math.floor(realFeetY) - 1) + 1;
    }

    public static int toReal(int cell) {
        return cell << SHIFT;
    }

    public static int toRealY(int cellY) {
        return (cellY << SHIFT) + REAL_MIN_Y;
    }

    public static boolean inRange(int realX, int realZ, int spawnX, int spawnZ) {
        return Math.abs(realX - spawnX) <= RADIUS && Math.abs(realZ - spawnZ) <= RADIUS;
    }

    public static boolean cellInRange(int cellX, int cellZ, int spawnX, int spawnZ) {
        return inRange(toReal(cellX) + SCALE / 2, toReal(cellZ) + SCALE / 2, spawnX, spawnZ);
    }

    public static int cellRadius() {
        return (RADIUS >> SHIFT) + 1;
    }
}
