package com.esark.gasp;

import static com.esark.framework.AndroidGame.signalBufferLen;

import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;

import com.esark.framework.Game;
import com.esark.framework.Graphics;
import com.esark.framework.Input;
import com.esark.framework.Input.TouchEvent;
import com.esark.framework.Screen;
import com.esark.framework.AndroidAudio;
import com.esark.framework.Sound;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import android.os.Handler;         // ADD THIS LINE

import android.graphics.Canvas;
import android.graphics.Paint;
import com.esark.framework.AndroidGraphics;


public class GameScreen extends Screen implements Input {
    boolean isAlertPlaying = false;
    Sound alertSound;
    int thresholdY = 0;
    Context context = null;
    private static final String TAG = "GameScreen";
    int xStart = 0, xStop = 0;
    double xStartPSD = 0, xStopPSD = 0;
    //public static double[] A2DVal = new double[3500];
    public static volatile double[] A2DVal = new double[signalBufferLen];   //was 1435
    //  public double[] A2DValMean = new double[signalBufferLen];
    public double A2DValMean = 0;

    public static volatile double[] movingRMS = new double[signalBufferLen];
    public static volatile double[] smoothedRMS = new double[signalBufferLen];
    double rmsScale = 0;
    double[] psd = new double[2048];

    double[] sineWave = new double[2048];
    public static double[][] eventArray = new double [50][2048];
    public static double[] lastEventArray = new double[2048];

    // Remove volatile, use final to keep the reference stable
// Ensure the size matches what your PSD calculator actually outputs
    public static volatile double[] psdResult = new double[2048];
    public static double[][] PSDArray = new double[50][2048];
    public static double[] lastEventPSDArray = new double[2048];
    int freq = 0;

    double freqScalar = 100;
    int amplitude = 100;
    int increasingFlag = 1;
    int freqIncreasingFlag = 1;
    int startRecording = 0;
    long startTimeMillis = 0;
    long recDeltaTimeMillis = 0;
    long currentTimeMillis = 0;
    long minutes = 0;
    long seconds = 0;
    long remainingMilliseconds = 0;
    int rmsThresholdTouch = 0;
    int rmsAmpThresh = 100, rmsWidthThresh = 0;
    int leftUpCount = 0, leftDownCount = 0, rightUpCount = 0, rightDownCount = 0;
    private static final double PI = 3.1415927;

    public static final int PSDYVAL = 3850;
    private static final int INVALID_POINTER_ID = -1;
    // The ‘active pointer’ is the one currently moving our object.
    private int mActivePointerId = INVALID_POINTER_ID;
    // public static int len = 0;
    public static int len = 0;
    public static String[] timeStamp = new String[100];
    public static int eventCount = 0;
    public int manualPatientEventUpCount = 0;
    public int rmsWidthThreshTouch = 0;
    public static android.view.View view;

    // Recording and Replay Variables
    public static volatile boolean isRecording = false;
    public static boolean isReplaying = false;
    private static FileOutputStream fos;
    public static volatile PrintWriter writer = null;
    private static List<Double> replayList = new ArrayList<>();
    private static int replayPosition = 0;
    private String fileName = "sEMG_Data.csv";
    // signalBufferLen is 1024.// We draw (1024 - 1) line segments. Each segment needs 4 floats (x1, y1, x2, y2).
    private final float[] lineBuffer = new float[(1024 - 1) * 4];
    // Initialize the Paint object
    private final Paint signalPaint = new Paint();
    // Background thread for disk I/O
    public static HandlerThread loggerThread;
    public static Handler loggerHandler;
    // Inside GameScreen.java
    // Pre-allocate for 100,000 samples (100 seconds of data)
    // USE THIS DECLARATION in GameScreen.java
// Pre-allocate 100,000 samples (~100 seconds) so the list doesn't have to resize
    public static List<Double> ramRecordBuffer = java.util.Collections.synchronizedList(new ArrayList<>(100000));
    //Constructor
    public GameScreen(Game game) {
        super(game);
        // Create a dedicated thread for writing to disk
        if (loggerThread == null) {
            loggerThread = new HandlerThread("DiskLogger");
            loggerThread.start();
            loggerHandler = new Handler(loggerThread.getLooper());
        }
        signalPaint.setAntiAlias(true);
        signalPaint.setStrokeWidth(5.0f);
        signalPaint.setColor(android.graphics.Color.BLACK);
        signalPaint.setStrokeCap(Paint.Cap.ROUND);
        try {
            alertSound = game.getAudio().newSound("ringtone.mp3");
        } catch (Exception e) {
            Log.e(TAG, "Failed to load ringtone.mp3: " + e.getMessage());
            alertSound = null; // Ensure it's null so the check in updateRunning works
        }
    }
    public GameScreenLastEvent gameScreenLastEvent = new GameScreenLastEvent(game);
    public GameScreenEventLog gameScreenEventLog = new GameScreenEventLog(game);
    @Override
    public void update(float deltaTime, Context context) {
        //framework.input
        List<TouchEvent> touchEvents = game.getInput().getTouchEvents();
        updateRunning(touchEvents, deltaTime, context);
    }

    private void updateRunning(List<TouchEvent> touchEvents, float deltaTime, Context context) {
        //updateRunning() contains controller code of our MVC scheme
        Graphics g = game.getGraphics();
        g.drawPortraitPixmap(Assets.laryngospasmBackgroundMain, 0, 0);
        len = touchEvents.size();
        //Check to see if paused
        for (int i = 0; i < len; i++) {
            TouchEvent event = touchEvents.get(i);
            if (event.type == TouchEvent.TOUCH_DRAGGED || event.type == TouchEvent.TOUCH_DOWN) {
                if (event.x > 1245 && event.x < 1715 && event.y > 2610 && event.y < 2710) {
                    //Back to Bluetooth Connect Screen
                    Intent intent2 = new Intent(context.getApplicationContext(), GaspSemg.class);
                    context.startActivity(intent2);
                    return;
                }
                //Start Recording Buttono
                else if (event.x > 45 && event.x < 1240 && event.y > 1240 && event.y < 2100) {
                    //Start
                    startTimeMillis = System.currentTimeMillis();
                    startRecording = 1;
                }
                //////////////////// Left Up Button ////////////////////////////////////////////////
                else if (event.x > 685 && event.x < 840 && event.y > 2110 && event.y < 2215) {
                    //RMS threshold amplitude to trigger event. Left Up Button.
                    rmsThresholdTouch = 1;
                    if (leftUpCount == 0) {       //Flag so we only increment the delay by 5 once per touch
                        rmsAmpThresh += 5;
                        leftUpCount = 1;

                    }
                }
                //////////////////// Left Down Button ////////////////////////////////////////////////
                else if (event.x > 685 && event.x < 840 && event.y > 2220 && event.y < 2325) {
                    //RMS threshold amplitude to trigger event. Left Down Button.
                    rmsThresholdTouch = 1;
                    if (leftDownCount == 0) {       //Flag so we only increment the delay by 5 once per touch
                        rmsAmpThresh -= 5;
                        leftDownCount = 1;
                    }
                }
                //////////////////// Right Up Button ////////////////////////////////////////////////
                else if (event.x > 1560 && event.x < 1715 && event.y > 2110 && event.y < 2215) {
                    //RMS threshold amplitude to trigger event. Left Up Button.
                    rmsWidthThreshTouch = 1;
                    if (rightUpCount == 0) {       //Flag so we only increment the delay by 5 once per touch
                        rmsWidthThresh += 5;
                        rightUpCount = 1;
                    }
                }
                //////////////////// Right Down Button ////////////////////////////////////////////////
                else if (event.x > 1560 && event.x < 1715 && event.y > 2220 && event.y < 2325) {
                    //RMS threshold amplitude to trigger event. Left Down Button.
                    rmsWidthThreshTouch = 1;
                    if (rightDownCount == 0) {       //Flag so we only increment the delay by 5 once per touch
                        rmsWidthThresh -= 5;
                        rightDownCount = 1;
                    }
                } else if (event.x > 720 && event.x < 1190 && event.y > 2600 && event.y < 2700) {
                    //Event Log Screen
                    game.setScreen(gameScreenEventLog);
                } else if (event.x > 1315 && event.x < 1660 && event.y > 2000 && event.y < 2100) {
                    //Stop Now clear events
                    //game.setScreen(gameScreenLastEvent);
                    eventCount = 0;
                } else if (event.x > 10 && event.x < 675 && event.y > 2450 && event.y < 2800) {
                    //Manual Patient Event
                    if (manualPatientEventUpCount == 0 && eventCount < 50) {
                        // Fast array copy instead of loop
                        System.arraycopy(A2DVal, 0, eventArray[eventCount], 0, Math.min(signalBufferLen, 2048));
                        System.arraycopy(psdResult, 0, PSDArray[eventCount], 0, psdResult.length);

                        SimpleDateFormat dateFormat = new SimpleDateFormat("HH:mm:ss");
                        timeStamp[eventCount] = dateFormat.format(new Date());
                        eventCount++;
                        manualPatientEventUpCount = 1;
                    }
                }

                //////////////////////////// Start/Stop/Save Recording /////////////////////////////
                /*  1. Start/Stop: It uses PrintWriter with a BufferedWriter. This is much faster than
                    standard file writing and prevents UI stuttering.
                    2. External Files Dir: It saves the data to Android/data/com.esark.gasp/files/sEMG_Data.csv.
                    This doesn't require extra Android permissions on newer versions.
                    3. Replay Logic: It loads the entire CSV into a List. During the draw loop, it swaps the
                    source of val1 and val2 from the live A2DVal array to the replayList.
                    4. Looping: When replaying, it increments replayPosition every frame, making the recorded
                    signal "slide" across the screen exactly like the real-time one.
                 */
                //////////////////////////// Start/Stop/Save Recording /////////////////////////////
                else if (event.x > 1600 && event.x < 1700 && event.y > 1330 && event.y < 1600) {
                    if (!isRecording) {
                        // --- START RECORDING ---
                        synchronized (ramRecordBuffer) {
                            ramRecordBuffer.clear(); // Clear RAM for new recording
                        }
                        isRecording = true;
                        isReplaying = false;
                        startRecording = 1;      // Start UI timer
                        startTimeMillis = System.currentTimeMillis();
                    } else {
                        // --- STOP AND SAVE (Full Capture Fix) ---
                        startRecording = 0; // Stop UI clock immediately

                        // 1. Give the Bluetooth thread 800ms to finish parsing
                        // the last samples currently in the Android OS hardware buffer
                        new Handler(Looper.getMainLooper()).postDelayed(() -> {

                            // 2. NOW stop the data flow into the RAM buffer
                            isRecording = false;

                            // 3. Save the data in a background thread
                            new Thread(() -> {
                                try {
                                    List<Double> snapshot;
                                    // Take a snapshot and clear the buffer immediately
                                    synchronized (ramRecordBuffer) {
                                        snapshot = new ArrayList<>(ramRecordBuffer);
                                        ramRecordBuffer.clear();
                                    }

                                    if (snapshot.isEmpty()) {
                                        Log.e("SAVE", "Buffer was empty! Check ConnectedThread parsing.");
                                        return;
                                    }

                                    File path = context.getExternalFilesDir(null);
                                    File file = new File(path, fileName);
                                    // Use a massive buffer (64KB) for writing 1000Hz data
                                    PrintWriter pw = new PrintWriter(new BufferedWriter(
                                            new OutputStreamWriter(new FileOutputStream(file, false)), 65536));

                                    for (Double val : snapshot) {
                                        pw.println(val);
                                    }

                                    pw.flush();
                                    pw.close();
                                    Log.d("SAVE", "SUCCESS! Saved " + snapshot.size() + " samples.");

                                } catch (IOException e) {
                                    e.printStackTrace();
                                }
                            }).start();

                        }, 800); // Grace period to catch the "tail" of the signal
                    }
                } // This brace correctly closes the Start/Stop button block

                /////////////////////// Replay Recording ///////////////////////////////////////////
                else if (event.x > 1600 && event.x < 1700 && event.y > 1610 && event.y < 1920) {
                    if (!isReplaying) {
                        loadReplayData(context);
                        if (!replayList.isEmpty()) {
                            // Start at 0 to see the wave emerge from the right side
                            replayPosition = 0;
                            isReplaying = true;
                            isRecording = false;
                        }
                    } else {
                        isReplaying = false;
                    }
                }

                if (rmsAmpThresh < 0) {
                    rmsAmpThresh = 0;
                }

            } // This brace closes the if (TOUCH_DOWN || TOUCH_DRAGGED) block

            else if (event.type == TouchEvent.TOUCH_UP) {
                // Reset flags on any lift to ensure buttons remain responsive
                leftUpCount = 0;
                leftDownCount = 0;
                rightUpCount = 0;
                rightDownCount = 0;
                manualPatientEventUpCount = 0;
            }
        } // This brace closes the for-loop

        //   if(landscape == 0) {

        /*
        g.drawRect(1245, 2610, 470, 100, 0);       //Bluetooth Connect
        g.drawRect(45, 2000, 1195, 100, 0);       //Start
        g.drawRect(1315, 2000, 345, 100, 0);       //Stop
      //  g.drawRect(350, 2185, 250, 85, 0);       //Manual RMS Height Above Threshold Text
        g.drawText("50", 395, 2235);    //Manual RMS Height Above Threshold Text
       // g.drawRect(350, 2380, 250, 85, 0);       //Auto RMS Height Threshold Text
        g.drawText("50", 395, 2445);        //Auto RMS Height Threshold Text
        g.drawRect(685, 2110, 155, 105, 0);       //Left Up Button
        g.drawRect(685, 2220, 155, 105, 0);       //Left Down Button
     //   g.drawRect(1240, 2180, 250, 85, 0);       //Manual RMS Width Above Threshold Text
        g.drawText("50", 1330, 2235);       //Manual RMS Width Above Threshold Text
        g.drawRect(1560, 2110, 155, 105, 0);       //Right Up Button
        g.drawRect(1560, 2220, 155, 105, 0);       //Right Down Button
        g.drawRect(720, 2600, 470, 100, 0);       //Event Log
        g.drawRect(25, 2580, 650, 200, 0);       //Manual Patient Event

     //   g.drawRect(725, 2400, 285, 150, 0);       //True Positive
        g.drawText("50", 880, 2480);    //True Positive Text
     //   g.drawRect(1055, 2400, 285, 150, 0);       //False Positive
        g.drawText("50", 1235, 2480);       //False Positive Text
     //   g.drawRect(1400, 2400, 285, 150, 0);       //False Negative
        g.drawText("50", 1560, 2480);       //False Negative Text
*/
        //   g.drawRect(1600, 1330, 100, 270, 0);       //Start/Stop Save a Sample
        //  g.drawRect(1600, 1610, 100, 310, 0);       //Replay


        String eventCountStr = String.valueOf(eventCount);
        g.drawText(eventCountStr, 570, 2660);
        ////////////////// Start / Stop Recording //////////////////////////////////////////
        if (startRecording == 0) {
            recDeltaTimeMillis = 0;
            minutes = 0;
            seconds = 0;
            remainingMilliseconds = 0;
            String formattedTime = String.format("%02d:%02d:%03d", minutes, seconds, remainingMilliseconds);
            g.drawText(formattedTime, 840, 2070);
        } else if (startRecording == 1) {
            currentTimeMillis = System.currentTimeMillis();
            recDeltaTimeMillis = (int) (currentTimeMillis - startTimeMillis);
            minutes = (int) recDeltaTimeMillis / 60000;
            seconds = (int) recDeltaTimeMillis / 1000;
            remainingMilliseconds = (int) recDeltaTimeMillis % 1000;
            String formattedTime = String.format("%02d:%02d:%03d", minutes, seconds, remainingMilliseconds);
            g.drawText(formattedTime, 840, 2070);
        }

        //////////////////// RMS Threshold to Trigger Event //////////////////////////////////
        if (rmsThresholdTouch == 0) {
            g.drawText("95", 395, 2235);    //Manual RMS Height Above Threshold Text
        } else if (rmsThresholdTouch == 1) {
            String rmsAmpThreshStr = String.valueOf(rmsAmpThresh);
            g.drawText(rmsAmpThreshStr, 395, 2235);    //Manual RMS Height Above Threshold Text

        }

        //////////////////////////////////////////////////////////////////////////////////////

        //////////////////// Manual RMS Width Above Threshold to Trigger Event //////////////////////
        if (rmsWidthThresh == 0) {
            g.drawText("0", 1330, 2235);    //Manual RMS Height Above Threshold Text
        } else if (rmsWidthThresh == 1) {
            String rmsWidthThreshStr = String.valueOf(rmsWidthThresh);
            g.drawText(rmsWidthThreshStr, 1330, 2235);    //Manual RMS Height Above Threshold Text
        }

///////////////////////////////////////////////////////////////////////////////////
        // --- LIVE RMS & PSD (Only shows when NOT replaying) ---
        if (!isReplaying) {
            int latestY = 0;
            if (smoothedRMS.length > 2) {
                xStart = 1600;
                int blueCenterY = 1300;
                float rmsYScale = 0.3f;

                for (int n = smoothedRMS.length - 1; n > 1; n--) {
                    int y1 = (int) (blueCenterY - smoothedRMS[n] * rmsYScale);
                    int y2 = (int) (blueCenterY - smoothedRMS[n - 1] * rmsYScale);

                    thresholdY = (int) (1050 - (rmsAmpThresh * 2.0f));
                    g.drawRedLine(155, thresholdY, 1590, thresholdY, 0);

                    if (y1 < 869) y1 = 869;
                    if (y1 > 1308) y1 = 1308;
                    if (y2 < 869) y2 = 869;
                    if (y2 > 1308) y2 = 1308;

                    g.drawBlueLine(xStart, y1, xStart - 2, y2, 0);
                    xStart -= 2;
                    if (xStart <= 180) break;
                }

                latestY = (int) (blueCenterY - smoothedRMS[smoothedRMS.length - 1] * rmsYScale);
                if (latestY < thresholdY) {
                    if (!isAlertPlaying && alertSound != null) {
                        alertSound.play(5.0f);
                        isAlertPlaying = true;
                    }
                } else {
                    isAlertPlaying = false;
                }
            }

            // --- PSD Drawing Logic ---
            float currentXpsd = 170;
            float xStepPsd = 2.0f;
            for (int i = 1; i < psdResult.length; i++) {
                float nextXpsd = 170 + (i * xStepPsd);
                g.drawRedLine((int) currentXpsd, (int) psdResult[i - 1] - 1695, (int) nextXpsd, (int) psdResult[i] - 1695, 0);
                currentXpsd = nextXpsd;
                if (currentXpsd >= 1600) break;
            }
        }

        // --- 4. RAW SIGNAL DRAWING (GPU Optimized - Right to Left) ---
        // --- 4. RAW SIGNAL DRAWING (Fixed for Clipping) ---
        // dataBaseline should match the middle of your raw ADC signal (e.g., 512 or 2048)
        double dataBaseline = 410.0;
        int screenCenterY = 460;

        // REDUCED GAIN: Changing from 0.2f to 0.1f prevents the peaks from hitting the limits
        float gain = 0.2f;

        // WIDENED LIMITS: Giving the signal more "headroom" and "footroom"
        int topLimit = 50;             // Moved up from 230
        int bottomLimit = 820;          // Moved down from 690

        // Total width is 1600 - 165 = 1435 pixels.
        float currentXStep = 1.4027f;
        float xRightEdge = 1600.0f;
        int bufferIdx = 0;

        signalPaint.setAntiAlias(true);
        // THINNER STROKE: 5.0f was too fat, making peaks look flat. 2.5f is sharper.
        signalPaint.setStrokeWidth(5.0f);

        if (!isReplaying) {
            // --- LIVE BLACK LINE ---
            signalPaint.setColor(android.graphics.Color.BLACK);
            Canvas canvas = ((AndroidGraphics) g).getCanvas();

            synchronized (A2DVal) {
                bufferIdx = 0;
                for (int n = signalBufferLen - 1; n > 0; n--) {
                    float x1 = xRightEdge - ((signalBufferLen - 1 - n) * currentXStep);
                    float y1 = (float) (screenCenterY - (A2DVal[n] - dataBaseline) * gain);

                    float x2 = xRightEdge - ((signalBufferLen - 1 - (n - 1)) * currentXStep);
                    float y2 = (float) (screenCenterY - (A2DVal[n - 1] - dataBaseline) * gain);

                    // Clamping with the new widened limits
                    if (y1 < topLimit) y1 = topLimit;
                    if (y1 > bottomLimit) y1 = bottomLimit;
                    if (y2 < topLimit) y2 = topLimit;
                    if (y2 > bottomLimit) y2 = bottomLimit;

                    lineBuffer[bufferIdx++] = x1;
                    lineBuffer[bufferIdx++] = y1;
                    lineBuffer[bufferIdx++] = x2;
                    lineBuffer[bufferIdx++] = y2;

                    if (x2 <= 165 || bufferIdx >= lineBuffer.length - 4) break;
                }
                if (bufferIdx > 0) {
                    canvas.drawLines(lineBuffer, 0, bufferIdx, signalPaint);
                }
            }
        } else if (isReplaying && !replayList.isEmpty()) {// --- DEBUG: Show how many samples were loaded ---
            g.drawText("Loaded: " + replayList.size(), 170, 200);
            g.drawText("Pos: " + replayPosition, 170, 250);

            // --- REPLAY RED LINE (Full Screen & Moving) ---
            signalPaint.setColor(android.graphics.Color.RED);
            Canvas canvas = ((AndroidGraphics) g).getCanvas();
            bufferIdx = 0;

            // We iterate through 'k' which represents pixels back from the right edge
            // k=0 is the right edge (1600), k=1023 is the left edge (165)
            for (int k = 0; k < 1023; k++) {
                // We map the playhead (replayPosition) to the right edge.
                // As k increases, we look back in the file indices.
                int pos1 = replayPosition - k;
                int pos2 = replayPosition - (k + 1);

                // If the file is short, we only draw what we have
                if (pos1 >= 0 && pos1 < replayList.size() && pos2 >= 0 && pos2 < replayList.size()) {

                    float x1 = xRightEdge - (k * currentXStep);
                    float y1 = (float) (screenCenterY - (replayList.get(pos1) - dataBaseline) * gain);

                    float x2 = xRightEdge - ((k + 1) * currentXStep);
                    float y2 = (float) (screenCenterY - (replayList.get(pos2) - dataBaseline) * gain);

                    // Clamping
                    if (y1 < topLimit) y1 = topLimit;
                    if (y1 > bottomLimit) y1 = bottomLimit;
                    if (y2 < topLimit) y2 = topLimit;
                    if (y2 > bottomLimit) y2 = bottomLimit;

                    lineBuffer[bufferIdx++] = x1;
                    lineBuffer[bufferIdx++] = y1;
                    lineBuffer[bufferIdx++] = x2;
                    lineBuffer[bufferIdx++] = y2;

                    if (x2 <= 165) break;
                }
                if (bufferIdx >= lineBuffer.length - 4) break;
            }

            if (bufferIdx > 0) {
                canvas.drawLines(lineBuffer, 0, bufferIdx, signalPaint);
            }

            // --- SPEED CONTROL ---
            // Advance the playhead. At 1000Hz, we need to move roughly 17-25 samples
            // every frame to look real-time.
            replayPosition += 17;       // Was 25

            // Loop back to start if we reach the end of the file
            if (replayPosition >= replayList.size() + 1024) {
                replayPosition = 0;
            }

            // Force the animation to continue
            if (GameScreen.view != null) {
                GameScreen.view.postInvalidate();
            }
        }

        //////////////////////////////////////////////////////////////////////////


    }



    /////////////// LoadReplayData Helper Method ///////////////////////////////////////////////////
    private void loadReplayData(Context context) {
        replayList.clear();
        replayPosition = 0;
        try {
            File path = context.getExternalFilesDir(null);
            File file = new File(path, fileName);
            BufferedReader br = new BufferedReader(new FileReader(file));
            String line;
            while ((line = br.readLine()) != null) {
                // Ensure we don't add empty lines
                if (!line.trim().isEmpty()) {
                    replayList.add(Double.parseDouble(line));
                }
            }
            br.close();
            if (!replayList.isEmpty()) {
                isReplaying = true;
                isRecording = false;
            }
        } catch (IOException | NumberFormatException e) {
            e.printStackTrace();
        }
    }


    @Override
    public void present ( float deltaTime){
        Graphics g = game.getGraphics();
    }

    @Override
    public void pause () {

    }

    @Override
    public void resume () {

    }

    @Override
    public void dispose () {
    }

    @Override
    public boolean isTouchDown(int pointer) {
        return false;
    }

    @Override
    public int getTouchX(int pointer) {
        return 0;
    }

    @Override
    public int getTouchY(int pointer) {
        return 0;
    }

    @Override
    public float getAccelX() {
        return 0;
    }

    @Override
    public float getAccelY() {
        return 0;
    }

    @Override
    public float getAccelZ() {
        return 0;
    }

    @Override
    public List<TouchEvent> getTouchEvents() {
        return null;
    }
}