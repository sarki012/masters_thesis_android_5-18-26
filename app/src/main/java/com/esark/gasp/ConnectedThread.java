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
        // HIGHEST PRIORITY: Match the UI/Audio engines
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY);

        byte[] buffer = new byte[4096];
        final double[] jitterBuffer = new double[32768]; // 32k sample cushion
        int jWrite = 0;
        int jRead = 0;
        int jCount = 0;

        final AtomicBoolean mathIsBusy = new AtomicBoolean(false);

        // --- PRECISION TIME-BASE VARIABLES ---
        long startTimeNs = 0;
        long totalSamplesReleased = 0;
        // 1,000,000 nanoseconds = 1 millisecond per sample
        final long NS_PER_SAMPLE = 1000000L;

        while (!Thread.currentThread().isInterrupted()) {
            try {
                // 1. DRAIN THE HARDWARE: Get bytes off the Bluetooth antenna immediately
                int bytesAvailable = mmInStream.available();
                if (bytesAvailable > 0) {
                    int bytesRead = mmInStream.read(buffer, 0, Math.min(bytesAvailable, buffer.length));
                    for (int i = 0; i < bytesRead; i++) {
                        int b = buffer[i] & 0xFF;
                        if (b == 120) { expectingLowByte = false; continue; } // 'x' sync
                        if (!expectingLowByte) {
                            tempHighByte = b;
                            expectingLowByte = true;
                        } else {
                            double val = ((tempHighByte << 8) | b) / 3.0;
                            expectingLowByte = false;

                            // Store in the large jitter buffer
                            jitterBuffer[jWrite] = val;
                            jWrite = (jWrite + 1) % jitterBuffer.length;
                            jCount++;

                            // Immediate Recording for CSV (stays accurate to source)
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

                // 2. THE PRECISION CLOCK ENGINE
                // Initialize start time when the first sample arrives
                if (startTimeNs == 0 && jCount > 0) {
                    startTimeNs = System.nanoTime();
                }

                if (startTimeNs > 0) {
                    long now = System.nanoTime();
                    // Determine how many samples SHOULD have been shown by now
                    long elapsedNs = now - startTimeNs;
                    long targetReleased = elapsedNs / NS_PER_SAMPLE;

                    // Calculate "Debt": Samples waiting in buffer that are owed to the screen
                    int debt = (int) (targetReleased - totalSamplesReleased);

                    // If we have samples in the jitter buffer and the clock says it's time to show them
                    if (debt > 0 && jCount > 0) {
                        // Release debt, but never more than what is actually in the buffer
                        int releaseSize = Math.min(debt, jCount);

                        // CAP RELEASE SIZE: This prevents "jumping"
                        // If the OS stalls, we release max 16 samples per iteration (16ms steps)
                        // This makes the "catch-up" slide fluidly instead of teleporting
                        int toProcess = Math.min(releaseSize, 16);

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
                            // frame-synced redraw
                            GameScreen.view.postInvalidateOnAnimation();
                        }

                        totalSamplesReleased += toProcess;
                    }
                }

                // 4. MATH HANDOFF (Non-Blocking)
                if (mathIsBusy.compareAndSet(false, true)) {
                    synchronized (A2DVal) {
                        System.arraycopy(A2DVal, 0, a2dCopyForMath, 0, signalBufferLen);
                    }
                    mathExecutor.execute(() -> {
                        try {
                            // (Keep your PSD/RMS math here)
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

                // 5. THE YIELD: Essential to keep the thread from consuming 100% CPU
                // but much tighter than sleep(16). We check the debt every 1ms.
                LockSupport.parkNanos(1000000L);

            } catch (IOException e) {
                break;
            } catch (Exception e) {
                Log.e("ConnectedThread", "Precision Engine Error", e);
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