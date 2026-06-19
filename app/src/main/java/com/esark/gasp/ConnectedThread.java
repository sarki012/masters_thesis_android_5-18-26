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

        // --- THE JITTER BUFFER (Shock Absorber) ---
        final double[] jitterBuffer = new double[8192];
        int jWrite = 0;
        int jRead = 0;
        int jCount = 0;

        while (!Thread.currentThread().isInterrupted()) {
            try {
                // 1. BLOCKING READ: Capture the 25-integer burst
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
                // We release data based on how much is "waiting" to smooth out Bluetooth jitter
                while (jCount > 0) {
                    int idealBuffer = 50; // 50ms cushion
                    int releaseSize;

                    if (jCount > idealBuffer + 30) {
                        releaseSize = 20; // Falling behind: Speed up release
                    } else if (jCount < idealBuffer - 20) {
                        releaseSize = 12; // Running low: Slow down release
                    } else {
                        releaseSize = 16; // Perfect: 16 samples per 16ms = 1000Hz
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

                    // Sleep for 16ms to align with the phone's 60Hz hardware clock
                //    SystemClock.sleep(16);

                    // If more data hit the Bluetooth antenna while we slept, stop dripping
                    // and go back to the read() loop to empty the hardware buffer.
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