package com.example.entitystacker;

import com.example.entitystacker.mixin.LivingEntityInvoker;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * All Entity Stacker gameplay logic and event wiring (Minecraft 26.1.2 / Mojang mappings).
 *
 * <h2>Forge → Fabric event mapping</h2>
 * <pre>
 *   Forge / NeoForge            ->  Fabric equivalent used here
 *   -----------------------------------------------------------------------------
 *   EntityJoinLevelEvent        ->  ServerEntityEvents.ENTITY_LOAD
 *   LivingDeathEvent (cancel)   ->  ServerLivingEntityEvents.ALLOW_DEATH (return false)
 *   BabyEntitySpawnEvent        ->  (no Fabric event) AnimalEntityBreedMixin -> onBreed(...)
 *   LivingTickEvent radius scan ->  ServerTickEvents.END_WORLD_TICK, throttled per level
 * </pre>
 *
 * <h2>Version-sensitive Mojmap names</h2>
 * These were translated from the original Yarn draft; if a future 26.x mapping renames one, the
 * compiler / mixin loader will tell you exactly which to update:
 * <ul>
 *   <li>{@code Animal#getAge()} (Yarn {@code getBreedingAge}) — &lt;0 baby, 0 ready, &gt;0 cooldown</li>
 *   <li>{@code LivingEntity#dropAllDeathLoot(ServerLevel, DamageSource)} (Yarn {@code drop}) — see invoker</li>
 *   <li>{@code Animal#finalizeSpawnChildFromBreeding(...)} (Yarn 3-arg {@code breed}) — see breed mixin</li>
 *   <li>{@code Level#getAllEntities()} / {@code getEntitiesOfClass(...)} / {@code AABB#inflate(...)}</li>
 * </ul>
 *
 * We deliberately use one throttled level-tick sweep instead of a per-entity tick hook: it scales
 * far better than running a radius query on every LivingEntity every tick.
 */
public final class StackEventHandler {

    private StackEventHandler() {}

    /* ====================================================================== */
    /* Registration                                                           */
    /* ====================================================================== */

    public static void register() {
        // (1) Throttled radius sweep — the authoritative merge mechanism.
        ServerTickEvents.END_WORLD_TICK.register(StackEventHandler::onWorldTick);

        // (2) Fast path: try to merge an entity the moment it loads/spawns.
        ServerEntityEvents.ENTITY_LOAD.register(StackEventHandler::onEntityLoad);

        // (3) Combat: intercept the death of stacked entities so we can decrement instead.
        ServerLivingEntityEvents.ALLOW_DEATH.register(StackEventHandler::onAllowDeath);

        // (4) Breeding is driven by AnimalEntityBreedMixin, which calls onBreed(...).
    }

    /* ====================================================================== */
    /* Stack-count storage helpers                                            */
    /* ====================================================================== */

    /** @return the stack count, or 1 if this entity is not stacked. */
    public static int getCount(Entity e) {
        Integer v = e.getAttached(EntityStackerMod.STACK_COUNT);
        return v == null ? 1 : v;
    }

    /**
     * Single choke-point for changing a stack count: it updates the stored value AND refreshes the
     * floating name so the display can never drift out of sync.
     */
    public static void setCount(Entity e, int count) {
        if (count <= 1) {
            e.removeAttached(EntityStackerMod.STACK_COUNT);
        } else {
            e.setAttached(EntityStackerMod.STACK_COUNT, count);
        }
        refreshDisplayName(e, count);
    }

    /**
     * Renders "Cow x5" above the head. The custom name is part of the entity's synced data, so it
     * reaches every watching client automatically — no client-side render mixin needed.
     */
    public static void refreshDisplayName(Entity e, int count) {
        if (count > 1) {
            Component name = Component.empty()
                    .append(e.getType().getDescription())   // localized BASE name (not the custom name)
                    .append(Component.literal(" x" + count));
            e.setCustomName(name);
            e.setCustomNameVisible(true);
        } else {
            // Back to a single entity: drop the stack label entirely.
            e.setCustomName(null);
            e.setCustomNameVisible(false);
        }
    }

    /* ====================================================================== */
    /* Eligibility                                                            */
    /* ====================================================================== */

    /** Can this entity ever take part in stacking at all? */
    public static boolean isStackable(Entity e) {
        if (!(e instanceof Mob mob)) return false;              // excludes players, items, projectiles…
        if (!mob.isAlive() || mob.isRemoved()) return false;
        if (mob.isPassenger() || mob.isVehicle()) return false; // riding or being ridden
        if (mob.isLeashed()) return false;
        if (mob instanceof AgeableMob age && age.isBaby()) return false;         // never stack babies
        if (mob instanceof TamableAnimal tame && tame.isTame()) return false;    // never stack pets
        if (!StackConfig.isTypeAllowed(mob.getType())) return false;
        if (!StackConfig.isCategoryAllowed(mob)) return false;

        // Respect player name-tags: a single (un-stacked) mob that already carries a custom name was
        // named by a player, so leave it out of the system.
        if (getCount(mob) == 1 && mob.hasCustomName()) return false;

        return true;
    }

    /**
     * Can this entity merge <i>right now</i>? Stricter than {@link #isStackable}: an animal on its
     * post-breeding cooldown ({@code getAge() != 0}) or one actively in love is excluded until the
     * cooldown counts back down to 0.
     */
    public static boolean canMerge(Mob mob) {
        if (!isStackable(mob)) return false;
        if (mob instanceof Animal animal) {
            if (animal.isInLove()) return false;       // currently seeking a mate
            if (animal.getAge() != 0) return false;    // <0 = baby, >0 = breeding cooldown
        }
        return true;
    }

    /** Two mobs may merge only if they are the exact same type. */
    private static boolean compatible(Mob a, Mob b) {
        return a.getType() == b.getType();
        // EXTENSION POINT: also compare variants (sheep colour, cat/axolotl/horse variant, …)
        // here if you don't want visually different mobs collapsing into one stack.
    }

    /* ====================================================================== */
    /* Merging                                                                */
    /* ====================================================================== */

    /** Absorb {@code absorbed} into {@code keeper}, honouring MAX_STACK (with overflow). */
    private static void merge(Mob keeper, Mob absorbed) {
        int combined = getCount(keeper) + getCount(absorbed);
        int kept = Math.min(combined, StackConfig.MAX_STACK);
        int overflow = combined - kept;

        setCount(keeper, kept);
        if (overflow > 0) {
            // Stack is full: leave the remainder behind as its own (smaller) stack.
            setCount(absorbed, overflow);
        } else {
            absorbed.discard();   // fully consumed
        }
    }

    /* ====================================================================== */
    /* (1) Throttled radius sweep                                             */
    /* ====================================================================== */

    private static void onWorldTick(ServerLevel level) {
        // Throttle: only sweep once every MERGE_INTERVAL ticks for performance.
        if (level.getGameTime() % StackConfig.MERGE_INTERVAL != 0L) return;

        // Gather every currently-mergeable mob once, tallying how many share each type so we can
        // skip the (relatively expensive) spatial query for types that have no possible partner.
        List<Mob> candidates = new ArrayList<>();
        Map<EntityType<?>, Integer> typeCounts = new HashMap<>();
        for (Entity e : level.getAllEntities()) {
            if (e instanceof Mob mob && canMerge(mob)) {
                candidates.add(mob);
                typeCounts.merge(mob.getType(), 1, Integer::sum);
            }
        }
        if (candidates.size() < 2) return;

        final double r2 = StackConfig.RADIUS * StackConfig.RADIUS;

        for (Mob keeper : candidates) {
            if (keeper.isRemoved() || getCount(keeper) >= StackConfig.MAX_STACK) continue;

            // Cheap pre-filter: a lone mob of its type (the common case for wild wanderers) can
            // never merge, so don't bother issuing a spatial query for it.
            if (typeCounts.getOrDefault(keeper.getType(), 0) < 2) continue;

            // Broad-phase via the level's spatial entity index, then an exact sphere check.
            AABB box = keeper.getBoundingBox().inflate(StackConfig.RADIUS);
            List<Mob> nearby = level.getEntitiesOfClass(Mob.class, box, other ->
                       other != keeper
                    && !other.isRemoved()
                    && compatible(keeper, other)
                    && canMerge(other)
                    && keeper.distanceToSqr(other) <= r2);

            for (Mob other : nearby) {
                if (keeper.isRemoved() || getCount(keeper) >= StackConfig.MAX_STACK) break;
                merge(keeper, other);
                // One fewer distinct mob of this type now exists (the absorbed one was discarded).
                typeCounts.merge(keeper.getType(), -1, Integer::sum);
            }
        }
    }

    /* ====================================================================== */
    /* (2) Entity load / join                                                 */
    /* ====================================================================== */

    private static void onEntityLoad(Entity entity, ServerLevel level) {
        if (!(entity instanceof Mob mob)) return;

        // Re-apply the floating name from the persisted count after a save/reload.
        int count = getCount(mob);
        if (count > 1) refreshDisplayName(mob, count);

        if (!canMerge(mob)) return;

        // Defer the merge onto the server main-thread task queue so it runs OUTSIDE any in-progress
        // entity-list iteration (discarding an entity mid-iteration is unsafe — the backing entity
        // list rejects re-entrant modification).
        level.getServer().execute(() -> {
            // Re-validate: between the load callback and this task draining the mob may have been
            // removed or changed dimension, so re-resolve its current level rather than capturing it.
            if (mob.isRemoved() || !canMerge(mob)) return;
            if (!(mob.level() instanceof ServerLevel currentLevel)) return;

            final double r2 = StackConfig.RADIUS * StackConfig.RADIUS;
            AABB box = mob.getBoundingBox().inflate(StackConfig.RADIUS);

            // Merge INTO the largest nearby compatible stack so counts coalesce upward.
            Mob keeper = currentLevel.getEntitiesOfClass(Mob.class, box, other ->
                       other != mob
                    && !other.isRemoved()
                    && compatible(mob, other)
                    && canMerge(other)
                    && mob.distanceToSqr(other) <= r2)
                    .stream()
                    .max(Comparator.comparingInt(StackEventHandler::getCount))
                    .orElse(null);

            if (keeper != null) merge(keeper, mob);
        });
    }

    /* ====================================================================== */
    /* (3) Combat & drops                                                     */
    /* ====================================================================== */

    /**
     * @return {@code true} to allow the death to proceed normally, {@code false} to cancel it.
     */
    private static boolean onAllowDeath(LivingEntity entity, DamageSource source, float amount) {
        int count = getCount(entity);
        if (count <= 1) {
            return true;   // last one in the stack — let it die for real.
        }

        // Void / out-of-world damage recurs every tick and a survivor cannot exist below the world,
        // so "reviving" it would just grind the whole stack down one unit per tick while the dropped
        // items fall into the void and are lost. Let the stack die normally in that case.
        if (source.is(DamageTypes.FELL_OUT_OF_WORLD)) {
            return true;
        }

        // It's a real stack: consume a single unit and keep the entity alive.
        setCount(entity, count - 1);
        entity.setHealth(entity.getMaxHealth());   // revive the survivor (damage had set HP to 0)
        entity.hurtTime = 0;
        entity.deathTime = 0;
        entity.clearFire();                         // don't leave the survivor on fire, etc.

        // Reproduce vanilla's FULL death-drop sequence for exactly one unit at its location.
        // dropAllDeathLoot(ServerLevel, DamageSource) is the very method LivingEntity#die calls: it
        // rolls the loot table AND drops equipment/inventory, derives the "killed by player" flag from
        // the player-hit window (so looting bonus + player-gated drops behave like a normal kill), and
        // applies the relevant gamerules (doMobLoot, …) itself — so we must NOT pre-gate it.
        if (entity.level() instanceof ServerLevel serverLevel) {
            ((LivingEntityInvoker) entity).entitystacker$dropAllDeathLoot(serverLevel, source);
        }

        return false;   // cancel the real death/removal.
    }

    /* ====================================================================== */
    /* (4) Breeding (called from AnimalEntityBreedMixin)                      */
    /* ====================================================================== */

    /**
     * Called from {@link com.example.entitystacker.mixin.AnimalEntityBreedMixin}, which injects at the
     * HEAD of {@code Animal#finalizeSpawnChildFromBreeding(ServerLevel, Animal, AgeableMob)}. That
     * method runs ONLY after vanilla's {@code getBreedOffspring(...)} produced a child, so the
     * decrement is correctly gated on a real offspring — there is no phantom unstack when a pair fails
     * to breed. Each stacked parent loses one unit to "unstack" the individual that just bred.
     *
     * <p>Vanilla then spawns the baby, sets BOTH parents' age to 6000 (the 5-minute cooldown) and
     * resets their love state. Because {@link #canMerge} rejects any animal whose {@code getAge() != 0},
     * the freshly-bred parents cannot re-merge into a stack until that cooldown counts back to 0.</p>
     *
     * <p><b>Known limitation of the single-entity stacking model:</b> the cooldown is applied to the
     * one entity that now represents the entire remaining stack (e.g. Cow x5 → Cow x4), so the whole
     * remaining stack is gated for the cooldown and a large stack releases only one baby per cycle.
     * For per-individual breeding you would split the breeder off as its own count-1 entity that
     * carries the cooldown while the rest of the stack stays ready.</p>
     */
    public static void onBreed(ServerLevel level, Animal self, Animal partner) {
        unstackForBreeding(self);
        if (partner != null) {
            unstackForBreeding(partner);
        }
    }

    private static void unstackForBreeding(Animal parent) {
        int count = getCount(parent);
        if (count > 1) {
            setCount(parent, count - 1);
        }
    }
}
