package com.codella.skymasters.game;

import org.bukkit.inventory.ItemStack;

import java.util.Arrays;

public class Kit {

    private final String name;
    private final String description;
    private final String permission;
    private final ItemStack[] items; // Main inventory items
    private final ItemStack[] armorContents; // Armor slots

    public Kit(String name, String description, String permission, ItemStack[] items, ItemStack[] armorContents) {
        this.name = name;
        this.description = description;
        this.permission = permission;
        this.items = items != null ? items : new ItemStack[0]; // Ensure not null
        this.armorContents = armorContents != null ? armorContents : new ItemStack[4]; // Ensure size 4 array
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getPermission() {
        return permission;
    }

    public ItemStack[] getItems() {
        // Return a copy to prevent modification of the original kit items
        return Arrays.stream(items).map(is -> is != null ? is.clone() : null).toArray(ItemStack[]::new);
    }

    public ItemStack[] getArmorContents() {
        // Return a copy
        return Arrays.stream(armorContents).map(is -> is != null ? is.clone() : null).toArray(ItemStack[]::new);
    }
}