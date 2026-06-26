package com.maohi.fakeplayer;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.HashMap;
import java.util.Map;

/**
 * Step 4: 从 VirtualPlayerManager 抽离的「背包材料决策」快照。
 *
 * 动机:
 *   - 原本 VPM.derivePhaseFromInventory / hasPendingGearCraft / hasMaterialsForPortal / countItem
 *     等多处「「从 inventory 查物品 ID」微决策」与决策点交叉重叠,重复 5+ 个多重 inventory loop。
 *   - 抽取 1 个快照接口进行 Polymorphic 复用,所有调用点走同一个 getter + 计数。
 *   - 快照是会话级只读视图,不修改 inventory。
 *
 * 与 Personality.group view 的关系:
 *   入口是 {@link BotInventorySnapshot#snapshotOf(ServerPlayerEntity)},该 View 面向背包而非 Personality。
 *
 * 使用示例:
 *   <pre>{@code
 *   var snap = BotInventorySnapshot.snapshotOf(player);
 *   if (snap.countOf(Items.OBSIDIAN) >= 10 && snap.hasInHotbar(Items.FLINT_AND_STEEL)) {
 *       // build portal
 *   }
 * }</pre>
 */
public class BotInventorySnapshot {

    /** 物品 ID 数量快照:key=ItemRegistry.getId(item)路径,value=总数。 */
    private final Map<String, Integer> itemCounts;
    /** 热门物品在 hotbar(0..8)中的位置快照:key=ItemRegistry.getId(item)路径,value=最近存位置。 */
    private final Map<String, Integer> hotbarSlots;
    /** 任意部位拥有某 item 的状态(主背包 + hotbar + equipped)。 */
    private final Map<Item, Boolean> hasIn;

    private BotInventorySnapshot(ServerPlayerEntity player) {
        this.itemCounts = new HashMap<>();
        this.hotbarSlots = new HashMap<>();
        this.hasIn = new HashMap<>();

        net.minecraft.entity.player.PlayerInventory inv = player.getInventory();
        // 主背包 + hotbar(统一扫描,hotbar 即 0..8,9..35 是 main)
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.getStack(i);
            if (s.isEmpty()) continue;
            String id = net.minecraft.registry.Registries.ITEM.getId(s.getItem()).getPath();
            itemCounts.merge(id, s.getCount(), Integer::sum);
            if (i < 9) hotbarSlots.putIfAbsent(id, i);
            hasIn.put(s.getItem(), true);
        }
        // 装备槽
        for (net.minecraft.entity.EquipmentSlot slot : net.minecraft.entity.EquipmentSlot.values()) {
            ItemStack s = player.getEquippedStack(slot);
            if (s.isEmpty()) continue;
            String id = net.minecraft.registry.Registries.ITEM.getId(s.getItem()).getPath();
            itemCounts.merge(id, s.getCount(), Integer::sum);
            hasIn.put(s.getItem(), true);
        }
    }

    /** 系列中的物品总数。例如多 talon 的 64 块同 ID(OAK_LOG 等) — 返回全背包 + 装备总数。 */
    public int countOf(Item item) {
        if (item == null) return 0;
        String id = net.minecraft.registry.Registries.ITEM.getId(item).getPath();
        return itemCounts.getOrDefault(id, 0);
    }

    /** ID 路径查询(返回 0 if missing)。供 Phase 木器时代 ID 检查使用(build portal/冶炼/勘察都是 ID 级别考量)。 */
    public int countOfId(String itemId) {
        return itemCounts.getOrDefault(itemId, 0);
    }

    /** hotbar 中是否拥有某物品。 */
    public boolean hasInHotbar(Item item) {
        if (item == null) return false;
        String id = net.minecraft.registry.Registries.ITEM.getId(item).getPath();
        return hotbarSlots.containsKey(id);
    }

    /** hotbar 槽位。—1 if 不在 hotbar。 */
    public int hotbarSlotOf(Item item) {
        if (item == null) return -1;
        String id = net.minecraft.registry.Registries.ITEM.getId(item).getPath();
        Integer slot = hotbarSlots.get(id);
        return slot == null ? -1 : slot;
    }

    /** 能否起黑曜石传送门 — 12 obsidian (1 缓冲) + 打火石。 */
    public boolean canBuildPortal() {
        return countOf(Items.OBSIDIAN) >= 12 && hasInHotbar(Items.FLINT_AND_STEEL);
    }

    /** 能否起下界门 — 最小 10 obsidian + 1 打火石(主背包任一位置)。 */
    public boolean canBuildPortalLenient() {
        return countOf(Items.OBSIDIAN) >= 10 && hasIn.containsKey(Items.FLINT_AND_STEEL);
    }

    /** 是否拥有足够铁制造全套装备(这里返回「整数计数」 — 供调用决定阈值)。 */
    public int ironIngotCount() {
        return countOfId("iron_ingot");
    }

    /** V5.80 防反向: raw_iron / ore / nugget 在本快照检测表中是不可计数为 「IRONGot」的。 */
    public int rawIronOrOre() {
        return countOfId("raw_iron") + countOfId("iron_ore");
    }

    /** 取快照。 */
    public static BotInventorySnapshot snapshotOf(ServerPlayerEntity player) {
        if (player == null) return EMPTY;
        return new BotInventorySnapshot(player);
    }

    /** 静态空快照。 */
    public static final BotInventorySnapshot EMPTY = new BotInventorySnapshot(null) {
        @Override public int countOf(Item item) { return 0; }
        @Override public int countOfId(String itemId) { return 0; }
        @Override public boolean hasInHotbar(Item item) { return false; }
        @Override public int hotbarSlotOf(Item item) { return -1; }
        @Override public boolean canBuildPortal() { return false; }
        @Override public boolean canBuildPortalLenient() { return false; }
        @Override public int ironIngotCount() { return 0; }
        @Override public int rawIronOrOre() { return 0; }
    };
}
