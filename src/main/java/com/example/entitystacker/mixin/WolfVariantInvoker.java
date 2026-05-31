package com.example.entitystacker.mixin;

import net.minecraft.core.Holder;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.world.entity.animal.wolf.WolfVariant;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Exposes {@code Wolf#getVariant()}, which is {@code private} in 26.x, so the stacker can read a wolf's
 * biome variant when deciding whether two wolves are visually identical (variant-aware merge). Every
 * other variant-bearing mob the stacker cares about (cow/pig/chicken/cat/parrot) has a public getter;
 * wolf is the only one that needs an invoker.
 */
@Mixin(Wolf.class)
public interface WolfVariantInvoker {

    @Invoker("getVariant")
    Holder<WolfVariant> entitystacker$getVariant();
}
