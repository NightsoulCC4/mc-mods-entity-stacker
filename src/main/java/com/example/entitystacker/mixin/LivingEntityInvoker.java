package com.example.entitystacker.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Exposes {@link LivingEntity}'s {@code protected dropAllDeathLoot(ServerLevel, DamageSource)} — the
 * exact method vanilla's {@code die(...)} calls to produce a mob's death drops (Mojang mappings).
 *
 * <p>We call {@code dropAllDeathLoot(...)} (rather than just rolling the loot table) on purpose: it
 * rolls the loot table AND drops equipment/inventory (an armoured zombie's gear, a skeleton's bow,
 * etc.), derives the "killed by player" flag from the player-hit window — so the looting bonus and
 * player-gated drops match a normal kill — and applies the relevant gamerules itself. That makes a
 * "partial" stack kill drop exactly what a single un-stacked mob would.</p>
 *
 * <p><b>Version note:</b> the Mojmap signature is {@code dropAllDeathLoot(ServerLevel, DamageSource)}.
 * If a future 26.x mapping renames it, the mixin loader will fail with the missing target — update the
 * {@code @Invoker} value accordingly.</p>
 */
@Mixin(LivingEntity.class)
public interface LivingEntityInvoker {

    @Invoker("dropAllDeathLoot")
    void entitystacker$dropAllDeathLoot(ServerLevel level, DamageSource source);
}
