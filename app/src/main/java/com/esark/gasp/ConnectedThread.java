package com.esark.gasp;

import static com.esark.gasp.GameScreen.*;
import android.os.Process;
import android.util.Log;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

public class ConnectedThread extends Thread {
    private final InputStream mmInStream;
    private int tempHighByte;
    private boolean expectingLowByte = false;

    private final ExecutorService mathExecutor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean mathIsBusy = new AtomicBoolean(false);
    private final double[] a2dCopyForMath = new double[signalBufferLen];

    public ConnectedThread(InputStream stream) {
        this.mmInStream = stream;
    }

    @Override
    public void run() {
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

        // 1. DATA ACQUISITION SUB-THREAD (Unchanged)
        Thread rxThread = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    int bytesRead = mmInStream.read(buffer);
                    if (bytesRead == -1) break;
                    for (int i = 0; i < bytesRead; i++) {
                        int b = buffer[i] & 0xFF;
                        if (b == 120) { expectingLowByte = false; continue; }
                        if (!expectingLowByte) {
                            tempHighByte = b;
                            expectingLowByte = true;
                        } else {
                            double val = ((tempHighByte << 8) | b) / 3.0;
                            expectingLowByte = false;
                            int w = jWrite.get();
                            jitterBuffer[w] = val;
                            jWrite.set((w + 1) % jitterBuffer.length);
                            jCount.incrementAndGet();
                            if (GameScreen.isRecording) {
                                synchronized (GameScreen.ramRecordBuffer) {
                                    if (ramRecordBufferIdx < ramRecordBuffer.length) {
                                        ramRecordBuffer[ramRecordBufferIdx++] = val;
                                    }
                                }
                            }
                        }
                    }
                } catch (IOException e) {
                    GameScreen.btStatus = "BT: Connection Lost";
                    break;
                }
            }
        });
        rxThread.start();

        // 2. MAIN PRECISION ENGINE (Dampened Discrete Step)
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

                    // --- THE LIQUID DISCRETE LOGIC ---
                    // We target a buffer of 80 samples.
                    // Instead of a hard "jump" to 2 samples, we distribute the catch-up.
                    int samplesToRelease = 1;

                    if (count > 120) {      //Was 120
                        // Buffer is too full. Catch up by releasing an extra sample
                        // ONLY once every 5 ticks. This spreads the "jerk" out.
                        if (tickCounter % 5 == 0) {     //Was 5
                            samplesToRelease = 4;       //Was 2
                        }
                    } else if (count < 20) {
                        // Buffer is too low. Slow down by skipping a sample
                        // ONLY once every 5 ticks.
                        if (tickCounter % 5 == 0) {
                            samplesToRelease = 0;
                        }
                    }

                    // Process the samples
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

                    // Advance clock by exactly 1.0ms
                    nextTickNs += NS_PER_SAMPLE;

                    // UI Redraw at 60Hz
                    if (now - lastUiPingNs >= UI_INTERVAL_NS) {
                        if (GameScreen.view != null) {
                            GameScreen.view.postInvalidateOnAnimation();
                        }
                        lastUiPingNs = now;
                    }

                    // 3. MATH HANDOFF (PSD & RMS) - Preserved
                    if (mathIsBusy.compareAndSet(false, true)) {
                        synchronized (A2DVal) {
                            System.arraycopy(A2DVal, 0, a2dCopyForMath, 0, signalBufferLen);
                        }
                        mathExecutor.execute(() -> {
                            try {
                                PowerSpectralDensityCalculator psdCalc = new PowerSpectralDensityCalculator(a2dCopyForMath, 1000);
                                double[] tempPsd = psdCalc.calculatePSD(a2dCopyForMath, 1000);
                                if (tempPsd != null && psdResult != null) {
                                    int psdLen = Math.min(tempPsd.length, psdResult.length);
                                    for (int j = 0; j < psdLen; j++) {
                                        psdResult[j] = tempPsd[j] * -0.1 + 3650;
                                    }
                                }
                                movingRMS = RMSCalculator.calculateMovingRMS(a2dCopyForMath, 10);
                                if (movingRMS != null) {
                                    smoothedRMS = MovingAverageCalculator.calculateMovingAverage(movingRMS, 20);
                                }
                            } catch (Exception e) {
                                Log.e("MATH", "Error", e);
                            } finally {
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
}