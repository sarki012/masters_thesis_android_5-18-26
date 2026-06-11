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
        // HIGHEST PRIORITY
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY);

        byte[] buffer = new byte[4096];
        double fs = 1000;
        PowerSpectralDensityCalculator psdCalc = null;

        // Cumulative buffer to stabilize the UI and Recording cadence
        // This prevents the 7-second lag by reducing context switching
        double[] cadenceBuffer = new double[2048];
        int cadenceIdx = 0;
        final int CADENCE_THRESHOLD = 25; // Update every 25ms (40Hz) - Perfect for real-time

        while (!Thread.currentThread().isInterrupted()) {
            try {
                if (A2DVal == null || ramRecordBuffer == null) {
                    SystemClock.sleep(10);
                    continue;
                }

                if (psdCalc == null) {
                    psdCalc = new PowerSpectralDensityCalculator(A2DVal, fs);
                }

                // 1. BLOCKING READ
                int bytesRead = mmInStream.read(buffer);
                if (bytesRead <= 0) continue;

                // 2. PARSE DATA INTO THE CADENCE BUFFER
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
                        int rawVal = (tempHighByte << 8) | b;
                        if (cadenceIdx < cadenceBuffer.length) {
                            cadenceBuffer[cadenceIdx++] = rawVal / 3.0;
                        }
                        expectingLowByte = false;
                    }
                }

                // 3. PROCESS DATA ONLY WHEN THRESHOLD IS MET (The Smoothness Fix)
                if (cadenceIdx >= CADENCE_THRESHOLD) {
                    final int numNew = cadenceIdx;
                    final double[] batchCopy = new double[numNew];
                    System.arraycopy(cadenceBuffer, 0, batchCopy, 0, numNew);
                    cadenceIdx = 0; // Reset accumulator

                    // A. ASYNC RECORDING (Off-loaded)
                    if (GameScreen.isRecording) {
                        recordExecutor.execute(() -> {
                            synchronized (ramRecordBuffer) {
                                for (double v : batchCopy) ramRecordBuffer.add(v);
                            }
                        });
                    }

                    // B. ATOMIC UI UPDATE (Immediate on BT Thread for zero lag)
                    synchronized (A2DVal) {
                        System.arraycopy(A2DVal, numNew, A2DVal, 0, signalBufferLen - numNew);
                        System.arraycopy(batchCopy, 0, A2DVal, signalBufferLen - numNew, numNew);
                    }

                    // C. FRAME-SYNCED PING
                    if (GameScreen.view != null) {
                        GameScreen.view.postInvalidateOnAnimation();
                    }

                    // D. HEAVY MATH (Heavily Throttled)
                    // PSD on 1024 points is CPU intensive.
                    // Lowering update frequency to ~8Hz is enough for the eye and saves the signal speed.
                    if (mathSkipCount++ % 5 == 0) { // mathSkipCount increments per batch, not per sample
                        synchronized (A2DVal) {
                            System.arraycopy(A2DVal, 0, a2dCopyForMath, 0, signalBufferLen);
                        }

                        final PowerSpectralDensityCalculator finalPsd = psdCalc;
                        mathExecutor.execute(() -> {
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