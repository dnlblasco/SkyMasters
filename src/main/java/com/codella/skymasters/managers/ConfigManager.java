package com.codella.skymasters.managers;

import com.codella.skymasters.SkyMasters;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class ConfigManager {

    private final SkyMasters plugin;
    private FileConfiguration messagesConfig;
    private File messagesFile;
    private Map<String, String> messages = new HashMap<>();

    public ConfigManager(SkyMasters plugin) {
        this.plugin = plugin;
        loadMessages();
    }

    // --- General Config Access ---

    public int getMinPlayersToStart() {
        return plugin.getConfig().getInt("min-players-to-start", 2);
    }

    public int getMaxPlayersPerArena() {
        return plugin.getConfig().getInt("max-players-per-arena", 12);
    }

    public int getLobbyCountdownSeconds() {
        return plugin.getConfig().getInt("lobby-countdown-seconds", 15);
    }

    public int getChestRefillTimeSeconds() {
        return plugin.getConfig().getInt("chest-refill-time-seconds", 180);
    }

     public int getChestRefillTimeSeconds2() {
        return plugin.getConfig().getInt("chest-refill-time-seconds-2", 120);
    }

    public int getGameTimeLimitSeconds() {
        return plugin.getConfig().getInt("game-time-limit-seconds", 600);
    }

     public int getStartInvincibilitySeconds() {
        return plugin.getConfig().getInt("start-invincibility-seconds", 5);
    }

    public int getEndGameDelaySeconds() {
        return plugin.getConfig().getInt("end-game-delay-seconds", 10);
    }

    public Material getSetupWandItem() {
        String materialName = plugin.getConfig().getString("setup-wand-item", "BLAZE_ROD");
        try {
            return Material.valueOf(materialName.toUpperCase());
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("Invalid setup wand item material '" + materialName + "' in config.yml. Using BLAZE_ROD.");
            return Material.BLAZE_ROD;
        }
    }

    public boolean isAutoEquipDefaultKit() {
        return plugin.getConfig().getBoolean("auto-equip-default-kit", false);
    }

    public String getDefaultKitName() {
        return plugin.getConfig().getString("default-kit-name", "default");
    }

    public boolean isHungerLossEnabled() {
        return plugin.getConfig().getBoolean("enable-hunger-loss", true);
    }

    public boolean isNaturalMobSpawningEnabled() {
        return plugin.getConfig().getBoolean("enable-natural-mob-spawning", false);
    }

    public boolean isFallDamageEnabled() {
         return plugin.getConfig().getBoolean("enable-fall-damage", true);
    }

    public String getRegenerationMode() {
        return plugin.getConfig().getString("regeneration-mode", "PARTIAL").toUpperCase();
    }

    public Set<Material> getPlayerPlacedBlocksForPartialRegen() {
        return plugin.getConfig().getStringList("player-placed-blocks-for-partial-regen")
                .stream()
                .map(name -> {
                    try {
                        return Material.valueOf(name.toUpperCase());
                    } catch (IllegalArgumentException e) {
                        plugin.getLogger().warning("Invalid material '" + name + "' in player-placed-blocks-for-partial-regen list. Skipping.");
                        return null;
                    }
                })
                .filter(mat -> mat != null)
                .collect(Collectors.toSet());
    }

    public boolean isSpectatorsAllowed() {
         return plugin.getConfig().getBoolean("allow-spectators", true);
    }

    public double getSpectatorSpeed() {
         return plugin.getConfig().getDouble("spectator-speed", 0.2);
    }

     public boolean getSpectatorNightVision() {
         return plugin.getConfig().getBoolean("spectator-night-vision", true);
     }

    public boolean showCountdownTitle() { return plugin.getConfig().getBoolean("show-countdown-title", true); }
    public boolean showStartTitle() { return plugin.getConfig().getBoolean("show-start-title", true); }
    public boolean showWinnerTitle() { return plugin.getConfig().getBoolean("show-winner-title", true); }
    public boolean showActionBarMessages() { return plugin.getConfig().getBoolean("show-action-bar-messages", true); }

    // --- Messages ---

    public void loadMessages() {
        messagesFile = new File(plugin.getDataFolder(), "messages.yml");
        if (!messagesFile.exists()) {
            plugin.saveResource("messages.yml", false);
        }

        messagesConfig = YamlConfiguration.loadConfiguration(messagesFile);

        // Load default messages from JAR
        InputStream defaultStream = plugin.getResource("messages.yml");
        if (defaultStream != null) {
            YamlConfiguration defaultConfig = YamlConfiguration.loadConfiguration(new InputStreamReader(defaultStream));
            messagesConfig.setDefaults(defaultConfig);
            messagesConfig.options().copyDefaults(true);
            try {
                messagesConfig.save(messagesFile);
            } catch (IOException e) {
                plugin.getLogger().severe("Could not save messages.yml!");
                e.printStackTrace();
            }
        }

        // Load messages into map
        messages.clear();
        ConfigurationSection messagesSection = messagesConfig; // Assuming messages are at the root
        if (messagesSection != null) {
            for (String key : messagesSection.getKeys(false)) {
                messages.put(key, ChatColor.translateAlternateColorCodes('&', messagesSection.getString(key, "&cMissing message: " + key)));
            }
        } else {
             plugin.getLogger().severe("Could not find any messages in messages.yml!");
        }

        // Ensure prefix is loaded correctly
        if (!messages.containsKey("prefix")) {
            messages.put("prefix", ChatColor.translateAlternateColorCodes('&', "&b&lSkyMasters &8»&r "));
        }
    }

    public String getMessage(String key) {
        return messages.getOrDefault(key, messages.get("prefix") + "&cUnknown message key: " + key);
    }

    public String getMessage(String key, Map<String, String> placeholders) {
        String message = getMessage(key);
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            message = message.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return message;
    }

    public String getPrefixedMessage(String key) {
        return messages.get("prefix") + getMessage(key);
    }

    public String getPrefixedMessage(String key, Map<String, String> placeholders) {
        String message = getMessage(key, placeholders);
        // Avoid double prefix if the message itself starts with the prefix placeholder (unlikely but possible)
        if (message.startsWith("{prefix}")) {
            message = message.substring("{prefix}".length());
        }
        return messages.get("prefix") + message;
    }

    // Reload all configurations
    public void reloadConfigs() {
        plugin.reloadConfig();
        loadMessages();
        plugin.getKitManager().loadKits(); // Reload kits
        plugin.getArenaManager().reloadArenas(); // Reload arenas
    }
}