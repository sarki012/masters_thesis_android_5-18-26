package com.esark.gasp;

import static com.esark.framework.AndroidGame.signalBufferLen;
import static com.esark.gasp.GameScreen.A2DVal;
import static com.esark.gasp.GameScreen.movingRMS;
import static com.esark.gasp.GameScreen.psdResult;
import static com.esark.gasp.GameScreen.ramRecordBuffer;
import static com.esark.gasp.GameScreen.smoothedRMS;
import static com.esark.gasp.GameScreen.writer;

import android.bluetooth.BluetoothSocket;
import android.os.Handler;
import android.os.Process;
import android.util.Log;

import com.esark.framework.AndroidGame;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ConnectedThread extends Thread {
    private final BluetoothSocket mmSocket;
    private final InputStream mmInStream;
    private final OutputStream mmOutStream;
    private final Handler mHandler;

    private int mathSkipCount = 0;

    // State machine variables for binary parsing
    private boolean expectingLowByte = false;
    private int tempHighByte = 0;

    private final ExecutorService displayExecutor = Executors.newSingleThreadExecutor();
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
        double fs = 1000;
        PowerSpectralDensityCalculator psdCalc = null;

        // Cumulative buffer to stabilize the UI cadence
        double[] uiAccumulator = new double[2048];
        int accumulatedSamples = 0;

        // FIXED STEP SIZE: The signal will always move in increments of 40.
        // This prevents the "jumping/bunching" effect.
        final int UI_STEP = 40;

        while (!Thread.currentThread().isInterrupted()) {
            try {
                if (A2DVal == null || ramRecordBuffer == null) {
                    SystemClock.sleep(100);
                    continue;
                }

                if (psdCalc == null) {
                    psdCalc = new PowerSpectralDensityCalculator(A2DVal, fs);
                }

                int bytesRead = mmInStream.read(buffer);
                if (bytesRead > 0) {
                    int samplesInPacket = 0;
                    double[] packetSamples = new double[1024];

                    // 1. PARSE BYTES
                    for (int i = 0; i < bytesRead; i++) {
                        int b = buffer[i] & 0xFF;
                        if (b == 'x' || b == 120) {
                            expectingLowByte = false;
                            continue;
                        }

                        if (!expectingLowByte) {
                            tempHighByte = b;
                            expectingLowByte = true;
                        } else {
                            int lowByte = b;
                            int rawVal = (tempHighByte << 8) | lowByte;
                            double parsedVal = rawVal / 3.0;
                            expectingLowByte = false;

                            if (samplesInPacket < packetSamples.length) {
                                packetSamples[samplesInPacket++] = parsedVal;
                            }
                        }
                    }

                    if (samplesInPacket > 0) {
                        // 2. RECORDING (Synchronized Batch)
                        if (GameScreen.isRecording) {
                            synchronized (ramRecordBuffer) {
                                for (int j = 0; j < samplesInPacket; j++) {
                                    ramRecordBuffer.add(packetSamples[j]);
                                }
                            }
                        }

                        // 3. ADD TO UI ACCUMULATOR
                        for (int j = 0; j < samplesInPacket; j++) {
                            if (accumulatedSamples < uiAccumulator.length) {
                                uiAccumulator[accumulatedSamples++] = packetSamples[j];
                            }
                        }

                        // 4. CHUNKED UI UPDATES (The Smoothness Fix)
                        // Instead of pushing everything at once, we break large bursts
                        // into consistent steps of 40 samples.
                        while (accumulatedSamples >= UI_STEP) {
                            final double[] displayChunk = new double[UI_STEP];
                            System.arraycopy(uiAccumulator, 0, displayChunk, 0, UI_STEP);

                            // Shift the remaining data in the accumulator to the front
                            accumulatedSamples -= UI_STEP;
                            System.arraycopy(uiAccumulator, UI_STEP, uiAccumulator, 0, accumulatedSamples);

                            // Send the fixed-size chunk to the executor
                            displayExecutor.execute(() -> {
                                if (A2DVal != null) {
                                    synchronized (A2DVal) {
                                        // Shift by exactly UI_STEP
                                        System.arraycopy(A2DVal, UI_STEP, A2DVal, 0, signalBufferLen - UI_STEP);
                                        for (int j = 0; j < UI_STEP; j++) {
                                            A2DVal[signalBufferLen - UI_STEP + j] = displayChunk[j];
                                        }
                                    }
                                    if (GameScreen.view != null) {
                                        // Request redraw for this smooth step
                                        GameScreen.view.postInvalidate();
                                    }
                                }
                            });
                        }

                        // 5. MATH (Throttled)
                        final PowerSpectralDensityCalculator finalPsdCalc = psdCalc;
                        if (mathSkipCount++ % 10 == 0 && finalPsdCalc != null) {
                            mathExecutor.execute(() -> {
                                if (A2DVal != null) {
                                    synchronized (A2DVal) {
                                        System.arraycopy(A2DVal, 0, a2dCopyForMath, 0, signalBufferLen);
                                    }
                                    double[] tempResult = finalPsdCalc.calculatePSD(a2dCopyForMath, fs);
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
                                }
                            });
                        }
                    }
                }
            } catch (IOException e) {
                break;
            } catch (Exception e) {
                Log.e("ConnectedThread", "Parsing Error", e);
            }
        }
        displayExecutor.shutdownNow();
        mathExecutor.shutdownNow();
        recordExecutor.shutdownNow();
    }


    private static class SystemClock {
        public static void sleep(long ms) {
            try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
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