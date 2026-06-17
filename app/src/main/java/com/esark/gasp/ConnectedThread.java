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
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY);
        byte[] buffer = new byte[2048];

        // --- JITTER BUFFER ---
        final double[] jitterBuffer = new double[8192];
        int jitterWriteIdx = 0;
        int jitterReadIdx = 0;
        int jitterCount = 0;

        // --- TIME-BASE ---
        final long NS_PER_SAMPLE = 1000000L; // 1ms
        long startTimeNs = 0;
        long totalSamplesReleased = 0;

        final AtomicBoolean mathIsBusy = new AtomicBoolean(false);

        while (!Thread.currentThread().isInterrupted()) {
            try {
                // 1. BLOCKING READ: Wait for the 25-integer burst
                int bytesRead = mmInStream.read(buffer);
                if (bytesRead <= 0) continue;

                // 2. PARSE AND ADD TO JITTER BUFFER
                for (int i = 0; i < bytesRead; i++) {
                    int b = buffer[i] & 0xFF;
                    if (b == 120) { expectingLowByte = false; continue; }
                    if (!expectingLowByte) {
                        tempHighByte = b;
                        expectingLowByte = true;
                    } else {
                        double val = ((tempHighByte << 8) | b) / 3.0;
                        expectingLowByte = false;

                        jitterBuffer[jitterWriteIdx] = val;
                        jitterWriteIdx = (jitterWriteIdx + 1) % jitterBuffer.length;
                        jitterCount++;

                        if (GameScreen.isRecording) {
                            synchronized (GameScreen.ramRecordBuffer) {
                                if (GameScreen.ramRecordBufferIdx < GameScreen.ramRecordBuffer.length) {
                                    GameScreen.ramRecordBuffer[GameScreen.ramRecordBufferIdx++] = val;
                                }
                            }
                        }
                    }
                }

                // Initialize timer on very first data arrival
                if (startTimeNs == 0 && jitterCount > 0) {
                    startTimeNs = System.nanoTime();
                }

                // 3. THE LIQUID ENGINE: Adaptive Drip
                if (startTimeNs > 0) {
                    long now = System.nanoTime();
                    long elapsedNs = now - startTimeNs;

                    // targetTotal = how many samples we should have released by now
                    int targetTotal = (int) (elapsedNs / NS_PER_SAMPLE);
                    int debt = targetTotal - (int) totalSamplesReleased;

                    // RELEASE LOGIC:
                    // If we owe samples (debt > 0) and have them (jitterCount > 0)
                    if (debt > 0 && jitterCount > 0) {
                        // Release either the debt or the count, whichever is smaller
                        // This prevents the "Permanent Blackout" crash
                        int chunkSize = Math.min(debt, jitterCount);

                        // We cap the visual jump to 32ms to keep it "Liquid"
                        // If the phone lags, it will slide fast to catch up rather than jumping
                        chunkSize = Math.min(chunkSize, 32);

                        double[] outputBatch = new double[chunkSize];
                        for (int k = 0; k < chunkSize; k++) {
                            outputBatch[k] = jitterBuffer[jitterReadIdx];
                            jitterReadIdx = (jitterReadIdx + 1) % jitterBuffer.length;
                        }
                        jitterCount -= chunkSize;

                        synchronized (A2DVal) {
                            System.arraycopy(A2DVal, chunkSize, A2DVal, 0, signalBufferLen - chunkSize);
                            System.arraycopy(outputBatch, 0, A2DVal, signalBufferLen - chunkSize, chunkSize);
                        }

                        if (GameScreen.view != null) {
                            GameScreen.view.postInvalidateOnAnimation();
                        }

                        totalSamplesReleased += chunkSize;
                    }
                }

                // 4. MATH (Non-Blocking)
                if (mathIsBusy.compareAndSet(false, true)) {
                    synchronized (A2DVal) {
                        System.arraycopy(A2DVal, 0, a2dCopyForMath, 0, signalBufferLen);
                    }
                    mathExecutor.execute(() -> {
                        try {
                            // PSD/RMS Math here...
                        } finally {
                            mathIsBusy.set(false);
                        }
                    });
                }

            } catch (IOException e) {
                break;
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