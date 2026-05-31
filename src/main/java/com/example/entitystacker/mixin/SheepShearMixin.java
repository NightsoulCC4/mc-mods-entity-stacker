package com.example.entitystacker.mixin;

import com.example.entitystacker.StackEventHandler;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Makes shearing a STACKED sheep peel exactly ONE sheep off the stack instead of leaving the whole
 * stack untouched.
 *
 * <p>A stack is a SINGLE entity, so shearing "Sheep x5" would otherwise just shear that one display
 * entity and drop one fleece while the count never changed — the reported bug (the merged stack didn't
 * split out a sheared sheep). We hook {@link Sheep#shear} at its HEAD and, for a real stack
 * (count &gt; 1), split the leftover {@code (count - 1)} off as its own UNSHEARED flock so the
 * interacted entity is left as a single sheep that vanilla then shears normally.</p>
 *
 * <p>We do NOT cancel: {@link StackEventHandler#splitOffOneForShearing} drops the interacted sheep to
 * count 1 (and marks it sheared up front to stop the leftover merging straight back), then vanilla's
 * own {@code shear} body runs and drops the wool for that one unit. For a normal single (count == 1)
 * it does nothing and vanilla shears it directly.</p>
 *
 * <p><b>Version note:</b> Mojmap target is {@code Sheep#shear(ServerLevel, SoundSource, ItemStack)};
 * if a future 26.x mapping renames it, the mixin loader will report the missing target.</p>
 */
@Mixin(Sheep.class)
public abstract class SheepShearMixin {

    @Inject(method = "shear", at = @At("HEAD"))
    private void entitystacker$onShear(ServerLevel level, SoundSource soundSource, ItemStack shears, CallbackInfo ci) {
        StackEventHandler.splitOffOneForShearing((Sheep) (Object) this);
    }
}
