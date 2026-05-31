package com.example.entitystacker.mixin;

import com.example.entitystacker.StackEventHandler;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.DyeItem;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Makes dyeing a STACKED sheep peel exactly ONE sheep off and recolour only that one, instead of
 * recolouring the whole stack.
 *
 * <p>Vanilla dyes a sheep in {@link DyeItem#interactLivingEntity}: once it has confirmed the target is a
 * living, un-sheared sheep whose colour differs from the dye, it (server-side) calls
 * {@code sheep.setColor(dye)} and shrinks the dye. Because a stack is a SINGLE entity, that recolours the
 * entire "Sheep x5" at once — the reported bug.</p>
 *
 * <p>We inject right BEFORE that {@code setColor} call (so we are already past vanilla's own
 * server-side + colour-differs guards) and hand off to {@link StackEventHandler#splitOneForDyeing}: it
 * splits the leftover {@code (count-1)} off keeping the ORIGINAL colour, and pre-applies the NEW colour
 * to the interacted sheep so it sits in a different variant group and cannot merge back. Vanilla's own
 * {@code setColor(dye)} then runs as a harmless no-op and still consumes the dye. We do NOT cancel.</p>
 *
 * <p>For a normal single (count == 1) the handler does nothing and vanilla recolours it directly. The
 * injection only fires for sheep (the only branch that calls {@code Sheep#setColor}).</p>
 *
 * <p><b>Version note:</b> Mojmap target is {@code DyeItem#interactLivingEntity(...)} calling
 * {@code Sheep#setColor(DyeColor)}; if a future 26.x mapping renames either, the mixin loader will report
 * the missing target.</p>
 */
@Mixin(DyeItem.class)
public abstract class SheepDyeMixin {

    @Inject(
            method = "interactLivingEntity",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/animal/sheep/Sheep;setColor(Lnet/minecraft/world/item/DyeColor;)V"))
    private void entitystacker$onDyeSheep(ItemStack stack, Player player, LivingEntity entity, InteractionHand hand,
                                          CallbackInfoReturnable<InteractionResult> cir) {
        if (entity instanceof Sheep sheep) {
            DyeColor color = stack.get(DataComponents.DYE);
            if (color != null) {
                StackEventHandler.splitOneForDyeing(sheep, color);
            }
        }
    }
}
