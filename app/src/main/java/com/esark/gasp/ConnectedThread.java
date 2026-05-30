package com.esark.gasp;


import static com.esark.framework.AndroidGame.signalBufferLen;
import static com.esark.gasp.GameScreen.A2DVal;
import static com.esark.gasp.GameScreen.movingRMS;
import static com.esark.gasp.GameScreen.psdResult;

import android.bluetooth.BluetoothSocket;
import android.os.Handler;
import android.os.SystemClock;

import com.esark.framework.AndroidGame;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import static com.esark.gasp.GameScreen.smoothedRMS;
import static com.esark.gasp.GameScreen.writer;

// ... (keep your imports at the top)

public class ConnectedThread extends Thread {private final BluetoothSocket mmSocket;
    private final InputStream mmInStream;
    private final OutputStream mmOutStream;
    private final Handler mHandler;

    // FIX 1: Move count here so it can be accessed inside the Runnable
    private int count = 0;

    public ConnectedThread(BluetoothSocket socket, Handler handler) {
        mmSocket = socket;
        mHandler = handler;
        InputStream tmpIn = null;
        OutputStream tmpOut = null;

        // Get the input and output streams; using temp objects because
        // member streams are final.
        try {
            tmpIn = socket.getInputStream();
            tmpOut = socket.getOutputStream();
        } catch (IOException e) {
            e.printStackTrace();
        }

        mmInStream = tmpIn;
        mmOutStream = tmpOut;
    }

    @Override
    public void run() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        DataInputStream dataIn = new DataInputStream(mmInStream);

        double fs = 2000;
        PowerSpectralDensityCalculator psdCalc = new PowerSpectralDensityCalculator(A2DVal, fs);

        // REMOVED 'int count = 0' from here

        while (true) {
            try {
                byte[] buffer = new byte[24];
                dataIn.readFully(buffer);

                mHandler.obtainMessage(AndroidGame.MESSAGE_READ, 24, -1, buffer).sendToTarget();

                executor.execute(new Runnable() {
                    @Override
                    public void run() {
                        // ... (Keep your PSD and RMS math here) ...
                        double[] tempResult = psdCalc.calculatePSD(A2DVal, fs);
                        if (tempResult != null && tempResult.length <= psdResult.length) {
                            System.arraycopy(tempResult, 0, psdResult, 0, tempResult.length);
                        }
                        for (int i = 0; i < psdResult.length; i++) {
                            psdResult[i] = psdResult[i] * -1 + 3600;
                            if (psdResult[i] < 3165) psdResult[i] = 3165;
                        }
                        movingRMS = RMSCalculator.calculateMovingRMS(A2DVal, 10);
                        smoothedRMS = MovingAverageCalculator.calculateMovingAverage(movingRMS, 20);

                        /*  count++; and if (count % 10 == 0) (Throttling)
                        Your Bluetooth data is arriving at 2000Hz. Even though you read 24 bytes
                        at a time (12 samples), the executor is still running roughly 166 times per second.
                        The Problem: Most Android screens only refresh at 60Hz (60 times per second). If you
                        try to force the screen to redraw 166 times per second, you will overwhelm the RenderThread, leading to the "app crashes first time opening" issue you had earlier.
                        The Solution: This code implements Throttling. By using the "modulo" operator (% 10), it
                        only triggers a redraw every 10th packet. This reduces the refresh requests to about 16
                        times per second, which is plenty for a smooth human-readable 1Hz sine wave while saving
                        significant CPU and battery power.
                         */
                        count++;
                        if (count % 10 == 0) {
                            /* mHandler.post(new Runnable() { ... }) (Thread Jumping)
                             We use the mHandler to "post" a redraw request to the UI thread
                             In Android, there is a strict rule: Background threads are forbidden
                            from touching the UI.

                            The Context: This code is currently running inside the executor
                            (a background thread). If you tried to call GameScreen.view.invalidate()
                            directly here, the app would crash immediately with a CalledFromWrongThreadException.

                            The Action: mHandler.post takes a piece of code (the Runnable) and "posts"
                            it into a queue that the Main UI Thread manages. It essentially says:
                            "Hey UI thread, whenever you have a free millisecond, please run this code for me."
                             */
                            mHandler.post(new Runnable() {
                                @Override
                                public void run() {
                                    // This checks if the view exists and refreshes it
                                    /* GameScreen.view.invalidate(); (The Redraw Trigger)
                                    The Action: This is the command that actually makes your sine wave
                                    move. It tells Android: "The data in the A2DVal array has changed.
                                    Please clear the old lines and call the draw method in GameScreen again."
                                    The Result: This is what creates the "oscilloscope" effect. Without
                                    this line, your math would happen in the background, but the screen
                                    would remain a static, unmoving image.
                                     */
                                    if (GameScreen.view != null) {
                                        GameScreen.view.invalidate();
                                    }
                                }
                            });
                        }
                        // --- RECORDING LOGIC ---
                        /* This code block is responsible for saving the raw sEMG sensor data to a
                        file on your phone's storage in real-time.
                        Since you are sampling at 2000Hz, this logic is designed to be highly efficient
                        so it doesn't interrupt the smooth flow of the sine wave on your screen.
                         */
                        // 2000Hz means data comes fast. We must loop through the 24-byte
                        // buffer to extract and record every sample (2 bytes each).
                        /* The Guard Clause (if)
                        What it does: It checks two things: 1) Has the user pressed the "Start/Save"
                        button? 2) Is the file actually open and ready for writing? Why it's there:
                        This ensures that you aren't wasting CPU power writing to a file that doesn't
                        exist, and it prevents a NullPointerException crash.
                         */
                        if (GameScreen.isRecording && writer != null) {
                            // A 24-byte packet usually contains a specific number of samples.
                            // If your data is 2-bytes per sample, that's 12 samples.
                            // If it's ASCII text, it might vary.
                            // We will grab the last 'N' samples added to the A2DVal array.
                            writer.println(A2DVal[signalBufferLen - 1]);
                            /*
                            int samplesInPacket = 12; // Was 12 Adjust this to match your sampling rate/packet size

                            for (int i = signalBufferLen - samplesInPacket; i < signalBufferLen; i++) {
                                // Double check index bounds to prevent crashing
                                if (i >= 0 && i < A2DVal.length) {
                                    double val = A2DVal[i];

                                    // Write the actual parsed value from the array to the file
                                    writer.println(val);
                                }
                            }
                            */
                             
                            writer.flush();
                        }

                    }   // End of executor run()
                });
            } catch (IOException e) {
                e.printStackTrace();
                break;
            }
        }
    }
    // ... (keep write and cancel methods)
}