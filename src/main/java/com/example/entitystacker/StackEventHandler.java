package com.example.entitystacker;

import com.example.entitystacker.mixin.LivingEntityInvoker;
import com.example.entitystacker.mixin.WolfVariantInvoker;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.Holder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Leashable;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.chicken.Chicken;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.entity.animal.cow.MushroomCow;
import net.minecraft.world.entity.animal.feline.Cat;
import net.minecraft.world.entity.animal.parrot.Parrot;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * All Entity Stacker gameplay logic and event wiring (Minecraft 26.1.2 / Mojang mappings).
 *
 * <h2>Forge → Fabric event mapping</h2>
 * <pre>
 *   Forge / NeoForge            ->  Fabric equivalent used here
 *   -----------------------------------------------------------------------------
 *   EntityJoinLevelEvent        ->  ServerEntityEvents.ENTITY_LOAD
 *   LivingDeathEvent (cancel)   ->  ServerLivingEntityEvents.ALLOW_DEATH (return false)
 *   BabyEntitySpawnEvent        ->  (no Fabric event) AnimalEntityBreedMixin hooks Animal#setInLove
 *   LivingTickEvent radius scan ->  ServerTickEvents.END_LEVEL_TICK, throttled per level
 * </pre>
 *
 * <h2>Version-sensitive Mojmap names</h2>
 * These were translated from the original Yarn draft; if a future 26.x mapping renames one, the
 * compiler / mixin loader will tell you exactly which to update:
 * <ul>
 *   <li>{@code Animal#getAge()} (Yarn {@code getBreedingAge}) — &lt;0 baby, 0 ready, &gt;0 cooldown</li>
 *   <li>{@code LivingEntity#dropAllDeathLoot(ServerLevel, DamageSource)} (Yarn {@code drop}) — see invoker</li>
 *   <li>{@code Animal#setInLove(Player)} / {@code EntityType#create(Level, EntitySpawnReason)} / {@code Entity#snapTo(...)} — breeding split</li>
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
        ServerTickEvents.END_LEVEL_TICK.register(StackEventHandler::onWorldTick);

        // (2) Fast path: try to merge an entity the moment it loads/spawns.
        ServerEntityEvents.ENTITY_LOAD.register(StackEventHandler::onEntityLoad);

        // (3) Combat: intercept the death of stacked entities so we can decrement instead.
        ServerLivingEntityEvents.ALLOW_DEATH.register(StackEventHandler::onAllowDeath);

        // (4) Breeding is driven by AnimalEntityBreedMixin, which calls splitOneForBreeding(...).
        // (5) Taming is driven by TamableAnimalTameMixin, which calls splitOffRemainderBeforeTame(...).
        // (6) Sheep shearing is driven by SheepShearMixin, which calls splitOffOneForShearing(...).
        // (7) Mooshroom shearing is driven by MushroomCowShearMixin, which calls splitOffOneForMooshroomShearing(...).
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
        if (!StackConfig.isMobStackingEnabled(mob.getType())) return false;      // player toggle (GUI/command)
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

    /**
     * Make {@code target} a clean, INDEPENDENT copy of {@code source}'s appearance.
     *
     * <p>26.x gives many mobs a biome-driven VARIANT (cow, pig, chicken, wolf, …). A brand-new entity
     * from {@code EntityType#create} always carries the type DEFAULT variant, so a naively split-off
     * unit loses the look of the stack it came from. Rather than poke each mob's variant setter (several
     * are {@code private}: Pig/Wolf/Cat/Parrot), we round-trip the source through its own NBT: the
     * variant is part of the saved data, so this copies it for EVERY variant-bearing type at once
     * (and brings along colour, attributes, etc.).</p>
     *
     * <p>The round-trip also drags along state that belongs to the <i>individual</i> source, not to a
     * fresh split-off unit, so we scrub it afterwards to leave a clean baseline:
     * <ul>
     *   <li>a fresh UUID — reusing the source's would make the game treat them as the same entity and
     *       discard one;</li>
     *   <li>full health — otherwise a damaged stack would spawn damaged split units;</li>
     *   <li>no leash / passengers — a split unit must not share the source's lead or riders (a shared
     *       lead would also dupe the item); {@code removeLeash()} clears the link without dropping one;</li>
     *   <li>adult age and no love timer — a split unit is a fresh, mergeable adult; the breeding caller
     *       re-applies love itself.</li>
     * </ul>
     * {@code source} is not modified. Callers still set the stack count, name and position.</p>
     */
    private static void copyDataFrom(Mob target, Mob source, ServerLevel level) {
        TagValueOutput out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
        source.saveWithoutId(out);
        CompoundTag data = out.buildResult();
        target.load(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), data));

        // Scrub per-individual / relational state so the copy is a clean, independent unit.
        target.setUUID(UUID.randomUUID());
        target.setHealth(target.getMaxHealth());
        target.ejectPassengers();
        if (target instanceof Leashable leashable && leashable.isLeashed()) {
            leashable.removeLeash();                        // clear the inherited lead WITHOUT dropping one
        }
        if (target instanceof AgeableMob ageable) ageable.setAge(0);       // fresh adult, not a baby/cooldown
        if (target instanceof Animal animal) animal.setInLoveTime(0);      // no inherited love timer
    }

    /**
     * @return whether {@code other} belongs to the same stack group as a keeper identified by its
     *         pre-computed {@code (type, variantKey)} — i.e. the same entity type AND the same visible
     *         variant. Taking the keeper's key pre-computed lets the hot merge loops compute it once per
     *         keeper instead of once per candidate.
     *
     * <p>Variant-awareness is what keeps a white sheep and a black sheep (or a warm cow and a cold cow)
     * in separate stacks. Because the key is read live, re-colouring/converting a mob (e.g. dyeing a
     * white sheep black) moves it into its new variant's group on the next merge pass — the requested
     * "dye it and it stacks normally" behaviour, with no special-casing.</p>
     */
    private static boolean sameGroup(EntityType<?> type, String variant, Mob other) {
        return other.getType() == type && variant.equals(variantKey(other));
    }

    /**
     * A stable string identifying a mob's visible variant, or {@code ""} for mobs with no tracked variant
     * (those stack by type alone, as before). Covers the biome/colour variants added in 26.x. Wolf's
     * {@code getVariant()} is {@code private}, so it is read through {@link WolfVariantInvoker}; every
     * other type here has a public getter.
     */
    private static String variantKey(Mob m) {
        // Wool colour AND sheared state: a freshly-sheared sheep must NOT stack back into a fleeced flock
        // (that would undo the shear split and let the stack be re-sheared). It rejoins once it regrows.
        if (m instanceof Sheep s) return "sheep:" + s.getColor() + (s.isSheared() ? ":sheared" : "");
        if (m instanceof MushroomCow mc) return "mooshroom:" + mc.getVariant();   // red / brown
        if (m instanceof Cow c) return holderKey(c.getVariant());
        if (m instanceof Pig p) return holderKey(p.getVariant());
        if (m instanceof Chicken ch) return holderKey(ch.getVariant());
        if (m instanceof Cat ct) return holderKey(ct.getVariant());
        if (m instanceof Parrot pr) return "parrot:" + pr.getVariant();
        if (m instanceof Wolf w) return holderKey(((WolfVariantInvoker) w).entitystacker$getVariant());
        return "";
    }

    /** Registry id of a variant holder (e.g. "minecraft:cold"), stable across entities of that variant. */
    private static String holderKey(Holder<?> h) {
        return h == null ? "" : h.unwrapKey().map(Object::toString).orElse(h.toString());
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

        // Gather every currently-mergeable mob once, tallying how many share each stacking GROUP
        // (type + variant) so we can skip the (relatively expensive) spatial query for a mob that has no
        // possible partner — including a lone-of-its-variant mob in a mixed herd (e.g. the only black
        // sheep among whites), which a type-only tally would wrongly wave through.
        List<Mob> candidates = new ArrayList<>();
        Map<EntityType<?>, Map<String, Integer>> groupCounts = new HashMap<>();
        for (Entity e : level.getAllEntities()) {
            if (e instanceof Mob mob && canMerge(mob)) {
                candidates.add(mob);
                groupCounts.computeIfAbsent(mob.getType(), k -> new HashMap<>())
                        .merge(variantKey(mob), 1, Integer::sum);
            }
        }
        if (candidates.size() < 2) return;

        final double r2 = StackConfig.RADIUS * StackConfig.RADIUS;

        for (Mob keeper : candidates) {
            if (keeper.isRemoved() || getCount(keeper) >= StackConfig.MAX_STACK) continue;

            // Compute the keeper's variant key ONCE (not per candidate), then pre-filter: a mob with no
            // same-type-and-variant mate nearby can never merge, so skip its spatial query entirely.
            Map<String, Integer> byVariant = groupCounts.get(keeper.getType());
            String keeperVariant = variantKey(keeper);
            if (byVariant == null || byVariant.getOrDefault(keeperVariant, 0) < 2) continue;

            // Broad-phase via the level's spatial entity index, then an exact sphere check.
            AABB box = keeper.getBoundingBox().inflate(StackConfig.RADIUS);
            List<Mob> nearby = level.getEntitiesOfClass(Mob.class, box, other ->
                       other != keeper
                    && !other.isRemoved()
                    && sameGroup(keeper.getType(), keeperVariant, other)
                    && canMerge(other)
                    && keeper.distanceToSqr(other) <= r2);

            for (Mob other : nearby) {
                if (keeper.isRemoved() || getCount(keeper) >= StackConfig.MAX_STACK) break;
                merge(keeper, other);
                // One fewer distinct mob of this group now exists (the absorbed one was discarded).
                byVariant.merge(keeperVariant, -1, Integer::sum);
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
            final EntityType<?> type = mob.getType();
            final String variant = variantKey(mob);   // compute once, not per candidate

            // Merge INTO the largest nearby same-type-and-variant stack so counts coalesce upward.
            Mob keeper = currentLevel.getEntitiesOfClass(Mob.class, box, other ->
                       other != mob
                    && !other.isRemoved()
                    && sameGroup(type, variant, other)
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

        if (entity.level() instanceof ServerLevel serverLevel) {
            // (a) Make this count as a REAL kill for the killer. Because we cancel vanilla
            //     LivingEntity#die(), ALL of its kill bookkeeping is skipped — not just loot. The most
            //     visible casualty is the kill advancement (e.g. "Monster Hunter" / "Monsters Hunted"),
            //     but mob-kill STATS and SCOREBOARD kill objectives break in exactly the same way: a
            //     stacked kill silently counts for none of them, while only the final (count==1) unit
            //     dies for real and is credited. die() routes all three through
            //     killCredit.awardKillScore(victim, source) — ServerPlayer overrides it to fire the
            //     PLAYER_KILLED_ENTITY criterion, awardStat(...) and the scoreboard kill score — so we
            //     replay exactly that call for the one unit we just removed. Do it BEFORE reviving and
            //     dropping loot, while the kill credit is still fresh (the lethal blow landed this tick).
            //     Both getKillCredit() and awardKillScore(Entity, DamageSource) are public in 26.x — no
            //     mixin/invoker needed. (We deliberately do NOT replay entity#killedEntity(...): loot is
            //     already forced below, and that hook can carry side effects we don't want duplicated.)
            LivingEntity killCredit = entity.getKillCredit();
            if (killCredit != null) {
                killCredit.awardKillScore(entity, source);
            }

            // (b) Reproduce vanilla's FULL death-drop sequence for exactly one unit at its location.
            //     dropAllDeathLoot(ServerLevel, DamageSource) is the very method LivingEntity#die calls:
            //     it rolls the loot table AND drops equipment/inventory, derives the "killed by player"
            //     flag from the player-hit window (so looting bonus + player-gated drops behave like a
            //     normal kill), and applies the relevant gamerules (doMobLoot, …) itself — so we must
            //     NOT pre-gate it.
            ((LivingEntityInvoker) entity).entitystacker$dropAllDeathLoot(serverLevel, source);
        }

        // Revive the survivor (the lethal damage had driven HP to 0) and clear the death/combat state
        // so the next hit starts a clean kill — kill credit was already harvested above.
        entity.setHealth(entity.getMaxHealth());
        entity.hurtTime = 0;
        entity.deathTime = 0;
        entity.clearFire();                         // don't leave the survivor on fire, etc.

        return false;   // cancel the real death/removal.
    }

    /* ====================================================================== */
    /* (4) Breeding — split one individual out of a stack so it can breed     */
    /* ====================================================================== */

    /**
     * Called from {@link com.example.entitystacker.mixin.AnimalEntityBreedMixin} at the HEAD of
     * {@code Animal#setInLove(Player)} (fired when an animal is fed its breeding item).
     *
     * <p><b>Why this is necessary:</b> a stack is a SINGLE entity, so feeding it would only ever put
     * one entity into love mode — but vanilla breeding needs TWO distinct animals to pair up. So a
     * stack could never breed with itself, which is exactly the reported bug. Here we "unstack" one
     * individual: decrement the stack by 1 and spawn a fresh single adult of the same type that
     * enters love mode and acts as the real breeding partner.</p>
     *
     * <p>The stack itself is NOT put into love (the caller cancels the original {@code setInLove}); only
     * the split-off single breeds. Two feeds therefore yield two singles that pair and produce a baby —
     * the same two-feed cost as vanilla. After breeding, vanilla sets the singles' age to the cooldown,
     * and {@link #canMerge} (which rejects {@code getAge() != 0} and {@code isInLove()}) keeps them out
     * of any stack until the cooldown reaches 0, at which point they re-merge — so no adult is ever
     * lost, babies are simply added.</p>
     *
     * @return {@code true} if a single was split off (caller should cancel the stack's own love state);
     *         {@code false} for a normal, unstacked animal (let vanilla breed it directly).
     */
    public static boolean splitOneForBreeding(Animal self, Player player) {
        int count = getCount(self);
        if (count <= 1) return false;                       // a normal single — vanilla breeds it directly
        // Disabled type (per-mob toggle off): opt the stack out of split-on-breed too, so an already
        // existing disabled stack behaves like a frozen vanilla blob — it neither grows nor sheds
        // singles that could never re-merge (canMerge would reject them). It can still be whittled down
        // by killing one unit at a time (onAllowDeath, which is intentionally toggle-agnostic).
        if (!StackConfig.isMobStackingEnabled(self.getType())) return false;
        if (!(self.level() instanceof ServerLevel level)) return false;

        // Create the breeding partner FIRST, so we only take one out of the stack if it succeeds.
        Entity created = self.getType().create(level, EntitySpawnReason.BREEDING);
        if (!(created instanceof Animal partner)) {
            return false;                                   // couldn't create — leave the stack intact
        }

        // Inherit the stack's appearance (biome variant, colour, …) so the split-off breeder looks like
        // its herd instead of reverting to the default variant. Clone before we touch counts/name/love;
        // the helper hands the copy a fresh UUID.
        copyDataFrom(partner, self, level);
        setCount(partner, 1);                               // a clean single — clear any cloned count/name
        partner.snapTo(self.getX(), self.getY(), self.getZ(), self.getYRot(), self.getXRot());
        partner.setAge(0);                                  // adult, ready to breed

        // CRITICAL ORDERING: put the partner in love BEFORE adding it to the world.
        //
        // If we add it first, ServerEntityEvents.ENTITY_LOAD fires while the partner is still a plain
        // count-1 adult — a perfectly valid merge candidate — and the merge sweep (which runs
        // effectively synchronously on the server thread) immediately absorbs it straight back into THIS
        // still-mergeable stack and discard()s it. The net effect was: the stack count dropped by one,
        // but no partner ever appeared and no hearts showed — the exact reported breeding bug.
        //
        // An in-love animal fails canMerge() (see canMerge: rejects isInLove()), so marking it first
        // makes it un-absorbable the instant it loads. setInLove is safe pre-spawn: the entity's level
        // is set at construction and the heart broadcast simply reaches no trackers yet (the continuous
        // love hearts resume once it ticks). This re-enters setInLove, but count == 1 so it does NOT
        // split again — it just falls through to vanilla and becomes a normal in-love animal.
        partner.setInLove(player);

        if (!level.addFreshEntity(partner)) {
            return false;                                   // spawn rejected — leave the stack intact (no adult lost)
        }

        // Only now that the partner is really in the world do we take one out of the stack, so the
        // adult-count invariant holds even if the add fails.
        setCount(self, count - 1);                          // unstack one (updates the floating name)

        // Feed feedback: pop the heart particles on the STACK itself at the moment of feeding, exactly
        // like vanilla setInLove does for a normal fed animal. The stack does NOT actually enter love
        // mode (only the split-off partner breeds) — entity event 18 (IN_LOVE_HEARTS) is a pure
        // client-side particle burst, so the player still gets the familiar "fed -> hearts" feedback on
        // the animal they clicked, while the split-off partner carries the continuous hearts as it seeks
        // a mate.
        level.broadcastEntityEvent(self, (byte) 18);
        return true;
    }

    /* ====================================================================== */
    /* (5) Taming — hand out one pet instead of taming the whole stack        */
    /* ====================================================================== */

    /**
     * Called from {@link com.example.entitystacker.mixin.TamableAnimalTameMixin} at the HEAD of
     * {@code TamableAnimal#tame(Player)} (fired when a wolf/cat/parrot is successfully tamed).
     *
     * <p>A stack is a SINGLE entity, so taming it would otherwise tame all of its members at once.
     * Instead we keep the interacted entity AS the single tamed pet (count 1) and spawn the leftover
     * {@code (count - 1)} beside it as a separate UNTAMED stack. The tamed pet IS the original entity, so
     * it keeps its own variant / collar / sit pose / hearts / owner. The leftover herd's look is matched
     * to the original: a wolf (biome variant) inherits the original's variant via {@link #copyDataFrom},
     * while a cat (not biome-based) rolls a fresh random variant via {@code finalizeSpawn}.</p>
     *
     * <p><b>Ordering matters.</b> We mark {@code self} as tamed BEFORE the leftover joins the world: the
     * two are co-located same-type wild animals, and if both were stackable for an instant the merge pass
     * would fuse them right back into one stack which vanilla would then tame whole — the reported bug.
     * Taming {@code self} first makes it fail {@link #canMerge}, so the leftover has nothing to merge into.</p>
     *
     * <p>No adult is lost or duplicated: {@code self(1, tamed) + remainder(count-1, untamed)} totals
     * the original count. The tamed pet is then excluded from stacking by {@link #isStackable}
     * ({@code TamableAnimal#isTame()}), while the untamed leftover may re-merge with other wild stacks.</p>
     *
     * <p>If the leftover entity can't be created/added we restore the original untamed stack — vanilla
     * then tames the whole stack (the old behaviour) rather than risk losing mobs.</p>
     *
     * @return the leftover {@code (count-1)} stack that was split off, or {@code null} if nothing was
     *         split (a normal single, or a creation/spawn failure that left the stack intact).
     */
    public static Mob splitOffRemainderBeforeTame(TamableAnimal self) {
        int count = getCount(self);
        if (count <= 1) return null;                        // already a single — vanilla tames it directly
        if (!StackConfig.isMobStackingEnabled(self.getType())) return null;      // disabled type: don't split (see splitOneForBreeding)
        if (!(self.level() instanceof ServerLevel level)) return null;

        // Build the leftover herd first but DON'T add it yet, so a creation failure costs nothing.
        Entity created = self.getType().create(level, EntitySpawnReason.CONVERSION);
        if (!(created instanceof Mob remainder)) return null; // can't recreate the herd — leave the stack as-is

        // Give the leftover herd the right look.
        //   - Cats: their variant is NOT biome-driven, so per request the leftover rolls a fresh RANDOM
        //     variant via finalizeSpawn (the same way a wild cat picks its look on spawn).
        //   - Everything else (wolf is the biome-variant case; a parrot's colour is not biome-driven but
        //     is still saved data): we clone self's data, which carries whatever variant the original had
        //     through serialization. self is still untamed here, so the clone is untamed too.
        if (remainder instanceof Cat) {
            remainder.snapTo(self.getX(), self.getY(), self.getZ(), self.getYRot(), self.getXRot());
            remainder.finalizeSpawn(level, level.getCurrentDifficultyAt(remainder.blockPosition()),
                    EntitySpawnReason.CONVERSION, null);
        } else {
            copyDataFrom(remainder, self, level);
            remainder.snapTo(self.getX(), self.getY(), self.getZ(), self.getYRot(), self.getXRot());
        }
        setCount(remainder, count - 1);                     // the leftover wild herd, still untamed

        // CRITICAL ORDERING: tame 'self' BEFORE the (still untamed) leftover enters the world.
        //
        // 'self' and 'remainder' are co-located, same-type WILD animals. If both were mergeable for even
        // an instant, the merge pass — which runs effectively synchronously on the server thread (see the
        // breeding split, which hit the identical hazard) — would fuse them straight back into a single
        // stack. Vanilla then tames that whole re-merged stack: the reported bug (the entire stack tamed,
        // with a stray single left over from the race). A tamed animal fails isStackable()/canMerge(), so
        // taming 'self' first makes the merge-back impossible. Vanilla's tame() body — which called us —
        // then redundantly re-tames 'self' and sets the real owner; setTame is idempotent.
        self.setTame(true, true);                           // exclude 'self' from stacking up front
        setCount(self, 1);                                  // 'self' is the pet -> drop the floating "xN" name

        if (!level.addFreshEntity(remainder)) {
            // Spawn rejected (rare): fully restore the original untamed stack so no mob is lost. Vanilla
            // then tames the whole stack — the documented safe fallback rather than risking a vanished herd.
            self.setTame(false, false);
            setCount(self, count);
            return null;
        }

        return remainder;
    }

    /* ====================================================================== */
    /* (6) Shearing — peel one sheep off a stack so it can be sheared          */
    /* ====================================================================== */

    /**
     * Called from {@link com.example.entitystacker.mixin.SheepShearMixin} at the RETURN of
     * {@code Sheep#shear(...)} (fired when a sheep is sheared — by a player or a dispenser).
     *
     * <p>A stack is a SINGLE entity, so shearing "Sheep x5" would otherwise drop just one fleece while
     * the count stayed at 5 and no sheep ever split off — the reported bug. Instead we keep the
     * interacted sheep AS the single one that was just sheared (count 1) and spawn the leftover
     * {@code (count - 1)} beside it as a separate, still-FLEECED flock.</p>
     *
     * <p><b>Why this runs at RETURN.</b> The shear loot table ({@code shearing/sheep.json}) gates every
     * wool entry on {@code sheared:false}, so vanilla deliberately rolls the fleece BEFORE flipping the
     * sheared flag. By the time we run, vanilla has already dropped the wool and set {@code self}
     * sheared. We clone {@code self} (which therefore copies its colour) and then explicitly clear the
     * clone's sheared flag, because the leftover represents the {@code (count-1)} sheep that were NOT
     * sheared and still carry their wool. Exactly one fleece dropped — for the single unit we kept.</p>
     *
     * <p><b>No merge-back, for free.</b> {@code self} is ALREADY sheared when we add the fleeced
     * leftover, so the two sit in different {@link #variantKey} groups ("…:sheared" vs not) and the
     * merge pass — which can run effectively synchronously on the server thread (see the breeding/taming
     * splits, which had to pre-empt the identical hazard) — cannot fuse them. No pre-emptive marking is
     * needed: vanilla's own {@code setSheared(true)} already did it.</p>
     *
     * <p>No sheep is lost: {@code self(1, sheared) + remainder(count-1, fleeced)} totals the original
     * count. The sheared single re-merges with the flock once it eats grass and regrows its wool (its
     * variant key flips back), so the stack heals itself naturally — no special-casing.</p>
     *
     * <p>If the leftover can't be created/added we restore the original count; vanilla's single fleece
     * still dropped, so at worst the whole stack reads as sheared (a rare, cosmetic-only fallback) rather
     * than risking a lost or duplicated sheep.</p>
     *
     * @return {@code true} if a single was peeled off; {@code false} for a normal single or a
     *         creation/spawn failure that left the stack intact.
     */
    public static boolean splitOffOneForShearing(Sheep self) {
        int count = getCount(self);
        if (count <= 1) return false;                       // a normal single — vanilla sheared it directly
        if (!StackConfig.isMobStackingEnabled(self.getType())) return false;     // disabled type: don't split (see splitOneForBreeding)
        if (!(self.level() instanceof ServerLevel level)) return false;

        // Build the leftover flock first but DON'T add it yet, so a creation failure costs nothing.
        Entity created = self.getType().create(level, EntitySpawnReason.CONVERSION);
        if (!(created instanceof Sheep remainder)) return false; // can't recreate the flock — leave it as-is

        // Clone self's look (wool colour) into the leftover. self has JUST been sheared by vanilla, so the
        // clone comes out sheared too — clear it: the leftover is the (count-1) sheep that kept their wool.
        copyDataFrom(remainder, self, level);
        remainder.setSheared(false);                        // the leftover flock still has its fleece
        remainder.snapTo(self.getX(), self.getY(), self.getZ(), self.getYRot(), self.getXRot());
        setCount(remainder, count - 1);                     // the leftover flock

        setCount(self, 1);                                  // 'self' is the one that was sheared -> drop the "xN" name

        // self is already sheared (vanilla did it), so it is in a different variant group from the fleeced
        // leftover and cannot merge back even if the merge runs synchronously inside addFreshEntity.
        if (!level.addFreshEntity(remainder)) {
            // Spawn rejected (rare): restore the original count so no sheep is lost. The single fleece
            // vanilla dropped stands; the stack just reads as sheared — a cosmetic-only safe fallback.
            setCount(self, count);
            return false;
        }

        return true;
    }

    /* ====================================================================== */
    /* (7) Shearing a mooshroom — peel one off, let it convert to a cow        */
    /* ====================================================================== */

    /**
     * Called from {@link com.example.entitystacker.mixin.MushroomCowShearMixin} at the HEAD of
     * {@code MushroomCow#shear(...)} (fired when a mooshroom is sheared by a player or a dispenser).
     *
     * <p><b>The bug:</b> unlike a sheep (which merely flips a sheared flag and survives), vanilla shears a
     * mooshroom by {@code convertTo(EntityType.COW, ...)} — it DISCARDS the mooshroom and spawns a Cow in
     * its place, then drops mushrooms from the {@code SHEAR_MOOSHROOM} loot table. With no intervention,
     * shearing a stacked "Mooshroom x5" converts that one stack-bearing entity wholesale: its count and
     * the floating "xN" name ride across onto the replacement cow ({@code convertTo} copies the source's
     * custom name and Fabric's persistent attachments), so the whole stack becomes cows at once while only
     * a single mushroom drop is rolled — the reported behaviour.</p>
     *
     * <p><b>The fix</b> (mirror of {@link #splitOffOneForShearing}, but inverted): we peel the leftover
     * {@code (count - 1)} off as its own surviving mooshroom stack, then reduce the interacted entity to a
     * clean single — count 1, no floating name, attachment removed — <i>before</i> vanilla's
     * {@code convertTo} runs. Vanilla then converts that single mooshroom into exactly ONE fresh cow and
     * rolls one normal mushroom drop. Net result: the stack on the mooshroom's head drops by one, a new
     * single cow appears, and mushrooms drop as usual.</p>
     *
     * <p><b>Why HEAD here, not RETURN like the sheep hook:</b> the sheep handler must wait for vanilla to
     * roll the wool (its loot is gated on {@code sheared:false}) and the sheep survives the shear, so RETURN
     * works. A mooshroom does NOT survive — by RETURN it has already been discarded and replaced by the cow,
     * leaving nothing to decrement. And the mushroom loot is keyed off the mooshroom's red/brown VARIANT,
     * which this handler never touches, so running at HEAD does not suppress or alter the drop. We do NOT
     * cancel: vanilla still performs the conversion and the drop for the single unit we left behind.</p>
     *
     * <p><b>Why a merge-back guard IS needed</b> (just like the sheep/taming/breeding splits). For an
     * instant {@code self} (count 1) and {@code remainder} (count-1) are co-located same-variant mooshrooms
     * — a valid merge pair — and the {@code ENTITY_LOAD} fast-path merge runs <i>effectively synchronously</i>
     * INSIDE {@code addFreshEntity} (the same hazard the breeding split hit). Without a guard it absorbs the
     * leftover straight back into {@code self}, which becomes "Mooshroom x{count}" again; vanilla then
     * converts that whole stack WHOLESALE into a single cow (the count attachment is not carried across the
     * conversion), silently destroying the other {@code count-1} mooshrooms — confirmed by a headless test
     * (shearing x5 yielded one cow and zero leftover mooshrooms). The original "type change is the guard"
     * reasoning was wrong: the type only changes at vanilla's {@code convertTo}, which runs AFTER our HEAD
     * injection returns — too late for the merge that already fired during {@code addFreshEntity}.</p>
     *
     * <p>The guard, mirroring taming ({@code setTame}) / breeding ({@code setInLove}): we mark {@code self}
     * with a love timer BEFORE adding the leftover, so it fails {@link #canMerge} ({@code isInLove()}) and is
     * not a merge target. Unlike {@code setInLove(Player)} this sets the timer WITHOUT broadcasting hearts,
     * and {@code convertTo} copies only data components (not the love timer), so the cow comes out clean and
     * the marker never surfaces (vanilla converts {@code self} this same tick).</p>
     *
     * <p>No mob is lost: {@code self(1 -> 1 cow) + remainder(count-1 mooshroom)} totals the original count.
     * If the leftover can't be created/added we restore the original stack untouched and let vanilla
     * convert the whole stack (the old behaviour) rather than risk losing mooshrooms.</p>
     *
     * @return {@code true} if a single was peeled off; {@code false} for a normal single or a
     *         creation/spawn failure that left the stack intact.
     */
    public static boolean splitOffOneForMooshroomShearing(MushroomCow self) {
        int count = getCount(self);
        if (count <= 1) return false;                       // a normal single — vanilla converts it directly
        if (!StackConfig.isMobStackingEnabled(self.getType())) return false;     // disabled type: don't split (see splitOneForBreeding)
        if (!(self.level() instanceof ServerLevel level)) return false;

        // Build the leftover herd first but DON'T add it yet, so a creation failure costs nothing.
        Entity created = self.getType().create(level, EntitySpawnReason.CONVERSION);
        if (!(created instanceof MushroomCow remainder)) return false; // can't recreate — leave it as-is

        // Clone self's look (red / brown mushroom variant) into the leftover. self has not been converted
        // yet at this HEAD injection, so the clone is a same-variant, still-mooshroom herd.
        copyDataFrom(remainder, self, level);
        remainder.snapTo(self.getX(), self.getY(), self.getZ(), self.getYRot(), self.getXRot());
        setCount(remainder, count - 1);                     // the surviving mooshroom stack

        // Reduce 'self' to a clean single BEFORE vanilla's convertTo runs, so the cow it spawns inherits
        // neither the stack count nor the "xN" name (both of which convertTo would otherwise copy across).
        setCount(self, 1);

        // CRITICAL: make 'self' un-mergeable BEFORE the (still-mooshroom) leftover joins the world. The
        // ENTITY_LOAD merge runs effectively synchronously inside addFreshEntity, so otherwise it absorbs
        // the leftover right back into self -> "Mooshroom x{count}" -> vanilla converts the whole stack into
        // a single cow and the other count-1 are lost (see method javadoc). A love timer fails canMerge()
        // without broadcasting hearts; vanilla converts self to a cow this same tick and does not copy the
        // timer across, so the cow is unaffected.
        self.setInLoveTime(2);

        if (!level.addFreshEntity(remainder)) {
            // Spawn rejected (rare): restore the original stack so no mooshroom is lost. Vanilla then
            // converts the whole stack — the documented safe fallback.
            self.setInLoveTime(0);
            setCount(self, count);
            return false;
        }

        return true;
    }
}
