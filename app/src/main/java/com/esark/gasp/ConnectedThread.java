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

        byte[] buffer = new byte[2048];     // Standard MTU size is better for cadence
        double fs = 1000;
        PowerSpectralDensityCalculator psdCalc = null;

        // Pre-allocate temporary storage
        final double[] packetSamples = new double[1024];
        final java.util.concurrent.atomic.AtomicBoolean mathIsBusy = new java.util.concurrent.atomic.AtomicBoolean(false);

        while (!Thread.currentThread().isInterrupted()) {
            try {
                if (A2DVal == null || GameScreen.ramRecordBuffer == null) {
                    SystemClock.sleep(50);
                    continue;
                }

                if (psdCalc == null) {
                    psdCalc = new PowerSpectralDensityCalculator(A2DVal, fs);
                }

                // 1. BLOCKING READ: Get data as fast as the hardware provides it
                int bytesRead = mmInStream.read(buffer);
                if (bytesRead <= 0) continue;

                int samplesInPacket = 0;

                // 2. ULTRA-FAST BINARY PARSING
                for (int i = 0; i < bytesRead; i++) {
                    int b = buffer[i] & 0xFF;
                    if (b == 120) { // 'x' sync
                        expectingLowByte = false;
                        continue;
                    }
                    if (!expectingLowByte) {
                        tempHighByte = b;
                        expectingLowByte = true;
                    } else {
                        if (samplesInPacket < packetSamples.length) {
                            packetSamples[samplesInPacket++] = ((tempHighByte << 8) | b) / 3.0;
                        }
                        expectingLowByte = false;
                    }
                }

                if (samplesInPacket > 0) {
                    final int numNew = samplesInPacket;
                    // Create a final copy of the data for the executors
                    // This is essential to prevent the data from being overwritten by the next read()
                    final double[] dataToProcess = new double[numNew];
                    System.arraycopy(packetSamples, 0, dataToProcess, 0, numNew);

                    // 3. ASYNC RECORDING: Move off the BT thread
                    if (GameScreen.isRecording) {
                        recordExecutor.execute(() -> {
                            synchronized (GameScreen.ramRecordBuffer) {
                                int spaceLeft = GameScreen.ramRecordBuffer.length - GameScreen.ramRecordBufferIdx;
                                int toCopy = Math.min(numNew, spaceLeft);
                                if (toCopy > 0) {
                                    System.arraycopy(dataToProcess, 0, GameScreen.ramRecordBuffer, GameScreen.ramRecordBufferIdx, toCopy);
                                    GameScreen.ramRecordBufferIdx += toCopy;
                                }
                            }
                        });
                    }

                    // 4. ASYNC UI UPDATE: THIS IS THE KEY FIX
                    // By moving the 'synchronized(A2DVal)' block into an executor,
                    // the Bluetooth thread NEVER waits for the UI thread to finish drawing.
                    displayExecutor.execute(() -> {
                        if (A2DVal != null) {
                            synchronized (A2DVal) {
                                System.arraycopy(A2DVal, numNew, A2DVal, 0, signalBufferLen - numNew);
                                System.arraycopy(dataToProcess, 0, A2DVal, signalBufferLen - numNew, numNew);
                            }
                            if (GameScreen.view != null) {
                                GameScreen.view.postInvalidateOnAnimation();
                            }
                        }
                    });

                    // 5. MATH (Low Priority - Non-Blocking)
                    if (mathIsBusy.compareAndSet(false, true)) {
                        final PowerSpectralDensityCalculator finalPsd = psdCalc;
                        mathExecutor.execute(() -> {
                            try {
                                // Take a local snapshot of A2DVal for math
                                double[] mathSnapshot = new double[signalBufferLen];
                                synchronized (A2DVal) {
                                    System.arraycopy(A2DVal, 0, mathSnapshot, 0, signalBufferLen);
                                }

                                double[] tempResult = finalPsd.calculatePSD(mathSnapshot, fs);
                                if (tempResult != null && psdResult != null) {
                                    int copyLen = Math.min(tempResult.length, psdResult.length);
                                    System.arraycopy(tempResult, 0, psdResult, 0, copyLen);
                                    for (int j = 0; j < copyLen; j++) {
                                        psdResult[j] = psdResult[j] * -1 + 3600;
                                        if (psdResult[j] < 3165) psdResult[j] = 3165;
                                    }
                                }
                                movingRMS = RMSCalculator.calculateMovingRMS(mathSnapshot, 10);
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