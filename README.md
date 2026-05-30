# Entity Stacker (Fabric · Minecraft 26.1.2)

Merges identical nearby passive/hostile mobs into a single counted entity, shows the count above the
head (`Cow x5`), decrements (instead of one‑shotting the whole pile) on death with proper loot, unstacks one parent per
successful breed while respecting the breeding cooldown, and hands out one pet per successful tame
instead of taming the whole stack.

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

> **Mappings matter for this code.** The 26.x toolchain uses Mojang's official mappings (Mojmap).
> `build.gradle` has **no** `mappings` line and **no** `loom.officialMojangMappings()` call — Loom
> 1.16 supplies Mojmap by default (Yarn is not published for 26.1.2, and Mojang's 26.1.2 version JSON
> has no `client_mappings`, so the explicit `officialMojangMappings()` lookup fails — don't add it).
> Use the full plugin id `net.fabricmc.fabric-loom`, not the `fabric-loom` alias. Every Minecraft
> reference uses Mojmap names:
> `Mob`/`Animal`/`AgeableMob`, `Animal#getAge()`, `LivingEntity#dropAllDeathLoot(...)`,
> `Animal#finalizeSpawnChildFromBreeding(...)`, `ServerLevel`, `Component`, `AABB`.
>
> **26.x specifics confirmed by building against the real jars:**
> - `net.minecraft.resources.Identifier` (26.x Mojmap renamed `ResourceLocation` → `Identifier`).
> - Fabric API 0.150: `ServerEntityEvents` lives in `…api.event.lifecycle.v1` (not `…entity.event.v1`);
>   `ServerLivingEntityEvents` stays in `…entity.event.v1`. The tick fields were renamed
>   `WORLD`→`LEVEL`: use `ServerTickEvents.END_LEVEL_TICK` (not `END_WORLD_TICK`).
>   `ServerEntityEvents.ENTITY_LOAD` is unchanged.

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
| Breeding             | Mixin on `Animal#setInLove(...)` → split one in-love single off the stack    |
| Taming               | Mixin on `TamableAnimal#tame(...)` → tame one off the stack, leave the rest   |

### Forge → Fabric event mapping

| Forge / NeoForge            | Fabric equivalent used here                              |
|-----------------------------|---------------------------------------------------------|
| `EntityJoinLevelEvent`      | `ServerEntityEvents.ENTITY_LOAD`                        |
| `LivingDeathEvent` (cancel) | `ServerLivingEntityEvents.ALLOW_DEATH` (return `false`) |
| `BabyEntitySpawnEvent`      | `AnimalEntityBreedMixin` (hooks `Animal#setInLove`)     |
| `LivingTickEvent` scan      | throttled `ServerTickEvents.END_LEVEL_TICK`             |

## Tuning

See `StackConfig` (radius, sweep interval, max stack size, passive/hostile toggles, blacklist).

## Known limitations

- **Breeding splits one out per feed:** feeding a stack splits off a single in-love animal (the stack
  itself doesn't enter love), so it takes two feeds — same cost as vanilla — to make a baby. The
  split-off singles go on the normal cooldown and re-merge into the stack once it expires; no adult is
  lost. See `StackEventHandler.splitOneForBreeding`.
- **Taming hands out one pet per tame:** taming a stacked wolf/cat/parrot tames the interacted entity
  as a single pet (keeping its own variant, collar, sit pose and owner) and leaves the rest behind as a
  separate untamed stack. The leftover stack is a freshly created entity, so it reverts to the default
  variant; the kept *pet* keeps the original's appearance. See `StackEventHandler.splitOffRemainderBeforeTame`.
- **Variant‑blind merging:** stacks are keyed by entity *type* only. Add a variant comparison in
  `StackEventHandler.compatible(...)` to keep e.g. differently‑coloured sheep separate.
- A despawning stack takes all its units with it (no spill).

## Changelog

Newest first. Dates are commit dates.

### 2026-05-31 — Fix taming a stacked animal taming the whole stack

- **Bug:** taming a stacked wolf/cat/parrot could tame the entire stack at once and leave a stray
  single behind. `splitOffRemainderBeforeTame` added the leftover to the world while both it and the
  interacted entity were still *untamed* same-type animals sitting on the same block — so the merge
  pass (which runs effectively synchronously) immediately fused them back into one stack, which vanilla
  then tamed whole. This is the same merge-back hazard the breeding split documents.
- **Fix:** tame `self` (the interacted entity) *before* the leftover joins the world. A tamed animal
  fails `isStackable()`/`canMerge()`, so the leftover has nothing to merge into and the result is a
  deterministic one pet + one `(count-1)` untamed stack. If the leftover can't be spawned the original
  untamed stack is fully restored (no mob lost). Verified headlessly: post-split the merge sweep leaves
  the pet (count 1, tamed) and leftover (count 4, untamed) untouched, conserving the original count.

### 2026-05-31 — Repository hygiene (`dcc5498`)

- Added a Fabric/Gradle `.gitignore` so build output, Gradle caches, IDE files, and logs stay out of
  version control going forward.
- Untracked the `.gradle/` cache files and `build-verify.log` that had been committed by mistake.

### 2026-05-31 — Initial release (`228623a`)

First working version of the mod. Establishes the full feature set and build setup:

- **Core stacking** — `StackEventHandler` merges identical nearby mobs into one counted entity via a
  throttled `ServerTickEvents` radius sweep plus an `ServerEntityEvents.ENTITY_LOAD` instant fast‑path.
- **Overhead count** — stack size shown as a `CustomName` (`Cow x5`), auto‑synced to clients.
- **Death handling** — `ServerLivingEntityEvents.ALLOW_DEATH` decrements the stack one unit at a time
  (instead of one‑shotting the whole pile) and drops proper loot via `dropAllDeathLoot(...)`.
- **Breeding** — `AnimalEntityBreedMixin` (hooking `Animal#setInLove`) splits a single in‑love animal
  off the stack per feed, respecting the vanilla breeding cooldown; `LivingEntityInvoker` exposes the
  needed protected method.
- **Configuration** — `StackConfig` exposes radius, sweep interval, max stack size, passive/hostile
  toggles, and a blacklist.
- **Mod plumbing** — `EntityStackerMod` entry point, `entitystacker.mixins.json` mixin config, and
  `fabric.mod.json` metadata/dependencies/entry points.
- **Build** — `build.gradle`, `settings.gradle`, `gradle.properties`, and the Gradle wrapper, targeting
  the Minecraft `26.1.2` Fabric + Mojmap + Java 25 toolchain.
