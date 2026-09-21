package dev.hostd.mcinmc.mixin;

import dev.hostd.mcinmc.ServerSigns;
import dev.hostd.mcinmc.Table;
import net.minecraft.block.BlockState;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerWorld.class)
abstract class ServerWorldMixin {
    @Inject(method = "onBlockChanged", at = @At("HEAD"))
    private void mcinmc$onBlockChanged(BlockPos pos, BlockState oldBlock, BlockState newBlock, CallbackInfo info) {
        Table.onBlockChanged((ServerWorld) (Object) this, pos, oldBlock, newBlock);
        ServerSigns.onBlockChanged((ServerWorld) (Object) this, pos, oldBlock, newBlock);
    }
}
