package com.codella.skymasters.utils;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.Locale;

public class LocationUtil {

    /**
     * Serializes a Location into a String format: "world:x:y:z:yaw:pitch".
     * Yaw and pitch are rounded to 2 decimal places.
     */
    public static String serializeLocation(Location loc) {
        if (loc == null) return null;
        return String.format(Locale.US, "%s:%.2f:%.2f:%.2f:%.2f:%.2f",
                loc.getWorld().getName(),
                loc.getX(),
                loc.getY(),
                loc.getZ(),
                loc.getYaw(),
                loc.getPitch());
    }

     /**
     * Serializes a Location into a minimal String format: "world:x:y:z".
     * Primarily used for block locations where yaw/pitch are irrelevant.
     */
     public static String serializeLocationMinimal(Location loc) {
         if (loc == null) return null;
         return String.format(Locale.US, "%s:%d:%d:%d", // Use integers for block coords
                 loc.getWorld().getName(),
                 loc.getBlockX(),
                 loc.getBlockY(),
                 loc.getBlockZ());
     }

    /**
     * Deserializes a Location from the String format: "world:x:y:z:yaw:pitch".
     * Returns null if the format is invalid or the world doesn't exist.
     */
    public static Location deserializeLocation(String s) {
        if (s == null || s.trim().isEmpty()) {
            return null;
        }
        String[] parts = s.split(":");
        if (parts.length != 6) {
            // Maybe try parsing minimal format? For now, strict 6 parts required.
             // Check if it might be the minimal format (4 parts)
             if(parts.length == 4) {
                 return deserializeLocationMinimal(s); // Attempt minimal parse
             }
            System.err.println("Invalid location format (expected 6 parts): " + s);
            return null;
        }

        World world = Bukkit.getWorld(parts[0]);
        if (world == null) {
             System.err.println("World not found for location: " + parts[0]);
            return null;
        }

        try {
            double x = Double.parseDouble(parts[1]);
            double y = Double.parseDouble(parts[2]);
            double z = Double.parseDouble(parts[3]);
            float yaw = Float.parseFloat(parts[4]);
            float pitch = Float.parseFloat(parts[5]);
            return new Location(world, x, y, z, yaw, pitch);
        } catch (NumberFormatException e) {
            System.err.println("Error parsing location coordinates: " + s);
            e.printStackTrace();
            return null;
        }
    }

     /**
     * Deserializes a Location from the minimal String format: "world:x:y:z".
     * Yaw and Pitch default to 0. Returns null on error.
     */
    public static Location deserializeLocationMinimal(String s) {
         if (s == null || s.trim().isEmpty()) {
             return null;
         }
         String[] parts = s.split(":");
         if (parts.length != 4) {
              System.err.println("Invalid minimal location format (expected 4 parts): " + s);
             return null;
         }

         World world = Bukkit.getWorld(parts[0]);
         if (world == null) {
              System.err.println("World not found for minimal location: " + parts[0]);
             return null;
         }

         try {
             int x = Integer.parseInt(parts[1]);
             int y = Integer.parseInt(parts[2]);
             int z = Integer.parseInt(parts[3]);
             // Return location centered in the block for consistency? Or exact coords? Exact for now.
             return new Location(world, x, y, z);
         } catch (NumberFormatException e) {
             System.err.println("Error parsing minimal location coordinates: " + s);
             e.printStackTrace();
             return null;
         }
     }
}