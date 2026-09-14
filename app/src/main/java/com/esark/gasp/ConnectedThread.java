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
        Thread rxThread = new Thread(() -> {
            int currentHeader = 0;
            int metaByteCount = 0;
            int firstByte = -1;
            boolean inMetaBlock = false;
            int[] metaBytes = new int[4];
            int logThrottleCounter = 0; // Added for throttling signal logs

            while (!Thread.currentThread().isInterrupted()) {
                try {int bytesRead = mmInStream.read(buffer);
                    if (bytesRead == -1) break;

                    for (int i = 0; i < bytesRead; i++) {
                        int b = buffer[i] & 0xFF;

                        // --- 1. METADATA HEADER CHECK ---
                        // Only enter metadata block if:
                        // - We see the header '120'
                        // - AND we aren't already in the middle of a 16-bit sample (firstByte == -1)
                        if (!inMetaBlock && (b == 120) && (firstByte == -1)) {
                            inMetaBlock = true;
                            metaByteCount = 0;
                            continue;
                        }

                        if (inMetaBlock) {
                            // Collect 4 bytes of Metadata
                            metaBytes[metaByteCount++] = b;
                            if (metaByteCount == 4) {
                                int vRaw = ((metaBytes[0] & 0xFF) << 8) | (metaBytes[1] & 0xFF);
                                GameScreen.batVoltage = vRaw / 100.0;
                                int sRaw = ((metaBytes[2] & 0xFF) << 8) | (metaBytes[3] & 0xFF);
                                GameScreen.batSOC = sRaw / 100.0;

                                inMetaBlock = false;
                            }
                        } else {
                            // --- 2. COLLECT 16-BIT SIGNAL DATA ---
                            if (firstByte == -1) {
                                firstByte = b;
                            } else {
                                // Reconstruct 16-bit value
                                int val = ((firstByte & 0xFF) << 8) | (b & 0xFF);
                                // Reset for next pair immediately
                                firstByte = -1;
                                // --- DYNAMIC BASELINE FIX ---
                                // Instead of subtracting a hardcoded 32768, we center the signal
                                // based on where the raw data actually sits (e.g., in the 200s).
                                // We use a simple IIR low-pass to track the DC offset (the "quiet" level)

                                if (runningBaseline == -1) {
                                    runningBaseline = val; // Initialize on first sample
                                } else {
                                    // Slowly track the average raw value (DC Offset)
                                    runningBaseline = (runningBaseline * 0.999) + (val * 0.001);
                                }

                                // Center the signal around the discovered baseline
                                double bipolarRaw = (val - runningBaseline);

                                // Now apply scaling and filtering
                                // Since the signal is now centered at 0.0, we can use a higher gain
                                double filteredVal = filter60Hz.filter(bipolarRaw / 3.0);



                                if (logThrottleCounter++ % 100 == 0) {
                                    Log.d("BT_SIGNAL", "Raw: " + val + " | Filtered: " + filteredVal);
                                }

                                int w = jWrite.get();
                                jitterBuffer[w] = filteredVal;
                                jWrite.set((w + 1) % jitterBuffer.length);
                                jCount.incrementAndGet();

                                // --- 4. DATA LOGGING ---
                                if (GameScreen.isRecording) {
                                    synchronized (GameScreen.ramRecordBuffer) {
                                        if (GameScreen.ramRecordBufferIdx < GameScreen.ramRecordBuffer.length) {
                                            GameScreen.ramRecordBuffer[GameScreen.ramRecordBufferIdx++] = filteredVal;
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
        }); // End of rxThread
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
                                movingRMS = RMSCalculator.calculateMovingRMS(bipolarData, 60);      // Was 40
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
