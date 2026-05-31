package com.example.entitystacker;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Server-side settings GUI: a clickable "stackable mobs" board, rendered to the client as an ordinary
 * 9×3 chest screen.
 *
 * <h2>Why this works on a server-side-only mod with vanilla clients</h2>
 * We reuse the vanilla {@link MenuType#GENERIC_9x3} menu type, so the client already knows how to open
 * and draw it — no client mod, no custom screen, no networking payloads of our own. All behaviour lives
 * in this server-only subclass of {@link ChestMenu}: each managed mob ({@link StackConfig#MANAGED}) is an
 * item that this menu swaps between an "ON" and "OFF" appearance, and we override {@link #clicked} to turn
 * a click into a config toggle instead of an item pickup.
 *
 * <h2>It is a read-only control panel</h2>
 * {@link #clicked} NEVER calls {@code super.clicked(...)}, so the vanilla item-moving logic never runs —
 * a player can't pull a spawn egg out or shove items in (no dupes). After handling a click we call
 * {@link #sendAllDataToRemote()} to push the authoritative server state (all slots + the empty cursor)
 * back to the client, reverting its optimistic "I picked that item up" prediction. {@link #quickMoveStack}
 * is also stubbed to empty so shift-click transfers do nothing.
 */
public final class StackerConfigMenu extends ChestMenu {

    private static final int ROWS = 3;
    private static final int TOP_SIZE = ROWS * 9;       // 27 panel slots (slots 0..26)

    /** Panel slots that hold the mob toggles, in {@link StackConfig#MANAGED} order (centre row, spaced). */
    static final int[] MOB_SLOTS = {10, 12, 14, 16};

    private final Container panel;
    private final Map<Integer, EntityType<?>> slotToType = new HashMap<>();

    public StackerConfigMenu(int containerId, Inventory playerInventory) {
        super(MenuType.GENERIC_9x3, containerId, playerInventory, new SimpleContainer(TOP_SIZE), ROWS);
        this.panel = getContainer();
        buildPanel();
    }

    /** Fill the board: a glass-pane background, then one toggle item per managed mob. */
    private void buildPanel() {
        for (int i = 0; i < TOP_SIZE; i++) {
            panel.setItem(i, background());
        }
        List<StackConfig.ManagedMob> mobs = StackConfig.MANAGED;
        for (int i = 0; i < mobs.size() && i < MOB_SLOTS.length; i++) {
            StackConfig.ManagedMob mob = mobs.get(i);
            int slot = MOB_SLOTS[i];
            slotToType.put(slot, mob.type());
            panel.setItem(slot, toggleItem(mob, StackConfig.isMobStackingEnabled(mob.type())));
        }
    }

    @Override
    public void clicked(int slotId, int button, ContainerInput input, Player player) {
        EntityType<?> type = slotToType.get(slotId);
        if (type != null) {
            boolean now = StackConfig.toggleMob(type);
            // Refresh the clicked slot to its new ON/OFF appearance.
            for (StackConfig.ManagedMob mob : StackConfig.MANAGED) {
                if (mob.type() == type) {
                    panel.setItem(slotId, toggleItem(mob, now));
                    break;
                }
            }
        }
        // Read-only panel: deliberately do NOT call super.clicked(...) for any slot, so no item ever moves.
        // Resync the client to the authoritative state (this also bumps the menu state id), undoing the
        // client's optimistic pickup prediction.
        sendAllDataToRemote();
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;     // no shift-click transfers
    }

    @Override
    public boolean stillValid(Player player) {
        return true;                // backed by a throwaway in-memory container, always valid while open
    }

    /* ------------------------------------------------------------------ */
    /* Item builders                                                      */
    /* ------------------------------------------------------------------ */

    private static ItemStack toggleItem(StackConfig.ManagedMob mob, boolean on) {
        ItemStack stack = new ItemStack(mob.icon());

        MutableComponent name = Component.empty()
                .append(mob.type().getDescription().copy().withStyle(s -> s.withItalic(false).withColor(ChatFormatting.WHITE)))
                .append(Component.literal(on ? "  [ON]" : "  [OFF]")
                        .withStyle(s -> s.withItalic(false).withColor(on ? ChatFormatting.GREEN : ChatFormatting.RED)));
        stack.set(DataComponents.CUSTOM_NAME, name);

        // Enchant glint = a clear "checked/enabled" cue, on top of the green/red label.
        stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, on);

        stack.set(DataComponents.LORE, new ItemLore(List.of(
                Component.literal(on ? "Stacking ENABLED" : "Stacking DISABLED")
                        .withStyle(s -> s.withItalic(false).withColor(on ? ChatFormatting.GREEN : ChatFormatting.RED)),
                Component.literal("Click to toggle")
                        .withStyle(s -> s.withItalic(false).withColor(ChatFormatting.YELLOW)))));
        return stack;
    }

    private static ItemStack background() {
        ItemStack pane = new ItemStack(Items.GRAY_STAINED_GLASS_PANE);
        // Blank, non-italic name so the background panes render as empty tiles.
        pane.set(DataComponents.CUSTOM_NAME, Component.literal(" ").withStyle(s -> s.withItalic(false)));
        return pane;
    }
}
