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
package org.eclipse.iofog.message_bus;

import org.eclipse.iofog.local_api.ApiHandlerHelpers;
import org.eclipse.iofog.utils.configuration.Configuration;
import org.eclipse.iofog.utils.logging.LoggingService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import jakarta.json.Json;
import java.io.*;
import java.lang.reflect.Method;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;

import static java.lang.System.currentTimeMillis;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * @author nehanaithani
 *
 *
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@Disabled
public class MessageArchiveTest {
    private MessageArchive messageArchive;
    private long timestamp;
    private String message;
    private File file;
    private RandomAccessFile randomAccessFile;
    private File[] files;
    private MockedStatic<LoggingService> loggingServiceMockedStatic;
    private MockedStatic<Configuration> configurationMockedStatic;
    private MockedConstruction<File> fileMockedConstruction;
    private MockedConstruction<RandomAccessFile> randomAccessFileMockedConstruction;

    @BeforeEach
    public void setUp() throws Exception {
        timestamp = currentTimeMillis();
        message = "message";
        configurationMockedStatic = mockStatic(Configuration.class);
        loggingServiceMockedStatic = mockStatic(LoggingService.class);
        when(Configuration.getDiskDirectory()).thenReturn("dir/");
        file = mock(File.class);
        randomAccessFile = mock(RandomAccessFile.class);
        files = new File[1];
        files[0] = spy(new File("message1234545.idx"));
        when(file.listFiles(any(FilenameFilter.class))).thenReturn(files);
        when(files[0].isFile()).thenReturn(true);
        when(file.getName()).thenReturn("message.idx");
        fileMockedConstruction = Mockito.mockConstruction(File.class);
        randomAccessFileMockedConstruction = Mockito.mockConstruction(RandomAccessFile.class, (mock, context) -> {
            when(mock.getFilePointer()).thenReturn(1L);
            when(mock.length()).thenReturn(10L);
            when(mock.read(any(byte[].class), anyInt(), anyInt())).thenReturn(1);
            when(mock.readLong()).thenReturn(1L);
        });
        messageArchive = spy(new MessageArchive("message.idx"));
    }

    @AfterEach
    public void tearDown() throws Exception {
        files = null;
        reset(messageArchive, randomAccessFile);
        deleteDirectory("dir/messages/archive");
        configurationMockedStatic.close();
        loggingServiceMockedStatic.close();
        fileMockedConstruction.close();
        randomAccessFileMockedConstruction.close();
    }

    void deleteDirectory(String directoryFilePath) throws IOException {
        Path directory = Paths.get(directoryFilePath);

        if (Files.exists(directory))
        {
            Files.walkFileTree(directory, new SimpleFileVisitor<Path>()
            {
                @Override
                public FileVisitResult visitFile(Path path, BasicFileAttributes basicFileAttributes) throws IOException
                {
                    Files.delete(path);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path directory, IOException ioException) throws IOException
                {
                    Files.delete(directory);
                    return FileVisitResult.CONTINUE;
                }
            });
        }
    }
    /**
     * Test save
     */
    @Test
    public void testSave() {
        try {
            messageArchive.save(message.getBytes(UTF_8),timestamp);
            Mockito.verify(randomAccessFile, Mockito.atLeastOnce()).seek(anyLong());
            Mockito.verify(randomAccessFile, Mockito.atLeastOnce()).length();
            Mockito.verify(randomAccessFile, Mockito.atLeastOnce()).getFilePointer();
        } catch (Exception e) {
            fail("This shall never happen");
        }
    }

    /**
     * Test close
     */
    @Test
    public void testClose() {
        try {
            messageArchive.save(message.getBytes(UTF_8),timestamp);
            messageArchive.close();
            Mockito.verify(randomAccessFile, Mockito.atLeastOnce()).seek(anyLong());
            Mockito.verify(randomAccessFile, Mockito.atLeastOnce()).length();
            Mockito.verify(randomAccessFile, Mockito.atLeastOnce()).getFilePointer();
            Mockito.verify(randomAccessFile, Mockito.atLeastOnce()).close();
        } catch (Exception e) {
            fail("This shall never happen");
        }
    }

    /**
     * Test messageQuery
     */
    @Test
    public void testMessageQueryWithMessages() {
        try{
            when(files[0].isFile()).thenReturn(true);
            when(files[0].getName()).thenReturn("message1234545.idx");
            messageArchive.messageQuery(1, 50);
            Mockito.verify(file, Mockito.atLeastOnce()).listFiles(any(FilenameFilter.class));
            Mockito.verify(randomAccessFile, Mockito.atLeastOnce()).getFilePointer();
            Mockito.verify(randomAccessFile, Mockito.atLeastOnce()).read(any(byte[].class), anyInt(), anyInt());
        } catch (Exception e){
            fail("This shall never happen");
        }
    }

    /**
     * Test getDataSize
     */
    @Test
    public void testGetDataSize() {
        try {
            byte[] bytes = new byte[33];
            Method method = MessageArchive.class.getDeclaredMethod("getDataSize", byte[].class);
            method.setAccessible(true);
            assertEquals(0, (int) method.invoke(messageArchive, bytes));
        } catch (Exception e) {
            fail("This should not happen");
        }
    }
}