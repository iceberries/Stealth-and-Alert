package net.rev.stealthandalert.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.rev.stealthandalert.util.VisionWorldRevision;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Level.class)
public abstract class VisionBlockChangeMixin {
    @Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z", at = @At("RETURN"))
    private void visionBlockChanged(BlockPos pos, BlockState state, int flags, int recursion,
                                    CallbackInfoReturnable<Boolean> result) {
        if (result.getReturnValueZ()) VisionWorldRevision.changed((Level) (Object) this, pos);
    }
}
