package com.codella.skymasters.listeners;

import com.codella.skymasters.SkyMasters;
import com.codella.skymasters.game.Arena;
import com.codella.skymasters.game.GameState;
import com.codella.skymasters.game.Kit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.*;

import java.util.Map;

public class PlayerListener implements Listener {

    private final SkyMasters plugin;

    public PlayerListener(SkyMasters plugin) {
        this.plugin = plugin;
    }

    // Handle player joining the server
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        // Reset player state if needed (e.g., if they crashed mid-game previously)
        // This is complex, might need persistent storage of player state if necessary.
        // For now, we assume they are not in an arena on login.
    }

    // Handle player leaving the server
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        // If player is in an arena, remove them
        Arena arena = plugin.getArenaManager().getPlayerArena(player);
        if (arena != null) {
            arena.removePlayer(player); // Arena handles logic (messages, state checks)
        }
        // If player was in setup mode, cancel it
        if (plugin.getSetupManager().isInSetupMode(player)) {
            plugin.getSetupManager().endSetup(player);
        }
    }

    // Handle player death
    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        Arena arena = plugin.getArenaManager().getPlayerArena(player);

        if (arena != null && arena.getState() == GameState.IN_GAME) {
            event.getDrops().clear(); // Clear drops in Skywars game
            event.setDroppedExp(0); // Clear EXP
            event.setDeathMessage(null); // We handle our own death messages

            arena.handlePlayerDeath(player, player.getKiller());

            // Force respawn instantly as spectator or remove
             plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (player.isOnline()) { // Make sure player didn't log out exact moment of death
                    player.spigot().respawn(); // Force respawn

                     // Logic to make player a spectator is now inside Arena.handlePlayerDeath
                     // Or, if spectators are disabled, player will be removed from the arena entirely.
                 }
            }, 1L); // Delay 1 tick to ensure respawn happens correctly
        }
    }

    // Handle interactions for setup wand and spectator prevention
    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        Arena arena = plugin.getArenaManager().getPlayerArena(player);
        Material setupWand = plugin.getConfigManager().getSetupWandItem();

        // --- Spectator Interaction Prevention ---
         if (arena != null && arena.isSpectator(player)) {
             // Prevent all interactions for spectators
             event.setCancelled(true);
             player.sendMessage(plugin.getConfigManager().getPrefixedMessage("cannot-interact-spectator"));
             return; // Don't process further for spectators
         }

        // --- Setup Wand Logic ---
        if (plugin.getSetupManager().isInSetupMode(player) && event.getItem() != null && event.getItem().getType() == setupWand) {
             Action action = event.getAction();
             Block clickedBlock = event.getClickedBlock();

             if (action == Action.LEFT_CLICK_BLOCK) {
                 event.setCancelled(true); // Prevent breaking block
                 plugin.getSetupManager().handleWandClick(player, clickedBlock.getLocation(), true); // Left click = Pos1
             } else if (action == Action.RIGHT_CLICK_BLOCK) {
                  event.setCancelled(true); // Prevent interacting with block
                  // Check if clicking a chest for add/remove chest logic
                  if (clickedBlock.getType() == Material.CHEST || clickedBlock.getType() == Material.TRAPPED_CHEST) {
                      plugin.getSetupManager().addOrRemoveChest(player, clickedBlock);
                  } else {
                      // Otherwise, Right click = Pos2
                      plugin.getSetupManager().handleWandClick(player, clickedBlock.getLocation(), false);
                  }
              } else if (action == Action.LEFT_CLICK_AIR || action == Action.RIGHT_CLICK_AIR) {
                 // Allow air clicks if needed for some future setup feature?
                 // Currently not used for core setup.
              }
              // Do not return yet, other listeners might need this event if not setup related.
        }

         // --- General Game Interaction Restrictions ---
         if (arena != null && (arena.getState() == GameState.WAITING || arena.getState() == GameState.STARTING || arena.getState() == GameState.ENDING)) {
             // Prevent block breaking/placing, etc. during non-game phases unless admin?
             if (!player.hasPermission("skymasters.admin.bypass")) { // Add bypass permission if needed
                 if (event.getAction() == Action.LEFT_CLICK_BLOCK || event.getAction() == Action.RIGHT_CLICK_BLOCK) {
                     // Allow interaction with specific things like kit signs or GUIs?
                     // For now, cancel most block interactions.
                      Material clickedType = event.getClickedBlock() != null ? event.getClickedBlock().getType() : null;
                      if (clickedType != null && !isInteractableInLobby(clickedType)) {
                         event.setCancelled(true);
                      }
                 }
             }
         }
    }

    // Helper to check if a material should be interactable in lobby/countdown
    private boolean isInteractableInLobby(Material material) {
        // Allow interacting with chests (maybe for kit preview?), signs, buttons, etc.
        return material == Material.CHEST || material == Material.TRAPPED_CHEST ||
               material.name().endsWith("_SIGN") || material.name().endsWith("_BUTTON") ||
               material.name().endsWith("_DOOR") || material.name().endsWith("_GATE") ||
               material == Material.ENDER_CHEST || material == Material.CRAFTING_TABLE ||
               material == Material.ANVIL; // Add any other necessary interactables
    }


     // Prevent inventory clicks for spectators
     @EventHandler(priority = EventPriority.HIGH)
     public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
         Player player = (Player) event.getWhoClicked();
         Arena arena = plugin.getArenaManager().getPlayerArena(player);

         if (arena != null && arena.isSpectator(player)) {
             event.setCancelled(true); // Prevent spectators from moving items in any inventory
         }
     }

     // Prevent item dropping for spectators
    @EventHandler(priority = EventPriority.HIGH)
     public void onPlayerDropItem(PlayerDropItemEvent event) {
         Player player = event.getPlayer();
         Arena arena = plugin.getArenaManager().getPlayerArena(player);

         if (arena != null && arena.isSpectator(player)) {
             event.setCancelled(true);
         } else if (arena != null && arena.getState() != GameState.IN_GAME) {
             // Prevent item dropping before game starts
              if (!player.hasPermission("skymasters.admin.bypass")) {
                 event.setCancelled(true);
              }
         }
    }

    // Prevent item pickup for spectators
     @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerPickupItem(PlayerAttemptPickupItemEvent event) {
        Player player = event.getPlayer();
        Arena arena = plugin.getArenaManager().getPlayerArena(player);

        if (arena != null && arena.isSpectator(player)) {
            event.setCancelled(true);
        }
    }

    // Handle Hunger loss based on config
    @EventHandler
    public void onFoodLevelChange(org.bukkit.event.entity.FoodLevelChangeEvent event) {
        if (!(event.getEntity() instanceof Player)) return;
        Player player = (Player) event.getEntity();
        Arena arena = plugin.getArenaManager().getPlayerArena(player);

        if (arena != null && arena.getState() != GameState.IN_GAME && !plugin.getConfigManager().isHungerLossEnabled()) {
             event.setCancelled(true); // Prevent hunger loss if not in game or disabled globally
             // Optional: Keep food level full outside of game time
             //if (player.getFoodLevel() < 20) {
             //     player.setFoodLevel(20);
             // }
        }
    }


    // Handle Fall Damage based on config
     @EventHandler
     public void onEntityDamage(org.bukkit.event.entity.EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player)) return;
        Player player = (Player) event.getEntity();
        Arena arena = plugin.getArenaManager().getPlayerArena(player);

        if (arena != null) {
            // Prevent fall damage if disabled globally or during invincibility
            if (event.getCause() == org.bukkit.event.entity.EntityDamageEvent.DamageCause.FALL) {
                if (!plugin.getConfigManager().isFallDamageEnabled() || arena.hasInvincibility(player)) {
                     event.setCancelled(true);
                 }
             }
             // Prevent all damage during invincibility
            else if (arena.hasInvincibility(player)) {
                event.setCancelled(true);
             }
             // Prevent all damage for spectators
            else if (arena.isSpectator(player)){
                 event.setCancelled(true);
            }
            // Prevent damage in lobby/starting phases (except void?)
            else if (arena.getState() != GameState.IN_GAME && event.getCause() != org.bukkit.event.entity.EntityDamageEvent.DamageCause.VOID) {
                event.setCancelled(true);
            }

        }
     }

      // Keep players inside world boundaries or arena boundaries?
      // This is simple world boundary logic. Arena specific boundary might be better.
    // @EventHandler
    // public void onPlayerMove(PlayerMoveEvent event) {
    //     Player player = event.getPlayer();
    //     Arena arena = plugin.getArenaManager().getPlayerArena(player);
    //     if (arena != null && arena.getCorner1() != null && arena.getCorner2() != null) {
    //         if (!arena.isWithinBounds(event.getTo())) {
    //              // What to do? Teleport back? Damage (void)?
    //              // Simplest: Teleport back to previous location
    //              Location from = event.getFrom();
    //              player.teleport(from); // Teleport back instantly
    //              // Or maybe teleport to spectator spawn if falling out?
    //              // player.teleport(arena.getSpectatorSpawn()); arena.handlePlayerDeath(player, null); // Treat as void death
    //         }
    //     }
    // }


    // Handle default kit application if enabled
    @EventHandler(priority = EventPriority.LOW) // Run early
    public void onArenaJoin(PlayerJoinArenaEvent event) { // Use a custom event if you implement one, otherwise check in PlayerListener after addPlayerToArena
        if (plugin.getConfigManager().isAutoEquipDefaultKit()) {
            Arena arena = event.getArena();
            if (arena.getState() == GameState.WAITING || arena.getState() == GameState.STARTING) {
                Kit defaultKit = plugin.getKitManager().getKit(plugin.getConfigManager().getDefaultKitName());
                if (defaultKit != null) {
                     // Ensure player has permission for default kit? Or assume everyone does? Assume yes for now.
                    arena.setSelectedKit(event.getPlayer().getUniqueId(), defaultKit);
                     // Don't give items immediately, just set selection. Items given at game start.
                    event.getPlayer().sendMessage(plugin.getConfigManager().getPrefixedMessage("kit-selected", Map.of("kit", defaultKit.getName())));
                } else {
                     plugin.getLogger().warning("Default kit '" + plugin.getConfigManager().getDefaultKitName() + "' not found for auto-equip.");
                }
            }
        }
    }

    // Custom Event Stub (You would need to create and call this event)
    public static class PlayerJoinArenaEvent extends PlayerEvent {
        private static final org.bukkit.event.HandlerList handlers = new org.bukkit.event.HandlerList();
        private final Arena arena;

        public PlayerJoinArenaEvent(Player who, Arena arena) {
            super(who);
            this.arena = arena;
        }

        public Arena getArena() {
            return arena;
        }

        @Override
        public org.bukkit.event.HandlerList getHandlers() {
            return handlers;
        }

        public static org.bukkit.event.HandlerList getHandlerList() {
            return handlers;
        }
    }

}