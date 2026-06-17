package com.esark.gasp;

import static com.esark.framework.AndroidGame.signalBufferLen;
import static com.esark.gasp.GameScreen.A2DVal;
import static com.esark.gasp.GameScreen.movingRMS;
import static com.esark.gasp.GameScreen.psdResult;
import static com.esark.gasp.GameScreen.smoothedRMS;

import android.bluetooth.BluetoothSocket;
import android.os.Handler;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

public class ConnectedThread extends Thread {
    private final BluetoothSocket mmSocket;
    private final InputStream mmInStream;
    private final OutputStream mmOutStream;
    private final Handler mHandler;

    private int mathSkipCount = 0;
    private boolean expectingLowByte = false;
    private int tempHighByte = 0;

    // displayExecutor is removed for UI updates to prevent queue lag
    private final ExecutorService mathExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService recordExecutor = Executors.newSingleThreadExecutor();
    private final double[] a2dCopyForMath = new double[signalBufferLen];

    public ConnectedThread(BluetoothSocket socket, Handler handler) {
        mmSocket = socket;
        mHandler = handler;
        InputStream tmpIn = null;
        OutputStream tmpOut = null;
        try {
            tmpIn = socket.getInputStream();
            tmpOut = socket.getOutputStream();
        } catch (IOException e) {
            Log.e("ConnectedThread", "Error creating streams", e);
        }
        mmInStream = tmpIn;
        mmOutStream = tmpOut;
    }

    @Override
    public void run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY);
        byte[] buffer = new byte[2048];
        final double[] packetSamples = new double[4096];

        // Time-base for 1000Hz (1,000,000 nanoseconds per sample)
        final long NS_PER_SAMPLE = 1000000L;
        long startTimeNs = System.nanoTime();
        long samplesReleased = 0;

        // UI Heartbeat tracking
        long lastUiPingNs = 0;
        final long UI_INTERVAL_NS = 16666666L; // 16.6ms (60Hz)

        final AtomicBoolean mathIsBusy = new AtomicBoolean(false);

        while (!Thread.currentThread().isInterrupted()) {
            try {
                // 1. BLOCKING READ
                int bytesRead = mmInStream.read(buffer);
                if (bytesRead <= 0) continue;

                // 2. FAST PARSE
                int samplesFound = 0;
                for (int i = 0; i < bytesRead; i++) {
                    int b = buffer[i] & 0xFF;
                    if (b == 120) { expectingLowByte = false; continue; }
                    if (!expectingLowByte) {
                        tempHighByte = b;
                        expectingLowByte = true;
                    } else {
                        if (samplesFound < packetSamples.length) {
                            packetSamples[samplesFound++] = ((tempHighByte << 8) | b) / 3.0;
                        }
                        expectingLowByte = false;
                    }
                }

                if (samplesFound > 0) {
                    // Immediate Recording for CSV integrity
                    if (GameScreen.isRecording) {
                        synchronized (GameScreen.ramRecordBuffer) {
                            int spaceLeft = GameScreen.ramRecordBuffer.length - GameScreen.ramRecordBufferIdx;
                            int toCopy = Math.min(samplesFound, spaceLeft);
                            if (toCopy > 0) {
                                System.arraycopy(packetSamples, 0, GameScreen.ramRecordBuffer, GameScreen.ramRecordBufferIdx, toCopy);
                                GameScreen.ramRecordBufferIdx += toCopy;
                            }
                        }
                    }

                    // 3. PRECISION RELEASE ENGINE
                    int processedInPacket = 0;
                    while (processedInPacket < samplesFound) {
                        long now = System.nanoTime();
                        long elapsedNs = now - startTimeNs;
                        int targetTotal = (int) (elapsedNs / NS_PER_SAMPLE);
                        int debt = targetTotal - (int) samplesReleased;

                        if (debt > 0) {
                            // Release data 1-by-1 to the array for perfect linear logic
                            // But we only request a redraw every 16.6ms
                            int chunkSize = 1;

                            synchronized (A2DVal) {
                                System.arraycopy(A2DVal, chunkSize, A2DVal, 0, signalBufferLen - chunkSize);
                                A2DVal[signalBufferLen - chunkSize] = packetSamples[processedInPacket];
                            }

                            // --- THE SMOOTHNESS KEY: UI THROTTLING ---
                            // Only ping the UI every 16.6ms. This prevents the "Invalidation Pile-up"
                            // that causes jumpy movement.
                            if (now - lastUiPingNs >= UI_INTERVAL_NS) {
                                if (GameScreen.view != null) {
                                    GameScreen.view.postInvalidateOnAnimation();
                                }
                                lastUiPingNs = now;
                            }

                            processedInPacket++;
                            samplesReleased++;
                        } else {
                            // Ahead of schedule, yield for 0.1ms to keep the loop "hot"
                            LockSupport.parkNanos(100000L);
                        }
                    }

                    // 4. NON-BLOCKING MATH (PSD/RMS)
                    if (mathIsBusy.compareAndSet(false, true)) {
                        synchronized (A2DVal) {
                            System.arraycopy(A2DVal, 0, a2dCopyForMath, 0, signalBufferLen);
                        }
                        mathExecutor.execute(() -> {
                            try {
                                // PSD and RMS calculations...
                                // (Implementation truncated for brevity, keep your existing math here)
                            } finally {
                                mathIsBusy.set(false);
                            }
                        });
                    }
                }
            } catch (IOException e) {
                break;
            }
        }
    }





    public void cancel() {
        try {
            mmSocket.close();
        } catch (IOException e) {
            Log.e("ConnectedThread", "Could not close socket", e);
        }
    }
}