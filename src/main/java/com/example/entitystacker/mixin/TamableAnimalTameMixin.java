package com.example.entitystacker.mixin;

import com.example.entitystacker.StackEventHandler;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Makes taming a STACKED tameable animal (wolf / cat / parrot / …) hand out exactly ONE pet instead
 * of taming the whole stack.
 *
 * <p>Without this, a "Wolf x5" stack is a single entity, so feeding it a bone (or a cat a raw fish)
 * would tame the entire stack at once — the reported bug. {@link TamableAnimal#tame(Player)} is the
 * shared method both wolves and cats call once taming actually succeeds (the random bone chance is
 * rolled before it), so injecting at its HEAD fires only on a successful tame.</p>
 *
 * <p>We do NOT cancel: instead {@link StackEventHandler#splitOffRemainderBeforeTame} spawns the
 * leftover {@code (count - 1)} as a separate UNTAMED stack and drops the interacted entity's count to
 * 1, so vanilla then tames just this one individual — which keeps its own variant, collar, sit pose,
 * hearts and owner. For a normal single (count == 1) it does nothing and vanilla tames it directly.</p>
 *
 * <p><b>Version note:</b> Mojmap target is {@code TamableAnimal#tame(Player)}; if a future 26.x
 * mapping renames it, the mixin loader will report the missing target.</p>
 */
@Mixin(TamableAnimal.class)
public abstract class TamableAnimalTameMixin {

    @Inject(method = "tame", at = @At("HEAD"))
    private void entitystacker$onTame(Player player, CallbackInfo ci) {
        StackEventHandler.splitOffRemainderBeforeTame((TamableAnimal) (Object) this);
    }
}
