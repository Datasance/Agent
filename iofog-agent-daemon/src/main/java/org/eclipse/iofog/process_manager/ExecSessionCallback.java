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
package org.eclipse.iofog.process_manager;

import com.github.dockerjava.api.async.ResultCallbackTemplate;
import com.github.dockerjava.api.model.Frame;
import org.eclipse.iofog.utils.logging.LoggingService;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Callback for handling Docker exec session I/O and timeout
 */
public class ExecSessionCallback extends ResultCallbackTemplate<ExecSessionCallback, Frame> {
    private static final String MODULE_NAME = "Exec Session Callback";
    private static final int TIMEOUT_MINUTES = 10;

    private final OutputStream stdin;
    private final OutputStream stdout;
    private final OutputStream stderr;
    private final AtomicBoolean isRunning;
    private final ScheduledExecutorService scheduler;
    private ScheduledFuture<?> timeoutFuture;

    public ExecSessionCallback(OutputStream stdin, OutputStream stdout, OutputStream stderr) {
        this.stdin = stdin;
        this.stdout = stdout;
        this.stderr = stderr;
        this.isRunning = new AtomicBoolean(true);
        this.scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduleTimeout();
    }

    @Override
    public void onNext(Frame frame) {
        if (frame != null) {
            try {
                switch (frame.getStreamType()) {
                    case STDOUT:
                        if (stdout != null) {
                            stdout.write(frame.getPayload());
                            stdout.flush();
                        }
                        break;
                    case STDERR:
                        if (stderr != null) {
                            stderr.write(frame.getPayload());
                            stderr.flush();
                        }
                        break;
                }
                resetTimeout();
            } catch (IOException e) {
                LoggingService.logError(MODULE_NAME, "Error writing to output stream", e);
            }
        }
    }

    @Override
    public void onComplete() {
        close();
    }

    @Override
    public void onError(Throwable throwable) {
        LoggingService.logError(MODULE_NAME, "Exec session error", throwable);
        close();
    }

    public void writeInput(byte[] data) throws IOException {
        if (stdin != null && isRunning.get()) {
            stdin.write(data);
            stdin.flush();
            resetTimeout();
        }
    }

    private void scheduleTimeout() {
        timeoutFuture = scheduler.schedule(this::close, TIMEOUT_MINUTES, TimeUnit.MINUTES);
    }

    private void resetTimeout() {
        if (timeoutFuture != null) {
            timeoutFuture.cancel(false);
            scheduleTimeout();
        }
    }

    public void close() {
        if (isRunning.compareAndSet(true, false)) {
            try {
                if (stdin != null) stdin.close();
                if (stdout != null) stdout.close();
                if (stderr != null) stderr.close();
            } catch (IOException e) {
                LoggingService.logError(MODULE_NAME, "Error closing streams", e);
            }
            scheduler.shutdown();
        }
    }

    public boolean isRunning() {
        return isRunning.get();
    }
} 