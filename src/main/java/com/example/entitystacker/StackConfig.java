package com.example.entitystacker;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Central, tweakable configuration for the stacker (Mojang mappings).
 *
 * <p>The radius / interval / cap constants stay compile-time. The <b>per-mob "is this type allowed to
 * stack" toggles</b> ({@link #MANAGED}) are RUNTIME state, editable in-game via {@code /entitystacker}
 * (a clickable chest GUI or the {@code set} sub-command) and persisted to
 * {@code config/entitystacker.json}. The gameplay code only ever reads through this class, so the
 * storage details stay localized here.</p>
 */
public final class StackConfig {

    private StackConfig() {}

    /** Search radius (in blocks) used when looking for stack-mates. */
    public static final double RADIUS = 5.0D;

    /** Run the expensive radius sweep only every N server ticks (20 ticks = 1 second). */
    public static final int MERGE_INTERVAL = 20;

    /** Hard cap on how many entities a single stack can represent. */
    public static final int MAX_STACK = 64;

    /**
     * Master toggles for which broad mob category may stack at all.
     *
     * <p>{@code ALLOW_HOSTILE} gates every {@link net.minecraft.world.entity.monster.Enemy} (zombies,
     * skeletons, spiders, …). It is {@code false} (the default): hostiles are opted out of stacking
     * entirely — wild hostiles never merge and each is killed individually, so their kill
     * advancements/stats are unaffected by definition. Set it to {@code true} to enable hostile stacking;
     * {@code StackEventHandler#onAllowDeath} then replays the kill credit on every decrement so each
     * stacked kill still awards the advancement ("Monster Hunter"/…), mob-kill stat and scoreboard
     * score.</p>
     */
    public static final boolean ALLOW_PASSIVE = true;
    public static final boolean ALLOW_HOSTILE = false;

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

    /* ====================================================================== */
    /* Per-mob runtime toggles ("which mobs to stack")                        */
    /* ====================================================================== */

    /**
     * A mob the player can switch on/off in the settings GUI. {@code label} is the short id used by the
     * {@code /entitystacker set <label>} command and is the type's registry path; {@code icon} is the
     * item shown for it in the chest GUI.
     */
    public record ManagedMob(EntityType<?> type, String label, Item icon) {}

    /**
     * The configurable mob list, in display order. Starts with cow, chicken, sheep, pig — add a row here
     * to expose another mob (the GUI and command pick it up automatically; the GUI currently has room for
     * up to its {@link StackerConfigMenu#MOB_SLOTS} slots).
     */
    public static final List<ManagedMob> MANAGED = List.of(
            new ManagedMob(EntityType.COW, "cow", Items.COW_SPAWN_EGG),
            new ManagedMob(EntityType.CHICKEN, "chicken", Items.CHICKEN_SPAWN_EGG),
            new ManagedMob(EntityType.SHEEP, "sheep", Items.SHEEP_SPAWN_EGG),
            new ManagedMob(EntityType.PIG, "pig", Items.PIG_SPAWN_EGG)
    );

    /** Live enabled/disabled state per managed type. Default: every managed mob stacks. */
    private static final Map<EntityType<?>, Boolean> ENABLED = new LinkedHashMap<>();
    static {
        for (ManagedMob m : MANAGED) ENABLED.put(m.type(), Boolean.TRUE);
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Is this specific type a player-toggleable mob? */
    public static boolean isManaged(EntityType<?> type) {
        return ENABLED.containsKey(type);
    }

    /**
     * Is this type currently allowed to stack by the per-mob toggle? Unmanaged types are unaffected by
     * this feature and always return {@code true} (they remain governed by the blacklist / category gates).
     */
    public static boolean isMobStackingEnabled(EntityType<?> type) {
        Boolean v = ENABLED.get(type);
        return v == null || v;
    }

    /** Set a managed mob's toggle and persist. No-op for unmanaged types. Returns the value applied. */
    public static boolean setMobStacking(EntityType<?> type, boolean on) {
        if (ENABLED.containsKey(type)) {
            ENABLED.put(type, on);
            save();
        }
        return on;
    }

    /** Flip a managed mob's toggle and persist. Returns the NEW value. */
    public static boolean toggleMob(EntityType<?> type) {
        return setMobStacking(type, !isMobStackingEnabled(type));
    }

    /* ====================================================================== */
    /* Persistence (config/entitystacker.json)                                */
    /* ====================================================================== */

    private static Path configPath() {
        return FabricLoader.getInstance().getConfigDir().resolve(EntityStackerMod.MOD_ID + ".json");
    }

    /**
     * Load the per-mob toggles from disk. Missing file → write the defaults so admins can find/edit it.
     * Unknown keys are ignored and missing keys keep their default, so the file is forward/backward
     * compatible as the managed list grows.
     */
    public static void load() {
        Path path = configPath();
        try {
            if (!Files.exists(path)) {
                save();                                  // materialize defaults on first run
                return;
            }
            JsonObject root = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
            if (root.has("stackableMobs") && root.get("stackableMobs").isJsonObject()) {
                JsonObject mobs = root.getAsJsonObject("stackableMobs");
                for (ManagedMob m : MANAGED) {
                    String key = EntityType.getKey(m.type()).toString();
                    if (mobs.has(key)) ENABLED.put(m.type(), mobs.get(key).getAsBoolean());
                }
            }
            EntityStackerMod.LOGGER.info("Loaded stack config from {}", path);
        } catch (Exception e) {
            // Corrupt/partial file (e.g. truncated by a crash before the atomic write landed). Keep a
            // backup for diagnosis, then rewrite clean defaults so we neither warn on every boot nor lose
            // the file silently.
            EntityStackerMod.LOGGER.warn("Could not read {} — backing it up and writing defaults", path, e);
            try {
                Files.move(path, path.resolveSibling(path.getFileName() + ".corrupt"),
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ignored) { /* best effort */ }
            save();
        }
    }

    /** Write the current per-mob toggles to disk (keyed by registry id, e.g. {@code "minecraft:cow"}). */
    public static void save() {
        JsonObject mobs = new JsonObject();
        for (ManagedMob m : MANAGED) {
            mobs.addProperty(EntityType.getKey(m.type()).toString(), isMobStackingEnabled(m.type()));
        }
        JsonObject root = new JsonObject();
        root.add("stackableMobs", mobs);
        Path path = configPath();
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(tmp, GSON.toJson(root));
            // Atomic swap: a crash mid-write can never leave a truncated config — on disk the file is
            // always the complete old or new content. ATOMIC_MOVE isn't supported on every filesystem,
            // so fall back to a plain replace.
            try {
                Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            EntityStackerMod.LOGGER.warn("Could not write {}", path, e);
            try { Files.deleteIfExists(tmp); } catch (IOException ignored) { /* best effort cleanup */ }
        }
    }

    /* ====================================================================== */
    /* Category / blacklist gates (unchanged)                                 */
    /* ====================================================================== */

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
