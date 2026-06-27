package com.esark.gasp;

import static com.esark.gasp.GameScreen.*;
import android.os.Process;
import android.util.Log;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
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
        GameScreen.btStatus = "BT: Connected";

        byte[] buffer = new byte[1024];
        final double[] jitterBuffer = new double[65536];
        final int[] jWrite = {0};
        int jRead = 0;
        // Use volatile/Atomic for the count to ensure thread visibility
        java.util.concurrent.atomic.AtomicInteger jCount = new java.util.concurrent.atomic.AtomicInteger(0);

        long startTimeNs = 0;
        long totalSamplesReleased = 0;
        final long NS_PER_SAMPLE = 1000000L; // 1ms
        long lastUiPingNs = 0;
        final long UI_INTERVAL_NS = 16666666L; // 60Hz

        // 1. DATA ACQUISITION SUB-THREAD
        // We run the "Read" in a separate loop so it doesn't block the Metronome
        Thread rxThread = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    int bytesRead = mmInStream.read(buffer);
                    if (bytesRead == -1) {
                        // Connection closed gracefully by remote
                        throw new IOException("End of stream reached");
                    }
                    if (bytesRead > 0) {
                        for (int i = 0; i < bytesRead; i++) {
                            int b = buffer[i] & 0xFF;
                            if (b == 120) { expectingLowByte = false; continue; }
                            if (!expectingLowByte) {
                                tempHighByte = b;
                                expectingLowByte = true;
                            } else {
                                double val = ((tempHighByte << 8) | b) / 3.0;
                                expectingLowByte = false;

                                // Load Jitter Buffer
                                int nextWrite = (jWrite[0] + 1) % jitterBuffer.length;
                                jitterBuffer[jWrite[0]] = val;
                                jWrite[0] = nextWrite;
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
                    }
                } catch (IOException e) {
                    Log.e("BT_FATAL", "Disconnected during read: " + e.getMessage());
                    GameScreen.btStatus = "BT: Connection Lost";
                    break;
                }
            }
        });
        rxThread.start();

        // 2. MAIN METRONOME LOOP (The "Drip" Engine)
        while (!Thread.currentThread().isInterrupted()) {
            // Check if RxThread died
            if (!rxThread.isAlive()) break;

            if (startTimeNs == 0 && jCount.get() > 0) {
                startTimeNs = System.nanoTime();
                lastUiPingNs = startTimeNs;
            }

            if (startTimeNs > 0) {
                long now = System.nanoTime();
                long elapsedNs = now - startTimeNs;
                long targetTotal = elapsedNs / NS_PER_SAMPLE;

                // Move data into the display array based on the CLOCK
                while (totalSamplesReleased < targetTotal && jCount.get() > 0) {
                    double sample = jitterBuffer[jRead];
                    jRead = (jRead + 1) % jitterBuffer.length;
                    jCount.decrementAndGet();

                    synchronized (A2DVal) {
                        System.arraycopy(A2DVal, 1, A2DVal, 0, signalBufferLen - 1);
                        A2DVal[signalBufferLen - 1] = sample;
                    }
                    totalSamplesReleased++;

                    // Redraw logic
                    if (now - lastUiPingNs >= UI_INTERVAL_NS) {
                        if (GameScreen.view != null) {
                            GameScreen.view.postInvalidateOnAnimation();
                        }
                        lastUiPingNs = now;
                    }
                }
            }

            // 3. MATH HAND-OFF
            if (mathIsBusy.compareAndSet(false, true)) {
                synchronized (A2DVal) {
                    System.arraycopy(A2DVal, 0, a2dCopyForMath, 0, signalBufferLen);
                }
                mathExecutor.execute(() -> {
                    try {
                        PowerSpectralDensityCalculator psdCalc = new PowerSpectralDensityCalculator(a2dCopyForMath, 1000);
                        double[] tempPsd = psdCalc.calculatePSD(a2dCopyForMath, 1000);
                        if (tempPsd != null && psdResult != null) {
                            int limit = Math.min(tempPsd.length, psdResult.length);
                            for (int j = 0; j < limit; j++) {
                                psdResult[j] = tempPsd[j] * -1 + 3600;
                                if (psdResult[j] < 3165) psdResult[j] = 3165;
                            }
                        }
                        movingRMS = RMSCalculator.calculateMovingRMS(a2dCopyForMath, 10);
                        if (movingRMS != null) {
                            smoothedRMS = MovingAverageCalculator.calculateMovingAverage(movingRMS, 20);
                        }
                    } catch (Exception e) {
                        Log.e("MATH_ERROR", "Error in PSD/RMS: " + e.getMessage());
                    } finally {
                        mathIsBusy.set(false);
                    }
                });
            }

            // Tight yield to check the clock again
            LockSupport.parkNanos(100000L); // 0.1ms
        }

        rxThread.interrupt();
    }
}