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
        GameScreen.btStatus = "BT: Connected (Fixed Period Mode)";

        byte[] buffer = new byte[2048];
        final double[] jitterBuffer = new double[65536];
        final AtomicInteger jWrite = new AtomicInteger(0);
        final AtomicInteger jRead = new AtomicInteger(0);
        final AtomicInteger jCount = new AtomicInteger(0);

        // --- RIGID TIMEBASE VARIABLES ---
        final long NS_PER_SAMPLE = 1000000L; // Rigid 1.0ms
        long nextTickNs = 0;

        // UI Redraw pacing (60Hz)
        long lastUiPingNs = 0;
        final long UI_INTERVAL_NS = 16666666L;

        // 1. DATA ACQUISITION SUB-THREAD
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

        // 2. MAIN PRECISION ENGINE (Discrete Step Drip)
        while (!Thread.currentThread().isInterrupted()) {
            if (!rxThread.isAlive()) break;

            int count = jCount.get();
            long now = System.nanoTime();

            if (count > 0) {
                if (nextTickNs == 0) {
                    nextTickNs = now;
                    lastUiPingNs = now;
                }

                // If it's time to process the 1ms "Tick"
                if (now >= nextTickNs) {

                    // --- THE ANTI-ACCORDION LOGIC ---
                    // We keep the drip interval EXACTLY at 1.000ms.
                    // We only change HOW MANY samples we release in that 1ms.
                    int samplesToRelease = 1;

                    if (count > 100) {
                        // Buffer is getting full (Android Bluetooth burst).
                        // Release 2 samples instantly to catch up 1ms.
                        samplesToRelease = 2;
                    } else if (count < 30) {
                        // Buffer is running low.
                        // Release 0 samples this tick to let the buffer refill.
                        samplesToRelease = 0;
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

                    // Advance clock by exactly 1.0ms.
                    // No "adjustment" math here ensures the period never stretches or compresses.
                    nextTickNs += NS_PER_SAMPLE;

                    // UI Heartbeat (60Hz)
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
                // Buffer empty? Reset clock to current time to prevent a "catch-up burst" later
                nextTickNs = System.nanoTime() + NS_PER_SAMPLE;
            }

            // Yield briefly (50us) to keep loop responsive
            LockSupport.parkNanos(50000L);
        }
        rxThread.interrupt();
        mathExecutor.shutdownNow();
    }
}