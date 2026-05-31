package com.example.entitystacker;

import com.mojang.serialization.Codec;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entity Stacker — main mod initializer (Minecraft 26.1.2, Fabric, Java 25).
 *
 * <p>This class only wires up the persistent data storage and delegates all gameplay logic to
 * {@link StackEventHandler}, keeping the two concerns cleanly separated.</p>
 *
 * <p><b>Mappings:</b> 26.x uses Mojang's official mappings (Yarn is not published for it). Note the
 * key-class is {@code net.minecraft.resources.Identifier} in 26.x (Mojmap renamed the old
 * {@code ResourceLocation} to {@code Identifier}).</p>
 */
public final class EntityStackerMod implements ModInitializer {

    public static final String MOD_ID = "entitystacker";
    public static final Logger LOGGER = LoggerFactory.getLogger("Entity Stacker");

    /**
     * The stack count is stored per-entity with Fabric's <b>Data Attachment API</b>. Because the
     * attachment is created with {@code createPersistent(...)}, the value is automatically written to /
     * read from the entity's NBT (under the {@code "fabric:attachments"} compound) — satisfying the
     * "store the count in the entity's custom NBT data" requirement without hand-rolling NBT mixins.
     *
     * <p>An absent attachment is treated as a count of 1 (a normal, un-stacked entity).</p>
     */
    public static final AttachmentType<Integer> STACK_COUNT =
            AttachmentRegistry.createPersistent(
                    Identifier.fromNamespaceAndPath(MOD_ID, "stack_count"),
                    Codec.INT
            );

    @Override
    public void onInitialize() {
        LOGGER.info("Initializing Entity Stacker...");
        StackConfig.load();          // restore per-mob toggles (writes defaults on first run)
        StackEventHandler.register();
        StackCommands.register();     // /entitystacker — settings GUI + list/set sub-commands
        LOGGER.info("Entity Stacker ready.");
    }
}
