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
 * split out a sheared sheep). We hook {@link Sheep#shear} at its RETURN and, for a real stack
 * (count &gt; 1), split the leftover {@code (count - 1)} off as its own UNSHEARED flock, leaving the
 * interacted entity as the single sheep vanilla just sheared.</p>
 *
 * <p><b>Why RETURN and not HEAD:</b> the shear loot table ({@code shearing/sheep.json}) gates every
 * wool entry on {@code type_specific:{type:sheep, sheared:false}}, so the fleece only drops while the
 * sheep is still UNSHEARED — which is exactly why vanilla rolls the loot BEFORE calling
 * {@code setSheared(true)}. Injecting at HEAD and marking the sheep sheared early (to stop the leftover
 * merging back) therefore suppressed the drop entirely. Injecting at RETURN lets vanilla drop the wool
 * and set {@code sheared} first; {@link StackEventHandler#splitOffOneForShearing} then peels off the
 * still-fleeced leftover, and because the interacted sheep is ALREADY sheared by then it fails
 * {@code isStackable} (sheared sheep never stack) and cannot merge back. We do NOT cancel.</p>
 *
 * <p>For a normal single (count == 1) the handler does nothing and vanilla's shear stands as-is.</p>
 *
 * <p><b>Version note:</b> Mojmap target is {@code Sheep#shear(ServerLevel, SoundSource, ItemStack)};
 * if a future 26.x mapping renames it, the mixin loader will report the missing target.</p>
 */
@Mixin(Sheep.class)
public abstract class SheepShearMixin {

    @Inject(method = "shear", at = @At("RETURN"))
    private void entitystacker$onShear(ServerLevel level, SoundSource soundSource, ItemStack shears, CallbackInfo ci) {
        StackEventHandler.splitOffOneForShearing((Sheep) (Object) this);
    }
}
