package com.example.entitystacker.mixin;

import com.example.entitystacker.StackEventHandler;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.animal.Animal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Fabric has no equivalent of Forge's {@code BabyEntitySpawnEvent}, so we hook {@link Animal}'s
 * breeding directly (Mojang mappings).
 *
 * <p><b>Why the 3-argument {@code finalizeSpawnChildFromBreeding}?</b> {@code Animal} splits breeding:</p>
 * <ul>
 *   <li>{@code spawnChildFromBreeding(ServerLevel, Animal)} — the entry point the breed goal calls. Its
 *       body is roughly {@code AgeableMob child = getBreedOffspring(...); if (child != null)
 *       finalizeSpawnChildFromBreeding(level, partner, child);}</li>
 *   <li>{@code finalizeSpawnChildFromBreeding(ServerLevel, Animal, AgeableMob child)} — the overload that
 *       actually spawns the baby, applies the 6000-tick cooldown to both parents and resets their love.</li>
 * </ul>
 *
 * <p>Injecting at the HEAD of the 3-arg method means we only unstack when a child was actually
 * produced (no phantom decrement when a pair fails to breed), and the full descriptor keeps the
 * target unambiguous.</p>
 *
 * <p><b>Version note:</b> if a future 26.x mapping renames these methods, the mixin loader will fail
 * with the exact missing target — update the {@code method =} descriptor to match.</p>
 */
@Mixin(Animal.class)
public abstract class AnimalEntityBreedMixin {

    @Inject(
            method = "finalizeSpawnChildFromBreeding(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/animal/Animal;Lnet/minecraft/world/entity/AgeableMob;)V",
            at = @At("HEAD")
    )
    private void entitystacker$onBreed(ServerLevel level, Animal partner, AgeableMob child, CallbackInfo ci) {
        Animal self = (Animal) (Object) this;
        StackEventHandler.onBreed(level, self, partner);
    }
}
