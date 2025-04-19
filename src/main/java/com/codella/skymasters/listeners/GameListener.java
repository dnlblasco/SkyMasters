package com.codella.skymasters.listeners;

import com.codella.skymasters.SkyMasters;
import com.codella.skymasters.game.Arena;
import com.codella.skymasters.game.GameState;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.CreatureSpawnEvent; // Correct import for SpawnReason
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.weather.WeatherChangeEvent;
import org.bukkit.Location;

import java.util.Set; // Added missing import

public class GameListener implements Listener {

    private final SkyMasters plugin;
    // We don't need to cache this, ConfigManager handles it.

    public GameListener(SkyMasters plugin) {
        this.plugin = plugin;
    }

    // Control block breaking during the game
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        Arena arena = plugin.getArenaManager().getPlayerArena(player);

        if (arena == null) {
            // Player isn't participating, check if breaking inside *any* arena bounds
            arena = findArenaAtLocation(event.getBlock().getLocation());
             if (arena != null && arena.getState() != GameState.DISABLED && !player.hasPermission("skymasters.admin.bypass")) {
                 // Protect arena blocks even from non-participants unless bypassed
                 event.setCancelled(true);
                 player.sendMessage(plugin.getConfigManager().getPrefixedMessage("cannot-modify-arena"));
                 return;
             } else {
                 // Allow bypass or not inside an arena
                 return;
             }
        }


        // Player is participating in the arena
        if (arena.isSpectator(player) || arena.getState() != GameState.IN_GAME) {
            // Spectators or players in non-game states cannot break blocks
            event.setCancelled(true);
             if(arena.isSpectator(player)) {
                player.sendMessage(plugin.getConfigManager().getPrefixedMessage("cannot-interact-spectator"));
             }
        } else {
            // Player is in game - Allow breaking.
            // PARTIAL regen: We don't care about breaks, only places.
            // FULL regen: Initial state is saved, break is fine.
             // Optional: Restrict breaking specific materials like chests? Not typical for skywars.
        }
    }

    // Control block placing during the game
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        Arena arena = plugin.getArenaManager().getPlayerArena(player);
         Block blockPlaced = event.getBlockPlaced();

        if (arena == null) {
             // Protect arena bounds from outside placement too
             arena = findArenaAtLocation(event.getBlock().getLocation());
             if (arena != null && arena.getState() != GameState.DISABLED && !player.hasPermission("skymasters.admin.bypass")) {
                 event.setCancelled(true);
                 player.sendMessage(plugin.getConfigManager().getPrefixedMessage("cannot-modify-arena"));
                 return;
             } else {
                  // Allow bypass or not inside an arena
                  return;
             }
         }

        // Player is participating
        if (arena.isSpectator(player) || arena.getState() != GameState.IN_GAME) {
            // Spectators or players in non-game states cannot place blocks
            event.setCancelled(true);
             if(arena.isSpectator(player)) {
                player.sendMessage(plugin.getConfigManager().getPrefixedMessage("cannot-interact-spectator"));
             }
        } else {
            // Player is in game
            // Track placed block for PARTIAL regeneration
             arena.addPlayerPlacedBlock(blockPlaced.getLocation());
        }
    }


    // Prevent natural mob spawning if configured
     @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
     public void onEntitySpawn(EntitySpawnEvent event) {
         if (!plugin.getConfigManager().isNaturalMobSpawningEnabled()) {
             Arena arena = findArenaAtLocation(event.getLocation());
             // Check if it's a "natural" spawn
              boolean isNatural = event instanceof CreatureSpawnEvent &&
                                 ((CreatureSpawnEvent) event).getSpawnReason() == CreatureSpawnEvent.SpawnReason.NATURAL;

             if (arena != null && arena.getState() != GameState.DISABLED && isNatural) {
                  // We are inside an arena and natural spawning is disabled in config & it's a natural spawn
                  event.setCancelled(true);
              }
         }
     }

     // Prevent weather changes within arena worlds? (Optional, might be better per-player)
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
     public void onWeatherChange(WeatherChangeEvent event) {
         // Only cancel if changing *to* rain/storm
         if (!event.toWeatherState()) {
             return;
         }

         boolean worldHasActiveArena = false;
         for (Arena arena : plugin.getArenaManager().getAllArenas()) {
             // Check if the arena is active (not disabled, not regenerating) and in this world
             if (arena.getState() != GameState.DISABLED && arena.getState() != GameState.REGENERATING &&
                 arena.getWorld() != null && arena.getWorld().equals(event.getWorld())) {
                 worldHasActiveArena = true;
                 break;
              }
         }

         if (worldHasActiveArena) {
              event.setCancelled(true);
         }
     }


     // Helper method to find an arena containing a specific location
     private Arena findArenaAtLocation(Location location) {
         if (location == null) return null;
         for (Arena arena : plugin.getArenaManager().getAllArenas()) {
              // Check world first for efficiency
              if (arena.getWorld() != null && arena.getWorld().equals(location.getWorld())) {
                 if (arena.isWithinBounds(location)) {
                     return arena;
                 }
             }
         }
         return null;
     }

}