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
        // HIGHEST PRIORITY: Same as the UI and Audio engines
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY);

        byte[] buffer = new byte[4096];
        final double[] jitterBuffer = new double[16384]; // Large shock absorber
        int jWrite = 0;
        int jRead = 0;
        int jCount = 0;

        final AtomicBoolean mathIsBusy = new AtomicBoolean(false);

        // Target: 1000 samples per second = 16.66 samples per 60Hz frame
        long lastFrameNs = System.nanoTime();
        final long NS_PER_FRAME = 16666666L; // 16.66ms

        while (!Thread.currentThread().isInterrupted()) {
            try {
                // 1. FAST DRAIN: Empty the hardware antenna buffer immediately
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

                            // Add to Jitter Buffer
                            jitterBuffer[jWrite] = val;
                            jWrite = (jWrite + 1) % jitterBuffer.length;
                            jCount++;

                            // Immediate Recording (CSV stays 100% accurate)
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

                // 2. THE ELASTIC ENGINE: This runs even if no new data arrived this millisecond
                long nowNs = System.nanoTime();
                if (nowNs - lastFrameNs >= NS_PER_FRAME && jCount > 0) {

                    // --- ELASTIC CALCULATION ---
                    // We want to keep exactly 60 samples in the buffer as a "safety cushion"
                    int idealCushion = 60;
                    int releaseSize;

                    if (jCount > idealCushion + 40) {
                        releaseSize = 20; // Falling behind: Release more to catch up
                    } else if (jCount < idealCushion - 20) {
                        releaseSize = 12; // Running low: Release fewer to slow down
                    } else {
                        releaseSize = 16; // Perfect cadence (16ms worth of data)
                    }

                    int toProcess = Math.min(jCount, releaseSize);
                    double[] outputBatch = new double[toProcess];

                    for (int k = 0; k < toProcess; k++) {
                        outputBatch[k] = jitterBuffer[jRead];
                        jRead = (jRead + 1) % jitterBuffer.length;
                    }
                    jCount -= toProcess;

                    // 3. ATOMIC UI UPDATE
                    synchronized (A2DVal) {
                        System.arraycopy(A2DVal, toProcess, A2DVal, 0, signalBufferLen - toProcess);
                        System.arraycopy(outputBatch, 0, A2DVal, signalBufferLen - toProcess, toProcess);
                    }

                    if (GameScreen.view != null) {
                        GameScreen.view.postInvalidateOnAnimation();
                    }

                    lastFrameNs = nowNs;

                    // 4. MATH HANDOFF (Throttled)
                    if (mathIsBusy.compareAndSet(false, true)) {
                        synchronized (A2DVal) {
                            System.arraycopy(A2DVal, 0, a2dCopyForMath, 0, signalBufferLen);
                        }
                        final PowerSpectralDensityCalculator finalPsd = new PowerSpectralDensityCalculator(A2DVal, 1000);
                        mathExecutor.execute(() -> {
                            try {
                                // PSD/RMS math here...
                                double[] tempResult = finalPsd.calculatePSD(a2dCopyForMath, 1000);
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
                }

                // 5. YIELD: Prevents CPU maxing while maintaining sub-ms responsiveness
                LockSupport.parkNanos(500000L); // 0.5ms pause

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