package com.example.entitystacker.mixin;

import com.example.entitystacker.StackEventHandler;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.animal.cow.MushroomCow;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Makes shearing a STACKED mooshroom peel exactly ONE off the stack instead of converting the whole
 * stack into cows.
 *
 * <p>Vanilla shears a mooshroom by {@code convertTo(EntityType.COW, ...)}: it discards the mooshroom and
 * spawns a Cow in its place, then drops mushrooms. Because a stack is a SINGLE entity, shearing
 * "Mooshroom x5" otherwise converts that one stack-bearing entity wholesale — the count and floating name
 * ride across onto the replacement cow ({@code convertTo} copies the custom name and Fabric attachments),
 * so the entire stack turns into cows at once while only one mushroom drop is rolled — the reported bug.</p>
 *
 * <p>We hook {@link MushroomCow#shear} at its HEAD and, for a real stack (count &gt; 1),
 * {@link StackEventHandler#splitOffOneForMooshroomShearing} peels the leftover {@code (count - 1)} off as
 * its own surviving mooshroom stack and reduces the interacted entity to a clean single BEFORE vanilla
 * converts it. Vanilla then converts that single into exactly ONE fresh cow and drops mushrooms normally.
 * We do NOT cancel.</p>
 *
 * <p><b>Why HEAD, unlike {@link SheepShearMixin} (RETURN):</b> a sheep survives the shear and its wool
 * loot is gated on {@code sheared:false}, so that hook must wait for vanilla to roll the wool. A mooshroom
 * does NOT survive — by RETURN it has been discarded and replaced by the cow, so there is nothing left to
 * decrement. The mushroom drop is keyed off the mooshroom's red/brown variant (untouched here), so running
 * at HEAD does not affect it.</p>
 *
 * <p>For a normal single (count == 1) the handler does nothing and vanilla's conversion stands as-is.</p>
 *
 * <p><b>Version note:</b> Mojmap target is {@code MushroomCow#shear(ServerLevel, SoundSource, ItemStack)}
 * (the {@code Shearable} interface method, same signature as {@code Sheep#shear}); if a future 26.x
 * mapping renames it, the mixin loader will report the missing target.</p>
 */
@Mixin(MushroomCow.class)
public abstract class MushroomCowShearMixin {

    @Inject(method = "shear", at = @At("HEAD"))
    private void entitystacker$onShear(ServerLevel level, SoundSource soundSource, ItemStack shears, CallbackInfo ci) {
        StackEventHandler.splitOffOneForMooshroomShearing((MushroomCow) (Object) this);
    }
}
