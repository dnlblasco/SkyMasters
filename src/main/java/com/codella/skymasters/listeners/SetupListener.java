package com.codella.skymasters.listeners;

import com.codella.skymasters.SkyMasters;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;

// Listener specifically for handling interactions during setup mode
// Primarily prevents breaking/placing blocks *unless* it's part of the setup process
public class SetupListener implements Listener {

    private final SkyMasters plugin;

    public SetupListener(SkyMasters plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onBlockBreakInSetup(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (plugin.getSetupManager().isInSetupMode(player)) {
            // Allow breaking specific blocks if needed for setup?
            // Currently, setup relies on commands and wand clicks.
            // Prevent accidental breaking of the structure being set up.
            event.setCancelled(true);
             // Optionally send a message:
             // player.sendMessage(plugin.getConfigManager().getPrefix() + "&cBlock breaking is disabled in setup mode.");
        }
    }

    @EventHandler
    public void onBlockPlaceInSetup(BlockPlaceEvent event) {
         Player player = event.getPlayer();
         if (plugin.getSetupManager().isInSetupMode(player)) {
             // Prevent placing blocks during setup, as configuration is command/wand based.
             event.setCancelled(true);
             // Optionally send a message:
             // player.sendMessage(plugin.getConfigManager().getPrefix() + "&cBlock placing is disabled in setup mode.");
         }
    }

    // Note: PlayerInteractEvent handling for the wand is in PlayerListener
    // to avoid conflicts with spectator checks etc. which run at higher priority.
}