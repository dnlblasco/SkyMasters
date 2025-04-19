package com.codella.skymasters.managers;

import com.codella.skymasters.SkyMasters;
import com.codella.skymasters.game.Arena;
import com.codella.skymasters.game.GameState;
import com.codella.skymasters.utils.LocationUtil;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.stream.Collectors;

public class ArenaManager {

    private final SkyMasters plugin;
    private final Map<String, Arena> arenas = new ConcurrentHashMap<>();
    private final Map<UUID, Arena> playerArenas = new ConcurrentHashMap<>();

    public ArenaManager(SkyMasters plugin) {
        this.plugin = plugin;
    }

    public void loadArenas() {
        arenas.clear();
        File arenasFolder = plugin.getArenasFolder();
        File[] arenaFiles = arenasFolder.listFiles((dir, name) -> name.toLowerCase().endsWith(".yml"));

        if (arenaFiles == null || arenaFiles.length == 0) {
            plugin.getLogger().info("No arena configuration files found.");
            return;
        }

        for (File arenaFile : arenaFiles) {
            String arenaName = arenaFile.getName().replace(".yml", "");
            FileConfiguration arenaConfig = YamlConfiguration.loadConfiguration(arenaFile);

            try {
                // Basic validation
                if (!arenaConfig.contains("name") || !arenaConfig.getString("name", "").equalsIgnoreCase(arenaName)) {
                     plugin.getLogger().warning("Arena file " + arenaFile.getName() + " seems corrupted or missing name. Skipping.");
                     continue;
                }
                 if (!arenaConfig.contains("enabled")) {
                    plugin.getLogger().warning("Arena '" + arenaName + "' is missing 'enabled' status in config. Assuming disabled.");
                    // Set default in memory, but don't save automatically here
                    arenaConfig.set("enabled", false);
                 }
                 boolean enabled = arenaConfig.getBoolean("enabled", false);

                // Load crucial locations
                Location lobbySpawn = LocationUtil.deserializeLocation(arenaConfig.getString("lobbySpawn"));
                Location spectatorSpawn = LocationUtil.deserializeLocation(arenaConfig.getString("spectatorSpawn"));
                Location corner1 = LocationUtil.deserializeLocation(arenaConfig.getString("bounds.corner1"));
                Location corner2 = LocationUtil.deserializeLocation(arenaConfig.getString("bounds.corner2"));
                Location center = LocationUtil.deserializeLocation(arenaConfig.getString("center"));

                List<Location> playerSpawns = new ArrayList<>();
                List<String> spawnStrings = arenaConfig.getStringList("playerSpawns");
                for (String s : spawnStrings) {
                    playerSpawns.add(LocationUtil.deserializeLocation(s));
                }

                List<Location> chestLocations = new ArrayList<>();
                List<String> chestStrings = arenaConfig.getStringList("chestLocations");
                 for (String s : chestStrings) {
                     chestLocations.add(LocationUtil.deserializeLocation(s));
                 }


                // Validate required components before creating arena
                if (lobbySpawn == null || spectatorSpawn == null || corner1 == null || corner2 == null || playerSpawns.isEmpty()) {
                     plugin.getLogger().warning("Arena '" + arenaName + "' is missing critical location data (lobby, spectator, bounds, or spawns). Skipping load.");
                     continue;
                }
                 if (playerSpawns.size() < plugin.getConfigManager().getMinPlayersToStart()) {
                     plugin.getLogger().warning("Arena '" + arenaName + "' has only " + playerSpawns.size() + " spawns, less than min-players-to-start (" + plugin.getConfigManager().getMinPlayersToStart() + "). Arena might not start.");
                 }


                Arena arena = new Arena(plugin, arenaName, enabled, lobbySpawn, spectatorSpawn, playerSpawns, chestLocations, corner1, corner2, center);
                arenas.put(arenaName.toLowerCase(), arena);

                 if(enabled) {
                     plugin.getLogger().info("Loaded enabled arena: " + arenaName);
                 } else {
                      plugin.getLogger().info("Loaded disabled arena: " + arenaName);
                 }

            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to load arena configuration: " + arenaName, e);
            }
        }
         plugin.getLogger().info("Finished loading " + arenas.size() + " arenas.");
    }

    public void saveArena(Arena arena) {
        File arenaFile = new File(plugin.getArenasFolder(), arena.getName() + ".yml");
        FileConfiguration arenaConfig = new YamlConfiguration();

        arenaConfig.set("name", arena.getName());
        arenaConfig.set("enabled", arena.isEnabled()); // Save enabled state

        if (arena.getLobbySpawn() != null) arenaConfig.set("lobbySpawn", LocationUtil.serializeLocation(arena.getLobbySpawn()));
        if (arena.getSpectatorSpawn() != null) arenaConfig.set("spectatorSpawn", LocationUtil.serializeLocation(arena.getSpectatorSpawn()));
        if (arena.getCorner1() != null) arenaConfig.set("bounds.corner1", LocationUtil.serializeLocation(arena.getCorner1()));
        if (arena.getCorner2() != null) arenaConfig.set("bounds.corner2", LocationUtil.serializeLocation(arena.getCorner2()));
        if (arena.getCenter() != null) arenaConfig.set("center", LocationUtil.serializeLocation(arena.getCenter()));


        List<String> spawnStrings = arena.getPlayerSpawns().stream()
                .map(LocationUtil::serializeLocation)
                .collect(Collectors.toList());
        arenaConfig.set("playerSpawns", spawnStrings);

         List<String> chestStrings = arena.getChestLocations().stream()
                .map(LocationUtil::serializeLocation)
                .collect(Collectors.toList());
        arenaConfig.set("chestLocations", chestStrings);

        // Save block data only if using FULL regeneration and data exists
        if ("FULL".equals(plugin.getConfigManager().getRegenerationMode()) && arena.getOriginalBlockData() != null && !arena.getOriginalBlockData().isEmpty()) {
            // This can make the file huge. Consider alternative storage if this becomes an issue.
             Map<String, String> serializedBlocks = new HashMap<>();
             arena.getOriginalBlockData().forEach((loc, blockData) -> {
                 // Use a relative coordinate system based on a corner? Simpler for now: World:X:Y:Z -> Material:DataString
                 String key = LocationUtil.serializeLocationMinimal(loc); // Minimal string representation
                 String value = blockData.getMaterial().name() + "||" + blockData.getAsString(); // Separator unlikely to be in data
                 serializedBlocks.put(key, value);
             });
             arenaConfig.set("originalBlocks", serializedBlocks); // Store as a map section
        } else {
            arenaConfig.set("originalBlocks", null); // Ensure old data is removed if not used
        }


        try {
            arenaConfig.save(arenaFile);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not save arena file: " + arena.getName(), e);
        }
    }


    public Arena getArena(String name) {
        return arenas.get(name.toLowerCase());
    }

    public Collection<Arena> getAllArenas() {
        return arenas.values();
    }

    public Arena getPlayerArena(Player player) {
        return playerArenas.get(player.getUniqueId());
    }

    public boolean isPlayerInArena(Player player) {
        return playerArenas.containsKey(player.getUniqueId());
    }

    public void addPlayerToArena(Player player, Arena arena) {
        if (isPlayerInArena(player)) {
             player.sendMessage(plugin.getConfigManager().getPrefixedMessage("already-in-arena"));
             return;
        }
        if (arena.addPlayer(player)) {
            playerArenas.put(player.getUniqueId(), arena);
        }
    }

    public void removePlayerFromArena(Player player) {
        Arena arena = playerArenas.remove(player.getUniqueId());
        if (arena != null) {
            arena.removePlayer(player);
        }
    }

    public void createArena(String name) {
         if (arenas.containsKey(name.toLowerCase())) {
             // Optionally handle error: arena already exists
             return;
         }
         // Create a basic Arena object, expecting setup to fill details
         Arena newArena = new Arena(plugin, name, false, null, null, new ArrayList<>(), new ArrayList<>(), null, null, null);
         arenas.put(name.toLowerCase(), newArena);
         saveArena(newArena); // Save the initial empty file
    }

     public void deleteArena(String name) {
         Arena arena = arenas.remove(name.toLowerCase());
         if (arena != null) {
             arena.stopGame(true); // Force stop if running
             File arenaFile = new File(plugin.getArenasFolder(), arena.getName() + ".yml");
             if (arenaFile.exists()) {
                 arenaFile.delete();
             }
             // Kick any players still lingering (should be handled by stopGame usually)
             arena.getPlayers().forEach(uuid -> {
                 Player p = Bukkit.getPlayer(uuid);
                 if(p != null) removePlayerFromArena(p); // Let removePlayer handle state removal
             });
             arena.getSpectators().forEach(uuid -> {
                 Player p = Bukkit.getPlayer(uuid);
                  if(p != null) arena.removeSpectator(p, true); // Force remove spectator
             });
         }
     }

    public void enableArena(String name) {
         Arena arena = getArena(name);
         if (arena != null) {
             if (!arena.isFullySetup()) {
                  plugin.getLogger().warning("Cannot enable arena '" + name + "' because it's not fully configured.");
                  // Maybe send a message to an admin if command-triggered?
                  return;
             }
             arena.setEnabled(true);
             // If using FULL regeneration, attempt to save the initial state now
             if ("FULL".equals(plugin.getConfigManager().getRegenerationMode())) {
                 arena.saveInitialState();
             }
             saveArena(arena);
         }
    }

     public void disableArena(String name) {
         Arena arena = getArena(name);
         if (arena != null) {
             arena.setEnabled(false);
             arena.stopGame(true); // Stop the game if running
             saveArena(arena);
         }
     }

     public void stopAllArenas() {
         plugin.getLogger().info("Stopping all active arenas...");
         arenas.values().forEach(arena -> {
             if (arena.getState() != GameState.WAITING && arena.getState() != GameState.DISABLED) {
                 arena.stopGame(true); // Force stop
             }
         });
         plugin.getLogger().info("All active arenas stopped.");
     }

     public void reloadArenas() {
         plugin.getLogger().info("Reloading arena configurations...");
         stopAllArenas(); // Stop current games before reloading
         playerArenas.clear(); // Clear player tracking
         loadArenas(); // Reload from files
     }

      public Arena findAvailableArena() {
         for (Arena arena : arenas.values()) {
             if (arena.isEnabled() && arena.getState() == GameState.WAITING && arena.getPlayers().size() < plugin.getConfigManager().getMaxPlayersPerArena()) {
                 return arena;
             }
         }
         return null; // No suitable arena found
      }
}