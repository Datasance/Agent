/*
 * *******************************************************************************
 *  * Copyright (c) 2023 Datasance Teknoloji A.S.
 *  *
 *  * This program and the accompanying materials are made available under the
 *  * terms of the Eclipse Public License v. 2.0 which is available at
 *  * http://www.eclipse.org/legal/epl-2.0
 *  *
 *  * SPDX-License-Identifier: EPL-2.0
 *  *******************************************************************************
 *
 */
package org.eclipse.iofog.volume_mount;

import jakarta.json.*;
import org.eclipse.iofog.exception.AgentSystemException;
import org.eclipse.iofog.utils.configuration.Configuration;
import org.eclipse.iofog.utils.logging.LoggingService;
import org.eclipse.iofog.status_reporter.StatusReporter;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;

/**
 * Manages volume mounts for microservices
 * Handles creation, updates, and deletion of volume mounts
 * Manages the index file and directory structure
 */
public class VolumeMountManager {
    private static final String MODULE_NAME = "VolumeMountManager";
    private static final String VOLUMES_DIR = "volumes";
    private static final String INDEX_FILE = "index.json";
    
    private static VolumeMountManager instance;
    private final String baseDirectory;
    private JsonObject indexData;
    
    private VolumeMountManager() {
        this.baseDirectory = Configuration.getDiskDirectory() + VOLUMES_DIR + "/";
        init();
    }
    
    public static VolumeMountManager getInstance() {
        if (instance == null) {
            synchronized (VolumeMountManager.class) {
                if (instance == null) {
                    instance = new VolumeMountManager();
                }
            }
        }
        return instance;
    }
    
    /**
     * Initializes the volume mount manager
     * Creates necessary directories and loads index file
     */
    private void init() {
        try {
            LoggingService.logInfo(MODULE_NAME, "Initializing volume mount manager");
            // Create volumes directory if it doesn't exist
            Path volumesPath = Paths.get(baseDirectory);
            if (!Files.exists(volumesPath)) {
                LoggingService.logDebug(MODULE_NAME, "Creating volumes directory at: " + baseDirectory);
                Files.createDirectories(volumesPath);
            }
            
            // Load or create index file
            loadIndex();
            LoggingService.logInfo(MODULE_NAME, "Volume mount manager initialized successfully");
        } catch (Exception e) {
            LoggingService.logError(MODULE_NAME, "Error initializing volume mount manager", 
                new AgentSystemException(e.getMessage(), e));
        }
    }
    
    /**
     * Loads the index file or creates it if it doesn't exist
     */
    private void loadIndex() {
        try {
            File indexFile = new File(baseDirectory + INDEX_FILE);
            if (!indexFile.exists()) {
                LoggingService.logDebug(MODULE_NAME, "Creating new index file");
                // Create new index file with empty data
                indexData = Json.createObjectBuilder().build();
                saveIndex();
            } else {
                LoggingService.logDebug(MODULE_NAME, "Loading existing index file");
                // Load existing index file
                try (JsonReader reader = Json.createReader(new FileReader(indexFile))) {
                    JsonObject fileData = reader.readObject();
                    String storedChecksum = fileData.getString("checksum");
                    JsonObject data = fileData.getJsonObject("data");
                    
                    // Verify checksum
                    String computedChecksum = checksum(data.toString());
                    if (!computedChecksum.equals(storedChecksum)) {
                        LoggingService.logError(MODULE_NAME, "Index file checksum verification failed", 
                            new AgentSystemException("Index file may have been tampered with"));
                        // Initialize empty index if checksum fails
                        indexData = Json.createObjectBuilder().build();
                        return;
                    }
                    
                    indexData = data;
                }
            }
        } catch (Exception e) {
            LoggingService.logError(MODULE_NAME, "Error loading index file", 
                new AgentSystemException(e.getMessage(), e));
            // Initialize empty index if loading fails
            indexData = Json.createObjectBuilder().build();
        }
    }
    
    /**
     * Saves the index file
     */
    private void saveIndex() {
        try {
            LoggingService.logDebug(MODULE_NAME, "Saving index file");
            File indexFile = new File(baseDirectory + INDEX_FILE);
            
            // Create wrapper object with checksum and timestamp
            JsonObject wrapper = Json.createObjectBuilder()
                .add("checksum", checksum(indexData.toString()))
                .add("timestamp", System.currentTimeMillis())
                .add("data", indexData)
                .build();
                
            try (JsonWriter writer = Json.createWriter(new FileWriter(indexFile))) {
                writer.writeObject(wrapper);
            }

            // Update volume mount status
            StatusReporter.setVolumeMountManagerStatus(indexData.size(), System.currentTimeMillis());
        } catch (Exception e) {
            LoggingService.logError(MODULE_NAME, "Error saving index file", 
                new AgentSystemException(e.getMessage(), e));
        }
    }
    
    /**
     * Processes volume mount changes from controller
     * @param volumeMounts Array of volume mount objects from controller
     */
    public void processVolumeMountChanges(JsonArray volumeMounts) {
        try {
            LoggingService.logInfo(MODULE_NAME, "Processing volume mount changes");
            // Get existing volume mounts from index
            Set<String> existingUuids = indexData.keySet();
            
            // Get new volume mount UUIDs
            Set<String> newUuids = volumeMounts.stream()
                .map(JsonValue::asJsonObject)
                .map(obj -> obj.getString("uuid"))
                .collect(Collectors.toSet());
            
            // Handle removed volume mounts
            existingUuids.stream()
                .filter(uuid -> !newUuids.contains(uuid))
                .forEach(this::deleteVolumeMount);
            
            // Handle new and updated volume mounts
            volumeMounts.forEach(mount -> {
                JsonObject volumeMount = mount.asJsonObject();
                String uuid = volumeMount.getString("uuid");
                
                if (existingUuids.contains(uuid)) {
                    LoggingService.logDebug(MODULE_NAME, "Updating volume mount: " + uuid);
                    updateVolumeMount(volumeMount);
                } else {
                    LoggingService.logDebug(MODULE_NAME, "Creating new volume mount: " + uuid);
                    createVolumeMount(volumeMount);
                }
            });
            
            // Save updated index (status will be updated by saveIndex)
            saveIndex();
            LoggingService.logInfo(MODULE_NAME, "Volume mount changes processed successfully");
        } catch (Exception e) {
            LoggingService.logError(MODULE_NAME, "Error processing volume mount changes", 
                new AgentSystemException(e.getMessage(), e));
        }
    }
    
    /**
     * Creates a new volume mount
     * @param volumeMount Volume mount object from controller
     */
    private void createVolumeMount(JsonObject volumeMount) {
        try {
            String uuid = volumeMount.getString("uuid");
            String name = volumeMount.getString("name");
            int version = volumeMount.getInt("version");
            JsonObject data = volumeMount.getJsonObject("data");
            
            LoggingService.logDebug(MODULE_NAME, String.format("Creating volume mount - UUID: %s, Name: %s, Version: %d", 
                uuid, name, version));
            
            // Create directory for volume mount
            Path mountPath = Paths.get(baseDirectory + name);
            Files.createDirectories(mountPath);
            
            // Create files and store paths in index
            JsonObjectBuilder dataBuilder = Json.createObjectBuilder();
            data.forEach((key, value) -> {
                try {
                    String decodedContent = decodeBase64(value.toString());
                    Path filePath = mountPath.resolve(key);
                    Files.write(filePath, decodedContent.getBytes());
                    // Store relative path instead of content
                    dataBuilder.add(key, filePath.toString());
                } catch (Exception e) {
                    LoggingService.logError(MODULE_NAME, "Error creating file: " + key, 
                        new AgentSystemException(e.getMessage(), e));
                }
            });
            
            // Update index with paths instead of content
            JsonObject mountData = Json.createObjectBuilder()
                .add("name", name)
                .add("version", version)
                .add("data", dataBuilder)
                .build();
                
            indexData = Json.createObjectBuilder(indexData)
                .add(uuid, mountData)
                .build();
                
            saveIndex();
            LoggingService.logDebug(MODULE_NAME, "Volume mount created successfully: " + uuid);
        } catch (Exception e) {
            LoggingService.logError(MODULE_NAME, "Error creating volume mount", 
                new AgentSystemException(e.getMessage(), e));
        }
    }
    
    /**
     * Updates an existing volume mount
     * @param volumeMount Volume mount object from controller
     */
    private void updateVolumeMount(JsonObject volumeMount) {
        try {
            String uuid = volumeMount.getString("uuid");
            String name = volumeMount.getString("name");
            int version = volumeMount.getInt("version");
            JsonObject data = volumeMount.getJsonObject("data");
            
            LoggingService.logDebug(MODULE_NAME, String.format("Updating volume mount - UUID: %s, Name: %s, Version: %d", 
                uuid, name, version));
            
            // Get current version from index
            JsonObject currentMount = indexData.getJsonObject(uuid);
            if (currentMount != null) {
                int currentVersion = currentMount.getInt("version");
                if (version <= currentVersion) {
                    LoggingService.logWarning(MODULE_NAME, 
                        String.format("Skipping update - new version %d not greater than current version %d", 
                        version, currentVersion));
                    return;
                }
            }
            
            // Create or update directory for volume mount
            Path mountPath = Paths.get(baseDirectory + name);
            Files.createDirectories(mountPath);
            
            // Update files and store paths in index
            JsonObjectBuilder dataBuilder = Json.createObjectBuilder();
            data.forEach((key, value) -> {
                try {
                    String decodedContent = decodeBase64(value.toString());
                    Path filePath = mountPath.resolve(key);
                    Files.write(filePath, decodedContent.getBytes());
                    // Store relative path instead of content
                    dataBuilder.add(key, filePath.toString());
                } catch (Exception e) {
                    LoggingService.logError(MODULE_NAME, "Error updating file: " + key, 
                        new AgentSystemException(e.getMessage(), e));
                }
            });
            
            // Update index with paths instead of content
            JsonObject mountData = Json.createObjectBuilder()
                .add("name", name)
                .add("version", version)
                .add("data", dataBuilder)
                .build();
                
            indexData = Json.createObjectBuilder(indexData)
                .add(uuid, mountData)
                .build();
                
            saveIndex();
            LoggingService.logDebug(MODULE_NAME, "Volume mount updated successfully: " + uuid);
        } catch (Exception e) {
            LoggingService.logError(MODULE_NAME, "Error updating volume mount", 
                new AgentSystemException(e.getMessage(), e));
        }
    }
    
    /**
     * Deletes a volume mount
     * @param uuid UUID of the volume mount to delete
     */
    private void deleteVolumeMount(String uuid) {
        try {
            LoggingService.logDebug(MODULE_NAME, "Deleting volume mount: " + uuid);
            
            // Get mount info from index
            JsonObject mountData = indexData.getJsonObject(uuid);
            if (mountData == null) {
                LoggingService.logWarning(MODULE_NAME, "Volume mount not found: " + uuid);
                return;
            }
            
            // Delete mount directory and files
            String name = mountData.getString("name");
            Path mountPath = Paths.get(baseDirectory + name);
            if (Files.exists(mountPath)) {
                Files.walk(mountPath)
                    .sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.delete(path);
                        } catch (IOException e) {
                            LoggingService.logError(MODULE_NAME, "Error deleting file: " + path, 
                                new AgentSystemException(e.getMessage(), e));
                        }
                    });
            }
            
            // Remove from index
            JsonObjectBuilder newIndexBuilder = Json.createObjectBuilder();
            indexData.forEach((key, value) -> {
                if (!key.equals(uuid)) {
                    newIndexBuilder.add(key, value);
                }
            });
            indexData = newIndexBuilder.build();
            
            saveIndex();
            LoggingService.logDebug(MODULE_NAME, "Volume mount deleted successfully: " + uuid);
        } catch (Exception e) {
            LoggingService.logError(MODULE_NAME, "Error deleting volume mount", 
                new AgentSystemException(e.getMessage(), e));
        }
    }
    
    /**
     * Decodes a base64 encoded string
     * @param encoded Base64 encoded string
     * @return Decoded string
     */
    private String decodeBase64(String encoded) {
        try {
            // Remove quotes if present
            String cleanEncoded = encoded.replaceAll("^\"|\"$", "");
            byte[] decodedBytes = Base64.getDecoder().decode(cleanEncoded);
            return new String(decodedBytes);
        } catch (Exception e) {
            LoggingService.logError(MODULE_NAME, "Error decoding base64 string", 
                new AgentSystemException(e.getMessage(), e));
            return "";
        }
    }

    private String checksum(String data) {
        try {
            // Only compute checksum on the structure, not the content
            byte[] base64 = Base64.getEncoder().encode(data.getBytes(StandardCharsets.UTF_8));
            MessageDigest md = MessageDigest.getInstance("SHA1");
            md.update(base64);
            byte[] mdbytes = md.digest();
            StringBuilder sb = new StringBuilder();
            for (byte mdbyte : mdbytes) {
                sb.append(Integer.toString((mdbyte & 0xff) + 0x100, 16).substring(1));
            }
            return sb.toString();
        } catch (Exception e) {
            LoggingService.logError(MODULE_NAME, "Error computing checksum", 
                new AgentSystemException(e.getMessage(), e));
            return "";
        }
    }

    /**
     * Clears all volume mounts and their associated files
     * Used during deprovisioning
     */
    public void clear() {
        try {
            LoggingService.logDebug(MODULE_NAME, "Start clearing volume mounts");
            
            // Delete all volume mount directories
            File volumesDir = new File(baseDirectory);
            if (volumesDir.exists()) {
                File[] volumeDirs = volumesDir.listFiles(File::isDirectory);
                if (volumeDirs != null) {
                    for (File dir : volumeDirs) {
                        deleteDirectory(dir);
                    }
                }
            }
            
            // Delete index file
            File indexFile = new File(baseDirectory + INDEX_FILE);
            if (indexFile.exists()) {
                indexFile.delete();
            }
            
            // Clear index data
            indexData = Json.createObjectBuilder().build();
            
            // Update status reporter
            StatusReporter.setVolumeMountManagerStatus(0, System.currentTimeMillis());
            
            LoggingService.logDebug(MODULE_NAME, "Finished clearing volume mounts");
        } catch (Exception e) {
            LoggingService.logError(MODULE_NAME, "Error clearing volume mounts", 
                new AgentSystemException(e.getMessage(), e));
        }
    }

    private void deleteDirectory(File directory) {
        File[] files = directory.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    deleteDirectory(file);
                }
                file.delete();
            }
        }
        directory.delete();
    }
} 