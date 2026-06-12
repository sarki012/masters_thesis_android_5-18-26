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
        // 1. Set priority to the absolute maximum allowed
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);

        byte[] buffer = new byte[2048];
        double fs = 1000;
        PowerSpectralDensityCalculator psdCalc = null;

        final double[] tempSamples = new double[2048];
        final AtomicBoolean mathIsBusy = new AtomicBoolean(false);

        // Track time to enforce a steady 60Hz UI heartbeat
        long lastUiUpdateUptime = 0;

        while (!Thread.currentThread().isInterrupted()) {
            try {
        //        if (A2DVal == null || GameScreen.ramRecordBuffer == null) {
            //        SystemClock.sleep(100);
              //      continue;
              //  }

                if (psdCalc == null) {
                    psdCalc = new PowerSpectralDensityCalculator(A2DVal, fs);
                }

                // 2. THE "SOMETHING NEW": NON-BLOCKING DRAIN
                // Instead of calling read() and waiting, we check if data is there.
                // If data is there, we read it ALL before updating the UI.
                int bytesAvailable = mmInStream.available();
                if (bytesAvailable > 0) {

                    // Limit the read to our buffer size
                    int bytesToRead = Math.min(bytesAvailable, buffer.length);
                    int bytesRead = mmInStream.read(buffer, 0, bytesToRead);

                    int samplesFound = 0;
                    for (int i = 0; i < bytesRead; i++) {
                        int b = buffer[i] & 0xFF;
                        if (b == 'x') { // 'x' sync
                            expectingLowByte = false;
                            continue;
                        }
                        if (!expectingLowByte) {
                            tempHighByte = b;
                            expectingLowByte = true;
                        } else {
                            if (samplesFound < tempSamples.length) {
                                tempSamples[samplesFound++] = ((tempHighByte << 8) | b) / 3.0;
                            }
                            expectingLowByte = false;
                        }
                    }

                    if (samplesFound > 0) {
                        final int numNew = samplesFound;

                        // 3. ZERO-DELAY RECORDING (Direct Copy, no Executor)
                        if (GameScreen.isRecording) {
                            synchronized (GameScreen.ramRecordBuffer) {
                                int spaceLeft = GameScreen.ramRecordBuffer.length - GameScreen.ramRecordBufferIdx;
                                int toCopy = Math.min(numNew, spaceLeft);
                                if (toCopy > 0) {
                                    System.arraycopy(tempSamples, 0, GameScreen.ramRecordBuffer, GameScreen.ramRecordBufferIdx, toCopy);
                                    GameScreen.ramRecordBufferIdx += toCopy;
                                }
                            }
                        }

                        // 4. ATOMIC UI UPDATE
                        synchronized (A2DVal) {
                            System.arraycopy(A2DVal, numNew, A2DVal, 0, signalBufferLen - numNew);
                            System.arraycopy(tempSamples, 0, A2DVal, signalBufferLen - numNew, numNew);
                        }

                        // 5. THE "UI STABILIZER": Enforce steady visual cadence
                        // Only ping the UI if a new frame is actually due (16ms)
                        long now = SystemClock.uptimeMillis();
                        if (now - lastUiUpdateUptime >= 16) {
                            if (GameScreen.view != null) {
                                GameScreen.view.postInvalidateOnAnimation();
                            }
                            lastUiUpdateUptime = now;
                        }

                        // 6. THROTTLED MATH
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
                } else {
                    // 7. PRECISION SLEEP
                    // If no data, sleep for exactly 1ms to prevent CPU maxing
                    // but keep the thread "hot" for the next byte.
                    SystemClock.sleep(1);
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