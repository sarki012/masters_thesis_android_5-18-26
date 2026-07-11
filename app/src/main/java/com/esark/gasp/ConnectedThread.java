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

        //1. DATA ACQUISITION SUB-THREAD (10-Second Packet Parser)
        Thread rxThread = new Thread(() -> {
            int currentHeader = 0;
            int metaByteCount = 0;
            int firstByte = -1;
            boolean inMetaBlock = false;

            // Temporary storage for metadata bytes
            int[] metaBytes = new int[4];

            while (!Thread.currentThread().isInterrupted()) {
                try {
                    int bytesRead = mmInStream.read(buffer);
                    if (bytesRead == -1) break;

                    for (int i = 0; i < bytesRead; i++) {
                        int b = buffer[i] & 0xFF;

                        // 1. Check for the Master Sync Header
                        if (b == 120) { // char 'x'
                            currentHeader = b;
                            inMetaBlock = true;
                            metaByteCount = 0;
                            firstByte = -1;
                            continue;
                        }

                        if (inMetaBlock) {
                            // 2. Collect 4 bytes of Metadata (Voltage L/H, SOC L/H)
                            metaBytes[metaByteCount++] = b;

                            if (metaByteCount == 4) {
                                // Reconstruct Voltage (Little-Endian)
                                int vRaw = (metaBytes[1] << 8) | metaBytes[0];
                                GameScreen.batVoltage = vRaw / 100.0;

                                // Reconstruct SOC (Little-Endian)
                                int sRaw = (metaBytes[3] << 8) | metaBytes[2];
                                GameScreen.batSOC = sRaw / 100.0;

                                inMetaBlock = false; // Switch to Signal Streaming Mode
                            }
                        } else {
                            // 3. Collect Signal Data (A2DVal) in continuous 2-byte pairs
                            if (firstByte == -1) {
                                firstByte = b; // Store Low Byte
                            } else {
                                // Reconstruct Signal (Little-Endian)
                                int val = (b << 8) | firstByte;
                                firstByte = -1; // Reset for next signal pair

                                // Apply Filter and Buffer logic
                                double rawVal = val / 3.0;
                                double filteredVal = filter60Hz.filter(rawVal);

                                int w = jWrite.get();
                                jitterBuffer[w] = filteredVal;
                                jWrite.set((w + 1) % jitterBuffer.length);
                                jCount.incrementAndGet();

                                if (GameScreen.isRecording) {
                                    synchronized (GameScreen.ramRecordBuffer) {
                                        if (ramRecordBufferIdx < ramRecordBuffer.length) {
                                            ramRecordBuffer[ramRecordBufferIdx++] = filteredVal;
                                        }
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
                                // Inside ConnectedThread mathExecutor block
                                PowerSpectralDensityCalculator psdCalc = new PowerSpectralDensityCalculator(a2dCopyForMath, 1000);

                                // NEW: Pre-processing for PSD
                                double psdSum = 0;
                                for (double v : a2dCopyForMath) psdSum += v;
                                double psdMean = psdSum / a2dCopyForMath.length;

                                double[] windowedData = new double[a2dCopyForMath.length];
                                for (int i = 0; i < a2dCopyForMath.length; i++) {
                                    // Subtract mean and apply Hanning window
                                    double window = 0.5 * (1.0 - Math.cos(2.0 * Math.PI * i / (a2dCopyForMath.length - 1)));
                                    windowedData[i] = (a2dCopyForMath[i] - psdMean) * window;
                                }

                                double[] tempPsd = psdCalc.calculatePSD(windowedData, 1000);

                                if (tempPsd != null && psdResult != null) {
                                    int psdLen = Math.min(tempPsd.length, psdResult.length);
                                    for (int j = 0; j < psdLen; j++) {
                                        // Store as raw power; conversion to dB happens in GameScreen drawing logic
                                        psdResult[j] = tempPsd[j];
                                    }
                                }
                                // --- 1. CALCULATE MEAN (DC OFFSET) ---
                                double sum = 0;
                                for (int i = 0; i < a2dCopyForMath.length; i++) {
                                    sum += a2dCopyForMath[i];
                                }
                                double mean = sum / a2dCopyForMath.length;

                                // --- 2. CONVERT TO BIPOLAR (REMOVE MEAN) ---
                                double[] bipolarData = new double[a2dCopyForMath.length];
                                for (int i = 0; i < a2dCopyForMath.length; i++) {
                                    bipolarData[i] = a2dCopyForMath[i] - mean;
                                }

                                // --- 3. CALCULATE RMS ON BIPOLAR SIGNAL ---
                                // Using the zero-centered data ensures the RMS reflects
                                // only the actual signal power/artifact.
                                movingRMS = RMSCalculator.calculateMovingRMS(bipolarData, 40);
                                if (movingRMS != null) {
                                    // --- NEW: INCREASE RMS SCALE ---
                                    // Multiplying by 8.0 (or higher) boosts the small bipolar values
                                    // so the drawing engine can see the "spikes" clearly.
                                    for (int k = 0; k < movingRMS.length; k++) {
                                        movingRMS[k] *= 1.75;
                                    }
                                    smoothedRMS = MovingAverageCalculator.calculateMovingAverage(movingRMS, 80);
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