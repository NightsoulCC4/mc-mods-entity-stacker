# Entity Stacker (Fabric · Minecraft 26.1.2)

Merges identical nearby passive/hostile mobs into a single counted entity, shows the count above the
head (`Cow x5`), decrements (instead of one‑shotting the whole pile) on death with proper loot, and
unstacks one parent per successful breed while respecting the breeding cooldown.

## Toolchain (from <https://fabricmc.net/develop> + the official `fabric-example-mod`)

| Thing            | Value                          |
|------------------|--------------------------------|
| Minecraft        | `26.1.2`                       |
| Fabric Loader    | `0.19.2`                       |
| Fabric API       | `0.150.0+26.1.2`               |
| Loom             | `1.16-SNAPSHOT`                |
| **Mappings**     | **Mojang official (Mojmap)** — Yarn is *not* published for 26.1.2 |
| **Java**         | **25**                         |
| Gradle           | `9.4.1`                        |

> **Mappings matter for this code.** `build.gradle` declares `mappings loom.officialMojangMappings()`
> (Loom requires an explicit mappings dependency; Yarn is not published for 26.1.2, hence no
> `yarn_mappings` property). Every Minecraft reference therefore uses Mojmap names:
> `Mob`/`Animal`/`AgeableMob`, `Animal#getAge()`, `LivingEntity#dropAllDeathLoot(...)`,
> `Animal#finalizeSpawnChildFromBreeding(...)`, `ServerLevel`, `Component`, `AABB`, `ResourceLocation`.
> The few version‑sensitive method names are flagged with `Version note:` comments in the source; if a
> later 26.x mapping renames one, the compiler / mixin loader points at the exact spot to fix.

## Building

This repo pins Gradle **9.4.1** via `gradle/wrapper/gradle-wrapper.properties`, but the binary
`gradle-wrapper.jar` and the `gradlew`/`gradlew.bat` launch scripts are **not** included (they can't be
shipped as text). Generate them once with a local Gradle (and **JDK 25** on your PATH):

```bash
gradle wrapper --gradle-version 9.4.1 --distribution-type bin
```

(or copy `gradlew`, `gradlew.bat` and `gradle/wrapper/gradle-wrapper.jar` from the official
[`fabric-example-mod`](https://github.com/FabricMC/fabric-example-mod).)

Then:

```bash
./gradlew build      # jar -> build/libs/
./gradlew runClient  # dev client (Loom)
```

## How it works

| Concern              | Mechanism                                                                 |
|----------------------|---------------------------------------------------------------------------|
| Stack count storage  | Fabric **Data Attachment API** (`createPersistent`) → saved to entity NBT  |
| Overhead display     | `CustomName` (`Cow x5`), synced to clients automatically                    |
| Merge (radius scan)  | Throttled `ServerTickEvents.END_WORLD_TICK` sweep (every 20 ticks)          |
| Merge (instant)      | `ServerEntityEvents.ENTITY_LOAD` fast‑path                                  |
| Combat / drops       | `ServerLivingEntityEvents.ALLOW_DEATH` → decrement + `dropAllDeathLoot(...)`|
| Breeding             | Mixin on `Animal#finalizeSpawnChildFromBreeding(...)` → unstack one parent  |

### Forge → Fabric event mapping

| Forge / NeoForge            | Fabric equivalent used here                              |
|-----------------------------|---------------------------------------------------------|
| `EntityJoinLevelEvent`      | `ServerEntityEvents.ENTITY_LOAD`                        |
| `LivingDeathEvent` (cancel) | `ServerLivingEntityEvents.ALLOW_DEATH` (return `false`) |
| `BabyEntitySpawnEvent`      | `AnimalEntityBreedMixin` → `StackEventHandler.onBreed`  |
| `LivingTickEvent` scan      | throttled `ServerTickEvents.END_WORLD_TICK`             |

## Tuning

See `StackConfig` (radius, sweep interval, max stack size, passive/hostile toggles, blacklist).

## Known limitations

- **Whole‑stack breeding cooldown:** the cooldown applies to the single entity that represents the
  remaining stack, so a big stack releases one baby per cooldown cycle (see `onBreed` Javadoc).
- **Variant‑blind merging:** stacks are keyed by entity *type* only. Add a variant comparison in
  `StackEventHandler.compatible(...)` to keep e.g. differently‑coloured sheep separate.
- A despawning stack takes all its units with it (no spill).
