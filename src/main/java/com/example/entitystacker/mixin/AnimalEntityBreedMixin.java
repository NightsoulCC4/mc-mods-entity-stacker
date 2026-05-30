package com.example.entitystacker.mixin;

import com.example.entitystacker.StackEventHandler;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Makes a stacked animal actually breedable.
 *
 * <p>A stack is a SINGLE entity, so feeding it would only ever put one entity into love mode — yet
 * vanilla breeding needs TWO distinct animals to pair up. That is why a "Cow x5" can't breed with
 * itself (the reported bug). We hook {@link Animal#setInLove(Player)} — called when an animal is fed
 * its breeding item — and, for a real stack (count &gt; 1), split ONE individual out as a separate
 * single animal that enters love mode and serves as a genuine breeding partner.</p>
 *
 * <p>The stack itself is NOT put into love: we {@code cancel()} the original call so only the
 * split-off single breeds (see {@link StackEventHandler#splitOneForBreeding}). For a normal,
 * unstacked animal (count == 1) we do nothing and let vanilla run.</p>
 *
 * <p><b>Version note:</b> the Mojmap target is {@code Animal#setInLove(Player)} (a sibling of
 * {@code setInLoveTime(int)}, so the bare name is unambiguous). If a future 26.x mapping renames it,
 * the mixin loader will report the missing target.</p>
 */
@Mixin(Animal.class)
public abstract class AnimalEntityBreedMixin {

    @Inject(method = "setInLove", at = @At("HEAD"), cancellable = true)
    private void entitystacker$onEnterLove(Player player, CallbackInfo ci) {
        Animal self = (Animal) (Object) this;
        if (StackEventHandler.splitOneForBreeding(self, player)) {
            // The stack handled breeding by splitting off a single in-love animal — don't also put
            // the whole stack into love mode.
            ci.cancel();
        }
    }
}
