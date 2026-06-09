package com.esark.gasp;

import static com.esark.framework.AndroidGame.signalBufferLen;
import static com.esark.gasp.GameScreen.A2DVal;
import static com.esark.gasp.GameScreen.movingRMS;
import static com.esark.gasp.GameScreen.psdResult;
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

        double[] localBatch = new double[512];
        int batchIdx = 0;
        final int batchThreshold = 20;

        // Local reference to the RAM buffer for speed
        final java.util.List<Double> ramBuffer = GameScreen.ramRecordBuffer;

        while (!Thread.currentThread().isInterrupted()) {
            try {
                if (A2DVal == null || ramBuffer == null) {
                    SystemClock.sleep(100);
                    continue;
                }

                if (psdCalc == null) {
                    psdCalc = new PowerSpectralDensityCalculator(A2DVal, fs);
                }

                int bytesRead = mmInStream.read(buffer);
                if (bytesRead > 0) {
                    for (int i = 0; i < bytesRead; i++) {
                        int b = buffer[i] & 0xFF;

                        // 1. Sync Marker
                        if (b == 'x') {
                            expectingLowByte = false;
                            continue;
                        }

                        // 2. Binary Parser
                        if (!expectingLowByte) {
                            tempHighByte = b;
                            expectingLowByte = true;
                        } else {
                            int lowByte = b;
                            int rawVal = (tempHighByte << 8) | lowByte;
                            double parsedVal = rawVal / 3.0;
                            expectingLowByte = false;

                            // --- FIX 1: NON-BLOCKING RECORDING ---
                            // We only record if the flag is true.
                            // We do NOT use synchronized here because it slows down the thread.
                            if (GameScreen.isRecording) {
                                ramBuffer.add(parsedVal);
                            }

                            // Add to UI batch
                            if (batchIdx < localBatch.length) {
                                localBatch[batchIdx++] = parsedVal;
                            }

                            if (batchIdx >= batchThreshold) {
                                final int numNew = batchIdx;
                                final double[] samplesToProcess = new double[numNew];
                                System.arraycopy(localBatch, 0, samplesToProcess, 0, numNew);
                                batchIdx = 0;

                                // TASK: DISPLAY
                                displayExecutor.execute(() -> {
                                    if (A2DVal != null) {
                                        synchronized (A2DVal) {
                                            System.arraycopy(A2DVal, numNew, A2DVal, 0, signalBufferLen - numNew);
                                            for (int j = 0; j < numNew; j++) {
                                                A2DVal[signalBufferLen - numNew + j] = samplesToProcess[j];
                                            }
                                        }
                                        if (GameScreen.view != null) {
                                            GameScreen.view.postInvalidate();
                                        }
                                    }
                                });

                                // TASK: MATH (Throttled)
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