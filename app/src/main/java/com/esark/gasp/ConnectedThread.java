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

        // --- THE JITTER BUFFER ---
        final double[] jitterBuffer = new double[8192];
        int jWrite = 0;
        int jRead = 0;
        int jCount = 0;

        while (!Thread.currentThread().isInterrupted()) {
            try {
                // 1. BLOCKING READ: Empty the Bluetooth hardware buffer
                int bytesRead = mmInStream.read(buffer);
                if (bytesRead <= 0) continue;

                // 2. PARSE AND FILL JITTER BUFFER
                for (int i = 0; i < bytesRead; i++) {
                    int b = buffer[i] & 0xFF;
                    if (b == 120) { expectingLowByte = false; continue; }
                    if (!expectingLowByte) {
                        tempHighByte = b;
                        expectingLowByte = true;
                    } else {
                        double val = ((tempHighByte << 8) | b) / 3.0;
                        expectingLowByte = false;

                        jitterBuffer[jWrite] = val;
                        jWrite = (jWrite + 1) % jitterBuffer.length;
                        jCount++;

                        if (GameScreen.isRecording) {
                            synchronized (GameScreen.ramRecordBuffer) {
                                if (GameScreen.ramRecordBufferIdx < GameScreen.ramRecordBuffer.length) {
                                    GameScreen.ramRecordBuffer[GameScreen.ramRecordBufferIdx++] = val;
                                }
                            }
                        }
                    }
                }

                // 3. ELASTIC RELEASE ENGINE (The Smoothness Secret)
                // We want to update the UI at roughly 60Hz (every 16ms)
                // But we adjust the number of samples released to keep the buffer stable.
                while (jCount > 0) {
                    int idealBuffer = 60; // 60ms cushion
                    int releaseSize;

                    if (jCount > idealBuffer + 40) {
                        releaseSize = 20; // Too much data: Speed up
                    } else if (jCount < idealBuffer - 20) {
                        releaseSize = 12; // Too little data: Slow down
                    } else {
                        releaseSize = 16; // Perfectly on time (16ms @ 1000Hz)
                    }

                    int toProcess = Math.min(jCount, releaseSize);
                    double[] outputBatch = new double[toProcess];

                    for (int k = 0; k < toProcess; k++) {
                        outputBatch[k] = jitterBuffer[jRead];
                        jRead = (jRead + 1) % jitterBuffer.length;
                    }
                    jCount -= toProcess;

                    synchronized (A2DVal) {
                        System.arraycopy(A2DVal, toProcess, A2DVal, 0, signalBufferLen - toProcess);
                        System.arraycopy(outputBatch, 0, A2DVal, signalBufferLen - toProcess, toProcess);
                    }

                    if (GameScreen.view != null) {
                        GameScreen.view.postInvalidateOnAnimation();
                    }

                    // Sleep for 16ms to match the screen's 60Hz refresh rate
                    SystemClock.sleep(16);

                    // If more data arrived while we were sleeping, exit and read Bluetooth again
                    if (mmInStream.available() > 0) break;
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