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
        // HIGHEST PRIORITY: Ensure the OS doesn't throttle this thread
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY);

        byte[] buffer = new byte[4096];
        double fs = 1000;
        PowerSpectralDensityCalculator psdCalc = null;

        // PRE-ALLOCATE: Re-use these arrays to stop the 7-second GC lag
        double[] tempSamples = new double[2048];

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

                int samplesFound = 0;

                // 2. ULTRA-FAST PARSE
                for (int i = 0; i < bytesRead; i++) {
                    int b = buffer[i] & 0xFF;
                    if (b == 120) { // ASCII 'x'
                        expectingLowByte = false;
                        continue;
                    }

                    if (!expectingLowByte) {
                        tempHighByte = b;
                        expectingLowByte = true;
                    } else {
                        // Reconstruct 16-bit val
                        int rawVal = (tempHighByte << 8) | b;
                        if (samplesFound < tempSamples.length) {
                            tempSamples[samplesFound++] = rawVal / 3.0;
                        }
                        expectingLowByte = false;
                    }
                }

                if (samplesFound > 0) {
                    final int numNew = samplesFound;

                    // 3. OPTIMIZED RECORDING: Hand off to executor without allocating new arrays
                    if (GameScreen.isRecording) {
                        // We must create a snapshot ONLY when recording to maintain integrity
                        // but we do it only for the batch
                        final double[] recordBatch = new double[numNew];
                        System.arraycopy(tempSamples, 0, recordBatch, 0, numNew);
                        recordExecutor.execute(() -> {
                            synchronized (ramRecordBuffer) {
                                for (double v : recordBatch) ramRecordBuffer.add(v);
                            }
                        });
                    }

                    // 4. ATOMIC UI UPDATE: Update display array on the BT thread
                    // This ensures the plot is ALWAYS in sync with the incoming data
                    synchronized (A2DVal) {
                        System.arraycopy(A2DVal, numNew, A2DVal, 0, signalBufferLen - numNew);
                        System.arraycopy(tempSamples, 0, A2DVal, signalBufferLen - numNew, numNew);
                    }

                    // 5. THROTTLED UI NOTIFICATION
                    // Don't flood the UI thread. Only ping if the view exists.
                    if (GameScreen.view != null) {
                        GameScreen.view.postInvalidateOnAnimation();
                    }

                    // 6. ZERO-ALLOCATION MATH HAND-OFF
                    // We use the pre-allocated 'a2dCopyForMath' instead of 'new double[]'
                    if (mathSkipCount++ % 15 == 0) {
                        synchronized (A2DVal) {
                            System.arraycopy(A2DVal, 0, a2dCopyForMath, 0, signalBufferLen);
                        }

                        final PowerSpectralDensityCalculator finalPsd = psdCalc;
                        mathExecutor.execute(() -> {
                            // Calculate PSD using the shared pre-allocated math copy
                            double[] tempResult = finalPsd.calculatePSD(a2dCopyForMath, fs);
                            if (tempResult != null && psdResult != null) {
                                int copyLen = Math.min(tempResult.length, psdResult.length);
                                System.arraycopy(tempResult, 0, psdResult, 0, copyLen);
                                for (int j = 0; j < copyLen; j++) {
                                    psdResult[j] = psdResult[j] * -1 + 3600;
                                    if (psdResult[j] < 3165) psdResult[j] = 3165;
                                }
                            }
                            // Calculate RMS
                            movingRMS = RMSCalculator.calculateMovingRMS(a2dCopyForMath, 10);
                            if (movingRMS != null) {
                                smoothedRMS = MovingAverageCalculator.calculateMovingAverage(movingRMS, 20);
                            }
                        });
                    }
                }
            } catch (IOException e) {
                Log.e("BT", "Connection Lost");
                break;
            } catch (Exception e) {
                Log.e("BT", "Processing Error", e);
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