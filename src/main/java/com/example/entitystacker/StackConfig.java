package com.example.entitystacker;

import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;

import java.util.Set;

/**
 * Central, tweakable configuration for the stacker (Mojang mappings).
 *
 * <p>Kept as simple static constants for clarity. In a production mod you would load these from a
 * config file; the gameplay code only ever reads through this class, so swapping in a file-backed
 * config later is a localized change.</p>
 */
public final class StackConfig {

    private StackConfig() {}

    /** Search radius (in blocks) used when looking for stack-mates. */
    public static final double RADIUS = 5.0D;

    /** Run the expensive radius sweep only every N server ticks (20 ticks = 1 second). */
    public static final int MERGE_INTERVAL = 20;

    /** Hard cap on how many entities a single stack can represent. */
    public static final int MAX_STACK = 64;

    /** Toggle stacking per mob category. */
    public static final boolean ALLOW_PASSIVE = true;
    public static final boolean ALLOW_HOSTILE = true;

    /** Entity types that must never be stacked: bosses, mobs with unique data, etc. */
    public static final Set<EntityType<?>> BLACKLIST = Set.of(
            EntityType.ENDER_DRAGON,
            EntityType.WITHER,
            EntityType.WARDEN,
            EntityType.ELDER_GUARDIAN,
            EntityType.VILLAGER,          // trade data would be lost on merge
            EntityType.WANDERING_TRADER,
            EntityType.ARMOR_STAND
    );

    /**
     * Is this mob's broad category allowed to stack?
     * {@code Enemy} is Mojmap's marker interface for hostile mobs (Yarn's {@code Monster}).
     */
    public static boolean isCategoryAllowed(Mob mob) {
        if (mob instanceof Enemy) return ALLOW_HOSTILE;
        if (mob instanceof AgeableMob) return ALLOW_PASSIVE;
        // Ambient/water/other mobs: treat like passives.
        return ALLOW_PASSIVE;
    }

    /** Is this specific type allowed (i.e. not blacklisted)? */
    public static boolean isTypeAllowed(EntityType<?> type) {
        return !BLACKLIST.contains(type);
    }
}
