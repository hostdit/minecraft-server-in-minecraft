package dev.hostd.mcinmc.mixin;

import dev.hostd.mcinmc.ServerSigns;
import net.minecraft.block.BlockState;
import net.minecraft.block.ComparatorBlock;
import net.minecraft.block.HorizontalFacingBlock;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ComparatorBlock.class)
abstract class ComparatorBlockMixin {
    @Inject(method = "getPower", at = @At("HEAD"), cancellable = true)
    private void mcinmc$readServerSign(World world, BlockPos pos, BlockState state, CallbackInfoReturnable<Integer> info) {
        if (!(world instanceof ServerWorld serverWorld)) {
            return;
        }
        int players = ServerSigns.comparatorOutputBehind(serverWorld, pos.offset(state.get(HorizontalFacingBlock.FACING)));
        if (players >= 0) {
            info.setReturnValue(players);
        }
    }
}
