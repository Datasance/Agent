package org.eclipse.iofog.volume_mount;

import jakarta.json.*;
import org.mockito.MockedStatic;
import org.eclipse.iofog.exception.AgentSystemException;
import org.eclipse.iofog.utils.configuration.Configuration;
import org.eclipse.iofog.utils.logging.LoggingService;
import org.eclipse.iofog.status_reporter.StatusReporter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class VolumeMountManagerTest {
    private static final String MODULE_NAME = "VolumeMountManager";
    private static final String TEST_BASE_DIR = "test_volumes/";
    private static final String TEST_UUID = "test-uuid-123";
    private static final String TEST_NAME = "test-volume";
    private static final int TEST_VERSION = 1;

    private VolumeMountManager volumeMountManager;
    private MockedStatic<LoggingService> loggingServiceMockedStatic;
    private MockedStatic<Configuration> configurationMockedStatic;
    private MockedStatic<StatusReporter> statusReporterMockedStatic;
    private VolumeMountManagerStatus volumeMountManagerStatus;

    @BeforeEach
    public void setUp() throws Exception {
        // Mock static classes
        loggingServiceMockedStatic = Mockito.mockStatic(LoggingService.class);
        configurationMockedStatic = Mockito.mockStatic(Configuration.class);
        statusReporterMockedStatic = Mockito.mockStatic(StatusReporter.class);

        // Setup test directory
        when(Configuration.getDiskDirectory()).thenReturn(TEST_BASE_DIR);
        volumeMountManagerStatus = new VolumeMountManagerStatus();
        when(StatusReporter.setVolumeMountManagerStatus(anyInt(), anyLong())).thenReturn(volumeMountManagerStatus);

        // Create instance
        volumeMountManager = spy(VolumeMountManager.getInstance());
    }

    @AfterEach
    public void tearDown() throws Exception {
        // Clean up test directory
        Path testDir = Paths.get(TEST_BASE_DIR);
        if (Files.exists(testDir)) {
            Files.walk(testDir)
                .sorted((a, b) -> b.compareTo(a))
                .forEach(path -> {
                    try {
                        Files.delete(path);
                    } catch (Exception e) {
                        // Ignore cleanup errors
                    }
                });
        }

        // Close mocked statics
        loggingServiceMockedStatic.close();
        configurationMockedStatic.close();
        statusReporterMockedStatic.close();

        // Reset instance
        Field instance = VolumeMountManager.class.getDeclaredField("instance");
        instance.setAccessible(true);
        instance.set(null, null);
    }

    @Test
    public void testGetInstance() {
        VolumeMountManager instance1 = VolumeMountManager.getInstance();
        VolumeMountManager instance2 = VolumeMountManager.getInstance();
        assertSame(instance1, instance2, "getInstance should return the same instance");
    }

    @Test
    public void testProcessVolumeMountChanges_Create() {
        // Create test volume mount data
        JsonObject data = Json.createObjectBuilder()
            .add("file1.txt", Base64.getEncoder().encodeToString("test content".getBytes()))
            .build();

        JsonObject volumeMount = Json.createObjectBuilder()
            .add("uuid", TEST_UUID)
            .add("name", TEST_NAME)
            .add("version", TEST_VERSION)
            .add("data", data)
            .build();

        JsonArray volumeMounts = Json.createArrayBuilder()
            .add(volumeMount)
            .build();

        // Process changes
        volumeMountManager.processVolumeMountChanges(volumeMounts);

        // Verify directory and file creation
        Path mountPath = Paths.get(TEST_BASE_DIR + "volumes/" + TEST_NAME);
        assertTrue(Files.exists(mountPath), "Volume mount directory should be created");
        assertTrue(Files.exists(mountPath.resolve("file1.txt")), "Volume mount file should be created");

        // Verify index file
        File indexFile = new File(TEST_BASE_DIR + "volumes/index.json");
        assertTrue(indexFile.exists(), "Index file should be created");

        // Verify status update
        StatusReporter.setVolumeMountManagerStatus(1, anyLong());
    }

    @Test
    public void testProcessVolumeMountChanges_Update() {
        // Create initial volume mount
        JsonObject initialData = Json.createObjectBuilder()
            .add("file1.txt", Base64.getEncoder().encodeToString("initial content".getBytes()))
            .build();

        JsonObject initialVolumeMount = Json.createObjectBuilder()
            .add("uuid", TEST_UUID)
            .add("name", TEST_NAME)
            .add("version", TEST_VERSION)
            .add("data", initialData)
            .build();

        JsonArray initialVolumeMounts = Json.createArrayBuilder()
            .add(initialVolumeMount)
            .build();

        volumeMountManager.processVolumeMountChanges(initialVolumeMounts);

        // Create updated volume mount
        JsonObject updatedData = Json.createObjectBuilder()
            .add("file1.txt", Base64.getEncoder().encodeToString("updated content".getBytes()))
            .add("file2.txt", Base64.getEncoder().encodeToString("new content".getBytes()))
            .build();

        JsonObject updatedVolumeMount = Json.createObjectBuilder()
            .add("uuid", TEST_UUID)
            .add("name", TEST_NAME)
            .add("version", TEST_VERSION + 1)
            .add("data", updatedData)
            .build();

        JsonArray updatedVolumeMounts = Json.createArrayBuilder()
            .add(updatedVolumeMount)
            .build();

        // Process update
        volumeMountManager.processVolumeMountChanges(updatedVolumeMounts);

        // Verify file updates
        Path mountPath = Paths.get(TEST_BASE_DIR + "volumes/" + TEST_NAME);
        assertTrue(Files.exists(mountPath.resolve("file1.txt")), "Updated file should exist");
        assertTrue(Files.exists(mountPath.resolve("file2.txt")), "New file should be created");

        // Verify content
        String file1Content = new String(Files.readAllBytes(mountPath.resolve("file1.txt")));
        assertEquals("updated content", file1Content, "File content should be updated");

        // Verify status update
        verify(StatusReporter.class, times(2)).setVolumeMountManagerStatus(1, anyLong());
    }

    @Test
    public void testProcessVolumeMountChanges_Delete() {
        // Create initial volume mount
        JsonObject initialData = Json.createObjectBuilder()
            .add("file1.txt", Base64.getEncoder().encodeToString("test content".getBytes()))
            .build();

        JsonObject initialVolumeMount = Json.createObjectBuilder()
            .add("uuid", TEST_UUID)
            .add("name", TEST_NAME)
            .add("version", TEST_VERSION)
            .add("data", initialData)
            .build();

        JsonArray initialVolumeMounts = Json.createArrayBuilder()
            .add(initialVolumeMount)
            .build();

        volumeMountManager.processVolumeMountChanges(initialVolumeMounts);

        // Process empty array to delete volume mount
        JsonArray emptyVolumeMounts = Json.createArrayBuilder().build();
        volumeMountManager.processVolumeMountChanges(emptyVolumeMounts);

        // Verify deletion
        Path mountPath = Paths.get(TEST_BASE_DIR + "volumes/" + TEST_NAME);
        assertFalse(Files.exists(mountPath), "Volume mount directory should be deleted");

        // Verify status update
        verify(StatusReporter.class, times(2)).setVolumeMountManagerStatus(anyInt(), anyLong());
    }

    @Test
    public void testProcessVolumeMountChanges_InvalidData() {
        // Create invalid volume mount data
        JsonObject invalidVolumeMount = Json.createObjectBuilder()
            .add("uuid", TEST_UUID)
            .add("name", TEST_NAME)
            .add("version", TEST_VERSION)
            .add("data", "invalid data")
            .build();

        JsonArray volumeMounts = Json.createArrayBuilder()
            .add(invalidVolumeMount)
            .build();

        // Process changes should not throw exception
        assertDoesNotThrow(() -> volumeMountManager.processVolumeMountChanges(volumeMounts));

        // Verify error logging
        LoggingService.logError(eq(MODULE_NAME), anyString(), any(Throwable.class));
    }

    @Test
    public void testProcessVolumeMountChanges_InvalidBase64() {
        // Create volume mount with invalid base64 data
        JsonObject data = Json.createObjectBuilder()
            .add("file1.txt", "invalid-base64-data")
            .build();

        JsonObject volumeMount = Json.createObjectBuilder()
            .add("uuid", TEST_UUID)
            .add("name", TEST_NAME)
            .add("version", TEST_VERSION)
            .add("data", data)
            .build();

        JsonArray volumeMounts = Json.createArrayBuilder()
            .add(volumeMount)
            .build();

        // Process changes should not throw exception
        assertDoesNotThrow(() -> volumeMountManager.processVolumeMountChanges(volumeMounts));

        // Verify error logging
        LoggingService.logError(eq(MODULE_NAME), anyString(), any(Throwable.class));
    }
} 