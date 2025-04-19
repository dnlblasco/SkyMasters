package com.codella.skymasters.managers;

import com.codella.skymasters.SkyMasters;
import com.codella.skymasters.game.Kit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public class KitManager {

    private final SkyMasters plugin;
    private final Map<String, Kit> kits = new ConcurrentHashMap<>();
    private File kitsFile;
    private FileConfiguration kitsConfig;

    public KitManager(SkyMasters plugin) {
        this.plugin = plugin;
        setupKitFile();
    }

    private void setupKitFile() {
        kitsFile = new File(plugin.getDataFolder(), "kits.yml");
        if (!kitsFile.exists()) {
            try {
                kitsFile.createNewFile();
                 plugin.getLogger().info("Created kits.yml file.");
            } catch (IOException e) {
                plugin.getLogger().log(Level.SEVERE, "Could not create kits.yml!", e);
            }
        }
        kitsConfig = YamlConfiguration.loadConfiguration(kitsFile);
    }

    public void loadKits() {
        kits.clear();
        kitsConfig = YamlConfiguration.loadConfiguration(kitsFile); // Reload fresh config

        ConfigurationSection kitsSection = kitsConfig.getConfigurationSection("kits");
        if (kitsSection == null) {
            plugin.getLogger().info("No kits found in kits.yml.");
            return;
        }

        for (String kitName : kitsSection.getKeys(false)) {
            ConfigurationSection kitSection = kitsSection.getConfigurationSection(kitName);
            if (kitSection != null) {
                String permission = kitSection.getString("permission", "skymasters.kit." + kitName.toLowerCase());
                String description = kitSection.getString("description", "A Skywars kit.");
                List<ItemStack> items = (List<ItemStack>) kitSection.getList("items", new ArrayList<>());
                ItemStack[] armor = ((List<ItemStack>) kitSection.getList("armor", new ArrayList<>())).toArray(new ItemStack[0]);

                Kit kit = new Kit(kitName, description, permission, items.toArray(new ItemStack[0]), armor);
                kits.put(kitName.toLowerCase(), kit);
            }
        }
         plugin.getLogger().info("Loaded " + kits.size() + " kits.");
    }

    public void saveKits() {
        // Clear existing kits section to avoid duplicates/old data
         kitsConfig.set("kits", null); // Remove the entire kits section

         ConfigurationSection kitsSection = kitsConfig.createSection("kits");

        for (Map.Entry<String, Kit> entry : kits.entrySet()) {
            String kitName = entry.getKey(); // Use the lowercase key
            Kit kit = entry.getValue();
            ConfigurationSection kitSection = kitsSection.createSection(kit.getName()); // Save with original case name

            kitSection.set("description", kit.getDescription());
            kitSection.set("permission", kit.getPermission());
            // Bukkit handles ItemStack serialization directly for lists
            kitSection.set("items", kit.getItems());
            kitSection.set("armor", kit.getArmorContents());
        }

        try {
            kitsConfig.save(kitsFile);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not save kits.yml!", e);
        }
    }

    public Kit getKit(String name) {
        return kits.get(name.toLowerCase());
    }

    public boolean kitExists(String name) {
        return kits.containsKey(name.toLowerCase());
    }

    public Collection<Kit> getAllKits() {
        return kits.values();
    }

     public List<Kit> getAvailableKits(Player player) {
        List<Kit> available = new ArrayList<>();
        for (Kit kit : kits.values()) {
            if (player.hasPermission(kit.getPermission()) || player.hasPermission("skymasters.kit.*")) {
                available.add(kit);
            }
        }
         // Sort alphabetically for consistent display
        available.sort(Comparator.comparing(Kit::getName, String.CASE_INSENSITIVE_ORDER));
        return available;
     }

     public boolean canUseKit(Player player, Kit kit) {
        if (kit == null) return false;
         return player.hasPermission(kit.getPermission()) || player.hasPermission("skymasters.kit.*");
     }


    public boolean createKit(String name, String description, String permission, ItemStack[] items, ItemStack[] armor) {
        if (kitExists(name)) {
            return false; // Kit already exists
        }
        Kit kit = new Kit(name, description, permission, items, armor);
        kits.put(name.toLowerCase(), kit);
        saveKits(); // Save immediately after creation
        return true;
    }

    public boolean deleteKit(String name) {
        if (!kitExists(name)) {
            return false; // Kit doesn't exist
        }
        kits.remove(name.toLowerCase());
        saveKits(); // Save immediately after deletion
        return true;
    }

    public void giveKit(Player player, Kit kit) {
        if (kit == null) return;

        player.getInventory().clear();
        player.getInventory().setArmorContents(null); // Clear armor

        player.getInventory().setContents(kit.getItems());
        player.getInventory().setArmorContents(kit.getArmorContents());
        player.updateInventory();
    }
}