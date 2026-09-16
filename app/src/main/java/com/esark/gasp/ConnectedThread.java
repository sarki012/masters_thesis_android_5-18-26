package com.esark.gasp;

import static android.system.Os.*;
import static com.esark.gasp.GameScreen.*;
import android.os.Process;
import android.system.Os;
import android.util.Log;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import android.bluetooth.BluetoothSocket;

public class ConnectedThread extends Thread {
    private final BluetoothSocket mmSocket;    // Add this
    private final InputStream mmInStream;
    private int tempHighByte;
    private boolean expectingLowByte = false;

    private final ExecutorService mathExecutor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean mathIsBusy = new AtomicBoolean(false);
    private final double[] a2dCopyForMath = new double[signalBufferLen];
    static double runningBaseline = -1;

    // Change the constructor to accept the Socket
    public ConnectedThread(BluetoothSocket socket) {
        this.mmSocket = socket;
        InputStream tmpIn = null;

        // Extract the stream from the socket
        try {
            tmpIn = socket.getInputStream();
        } catch (IOException e) {
            Log.e("ConnectedThread", "Error occurred when creating input stream", e);
        }

        this.mmInStream = tmpIn;
    }

    @Override
    public void run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
        NotchFilter filter60Hz = new NotchFilter();
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
        GameScreen.btStatus = "BT: Connected (Liquid Discrete)";

        byte[] buffer = new byte[2048];
        final double[] jitterBuffer = new double[65536];
        final AtomicInteger jWrite = new AtomicInteger(0);
        final AtomicInteger jRead = new AtomicInteger(0);
        final AtomicInteger jCount = new AtomicInteger(0);

        // --- RIGID TIMEBASE VARIABLES ---
        final long NS_PER_SAMPLE = 1000000L; // 1.0ms
        long nextTickNs = 0;
        long tickCounter = 0; // NEW: Used for smoothing the steps

        // UI Redraw pacing (60Hz)
        long lastUiPingNs = 0;
        final long UI_INTERVAL_NS = 16666666L;

        // 1. DATA ACQUISITION SUB-THREAD (Context-Aware Self-Healing Parser)
        // 1. DATA ACQUISITION SUB-THREAD (State-Machine Parser)
        // 1. DATA ACQUISITION SUB-THREAD (State-Machine Parser)

        // 1. DATA ACQUISITION SUB-THREAD (State-Machine Parser)
        Thread rxThread = new Thread(() -> {
            int[] rawWindow = new int[3];
            int windowIdx = 0;
            int metaByteCount = 0;
            int[] metaBytes = new int[4];
            boolean inMetaBlock = false;

            while (!Thread.currentThread().isInterrupted()) {
                try {
                    int bytesRead = mmInStream.read(buffer);
                    if (bytesRead <= 0) break;

                    for (int i = 0; i < bytesRead; i++) {
                        int b = buffer[i] & 0xFF;

                        // --- STATE A: METADATA (Battery) ---
                        // If we see 120 and we aren't already in a metablock,
                        // we check if it's likely a header (windowIdx == 0 means we are between samples)
                        if (!inMetaBlock && b == 120 && windowIdx == 0) {
                            inMetaBlock = true;
                            metaByteCount = 0;
                            continue;
                        }

                        if (inMetaBlock) {
                            metaBytes[metaByteCount++] = b;
                            if (metaByteCount == 4) {
                                // Update Battery and SOC
                                GameScreen.batVoltage = (double)(((metaBytes[0] & 0xFF) << 8) | (metaBytes[1] & 0xFF)) / 100.0;
                                GameScreen.batSOC = (double)(((metaBytes[2] & 0xFF) << 8) | (metaBytes[3] & 0xFF)) / 100.0;
                                inMetaBlock = false;
                            }
                            continue;
                        }

                        // --- STATE B: ADC SIGNAL ---
                        rawWindow[windowIdx] = b;

                        // If footer found at the correct position
                        if (b == 165 && windowIdx == 2) {
                            int high = rawWindow[0];
                            int low  = rawWindow[1];
                            int val = ((high & 0xFF) << 8) | (low & 0xFF);

                            // Dynamic Baseline Tracking
                            if (runningBaseline == -1) runningBaseline = val;
                            else runningBaseline = (runningBaseline * 0.999) + (val * 0.001);

                            double bipolar = val - runningBaseline;
                            double filteredVal = filter60Hz.filter(bipolar / 5.0);

                            // Jitter Buffer Update
                            int w = jWrite.get();
                            jitterBuffer[w] = filteredVal;
                            jWrite.set((w + 1) % jitterBuffer.length);
                            jCount.incrementAndGet();

                            // CSV Logging
                            if (GameScreen.isRecording) {
                                synchronized (GameScreen.ramRecordBuffer) {
                                    if (GameScreen.ramRecordBufferIdx < GameScreen.ramRecordBuffer.length) {
                                        GameScreen.ramRecordBuffer[GameScreen.ramRecordBufferIdx++] = filteredVal;
                                    }
                                }
                            }
                            windowIdx = 0; // Reset for next sample
                            continue;
                        }

                        windowIdx++;

                        // Self-healing: if we haven't found footer 165 by the 3rd byte, shift window
                        if (windowIdx >= 3) {
                            rawWindow[0] = rawWindow[1];
                            rawWindow[1] = rawWindow[2];
                            windowIdx = 2;
                        }
                    }
                } catch (IOException e) {
                    GameScreen.btStatus = "BT: Connection Lost";
                    break;
                }
            }
        });
        rxThread.start();

        // 2. MAIN PRECISION ENGINE
        while (!Thread.currentThread().isInterrupted()) {
            if (!rxThread.isAlive()) break;

            int count = jCount.get();
            long now = System.nanoTime();

            if (count > 0) {
                if (nextTickNs == 0) {
                    nextTickNs = now;
                    lastUiPingNs = now;
                }

                if (now >= nextTickNs) {
                    tickCounter++;
                    int samplesToRelease = 1;

                    if (count > 120) {
                        if (tickCounter % 5 == 0) samplesToRelease = 4;
                    } else if (count < 20) {
                        if (tickCounter % 5 == 0) samplesToRelease = 0;
                    }

                    for (int s = 0; s < samplesToRelease; s++) {
                        if (jCount.get() > 0) {
                            int r = jRead.get();
                            double sample = jitterBuffer[r];
                            jRead.set((r + 1) % jitterBuffer.length);
                            jCount.decrementAndGet();

                            synchronized (A2DVal) {
                                System.arraycopy(A2DVal, 1, A2DVal, 0, signalBufferLen - 1);
                                A2DVal[signalBufferLen - 1] = sample;
                            }
                        }
                    }
                    nextTickNs += NS_PER_SAMPLE;

                    if (now - lastUiPingNs >= UI_INTERVAL_NS) {
                        if (GameScreen.view != null) GameScreen.view.postInvalidateOnAnimation();
                        lastUiPingNs = now;
                    }

                    // 3. MATH HANDOFF (PSD & RMS)
                    if (mathIsBusy.compareAndSet(false, true)) {
                        synchronized (A2DVal) {
                            System.arraycopy(A2DVal, 0, a2dCopyForMath, 0, signalBufferLen);
                        }
                        mathExecutor.execute(() -> {
                            try {
                                // PSD Calculation
                                PowerSpectralDensityCalculator psdCalc = new PowerSpectralDensityCalculator(a2dCopyForMath, 1000);
                                double psdSum = 0;
                                for (double v : a2dCopyForMath) psdSum += v;
                                double psdMean = psdSum / a2dCopyForMath.length;

                                double[] windowedData = new double[a2dCopyForMath.length];
                                for (int i = 0; i < a2dCopyForMath.length; i++) {
                                    double window = 0.5 * (1.0 - Math.cos(2.0 * Math.PI * i / (a2dCopyForMath.length - 1)));
                                    windowedData[i] = (a2dCopyForMath[i] - psdMean) * window;
                                }

                                double[] tempPsd = psdCalc.calculatePSD(windowedData, 1000);
                                if (tempPsd != null && psdResult != null) {
                                    System.arraycopy(tempPsd, 0, psdResult, 0, Math.min(tempPsd.length, psdResult.length));
                                }

                                // RMS Calculation
                                double rmsSum = 0;
                                for (double v : a2dCopyForMath) rmsSum += v;
                                double rmsMean = rmsSum / a2dCopyForMath.length;

                                double[] bipolarData = new double[a2dCopyForMath.length];
                                for (int i = 0; i < a2dCopyForMath.length; i++) {
                                    bipolarData[i] = a2dCopyForMath[i] - rmsMean;
                                }

                                movingRMS = RMSCalculator.calculateMovingRMS(bipolarData, 60);
                                if (movingRMS != null) {
                                    for (int k = 0; k < movingRMS.length; k++) movingRMS[k] *= 8.0;
                                    smoothedRMS = MovingAverageCalculator.calculateMovingAverage(movingRMS, 80);
                                }
                            } catch (Exception e) {
                                Log.e("MATH", "Error in math execution", e);
                            } finally {
                                // CRITICAL: Reset the flag so the next handoff can occur
                                mathIsBusy.set(false);
                            }
                        });
                    }
                }
            } else {
                nextTickNs = System.nanoTime() + NS_PER_SAMPLE;
            }

            // Yield briefly (20us) to keep loop ultra-tight for smoothness
            LockSupport.parkNanos(20000L);
        }
        rxThread.interrupt();
        mathExecutor.shutdownNow();
    }
    // Inside ConnectedThread.java
    public void cancel() {
        try {
            // This closes the Bluetooth socket, which forces the
            // mmInStream.read() to throw an IOException and stop the thread
            if (mmSocket != null) {
                mmSocket.close();
            }
        } catch (IOException e) {
            Log.e("ConnectedThread", "Could not close the connect socket", e);
        }
    }
}
