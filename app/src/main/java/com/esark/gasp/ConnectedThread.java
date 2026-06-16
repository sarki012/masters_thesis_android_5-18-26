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
        // URGENT_DISPLAY ensures the OS prioritizes this thread's timing
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY);

        byte[] buffer = new byte[4096];
        double fs = 1000;
        PowerSpectralDensityCalculator psdCalc = null;

        // Local storage for parsed samples
        final double[] packetSamples = new double[4096];
        final java.util.concurrent.atomic.AtomicBoolean mathIsBusy = new java.util.concurrent.atomic.AtomicBoolean(false);

        while (!Thread.currentThread().isInterrupted()) {
            try {
                if (psdCalc == null && A2DVal != null) {
                    psdCalc = new PowerSpectralDensityCalculator(A2DVal, fs);
                }

                // 1. BLOCKING READ: Wait for the next Bluetooth burst
                int bytesRead = mmInStream.read(buffer);
                if (bytesRead <= 0) continue;

                int samplesFound = 0;
                // 2. PARSE DATA
                for (int i = 0; i < bytesRead; i++) {
                    int b = buffer[i] & 0xFF;
                    if (b == 120) { // Sync 'x'
                        expectingLowByte = false;
                        continue;
                    }
                    if (!expectingLowByte) {
                        tempHighByte = b;
                        expectingLowByte = true;
                    } else {
                        if (samplesFound < packetSamples.length) {
                            packetSamples[samplesFound++] = ((tempHighByte << 8) | b) / 3.0;
                        }
                        expectingLowByte = false;
                    }
                }

                if (samplesFound > 0) {
                    // 3. IMMEDIATE RECORDING: Save raw data to RAM immediately (No lag)
                    if (GameScreen.isRecording) {
                        synchronized (GameScreen.ramRecordBuffer) {
                            int spaceLeft = GameScreen.ramRecordBuffer.length - GameScreen.ramRecordBufferIdx;
                            int toCopy = Math.min(samplesFound, spaceLeft);
                            if (toCopy > 0) {
                                System.arraycopy(packetSamples, 0, GameScreen.ramRecordBuffer, GameScreen.ramRecordBufferIdx, toCopy);
                                GameScreen.ramRecordBufferIdx += toCopy;
                            }
                        }
                    }

                    // 4. CADENCE STABILIZER: The "Drip" Loop
                    // Instead of jumping 100 pixels at once, we move in smooth 20ms steps.
                    int processed = 0;
                    while (processed < samplesFound) {
                        int chunkSize = Math.min(20, samplesFound - processed);
                        final double[] subBatch = new double[chunkSize];
                        System.arraycopy(packetSamples, processed, subBatch, 0, chunkSize);

                        synchronized (A2DVal) {
                            System.arraycopy(A2DVal, chunkSize, A2DVal, 0, signalBufferLen - chunkSize);
                            for (int k = 0; k < chunkSize; k++) {
                                A2DVal[signalBufferLen - chunkSize + k] = subBatch[k];
                            }
                        }

                        if (GameScreen.view != null) {
                            GameScreen.view.postInvalidateOnAnimation();
                        }

                        processed += chunkSize;

                        // This tiny sleep (approx 18-20ms) simulates real-time flow
                        // and prevents the "speed-up" accordion effect.
                        if (processed < samplesFound) {
                            SystemClock.sleep(18);
                        }
                    }

                    // 5. MATH (Throttled per burst)
                    if (mathIsBusy.compareAndSet(false, true)) {
                        synchronized (A2DVal) {
                            System.arraycopy(A2DVal, 0, a2dCopyForMath, 0, signalBufferLen);
                        }
                        final PowerSpectralDensityCalculator finalPsd = psdCalc;
                        mathExecutor.execute(() -> {
                            try {
                                double[] tempResult = finalPsd.calculatePSD(a2dCopyForMath, fs);
                                if (tempResult != null && psdResult != null) {
                                    int copyLen = Math.min(tempResult.length, psdResult.length);
                                    System.arraycopy(tempResult, 0, psdResult, 0, copyLen);
                                    for (int j = 0; j < copyLen; j++) {
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
            } catch (IOException e) {
                break;
            } catch (Exception e) {
                Log.e("BT", "Runtime Error", e);
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