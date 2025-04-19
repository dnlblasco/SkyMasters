package com.codella.skymasters;

import com.codella.skymasters.managers.ArenaManager;
import com.codella.skymasters.managers.ConfigManager;
import com.codella.skymasters.managers.KitManager;
import com.codella.skymasters.managers.SetupManager;
import com.codella.skymasters.commands.SkywarsCommand;
import com.codella.skymasters.commands.KitCommand;
import com.codella.skymasters.listeners.PlayerListener;
import com.codella.skymasters.listeners.GameListener;
import com.codella.skymasters.listeners.SetupListener;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.Objects;

public final class SkyMasters extends JavaPlugin {

    private static SkyMasters instance;
    private ConfigManager configManager;
    private ArenaManager arenaManager;
    private KitManager kitManager;
    private SetupManager setupManager;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig(); // Saves config.yml if it doesn't exist

        // Initialize Managers
        configManager = new ConfigManager(this);
        configManager.loadMessages(); // Load messages first for other managers
        kitManager = new KitManager(this);
        arenaManager = new ArenaManager(this);
        setupManager = new SetupManager(this);

        // Load data
        kitManager.loadKits();
        arenaManager.loadArenas(); // Load arenas after managers are ready

        // Register Commands
        Objects.requireNonNull(getCommand("skywars")).setExecutor(new SkywarsCommand(this));
        Objects.requireNonNull(getCommand("skywars")).setTabCompleter(new SkywarsCommand(this)); // Add tab completer
        Objects.requireNonNull(getCommand("kit")).setExecutor(new KitCommand(this));
        // Consider adding a KitTabCompleter as well

        // Register Listeners
        Bukkit.getPluginManager().registerEvents(new PlayerListener(this), this);
        Bukkit.getPluginManager().registerEvents(new GameListener(this), this);
        Bukkit.getPluginManager().registerEvents(new SetupListener(this), this);

        getLogger().info("SkyMasters has been enabled!");
    }

    @Override
    public void onDisable() {
        getLogger().info("Disabling SkyMasters...");
        if (arenaManager != null) {
            arenaManager.stopAllArenas(); // Cleanly stop games
        }
        // Perform any other necessary cleanup
        getLogger().info("SkyMasters has been disabled.");
        instance = null;
    }

    // --- Getters ---
    public static SkyMasters getInstance() {
        return instance;
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public ArenaManager getArenaManager() {
        return arenaManager;
    }

    public KitManager getKitManager() {
        return kitManager;
    }

    public SetupManager getSetupManager() {
        return setupManager;
    }

     public File getArenasFolder() {
        File arenasFolder = new File(getDataFolder(), "arenas");
        if (!arenasFolder.exists()) {
            arenasFolder.mkdirs();
        }
        return arenasFolder;
    }
}