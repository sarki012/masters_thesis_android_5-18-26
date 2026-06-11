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

    @Override    public void run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY);

        byte[] buffer = new byte[4096];
        double fs = 1000;
        PowerSpectralDensityCalculator psdCalc = null;

        double[] cadenceBuffer = new double[4096];
        int cadenceIdx = 0;

        final int UI_STEP = 25;
        final double[] stepChunk = new double[UI_STEP];

        while (!Thread.currentThread().isInterrupted()) {
            try {
                // Ensure buffers exist
                if (A2DVal == null || GameScreen.ramRecordBuffer == null) {
                    SystemClock.sleep(10);
                    continue;
                }

                if (psdCalc == null) {
                    psdCalc = new PowerSpectralDensityCalculator(A2DVal, fs);
                }

                int bytesRead = mmInStream.read(buffer);
                if (bytesRead <= 0) continue;

                for (int i = 0; i < bytesRead; i++) {
                    int b = buffer[i] & 0xFF;
                    if (b == 120) {
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

                while (cadenceIdx >= UI_STEP) {
                    System.arraycopy(cadenceBuffer, 0, stepChunk, 0, UI_STEP);
                    cadenceIdx -= UI_STEP;
                    System.arraycopy(cadenceBuffer, UI_STEP, cadenceBuffer, 0, cadenceIdx);

                    // A. RECORDING: Primitive Copy (ZERO OBJECT ALLOCATION)
                    if (GameScreen.isRecording) {
                        synchronized (GameScreen.ramRecordBuffer) {
                            int spaceLeft = GameScreen.ramRecordBuffer.length - GameScreen.ramRecordBufferIdx;
                            int toCopy = Math.min(UI_STEP, spaceLeft);
                            if (toCopy > 0) {
                                System.arraycopy(stepChunk, 0, GameScreen.ramRecordBuffer, GameScreen.ramRecordBufferIdx, toCopy);
                                GameScreen.ramRecordBufferIdx += toCopy;
                            }
                        }
                    }

                    // B. ATOMIC UI UPDATE
                    synchronized (A2DVal) {
                        System.arraycopy(A2DVal, UI_STEP, A2DVal, 0, signalBufferLen - UI_STEP);
                        System.arraycopy(stepChunk, 0, A2DVal, signalBufferLen - UI_STEP, UI_STEP);
                    }

                    if (GameScreen.view != null) {
                        GameScreen.view.postInvalidateOnAnimation();
                    }

                    // D. MATH: Throttled
                    if (mathSkipCount++ % 12 == 0) {
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