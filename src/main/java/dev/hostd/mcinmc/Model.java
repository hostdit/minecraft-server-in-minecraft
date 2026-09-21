package dev.hostd.mcinmc;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.LeavesBlock;
import net.minecraft.registry.tag.BlockTags;

public final class Model {

    public interface Reader {
        BlockState get(int x, int y, int z);
    }

    private Model() {
    }

    public static BlockState cube(Reader reader, int rx0, int ry0, int rz0) {
        Map<BlockState, Integer> counts = new HashMap<>();
        for (int y = ry0 + Cells.SCALE - 1; y >= ry0; y--) {
            counts.clear();
            for (int z = rz0; z < rz0 + Cells.SCALE; z++) {
                for (int x = rx0; x < rx0 + Cells.SCALE; x++) {
                    BlockState state = reader.get(x, y, z);
                    if (qualifies(state)) {
                        counts.merge(state, 1, Integer::sum);
                    }
                }
            }
            if (!counts.isEmpty()) {
                BlockState best = null;
                int bestCount = 0;
                for (Map.Entry<BlockState, Integer> entry : counts.entrySet()) {
                    if (entry.getValue() > bestCount) {
                        best = entry.getKey();
                        bestCount = entry.getValue();
                    }
                }
                return simplify(best);
            }
        }
        return Blocks.AIR.getDefaultState();
    }

    public static boolean isModelBlock(BlockState state) {
        return state.isAir() || qualifies(state) || state.isOf(Blocks.LIGHT_BLUE_STAINED_GLASS);
    }

    public static boolean qualifies(BlockState state) {
        if (state.isAir()) {
            return false;
        }
        return state.isOpaque() || !state.getFluidState().isEmpty() || state.isIn(BlockTags.LEAVES)
                || state.isOf(Blocks.SNOW) || state.isOf(Blocks.ICE);
    }

    public static BlockState simplify(BlockState state) {
        Block block = state.getBlock();
        if (!state.getFluidState().isEmpty()) {
            return state.getFluidState().isIn(net.minecraft.registry.tag.FluidTags.LAVA)
                    ? Blocks.MAGMA_BLOCK.getDefaultState()
                    : Blocks.LIGHT_BLUE_STAINED_GLASS.getDefaultState();
        }
        if (block == Blocks.SNOW || block == Blocks.SNOW_BLOCK || block == Blocks.POWDER_SNOW) {
            return Blocks.SNOW_BLOCK.getDefaultState();
        }
        if (block == Blocks.ICE || block == Blocks.FROSTED_ICE) {
            return Blocks.PACKED_ICE.getDefaultState();
        }
        if (block == Blocks.SAND) {
            return Blocks.SANDSTONE.getDefaultState();
        }
        if (block == Blocks.RED_SAND) {
            return Blocks.RED_SANDSTONE.getDefaultState();
        }
        if (block == Blocks.GRAVEL) {
            return Blocks.TUFF.getDefaultState();
        }
        if (state.isIn(BlockTags.LEAVES) && state.contains(LeavesBlock.PERSISTENT)) {
            return block.getDefaultState().with(LeavesBlock.PERSISTENT, true);
        }
        BlockState plain = block.getDefaultState();
        if (plain.hasBlockEntity() || block instanceof net.minecraft.block.FallingBlock) {
            return Blocks.OAK_PLANKS.getDefaultState();
        }
        return plain;
    }
}
