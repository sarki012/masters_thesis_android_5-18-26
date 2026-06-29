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
        GameScreen.btStatus = "BT: Connected (Zero-Loss Mode)";

        byte[] buffer = new byte[2048];
        final double[] jitterBuffer = new double[65536];
        final AtomicInteger jWrite = new AtomicInteger(0);
        final AtomicInteger jRead = new AtomicInteger(0);
        final AtomicInteger jCount = new AtomicInteger(0);

        // --- CONSTANT TIMEBASE VARIABLES ---
        final long BASE_INTERVAL_NS = 1000000L; // 1.0ms Target
        long nextTickNs = 0;

        // UI Redraw pacing (60Hz)
        long lastUiPingNs = 0;
        final long UI_INTERVAL_NS = 16666666L;

        // 1. DATA ACQUISITION SUB-THREAD (Drains BT Hardware)
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

        // 2. MAIN PRECISION ENGINE (The "Drip" loop)
        while (!Thread.currentThread().isInterrupted()) {
            if (!rxThread.isAlive()) break;

            int count = jCount.get();
            long now = System.nanoTime();

            if (count > 0) {
                // Initialize master clock on first sample arrival
                if (nextTickNs == 0) {
                    nextTickNs = now;
                    lastUiPingNs = now;
                }

                if (now >= nextTickNs) {
                    // --- VARIABLE DRIP RATE (CATCH-UP WITHOUT DISCARD) ---
                    // Instead of discarding, we shorten the wait time to "fast forward"
                    // Target cushion = 60 samples
                    int error = count - 60;

                    // Adjustment gain: 2000ns per sample error.
                    // If buffer has 160 samples (100 sample error), it speeds up by 200,000ns.
                    // The drip becomes 0.8ms, clearing the backlog in ~0.5 seconds.
                    // --- REFINED PI-CONTROL (Cruise Control) ---
                    // Target: 60 samples.
                    // Error Multiplier: 5000ns (5 microseconds) per sample of error.
                    // If we are 40 samples over (100 total), we speed up by 200,000ns (0.2ms).
                    long adjustment = error * 5000L;

                    // CAP: Max speedup/slowdown is 10% (100,000ns).
                    // A 10% change in frequency is almost invisible to the human eye,
                    // preventing the "stretching/compressing" rubber band look.
                    if (adjustment > 100000L) adjustment = 100000L;
                    if (adjustment < -100000L) adjustment = -100000L;


                    // Release EXACTLY 1 sample
                    int r = jRead.get();
                    double sample = jitterBuffer[r];
                    jRead.set((r + 1) % jitterBuffer.length);
                    jCount.decrementAndGet();

                    synchronized (A2DVal) {
                        System.arraycopy(A2DVal, 1, A2DVal, 0, signalBufferLen - 1);
                        A2DVal[signalBufferLen - 1] = sample;
                    }

                    // Advance the clock by (1ms - adjustment)
                    nextTickNs += (BASE_INTERVAL_NS - adjustment);

                    // UI Heartbeat (60Hz)
                    if (now - lastUiPingNs >= UI_INTERVAL_NS) {
                        if (GameScreen.view != null) {
                            GameScreen.view.postInvalidateOnAnimation();
                        }
                        lastUiPingNs = now;
                    }

                    // 3. MATH HANDOFF (PSD & RMS) - Optimized Background Execution
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
                                        // Keep your specific scaling
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
                // If we ran out of data, reset nextTick to "now" to avoid sudden speed jumps later
                nextTickNs = System.nanoTime() + BASE_INTERVAL_NS;
            }

            // Yield briefly to keep loop timing tight (50us)
            LockSupport.parkNanos(50000L);
        }
        rxThread.interrupt();
        mathExecutor.shutdownNow();
    }
}