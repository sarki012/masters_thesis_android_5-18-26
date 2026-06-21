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
        // Boost priority to Audio level to get the tightest timing possible from the Linux kernel
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);

        byte[] buffer = new byte[4096];
        final double[] jitterBuffer = new double[65536];
        int jWrite = 0;
        int jRead = 0;
        int jCount = 0;

        final AtomicBoolean mathIsBusy = new AtomicBoolean(false);

        // --- PRECISION 1ms ENGINE VARIABLES ---
        long startTimeNs = 0;
        long totalSamplesReleased = 0;
        final long NS_PER_SAMPLE = 1000000L; // Exactly 1ms

        // UI Heartbeat: Target 60Hz (16.66ms)
        long lastUiPingNs = 0;
        final long UI_INTERVAL_NS = 16666666L;

        while (!Thread.currentThread().isInterrupted()) {
            try {
                // 1. DRAIN THE HARDWARE (Get data off the antenna ASAP)
                int bytesAvailable = mmInStream.available();
                if (bytesAvailable > 0) {
                    int bytesRead = mmInStream.read(buffer, 0, Math.min(bytesAvailable, buffer.length));
                    for (int i = 0; i < bytesRead; i++) {
                        int b = buffer[i] & 0xFF;
                        if (b == 120) { expectingLowByte = false; continue; }
                        if (!expectingLowByte) {
                            tempHighByte = b;
                            expectingLowByte = true;
                        } else {
                            double val = ((tempHighByte << 8) | b) / 3.0;
                            expectingLowByte = false;

                            jitterBuffer[jWrite] = val;
                            jWrite = (jWrite + 1) % jitterBuffer.length;
                            jCount++;

                            if (GameScreen.isRecording) {
                                synchronized (GameScreen.ramRecordBuffer) {
                                    if (GameScreen.ramRecordBufferIdx < GameScreen.ramRecordBuffer.length) {
                                        GameScreen.ramRecordBuffer[GameScreen.ramRecordBufferIdx++] = val;
                                    }
                                }
                            }
                        }
                    }
                }

                // 2. THE PRECISION METRONOME (The Smoothness Secret)
                if (startTimeNs == 0 && jCount > 0) {
                    startTimeNs = System.nanoTime();
                    lastUiPingNs = startTimeNs;
                }

                if (startTimeNs > 0) {
                    long now = System.nanoTime();
                    long elapsedNs = now - startTimeNs;

                    // targetReleased = How many 1ms steps should have occurred by now
                    long targetReleased = elapsedNs / NS_PER_SAMPLE;

                    // DRIP-FEED: Release samples to catch up to the clock.
                    // We remove the 'while' loop catch-up to prevent "Jumping".
                    // Instead, we release max 1 sample per "loop tick" (0.1ms).
                    // This creates a smooth slide instead of a teleport.
                    if (totalSamplesReleased < targetReleased && jCount > 0) {
                        double singleSample = jitterBuffer[jRead];
                        jRead = (jRead + 1) % jitterBuffer.length;
                        jCount--;

                        synchronized (A2DVal) {
                            // Perfect 1-pixel/1ms shift
                            System.arraycopy(A2DVal, 1, A2DVal, 0, signalBufferLen - 1);
                            A2DVal[signalBufferLen - 1] = singleSample;
                        }
                        totalSamplesReleased++;
                    }

                    // 3. UI HEARTBEAT (Steady 60Hz)
                    // Decoupling UI pings from data release fixes Duty Cycle "shimmer"
                    if (now - lastUiPingNs >= UI_INTERVAL_NS) {
                        if (GameScreen.view != null) {
                            GameScreen.view.postInvalidateOnAnimation();
                        }
                        lastUiPingNs = now;
                    }
                }

                // 4. MATH HANDOFF (Throttled)
                if (mathIsBusy.compareAndSet(false, true)) {
                    synchronized (A2DVal) {
                        System.arraycopy(A2DVal, 0, a2dCopyForMath, 0, signalBufferLen);
                    }
                    mathExecutor.execute(() -> {
                        try {
                            double[] tempResult = new PowerSpectralDensityCalculator(a2dCopyForMath, 1000).calculatePSD(a2dCopyForMath, 1000);
                            if (tempResult != null && psdResult != null) {
                                System.arraycopy(tempResult, 0, psdResult, 0, Math.min(tempResult.length, psdResult.length));
                                for (int j = 0; j < psdResult.length; j++) {
                                    psdResult[j] = psdResult[j] * -1 + 3600;
                                    if (psdResult[j] < 3165) psdResult[j] = 3165;
                                }
                            }
                            movingRMS = RMSCalculator.calculateMovingRMS(a2dCopyForMath, 10);
                            if (movingRMS != null) {
                                smoothedRMS = MovingAverageCalculator.calculateMovingAverage(movingRMS, 20);
                            }
                        } finally {
                            mathIsBusy.set(false);
                        }
                    });
                }

                // 5. HIGH-PRECISION YIELD (0.1ms)
                // This allows the engine to check for clock "debt" 10 times every millisecond.
                LockSupport.parkNanos(100000L);

            } catch (IOException e) {
                break;
            } catch (Exception e) {
                Log.e("ConnectedThread", "Engine Error", e);
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