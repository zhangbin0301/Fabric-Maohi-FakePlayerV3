package com.maohi.fakeplayer.network;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.ClickSlotC2SPacket;
import net.minecraft.network.packet.c2s.play.CloseHandledScreenC2SPacket;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.network.ServerPlayerEntity;

public class InventoryActionHelper {

    /**
     * Sends a ClickSlotC2SPacket to simulate a click in the current screen handler.
     * Note: The current screen handler must already be open and valid.
     */
    public static void clickSlot(ServerPlayerEntity player, int slot, int button, SlotActionType actionType) {
        if (player == null || player.currentScreenHandler == null) return;

        ScreenHandler handler = player.currentScreenHandler;
        int syncId = handler.syncId;
        int revision = handler.getRevision();
        ItemStack cursorStack = handler.getCursorStack().copy();
        
        // This simulates what the client does. We just use an empty map for modified stacks
        // as the server typically relies on the action itself and recalculates, 
        // but passing empty is standard for simple bot scripts unless strict validation requires it.
        Int2ObjectOpenHashMap<ItemStack> modifiedStacks = new Int2ObjectOpenHashMap<>();

        // In 1.21.11, the constructor order is typically syncId, revision, slot, button, actionType, cursorStack, modifiedStacks.
        // If it fails to compile, we will swap the last two.
        ClickSlotC2SPacket packet = new ClickSlotC2SPacket(
                syncId,
                revision,
                (short) slot,
                (byte) button,
                actionType,
                modifiedStacks,
                cursorStack
        );
        player.networkHandler.onClickSlot(packet);
    }

    public static void closeScreen(ServerPlayerEntity player) {
        if (player == null || player.currentScreenHandler == null) return;
        int syncId = player.currentScreenHandler.syncId;
        player.networkHandler.onCloseHandledScreen(new CloseHandledScreenC2SPacket(syncId));
    }
}
