package com.codella.skymasters.managers;

import com.codella.skymasters.SkyMasters;
import com.codella.skymasters.game.Arena;
import com.codella.skymasters.objects.SetupSession;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack; // Added missing import

import java.util.*;

public class SetupManager {

    private final SkyMasters plugin;
    private final Map<UUID, SetupSession> setupSessions = new HashMap<>();

    public SetupManager(SkyMasters plugin) {
        this.plugin = plugin;
    }

    public boolean isInSetupMode(Player player) {
        return setupSessions.containsKey(player.getUniqueId());
    }

    public SetupSession getSession(Player player) {
        return setupSessions.get(player.getUniqueId());
    }

    public void startSetup(Player player, String arenaName) {
        if (isInSetupMode(player)) {
             player.sendMessage(plugin.getConfigManager().getPrefixedMessage("setup-already-in-mode"));
             return;
        }

        Arena arena = plugin.getArenaManager().getArena(arenaName);
        if (arena == null) {
            // Arena doesn't exist, create it
            plugin.getArenaManager().createArena(arenaName);
            arena = plugin.getArenaManager().getArena(arenaName);
            if (arena == null) { // Should not happen, but safety check
                 player.sendMessage(plugin.getConfigManager().getPrefixedMessage("arena-creation-failed"));
                 return;
            }
             player.sendMessage(plugin.getConfigManager().getPrefixedMessage("arena-created-start-setup", Map.of("arena", arenaName)));
        }

        // Load existing data into the session if arena already had some setup
        SetupSession session = new SetupSession(arenaName);
        if(arena.getCorner1() != null) session.setPos1(arena.getCorner1());
        if(arena.getCorner2() != null) session.setPos2(arena.getCorner2());
        if(arena.getLobbySpawn() != null) session.setLobbySpawn(arena.getLobbySpawn());
        if(arena.getSpectatorSpawn() != null) session.setSpectatorSpawn(arena.getSpectatorSpawn());
        if(arena.getCenter() != null) session.setCenter(arena.getCenter());
        // Ensure lists are not null before adding
        if (arena.getPlayerSpawns() != null) session.getPlayerSpawns().addAll(arena.getPlayerSpawns());
        if (arena.getChestLocations() != null) session.getChestLocations().addAll(arena.getChestLocations());


        setupSessions.put(player.getUniqueId(), session);
        player.sendMessage(plugin.getConfigManager().getPrefixedMessage("setup-mode-enter", Map.of("arena", arenaName)));
        // Give setup wand if configured
        ItemStack wandItem = new ItemStack(plugin.getConfigManager().getSetupWandItem()); // Use new ItemStack()
        player.getInventory().addItem(wandItem);
         player.sendMessage(plugin.getConfigManager().getPrefixedMessage("setup-wand-give"));
    }

    public void finishSetup(Player player) {
        if (!isInSetupMode(player)) {
            player.sendMessage(plugin.getConfigManager().getPrefixedMessage("setup-not-in-setup-mode"));
            return;
        }

        SetupSession session = getSession(player);
        Arena arena = plugin.getArenaManager().getArena(session.getArenaName());
        if (arena == null) {
            player.sendMessage(plugin.getConfigManager().getPrefixedMessage("arena-not-found", Map.of("arena", session.getArenaName())));
            endSetup(player); // Clean up session anyway
            return;
        }

        // Validate completeness
         String missing = checkCompleteness(session);
         if (missing != null) {
            player.sendMessage(plugin.getConfigManager().getPrefixedMessage("setup-finish-incomplete", Map.of("missing", missing)));
            return;
         }

        player.sendMessage(plugin.getConfigManager().getPrefixedMessage("setup-finish-confirm", Map.of("arena", session.getArenaName())));

        // Apply session data to the arena
        arena.setCorner1(session.getPos1());
        arena.setCorner2(session.getPos2());
        arena.setLobbySpawn(session.getLobbySpawn());
        arena.setSpectatorSpawn(session.getSpectatorSpawn());
        arena.setCenter(session.getCenter());
        arena.setPlayerSpawns(new ArrayList<>(session.getPlayerSpawns())); // Copy list
        arena.setChestLocations(new ArrayList<>(session.getChestLocations())); // Copy list

         // Save the arena config first *without* block data
        plugin.getArenaManager().saveArena(arena);


        // Attempt to save initial state for FULL regeneration *after* saving setup
        // This happens BEFORE enabling the arena.
        if ("FULL".equals(plugin.getConfigManager().getRegenerationMode())) {
            arena.saveInitialState();
            // Only save again if initial state save was successful and produced data
            if (arena.getOriginalBlockData() != null && !arena.getOriginalBlockData().isEmpty()) {
                plugin.getArenaManager().saveArena(arena); // Re-save to include block data
            }
        }


        player.sendMessage(plugin.getConfigManager().getPrefixedMessage("setup-finish-success", Map.of("arena", session.getArenaName())));
        endSetup(player); // Exit setup mode
    }

    public void endSetup(Player player) {
        SetupSession session = setupSessions.remove(player.getUniqueId());
        if (session != null) {
            player.sendMessage(plugin.getConfigManager().getPrefixedMessage("setup-mode-exit", Map.of("arena", session.getArenaName())));
            // Remove setup wand if player still has it
             player.getInventory().remove(plugin.getConfigManager().getSetupWandItem());
        }
    }

    public void handleWandClick(Player player, Location location, boolean leftClick) {
        if (!isInSetupMode(player)) return;
        SetupSession session = getSession(player);

        if (leftClick) {
            session.setPos1(location);
            player.sendMessage(plugin.getConfigManager().getPrefixedMessage("setup-pos1-set"));
        } else {
            session.setPos2(location);
            player.sendMessage(plugin.getConfigManager().getPrefixedMessage("setup-pos2-set"));
        }
        // Check if both are set and provide feedback if needed
        if (session.getPos1() != null && session.getPos2() != null) {
             player.sendMessage(plugin.getConfigManager().getPrefixedMessage("setup-bounds-defined"));
        }
    }

     public void addSpawnPoint(Player player) {
         if (!isInSetupMode(player)) {
             player.sendMessage(plugin.getConfigManager().getPrefixedMessage("setup-not-in-setup-mode"));
             return;
         }
         SetupSession session = getSession(player);
          // Use precise player location, keep pitch/yaw
         Location loc = player.getLocation();
         session.getPlayerSpawns().add(loc);
         player.sendMessage(plugin.getConfigManager().getPrefixedMessage("setup-spawn-added", Map.of("index", String.valueOf(session.getPlayerSpawns().size()))));
     }

    public void addOrRemoveChest(Player player, Block targetBlock) {
        if (!isInSetupMode(player)) {
             player.sendMessage(plugin.getConfigManager().getPrefixedMessage("setup-not-in-setup-mode"));
             return;
         }
         if (!(targetBlock.getState() instanceof Chest)) {
            player.sendMessage(plugin.getConfigManager().getPrefixedMessage("setup-invalid-block-chest"));
            return;
         }

         SetupSession session = getSession(player);
         Location chestLoc = targetBlock.getLocation();

        // Check if this location is already tracked
        boolean removed = session.getChestLocations().removeIf(loc ->
            loc.getWorld().equals(chestLoc.getWorld()) &&
            loc.getBlockX() == chestLoc.getBlockX() &&
            loc.getBlockY() == chestLoc.getBlockY() &&
            loc.getBlockZ() == chestLoc.getBlockZ()
        );


        if (removed) {
             player.sendMessage(plugin.getConfigManager().getPrefixedMessage("setup-chest-removed", Map.of("count", String.valueOf(session.getChestLocations().size()))));
        } else {
             session.getChestLocations().add(chestLoc);
             player.sendMessage(plugin.getConfigManager().getPrefixedMessage("setup-chest-added", Map.of("count", String.valueOf(session.getChestLocations().size()))));
         }
    }


     public void setLobbySpawn(Player player) {
        if (!isInSetupMode(player)) {
            player.sendMessage(plugin.getConfigManager().getPrefixedMessage("setup-not-in-setup-mode"));
            return;
        }
        SetupSession session = getSession(player);
        session.setLobbySpawn(player.getLocation());
        player.sendMessage(plugin.getConfigManager().getPrefixedMessage("setup-lobby-set"));
     }

     public void setSpectatorSpawn(Player player) {
         if (!isInSetupMode(player)) {
             player.sendMessage(plugin.getConfigManager().getPrefixedMessage("setup-not-in-setup-mode"));
             return;
         }
         SetupSession session = getSession(player);
         session.setSpectatorSpawn(player.getLocation());
         player.sendMessage(plugin.getConfigManager().getPrefixedMessage("setup-spectator-spawn-set"));
     }

      public void setCenter(Player player) {
         if (!isInSetupMode(player)) {
             player.sendMessage(plugin.getConfigManager().getPrefixedMessage("setup-not-in-setup-mode"));
             return;
         }
         SetupSession session = getSession(player);
         session.setCenter(player.getLocation());
         player.sendMessage(plugin.getConfigManager().getPrefixedMessage("setup-center-set"));
      }


    private String checkCompleteness(SetupSession session) {
         List<String> missingItems = new ArrayList<>();
         int minSpawns = plugin.getConfigManager().getMinPlayersToStart(); // Base required spawns on min players

         if (session.getPos1() == null || session.getPos2() == null) {
             missingItems.add(plugin.getConfigManager().getMessage("setup-missing-bounds"));
         }
         if (session.getPlayerSpawns().size() < minSpawns) {
              missingItems.add(plugin.getConfigManager().getMessage("setup-missing-spawns", Map.of("min", String.valueOf(minSpawns), "current", String.valueOf(session.getPlayerSpawns().size()))));
         }
         if (session.getLobbySpawn() == null) {
             missingItems.add(plugin.getConfigManager().getMessage("setup-missing-lobby"));
         }
        if (session.getSpectatorSpawn() == null) {
             missingItems.add(plugin.getConfigManager().getMessage("setup-missing-spectator"));
         }
        // Chests and Center are optional for basic functionality

        return missingItems.isEmpty() ? null : String.join(", ", missingItems);
    }
}