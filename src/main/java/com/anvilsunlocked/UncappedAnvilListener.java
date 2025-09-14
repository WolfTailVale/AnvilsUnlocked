package com.anvilsunlocked;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.boss.BossBar;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.Repairable;
import org.bukkit.inventory.view.AnvilView;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import net.kyori.adventure.text.Component;

/**
 * Listener that emulates vanilla anvil logic but removes the 40-level cap and
 * "Too Expensive" gate.
 * Notes:
 * - We keep vanilla cost math (prior work penalties, base costs, book halving,
 * conflict rules)
 * - Incompatible enchants: apply left-to-right priority (right overrides left
 * on conflict)
 * - Cost can exceed 40; we set repair cost directly on the inventory
 */
public final class UncappedAnvilListener implements Listener {
    // Visible cost overlay per player
    private final Map<java.util.UUID, BossBar> costBars = new HashMap<>();

    // PDC key for tracking anvil prior-uses independent of Mending repairs
    private NamespacedKey usesKey() {
        return new NamespacedKey(AnvilsUnlocked.getInstance(), "anvil_uses");
    }

    // Set of any plank materials for unit repairs of wooden gear / shields
    private static final Set<Material> ANY_PLANKS = new HashSet<>();
    static {
        for (Material m : Material.values()) {
            if (m.name().endsWith("_PLANKS"))
                ANY_PLANKS.add(m);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPrepareAnvil(PrepareAnvilEvent event) {
        AnvilInventory inv = event.getInventory();
        ItemStack left = inv.getItem(0);
        ItemStack right = inv.getItem(1);

        if (left == null || left.getType() == Material.AIR) {
            event.setResult(null);
            // Allow custom result setting by providing a non-negative cost on view
            AnvilView view = event.getView();
            view.setRepairCost(0);
            view.setMaximumRepairCost(0);
            return;
        }

        AnvilView view = event.getView();
        String renameText = view.getRenameText();

        // Rename-only: cost 1, does not change prior work uses
        if ((right == null || right.getType() == Material.AIR) && renameText != null && !renameText.isEmpty()) {
            ItemStack out = left.clone();
            ItemMeta meta = out.getItemMeta();
            meta.displayName(Component.text(renameText));
            out.setItemMeta(meta);
            view.setRepairCost(1);
            view.setMaximumRepairCost(1);
            try {
                var he = event.getView().getPlayer();
                if (he instanceof Player p) {
                    showOrUpdateCostBar(p, 1);
                }
            } catch (Throwable ignored) {
            }
            event.setResult(out);
            return;
        }

        // Try unit-material repair first (e.g., diamonds for diamond gear, planks for
        // wooden tools, membranes for elytra)
        if (right != null && right.getType() != Material.AIR) {
            Integer units = getUnitRepairUnits(left, right);
            if (units != null && units > 0) {
                ItemStack out = left.clone();
                applyUnitRepair(out, units);
                if (renameText != null && !renameText.isEmpty()) {
                    ItemMeta meta = out.getItemMeta();
                    meta.displayName(Component.text(renameText));
                    out.setItemMeta(meta);
                }

                // Uses increment: max(left,right) + 1 (right is material, so 0)
                int newUses = Math.max(getRepairUses(left), getRepairUses(right)) + 1;
                setRepairUses(out, newUses);

                // If nothing changed and no rename, refuse
                if (!hasAnyChange(left, out)) {
                    view.setRepairCost(0);
                    view.setMaximumRepairCost(0);
                    event.setResult(null);
                    return;
                }

                int cost = priorWorkPenalty(getRepairUses(left)) + priorWorkPenalty(getRepairUses(right)) + units;
                // Expose the number of materials to be consumed
                try {
                    view.setRepairItemCountCost(units);
                } catch (Throwable ignored) {
                }
                view.setRepairCost(cost);
                view.setMaximumRepairCost(Integer.MAX_VALUE); // Remove client-side "Too Expensive!" cap
                try {
                    var he = event.getView().getPlayer();
                    if (he instanceof Player p) {
                        showOrUpdateCostBar(p, cost);
                    }
                } catch (Throwable ignored) {
                }
                event.setResult(out);
                return;
            }
        }

        ItemStack output = computeAnvilResult(left, right, renameText);

        if (output == null || !hasAnyChange(left, output)) {
            event.setResult(null);
            view.setRepairCost(0);
            view.setMaximumRepairCost(0);
            try {
                var he = event.getView().getPlayer();
                if (he instanceof Player p)
                    hideCostBar(p);
            } catch (Throwable ignored) {
            }
            return;
        }

        // Compute total level cost per vanilla logic (but do not clamp to 40)
        int cost = computeTotalCost(left, right, renameText, output);
        view.setRepairCost(cost);
        view.setMaximumRepairCost(Integer.MAX_VALUE); // Remove client-side "Too Expensive!" cap
        try {
            var he = event.getView().getPlayer();
            if (he instanceof Player p) {
                showOrUpdateCostBar(p, cost);
            }
        } catch (Throwable ignored) {
        }
        event.setResult(output);
    }

    // Tail pass to defeat plugins that clamp maximum cost (e.g., to 39). Runs after
    // others when combined with softdepend.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPrepareAnvilTail(PrepareAnvilEvent event) {
        AnvilView view = event.getView();
        int max = view.getMaximumRepairCost();
        int desired = Integer.MAX_VALUE;
        if (max < desired) {
            view.setMaximumRepairCost(desired);
        }
        // Also enforce next tick in case another MONITOR handler modified it after us
        Bukkit.getScheduler().runTask(AnvilsUnlocked.getInstance(), () -> {
            try {
                int currentMax = view.getMaximumRepairCost();
                int d = Integer.MAX_VALUE;
                if (currentMax < d) {
                    view.setMaximumRepairCost(d);
                }
            } catch (Throwable ignored) {
            }
        });
    }

    // Prevent vanilla click-blocking at >40 by allowing pickup regardless of cost
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onAnvilClick(InventoryClickEvent event) {
        if (event.getInventory().getType() != InventoryType.ANVIL)
            return;
        if (event.getSlotType() != InventoryType.SlotType.RESULT)
            return;
        if (!(event.getWhoClicked() instanceof Player player))
            return;

        ItemStack result = event.getCurrentItem();
        if (result == null || result.getType() == Material.AIR)
            return;

        int cost;
        if (event.getView() instanceof AnvilView av) {
            cost = av.getRepairCost();
        } else {
            // Fallback; but on modern Paper, AnvilView will be present
            cost = 0;
        }
        if (cost <= 0)
            return;

        // If player lacks levels, vanilla blocks the click.
        // We keep vanilla requirement; user must have required levels, but no 40 cap.
        if (player.getGameMode() != org.bukkit.GameMode.CREATIVE && player.getLevel() < cost) {
            event.setCancelled(true);
            return;
        }

        // Consume levels (vanilla behavior) without capping at 40
        // Let vanilla proceed to handle the transaction and experience consumption
    }

    // When a player opens an anvil, push our unbounded maximum so the client UI
    // shows numeric cost instead of "Too Expensive!" from the start.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAnvilOpen(InventoryOpenEvent event) {
        if (event.getInventory().getType() != InventoryType.ANVIL)
            return;
        if (!(event.getView() instanceof AnvilView view))
            return;
        try {
            view.setMaximumRepairCost(Integer.MAX_VALUE);
        } catch (Throwable ignored) {
        }
        // Also next tick in case other plugins modify post-open
        Bukkit.getScheduler().runTask(AnvilsUnlocked.getInstance(), () -> {
            try {
                if (view.getMaximumRepairCost() < Integer.MAX_VALUE) {
                    view.setMaximumRepairCost(Integer.MAX_VALUE);
                }
            } catch (Throwable ignored2) {
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAnvilClose(org.bukkit.event.inventory.InventoryCloseEvent event) {
        if (event.getInventory().getType() != InventoryType.ANVIL)
            return;
        if (event.getPlayer() instanceof Player p)
            hideCostBar(p);
    }

    private void showOrUpdateCostBar(Player player, int cost) {
        try {
            BossBar bar = costBars.get(player.getUniqueId());
            boolean enough = player.getGameMode() == org.bukkit.GameMode.CREATIVE || player.getLevel() >= cost;
            org.bukkit.boss.BarColor color = enough ? org.bukkit.boss.BarColor.GREEN : org.bukkit.boss.BarColor.RED;
            String title = "Anvil cost: " + cost;
            if (bar == null) {
                bar = Bukkit.createBossBar(title, color, org.bukkit.boss.BarStyle.SOLID);
                costBars.put(player.getUniqueId(), bar);
                bar.addPlayer(player);
            } else {
                bar.setTitle(title);
                bar.setColor(color);
                if (!bar.getPlayers().contains(player))
                    bar.addPlayer(player);
            }
            double progress = 1.0;
            if (player.getGameMode() != org.bukkit.GameMode.CREATIVE && cost > 0) {
                progress = Math.min(1.0, (double) Math.min(player.getLevel(), cost) / (double) cost);
            }
            bar.setProgress(progress);
        } catch (Throwable ignored) {
        }
    }

    private void hideCostBar(Player player) {
        try {
            BossBar bar = costBars.remove(player.getUniqueId());
            if (bar != null)
                bar.removeAll();
        } catch (Throwable ignored) {
        }
    }

    private ItemStack computeAnvilResult(ItemStack left, ItemStack right, String renameText) {
        if (left == null) {
            return null;
        }

        if (right == null || right.getType() == Material.AIR) {
            // rename only
            ItemStack out = left.clone();
            if (renameText != null && !renameText.isEmpty()) {
                ItemMeta meta = out.getItemMeta();
                meta.displayName(Component.text(renameText));
                out.setItemMeta(meta);
            }
            return out;
        }

        // Handle repair by combining two of the same tool/armor (vanilla rule)
        if (left.getType() == right.getType() && left.getType().getMaxDurability() > 0) {
            ItemStack out = left.clone();
            ItemMeta outMeta = out.getItemMeta();
            // Durability combine with ~12% bonus
            if (outMeta instanceof Damageable dm) {
                int max = out.getType().getMaxDurability();
                int leftRemaining = max - dm.getDamage();
                int rightRemaining = 0;
                ItemMeta rightMeta = right.getItemMeta();
                if (rightMeta instanceof Damageable rdm) {
                    rightRemaining = max - rdm.getDamage();
                }
                int bonus = (int) Math.floor(max * 0.12);
                int totalRemaining = leftRemaining + rightRemaining + bonus;
                int newDamage = Math.max(0, max - Math.min(totalRemaining, max));
                dm.setDamage(newDamage);
                out.setItemMeta((ItemMeta) dm);
                outMeta = out.getItemMeta();
            }

            // Enchantment merge (right overrides left on conflict)
            Map<Enchantment, Integer> base = new HashMap<>(extractEnchantments(left));
            Map<Enchantment, Integer> add = extractEnchantments(right);
            for (Map.Entry<Enchantment, Integer> e : add.entrySet()) {
                Enchantment ench = e.getKey();
                int level = e.getValue();
                if (!ench.canEnchantItem(out)) {
                    continue;
                }
                if (base.containsKey(ench)) {
                    int current = base.get(ench);
                    int target = current == level ? Math.min(ench.getMaxLevel(), current + 1)
                            : Math.max(current, level);
                    base.put(ench, target);
                } else {
                    for (Enchantment existing : new ArrayList<>(base.keySet())) {
                        if (!ench.equals(existing) && ench.conflictsWith(existing)) {
                            base.remove(existing);
                        }
                    }
                    base.put(ench, Math.min(level, ench.getMaxLevel()));
                }
            }

            // Apply merged enchants to the item (regular items, not books)
            outMeta = out.getItemMeta();
            Set<Enchantment> toRemove = new HashSet<>(outMeta.getEnchants().keySet());
            for (Enchantment ench : toRemove) {
                outMeta.removeEnchant(ench);
            }
            for (Map.Entry<Enchantment, Integer> entry : base.entrySet()) {
                Enchantment ench = entry.getKey();
                int lvl = entry.getValue();
                outMeta.addEnchant(ench, Math.min(lvl, ench.getMaxLevel()), true);
            }
            out.setItemMeta(outMeta);

            int uses = Math.max(getRepairUses(left), getRepairUses(right)) + 1;
            setRepairUses(out, uses);
            if (renameText != null && !renameText.isEmpty()) {
                ItemMeta meta = out.getItemMeta();
                meta.displayName(Component.text(renameText));
                out.setItemMeta(meta);
            }
            return out;
        }

        // Enchant / book merge
        ItemStack out = left.clone();
        ItemMeta outMeta = out.getItemMeta();
        Map<Enchantment, Integer> base = new HashMap<>(extractEnchantments(left));
        Map<Enchantment, Integer> add = extractEnchantments(right);

        // Merge with conflict rule: right overrides left on conflict
        for (Map.Entry<Enchantment, Integer> e : add.entrySet()) {
            Enchantment ench = e.getKey();
            int level = e.getValue();

            // Only allow enchants that can apply to the target item; skip others (vanilla
            // rule)
            // Exception: if output is a book, allow all enchantments to be stored
            if (!(out.getType() == Material.ENCHANTED_BOOK) && !ench.canEnchantItem(out)) {
                continue;
            }

            if (base.containsKey(ench)) {
                int current = base.get(ench);
                int target = current == level ? Math.min(ench.getMaxLevel(), current + 1) : Math.max(current, level);
                base.put(ench, target);
            } else {
                // Check conflicts: if conflicts with any existing, remove the conflicting LEFT
                // enchant (right overrides)
                for (Enchantment existing : new ArrayList<>(base.keySet())) {
                    if (!ench.equals(existing) && ench.conflictsWith(existing)) {
                        base.remove(existing);
                    }
                }
                // Still allow adding the right enchant even if it conflicted
                base.put(ench, Math.min(level, ench.getMaxLevel()));
            }
        }

        // Apply enchants to meta
        outMeta = out.getItemMeta();
        // clear existing enchants then add
        if (outMeta instanceof EnchantmentStorageMeta esm) {
            // For books, clear stored enchants
            Set<Enchantment> toRemove = new HashSet<>(esm.getStoredEnchants().keySet());
            for (Enchantment ench : toRemove) {
                esm.removeStoredEnchant(ench);
            }
            for (Map.Entry<Enchantment, Integer> entry : base.entrySet()) {
                Enchantment ench = entry.getKey();
                int lvl = entry.getValue();
                esm.addStoredEnchant(ench, Math.min(lvl, ench.getMaxLevel()), true);
            }
        } else {
            // For regular items, clear regular enchants
            Set<Enchantment> toRemove = new HashSet<>(outMeta.getEnchants().keySet());
            for (Enchantment ench : toRemove) {
                outMeta.removeEnchant(ench);
            }
            for (Map.Entry<Enchantment, Integer> entry : base.entrySet()) {
                Enchantment ench = entry.getKey();
                int lvl = entry.getValue();
                outMeta.addEnchant(ench, Math.min(lvl, ench.getMaxLevel()), true);
            }
        }

        if (renameText != null && !renameText.isEmpty()) {
            outMeta.displayName(Component.text(renameText));
        }
        out.setItemMeta(outMeta);
        setRepairUses(out, Math.max(getRepairUses(left), getRepairUses(right)) + 1);
        return out;
    }

    private Map<Enchantment, Integer> extractEnchantments(ItemStack stack) {
        Map<Enchantment, Integer> out = new HashMap<>();
        if (stack == null || stack.getType() == Material.AIR)
            return out;
        ItemMeta meta = stack.getItemMeta();
        if (meta instanceof EnchantmentStorageMeta esm) {
            out.putAll(esm.getStoredEnchants());
        } else {
            out.putAll(meta.getEnchants());
        }
        return out;
    }

    private int computeTotalCost(ItemStack left, ItemStack right, String renameText, ItemStack output) {
        int cost = 0;

        // Prior work penalties
        int leftRepairCost = getRepairUses(left);
        int rightRepairCost = right != null ? getRepairUses(right) : 0;
        int priorWorkCost = priorWorkPenalty(leftRepairCost) + priorWorkPenalty(rightRepairCost);
        cost += priorWorkCost;

        // Durability repair via same-type combine adds +2 if damage reduced
        if (right != null && left.getType() == right.getType() && left.getType().getMaxDurability() > 0) {
            int beforeDamage = 0;
            int afterDamage = 0;
            ItemMeta lm = left.getItemMeta();
            if (lm instanceof Damageable ldm)
                beforeDamage = ldm.getDamage();
            ItemMeta om = output.getItemMeta();
            if (om instanceof Damageable odm)
                afterDamage = odm.getDamage();
            if (afterDamage < beforeDamage)
                cost += 2;
        }

        // Enchantment costs from right contribution; books are halved
        boolean rightIsBook = right != null && right.getItemMeta() instanceof EnchantmentStorageMeta;
        Map<Enchantment, Integer> before = extractEnchantments(left);
        Map<Enchantment, Integer> after = extractEnchantments(output);
        Map<Enchantment, Integer> rightEnchants = extractEnchantments(right);

        int enchantCost = 0;
        for (Map.Entry<Enchantment, Integer> e : rightEnchants.entrySet()) {
            Enchantment ench = e.getKey();
            int rightLvl = e.getValue();
            int prev = before.getOrDefault(ench, 0);
            Integer fin = after.get(ench);
            if (fin == null) {
                continue; // not applied
            }
            if (fin <= prev) {
                continue; // no improvement from right
            }
            int usedLevel = (rightLvl == prev) ? fin : rightLvl; // equal-level merge uses final level
            int base = enchantmentBaseCost(ench) * usedLevel;
            if (rightIsBook)
                base = (base + 1) / 2;
            enchantCost += base;
        }
        cost += enchantCost;

        // Rename cost +1 if changed name
        ItemMeta leftMeta = left.getItemMeta();
        boolean renameChanged = renameText != null && !renameText.isEmpty();
        if (renameChanged && leftMeta != null && leftMeta.hasDisplayName()) {
            Component current = leftMeta.displayName();
            if (current != null && current.equals(Component.text(renameText)))
                renameChanged = false;
        }
        if (renameChanged)
            cost += 1;

        // Ensure minimum of 1 if any change occurred
        if (cost < 1) {
            if (!Objects.equals(before, after) || renameChanged) {
                cost = 1;
            }
        }
        return cost;
    }

    private int getRepairUses(ItemStack stack) {
        if (stack == null)
            return 0;
        ItemMeta meta = stack.getItemMeta();
        if (meta == null)
            return 0;
        try {
            PersistentDataContainer pdc = meta.getPersistentDataContainer();
            Integer v = pdc.get(usesKey(), PersistentDataType.INTEGER);
            if (v != null)
                return Math.max(0, v);
        } catch (Throwable ignored) {
        }
        if (meta instanceof Repairable rep) {
            // Fallback for legacy items without our PDC marker
            return Math.max(0, rep.getRepairCost());
        }
        return 0;
    }

    private void setRepairUses(ItemStack stack, int uses) {
        if (stack == null)
            return;
        ItemMeta meta = stack.getItemMeta();
        if (meta == null)
            return;
        int u = Math.max(0, uses);
        try {
            meta.getPersistentDataContainer().set(usesKey(), PersistentDataType.INTEGER, u);
        } catch (Throwable ignored) {
        }
        if (meta instanceof Repairable rep) {
            // Mirror for cross-plugin/vanilla compatibility
            rep.setRepairCost(u);
            stack.setItemMeta((ItemMeta) rep);
        } else {
            stack.setItemMeta(meta);
        }
    }

    private int priorWorkPenalty(int repairCostField) {
        // Map uses (repairCost field) to penalty 2^uses - 1; clamp to avoid overflow
        // craziness
        int n = Math.max(0, Math.min(15, repairCostField));
        return (1 << n) - 1;
    }

    private int enchantmentBaseCost(Enchantment ench) {
        // Vanilla-like base cost multipliers (rounded to familiar community tables)
        String k = ench.getKey().getKey();
        return switch (k) {
            // Armor
            case "protection" -> 1;
            case "fire_protection" -> 2;
            case "feather_falling" -> 4;
            case "blast_protection" -> 4;
            case "projectile_protection" -> 2;
            case "respiration" -> 4;
            case "aqua_affinity" -> 4;
            case "thorns" -> 8;
            case "depth_strider" -> 4;
            case "frost_walker" -> 4;
            case "soul_speed" -> 8;
            case "swift_sneak" -> 8;

            // Tools / weapons
            case "sharpness" -> 1;
            case "smite" -> 2;
            case "bane_of_arthropods" -> 2;
            case "knockback" -> 2;
            case "fire_aspect" -> 4;
            case "looting" -> 4;
            case "sweeping" -> 4; // sweeping edge
            case "efficiency" -> 1;
            case "silk_touch" -> 8;
            case "unbreaking" -> 2;
            case "fortune" -> 4;

            // Bows
            case "power" -> 1;
            case "punch" -> 4;
            case "flame" -> 4;
            case "infinity" -> 8;

            // Trident
            case "impaling" -> 4;
            case "riptide" -> 4;
            case "loyalty" -> 4;
            case "channeling" -> 8;

            // Crossbow
            case "multishot" -> 4;
            case "piercing" -> 4;
            case "quick_charge" -> 4;

            // Fishing rod
            case "luck_of_the_sea" -> 4;
            case "lure" -> 4;

            // Misc
            case "mending" -> 4;
            case "binding_curse", "vanishing_curse" -> 8; // curses are pricey (no level ups)
            default -> 1;
        };
    }

    // ===== Unit material repair helpers =====
    private Integer getUnitRepairUnits(ItemStack left, ItemStack right) {
        Material lt = left.getType();
        Material rt = right.getType();
        if (lt == Material.AIR || rt == Material.AIR)
            return null;
        // Items with custom materials
        if (lt == Material.ELYTRA && rt == Material.PHANTOM_MEMBRANE)
            return computeUnits(left, right);
        Material SCUTE = Material.matchMaterial("SCUTE");
        Material WOLF_ARMOR = Material.matchMaterial("WOLF_ARMOR");
        Material ARMADILLO_SCUTE = Material.matchMaterial("ARMADILLO_SCUTE");
        Material MACE = Material.matchMaterial("MACE");
        Material BREEZE_ROD = Material.matchMaterial("BREEZE_ROD");
        if (lt == Material.TURTLE_HELMET && SCUTE != null && rt == SCUTE)
            return computeUnits(left, right);
        if (WOLF_ARMOR != null && lt == WOLF_ARMOR && ARMADILLO_SCUTE != null && rt == ARMADILLO_SCUTE)
            return computeUnits(left, right);
        if (MACE != null && lt == MACE && BREEZE_ROD != null && rt == BREEZE_ROD)
            return computeUnits(left, right);
        if (isLeatherArmor(lt) && rt == Material.LEATHER)
            return computeUnits(left, right);
        if (isChainmailArmor(lt) && rt == Material.IRON_INGOT)
            return computeUnits(left, right);
        if (isWoodenToolOrArmor(lt) && ANY_PLANKS.contains(rt))
            return computeUnits(left, right);
        if (lt == Material.SHIELD && ANY_PLANKS.contains(rt))
            return computeUnits(left, right);
        if (isStoneTool(lt)
                && (rt == Material.COBBLESTONE || rt == Material.COBBLED_DEEPSLATE || rt == Material.BLACKSTONE))
            return computeUnits(left, right);
        if (isIronToolOrArmor(lt) && rt == Material.IRON_INGOT)
            return computeUnits(left, right);
        if (isGoldenToolOrArmor(lt) && rt == Material.GOLD_INGOT)
            return computeUnits(left, right);
        if (isDiamondToolOrArmor(lt) && rt == Material.DIAMOND)
            return computeUnits(left, right);
        if (isNetheriteToolOrArmor(lt) && rt == Material.NETHERITE_INGOT)
            return computeUnits(left, right);
        return null;
    }

    private int computeUnits(ItemStack left, ItemStack right) {
        ItemMeta meta = left.getItemMeta();
        if (!(meta instanceof Damageable dm))
            return 0;
        int max = left.getType().getMaxDurability();
        if (max <= 0)
            return 0;
        int damage = dm.getDamage();
        if (damage <= 0)
            return 0;
        int perUnit = Math.max(1, max / 4);
        int needed = (int) Math.ceil(damage / (double) perUnit);
        return Math.max(0, Math.min(needed, right.getAmount()));
    }

    private int applyUnitRepair(ItemStack out, int units) {
        ItemMeta meta = out.getItemMeta();
        if (!(meta instanceof Damageable dm))
            return 0;
        int max = out.getType().getMaxDurability();
        int perUnit = Math.max(1, max / 4);
        int total = perUnit * units;
        int before = dm.getDamage();
        dm.setDamage(Math.max(0, before - total));
        out.setItemMeta((ItemMeta) dm);
        return Math.max(0, before - ((Damageable) out.getItemMeta()).getDamage());
    }

    private boolean isLeatherArmor(Material m) {
        return m == Material.LEATHER_HELMET || m == Material.LEATHER_CHESTPLATE || m == Material.LEATHER_LEGGINGS
                || m == Material.LEATHER_BOOTS;
    }

    private boolean isChainmailArmor(Material m) {
        return m == Material.CHAINMAIL_HELMET || m == Material.CHAINMAIL_CHESTPLATE || m == Material.CHAINMAIL_LEGGINGS
                || m == Material.CHAINMAIL_BOOTS;
    }

    private boolean isWoodenToolOrArmor(Material m) {
        return m.name().startsWith("WOODEN_");
    }

    private boolean isStoneTool(Material m) {
        return m.name().startsWith("STONE_");
    }

    private boolean isIronToolOrArmor(Material m) {
        return m.name().startsWith("IRON_") || isChainmailArmor(m);
    }

    private boolean isGoldenToolOrArmor(Material m) {
        return m.name().startsWith("GOLDEN_");
    }

    private boolean isDiamondToolOrArmor(Material m) {
        return m.name().startsWith("DIAMOND_");
    }

    private boolean isNetheriteToolOrArmor(Material m) {
        return m.name().startsWith("NETHERITE_");
    }

    private boolean hasAnyChange(ItemStack before, ItemStack after) {
        if (before == null && after == null)
            return false;
        if (before == null || after == null)
            return true;

        Map<Enchantment, Integer> beforeEnchants = extractEnchantments(before);
        Map<Enchantment, Integer> afterEnchants = extractEnchantments(after);
        boolean enchantsChanged = !Objects.equals(beforeEnchants, afterEnchants);

        if (enchantsChanged)
            return true;
        ItemMeta bm = before.getItemMeta();
        ItemMeta am = after.getItemMeta();
        if (bm instanceof Damageable bdm && am instanceof Damageable adm) {
            boolean damageChanged = bdm.getDamage() != adm.getDamage();
            if (damageChanged)
                return true;
        }
        Component bn = bm != null ? bm.displayName() : null;
        Component an = am != null ? am.displayName() : null;
        boolean nameChanged = !Objects.equals(bn, an);
        return nameChanged;
    }
}
