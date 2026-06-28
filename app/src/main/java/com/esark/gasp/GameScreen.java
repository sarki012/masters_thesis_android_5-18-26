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

import com.esark.framework.AndroidGame;
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

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class GameScreen extends Screen implements Input {
    boolean isAlertPlaying = false;
    Sound alertSound;
    int thresholdY = 0;
    Context context = null;
    private static final String TAG = "GameScreen";
    int xStart = 0, xStop = 0;
    double xStartPSD = 0, xStopPSD = 0;
    //public static double[] A2DVal = new double[3500];
    public static int signalBufferLen = 1436;
    public static volatile double[] A2DVal = new double[signalBufferLen];   //was 1435
    //  public double[] A2DValMean = new double[signalBufferLen];
    public double A2DValMean = 0;

    public static volatile double[] movingRMS = new double[signalBufferLen];
    public static volatile double[] smoothedRMS = new double[signalBufferLen];
    double rmsScale = 0;
    double[] psd = new double[2048];

    double[] sineWave = new double[2048];

    // Remove volatile, use final to keep the reference stable
// Ensure the size matches what your PSD calculator actually outputs

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
    private int len = 0;
    public static String[] timeStamp = new String[100];
    public static double[] eventData = new double[100];
    public static int eventCount = 0;
    public static volatile double[] psdResult = new double[signalBufferLen];
    public static double[][] PSDArray = new double[100][signalBufferLen];
    public static double[] lastEventPSDArray = new double[signalBufferLen];
    public static double[][] eventArray = new double [100][signalBufferLen];
    public static double[] lastEventArray = new double[signalBufferLen];
    public static int manualPatientEventUpCount = 0;
    public int rmsWidthThreshTouch = 0;
    public static android.view.View view;

    // Recording and Replay Variables
    public static volatile boolean isRecording = false;
    public static volatile boolean isReplaying = false;
    private static FileOutputStream fos;
    public static volatile PrintWriter writer = null;
    private static List<Double> replayList = new ArrayList<>();
    private static int replayPosition = 0;
    private String fileName = "sEMG_Data.csv";
    private String fileNameLoop = "sEMG_data_loop.csv";
    // signalBufferLen is 1435.// We draw (1435 - 1) line segments. Each segment needs 4 floats (x1, y1, x2, y2).
    private final float[] lineBuffer = new float[(signalBufferLen - 1) * 4];
    // Initialize the Paint object
    private final Paint signalPaint = new Paint();
    // Background thread for disk I/O
    public static HandlerThread loggerThread;
    public static Handler loggerHandler;
    // Inside GameScreen.java
    // Pre-allocate for 100,000 samples (100 seconds of data)
    // USE THIS DECLARATION in GameScreen.java
// Pre-allocate 100,000 samples (~100 seconds) so the list doesn't have to resize
    //  public static List<Double> ramRecordBuffer = java.util.Collections.synchronizedList(new ArrayList<>(100000));
    // Inside GameScreen.java - replace your current ramRecordBuffer declaration
    public static double[] ramRecordBuffer = new double[600000]; //Was 400,000 // Fits 5 minutes at 1000Hz
    public static int ramRecordBufferIdx =0;
    private final static int currentXStep = 1;

    // FIX 1: Use signalBufferLen instead of hardcoded 1435 to prevent Bounds Crash
    private final double[] drawingSnapshot = new double[signalBufferLen];

    // FIX 2: Declare these here, but do NOT initialize them here
    public GameScreenEventLog gameScreenEventLog;
    private long totalRecordingTime = 0;
    public static int[] eventBufferPointers = new int[100]; // Stores the index of each event
    public static int selectedEventPointer = -1;           // Which event we are currently replaying
    // Inside GameScreen.java
    public static GameScreen liveScreen;
    private final ExecutorService saveExecutor = Executors.newSingleThreadExecutor();
    public static String btStatus = "Bluetooth: Disconnected";
    public static double[] replayRMSArray;
    public static double[] replayPSDArray;
    public static double[] replayRawArray; // Add this
    // Constructor
    public GameScreen(Game game) {
        super(game);
        liveScreen = this; // Store this instance
        // Cast the 'game' object to Context.
        // This works because AndroidGame extends Activity, which is a Context.
        this.context = (Context) game;

        // FIX 4: Initialize sub-screens here so 'game' is valid
        //   gameScreenLastEvent = new GameScreenLastEvent(game);
        //   gameScreenEventLog = new GameScreenEventLog(game);

        // Create a dedicated thread for writing to disk
        if (loggerThread == null) {
            loggerThread = new HandlerThread("DiskLogger");
            loggerThread.start();
            loggerHandler = new Handler(loggerThread.getLooper());
        }

        // Initialize Paint
        signalPaint.setAntiAlias(true);
        signalPaint.setStrokeWidth(5.0f);
        signalPaint.setColor(android.graphics.Color.BLACK);
        signalPaint.setStrokeCap(Paint.Cap.ROUND);

        try {
            alertSound = game.getAudio().newSound("ringtone.mp3");
        } catch (Exception e) {
            Log.e(TAG, "Failed to load ringtone.mp3: " + e.getMessage());
            alertSound = null;
        }
    }
    // public GameScreenLastEvent gameScreenLastEvent = new GameScreenLastEvent(game);
    //public GameScreenEventLog gameScreenEventLog = new GameScreenEventLog(game);
    @Override
    public void update(float deltaTime, Context context) {
        //framework.input
        List<TouchEvent> touchEvents = game.getInput().getTouchEvents();
        updateRunning(touchEvents, deltaTime, context);
    }

    private void updateRunning(List<TouchEvent> touchEvents, float deltaTime, Context context) {
        //updateRunning() contains controller code of our MVC scheme
        Graphics g = game.getGraphics();
        // FIX: Iterate directly and check size every time to prevent IndexOutOfBounds
        // if the list is cleared mid-loop by the UI thread.
        for (int i = 0; i < touchEvents.size(); i++) {
            // Final safety check: if the list was cleared/shrunk mid-iteration
            if (i >= touchEvents.size()) {
                break;
            }

            TouchEvent event = touchEvents.get(i);
            if (event.type == TouchEvent.TOUCH_DOWN || event.type == TouchEvent.TOUCH_DRAGGED) {
                if (event.x > 1245 && event.x < 1715 && event.y > 2535 && event.y < 2735) {
                    //Back to Bluetooth Connect Screen      //Bluetooth Connect
                    Intent intent2 = new Intent(context.getApplicationContext(), GaspSemg.class);
                    context.startActivity(intent2);
                    return;
                }
                //////////////////// Start Recording Button (Green Button) ////////////////////////////////////////////////
                else if (event.x > 45 && event.x < 845 && event.y > 2000 && event.y < 2100) {//Start
                    if (!isRecording) {
                        startTimeMillis = System.currentTimeMillis();
                        startRecording = 1; // Timer starts counting
                        synchronized (ramRecordBuffer) {
                            ramRecordBufferIdx = 0; // Reset index for new data
                        }
                        isRecording = true;
                        isReplaying = false;
                        Log.d("RECORD", "Recording Started");
                    }
                }
                //////////////////// Stop Recording Button (Red Button) ////////////////////////////////////////////////
                else if (event.x > 910 && event.x < 1265 && event.y > 2000 && event.y < 2100) {
                    if (isRecording) {
                        // Freeze the timer at the current duration
                        totalRecordingTime = System.currentTimeMillis() - startTimeMillis;
                        startRecording = 2; // State 2: Display frozen totalRecordingTime
                        isRecording = false;

                        // Replace the "new Thread(() -> { ... }).start();" block with:
                        saveExecutor.execute(() -> {
                            try {
                                // Wait 5 seconds to capture the "future" data
                                Thread.sleep(5000);
                                File path = context.getExternalFilesDir(null);
                                File file = new File(path, fileNameLoop);
                                PrintWriter pw = new PrintWriter(new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file, false)), 65536));
                                synchronized (ramRecordBuffer) {
                                    for (int k = 0; k < ramRecordBufferIdx; k++) {
                                        pw.println(ramRecordBuffer[k]);
                                    }
                                }
                                pw.flush();
                                pw.close();
                                Log.d("SAVE", "Saved " + ramRecordBufferIdx + " samples");
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                        });
                    }
                }
                /////////////////////// Replay Recording (Blue Button) ///////////////////////////////////////////
                else if (event.x > 1310 && event.x < 1665 && event.y > 2000 && event.y < 2100) {
                    if (!isReplaying) {
                        loadReplayDataLoop(context);
                        if (!replayList.isEmpty()) {
                            replayPosition = 0;
                            isReplaying = true;
                            isRecording = false;
                            startRecording = 2; // Show the length of the recording on the timer
                        }
                    } else {
                        isReplaying = false;
                    }
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
                } else if (event.x > 720 && event.x < 1190 && event.y > 2535 && event.y < 2735) {
                    //Event Log Screen
                    // Only create it when the user actually wants to see it
                    if (gameScreenEventLog == null) {
                        gameScreenEventLog = new GameScreenEventLog(game);
                    }
                    // Stop recording or any heavy UI tasks before switching to save memory
                    isRecording = false;
                    game.setScreen(gameScreenEventLog);
                }
                //////////////////// Manual Patient Event /////////////////////
                else if (event.x > 10 && event.x < 675 && event.y > 2450 && event.y < 2800) {
                    // Only trigger if we are recording, haven't hit the 100 limit,
                    // and the button isn't already "held down" (manualPatientEventUpCount)
                    if (manualPatientEventUpCount == 0 && isRecording && eventCount < 100) {

                        // 1. Snapshot the current state IMMEDIATELY on the UI thread
                        final int currentEndIdx = ramRecordBufferIdx;
                        final int currentID = eventCount;
                        // Use the game instance cast to Context to avoid NullPointer
                        final Context threadContext = (Context) game;

                        // 2. Save the Timestamp for the Log Screen array
                        long delta = System.currentTimeMillis() - startTimeMillis;
                        String formattedTime = String.format("%02d:%02d:%03d",
                                (delta / 60000), (delta / 1000) % 60, (delta % 1000));

                        if (timeStamp != null) {
                            timeStamp[currentID] = formattedTime;
                        }

                        // 3. Increment counter immediately
                        eventCount++;
                        manualPatientEventUpCount = 1;

                        // 4. Save to SD Card in background
                        saveExecutor.execute(() -> {
                            try {
                                if (threadContext == null) return;

                                // CALCULATE BOUNDS: 1000Hz * 5 seconds = 5000 samples
                                int startIdx = currentEndIdx - 5000;
                                if (startIdx < 0) startIdx = 0; // Prevent Negative Index Crash

                                File path = threadContext.getExternalFilesDir(null);
                                String eventFileName = "Event_" + currentID + ".csv";
                                File file = new File(path, eventFileName);

                                // Open file with large 64KB buffer for speed
                                PrintWriter pw = new PrintWriter(new BufferedWriter(
                                        new OutputStreamWriter(new FileOutputStream(file, false)), 65536));

                                // Synchronize so Bluetooth thread doesn't conflict
                                synchronized (ramRecordBuffer) {
                                    for (int k = startIdx; k < currentEndIdx; k++) {
                                        if (k >= 0 && k < ramRecordBuffer.length) {
                                            pw.println(ramRecordBuffer[k]);
                                        }
                                    }
                                }
                                pw.flush();
                                pw.close();
                                Log.d("MANUAL_EVENT", "Saved Event_" + currentID);
                            } catch (Exception e) {
                                Log.e("CRASH_PREVENT", "Save failed: " + e.getMessage());
                            }
                        });
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
                    if (event.type == TouchEvent.TOUCH_DOWN) { // ONLY trigger on DOWN, not DRAGGED
                        if (!isRecording) {
                            // --- START ---
                            synchronized (ramRecordBuffer) {
                                // Reset the index pointer to start recording at the beginning of the array
                                ramRecordBufferIdx = 0;
                            }
                            isRecording = true;
                            isReplaying = false;
                            Log.d("RECORD", "Recording Started");
                        } else {
                            // --- STOP ---
                            isRecording = false; // Stop adding to RAM immediately

                            // Launch the Save Thread
                            // Inside GameScreen.java Stop logic
                            new Thread(() -> {
                                try {
                                    File path = context.getExternalFilesDir(null);
                                    File file = new File(path, fileName);
                                    PrintWriter pw = new PrintWriter(new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file, false)), 65536));

                                    synchronized (ramRecordBuffer) {
                                        for (int k = 0; k < ramRecordBufferIdx; k++) {
                                            pw.println(ramRecordBuffer[k]);
                                        }
                                        ramRecordBufferIdx = 0; // Reset for next recording
                                    }
                                    pw.flush();
                                    pw.close();
                                } catch (IOException e) {
                                    e.printStackTrace();
                                }
                            }).start();
                        }
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
    }
    ///////////////////////////////////////////////////////////////////////////////////

    @Override
    public void present ( float deltaTime) {
        Graphics g = game.getGraphics();
        Canvas canvas = ((AndroidGraphics) g).getCanvas();
        g.drawPortraitPixmap(Assets.laryngospasmBackgroundMain, 0, 0);

        // 2. DRAW BLUETOOTH STATUS AT THE TOP
        // White text, centered at top (adjust 850/100 based on your font size)
        g.drawMedText(btStatus, 100, 130);
//        g.drawRect(1245, 2535, 470, 200, 0);       //Bluetooth Connect
        //     g.drawRect(45, 2000, 800, 100, 0);       //Start
        //   g.drawRect(910, 2000, 355, 100, 0);       //Stop

        // g.drawRect(1310, 2000, 355, 100, 0);       //Replay (Blue Button)
        //  g.drawRect(350, 2185, 250, 85, 0);       //Manual RMS Height Above Threshold Text
        //     g.drawText("50", 395, 2235);    //Manual RMS Height Above Threshold Text
        // g.drawRect(350, 2380, 250, 85, 0);       //Auto RMS Height Threshold Text
        //   g.drawText("50", 395, 2445);        //Auto RMS Height Threshold Text
        //    g.drawRect(685, 2110, 155, 105, 0);       //Left Up Button
        //  g.drawRect(685, 2220, 155, 105, 0);       //Left Down Button
        //   g.drawRect(1240, 2180, 250, 85, 0);       //Manual RMS Width Above Threshold Text
        //  g.drawText("50", 1330, 2235);       //Manual RMS Width Above Threshold Text
        //  g.drawRect(1560, 2110, 155, 105, 0);       //Right Up Button
        //  g.drawRect(1560, 2220, 155, 105, 0);       //Right Down Button
        //     g.drawRect(720, 2535, 470, 200, 0);       //Event Log
        //   g.drawRect(25, 2535, 650, 200, 0);       //Manual Patient Event

        //   g.drawRect(725, 2400, 285, 150, 0);       //True Positive
        //  g.drawText("50", 880, 2480);    //True Positive Text
        //   g.drawRect(1055, 2400, 285, 150, 0);       //False Positive
        //  g.drawText("50", 1235, 2480);       //False Positive Text
        //   g.drawRect(1400, 2400, 285, 150, 0);       //False Negative
        //  g.drawText("50", 1560, 2480);       //False Negative Text

        //   g.drawRect(1600, 1330, 100, 270, 0);       //Start/Stop Save a Sample
        //  g.drawRect(1600, 1610, 100, 310, 0);       //Replay



        String eventCountStr = String.valueOf(eventCount);
        g.drawText(eventCountStr, 570, 2660);

        // Inside present() method
        if (startRecording == 0) {
            g.drawText("00:00:000", 245, 2070);
        } else if (startRecording == 1) {
            // Mode 1: Timer is actively counting
            long delta = System.currentTimeMillis() - startTimeMillis;
            String time = String.format("%02d:%02d:%03d", (delta/60000), (delta/1000)%60, (delta%1000));
            g.drawText(time, 245, 2070);
        } else if (startRecording == 2) {
            // Mode 2: Timer is stopped/frozen at totalRecordingTime
            String time = String.format("%02d:%02d:%03d", (totalRecordingTime/60000), (totalRecordingTime/1000)%60, (totalRecordingTime%1000));
            g.drawText(time, 245, 2070);
        }
        /*
        ////////////////// Start / Stop Recording //////////////////////////////////////////
        if (startRecording == 0) {
            recDeltaTimeMillis = 0;
            minutes = 0;
            seconds = 0;
            remainingMilliseconds = 0;
            String formattedTime = String.format("%02d:%02d:%03d", minutes, seconds, remainingMilliseconds);
            g.drawText(formattedTime, 245, 2070);
        } else if (startRecording == 1) {
            currentTimeMillis = System.currentTimeMillis();
            recDeltaTimeMillis = (int) (currentTimeMillis - startTimeMillis);
            minutes = (int) recDeltaTimeMillis / 60000;
            seconds = (int) recDeltaTimeMillis / 1000;
            remainingMilliseconds = (int) recDeltaTimeMillis % 1000;
            String formattedTime = String.format("%02d:%02d:%03d", minutes, seconds, remainingMilliseconds);
            g.drawText(formattedTime, 245, 2070);
        }
        */


        // --- LIVE RMS & PSD (Only shows when NOT replaying) ---
        if (!isReplaying) {
            int latestY = 0;
            if (smoothedRMS.length > 2) {
                xStart = 1600;
                int blueCenterY = 1575;     //Was 1400
                float rmsYScale = 0.5f;

                for (int n = smoothedRMS.length - 1; n > 1; n--) {
                    int y1 = (int) (blueCenterY - smoothedRMS[n] * rmsYScale);
                    int y2 = (int) (blueCenterY - smoothedRMS[n - 1] * rmsYScale);

                    thresholdY = (int) (1050 - (rmsAmpThresh * 2.0f));
                    g.drawRedLine(155, thresholdY, 1590, thresholdY, 0);

                    if (y1 < 869) y1 = 869;
                    if (y1 > 1308) y1 = 1308;
                    if (y2 < 869) y2 = 869;
                    if (y2 > 1308) y2 = 1308;

                    g.drawBlueLine(xStart, y1, xStart - 1, y2, 0);
                    xStart -= 1;
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

        // --- 1. CONSTANTS FOR 2-SECOND WINDOW ---
        final float xRightLimit = 1600.0f;
        final float xLeftLimit = 165.0f;
        final float totalWidth = xRightLimit - xLeftLimit; // 1435 pixels

        // This maps 2000 samples to 1435 pixels (0.7175 pixels per sample)
        final float timeScaledXStep = totalWidth / (signalBufferLen - 1);

        // 2. Constants
        final int xRight = 1600;
        final int xLeft = 165;
        final float centerY = 565.0f;
        final float gMult = 0.15f;
        final float base = 410.0f;
        int bufferIdx = 0;

        // --- 2. PAINT SETUP ---
        signalPaint.setAntiAlias(false); // Sharp edges for square waves
        signalPaint.setStrokeCap(Paint.Cap.BUTT);
        signalPaint.setStrokeWidth(5.0f);


        if (!isReplaying) {
            // --- LIVE BLACK LINE ---
            signalPaint.setColor(android.graphics.Color.BLACK);
            signalPaint.setStrokeWidth(5.0f); // Thinner is better for square waves
            signalPaint.setAntiAlias(false);
            signalPaint.setStrokeCap(Paint.Cap.BUTT);

            synchronized (A2DVal) {
                System.arraycopy(A2DVal, 0, drawingSnapshot, 0, signalBufferLen);
            }

            bufferIdx = 0;
            // Start from most recent (right edge)
            float yLast = centerY - ((float)drawingSnapshot[signalBufferLen - 1] - base) * gMult;

            for (int n = 1; n < signalBufferLen; n++) {
                // FIXED INTEGER STEP: This fixes the Duty Cycle variation
                int x1 = xRight - (n - 1);
                int x2 = xRight - n;

                int dataIdx = (signalBufferLen - 1) - n;
                float yNext = centerY - ((float)drawingSnapshot[dataIdx] - base) * gMult;

                if (yNext < 10) yNext = 10;
                if (yNext > 880) yNext = 880;

                lineBuffer[bufferIdx++] = (float)x1;
                lineBuffer[bufferIdx++] = yLast;
                lineBuffer[bufferIdx++] = (float)x2;
                lineBuffer[bufferIdx++] = yNext;
                yLast = yNext;

                if (x2 <= xLeft || bufferIdx >= lineBuffer.length - 4) break;
            }
            if (bufferIdx > 0) canvas.drawLines(lineBuffer, 0, bufferIdx, signalPaint);


        }
        if (isReplaying && !replayList.isEmpty() && replayRawArray != null) {
            // --- 1. DRAW REPLAY RAW SIGNAL (RED) ---
            signalPaint.setColor(android.graphics.Color.RED);
            signalPaint.setStrokeWidth(5.0f);
            bufferIdx = 0;
            int safePos = Math.min(replayPosition, replayRawArray.length - 1);
            float yLast = 500.0f - (float)((replayRawArray[safePos] - 410.0f) * 0.15f);

            for (int n = 1; n < signalBufferLen; n++) {
                int x1 = 1600 - (n - 1);
                int x2 = 1600 - n;
                int dataIdx = replayPosition - n;
                if (dataIdx >= 0 && dataIdx < replayRawArray.length) {
                    float yNext = 500.0f - (float)((replayRawArray[dataIdx] - 410.0f) * 0.15f);
                    if (yNext < 50) yNext = 50;
                    if (yNext > 950) yNext = 950;
                    lineBuffer[bufferIdx++] = (float)x1;
                    lineBuffer[bufferIdx++] = yLast;
                    lineBuffer[bufferIdx++] = (float)x2;
                    lineBuffer[bufferIdx++] = yNext;
                    yLast = yNext;
                }
                if (x2 <= 165 || bufferIdx >= lineBuffer.length - 4) break;
            }
            if (bufferIdx > 0) canvas.drawLines(lineBuffer, 0, bufferIdx, signalPaint);

            // --- 2. DRAW REPLAY RMS (BLUE LINE) ---
            if (replayRMSArray != null) {
                final int blueCenterY = 1300;
                final float rmsYScale = 0.3f;
                int thresholdY = (int) (1050 - (rmsAmpThresh * 2.0f));
                g.drawRedLine(165, thresholdY, 1600, thresholdY, 0);
                for (int n = 1; n < signalBufferLen; n++) {
                    int x1 = 1600 - (n - 1);
                    int x2 = 1600 - n;
                    int dataIdx = replayPosition - n;
                    if (dataIdx >= 0 && dataIdx < replayRMSArray.length - 1) {
                        int ry1 = (int) (blueCenterY - replayRMSArray[dataIdx + 1] * rmsYScale);
                        int ry2 = (int) (blueCenterY - replayRMSArray[dataIdx] * rmsYScale);
                        if (ry1 < 869) ry1 = 869; if (ry1 > 1308) ry1 = 1308;
                        if (ry2 < 869) ry2 = 869; if (ry2 > 1308) ry2 = 1308;
                        g.drawBlueLine(x1, ry1, x2, ry2, 0);
                    }
                    if (x2 <= 165) break;
                }
            }

            // --- 3. ANIMATED REPLAY PSD (PHASE-SYNCED) ---
            // --- 3. ANIMATED REPLAY PSD (PROPORTIONAL SLIDE) ---
            int psdWin = 1024;
            if (replayRawArray.length >= psdWin) {
                double[] psdBuf = new double[psdWin];

                // 1. Calculate the total "Life" of the animation
                // This is the 5s of data + the 2s it takes to slide off the screen
                float totalAnimationDuration = replayRawArray.length + signalBufferLen;

                // 2. Calculate Progress (0.0 to 1.0)
                float progress = (float) replayPosition / totalAnimationDuration;
                if (progress > 1.0f) progress = 1.0f;

                // 3. Map progress to the data array
                // This forces the PSD window to slide from the very start to the very end
                // perfectly synchronized with the total replay time.
                int maxPossibleStart = replayRawArray.length - psdWin;
                int windowStart = (int) (progress * maxPossibleStart);

                // Final safety bounds
                if (windowStart < 0) windowStart = 0;
                if (windowStart > maxPossibleStart) windowStart = maxPossibleStart;

                // 4. FAST copy and calculate
                System.arraycopy(replayRawArray, windowStart, psdBuf, 0, psdWin);

                PowerSpectralDensityCalculator psdCalc = new PowerSpectralDensityCalculator(psdBuf, 1000);
                double[] currentPsd = psdCalc.calculatePSD(psdBuf, 1000);

                if (currentPsd != null) {
                    float curX = 170;
                    float xStep = 2.0f;
                    for (int i = 1; i < currentPsd.length; i++) {
                        float nextX = 170 + (i * xStep);

                        // Y-MATH: Use 1715 as the offset to ensure it's centered in the middle box
                        float y1 = (float) (currentPsd[i - 1] * -1 + 3600) - 1715;
                        float y2 = (float) (currentPsd[i] * -1 + 3600) - 1715;

                        // CLAMPING: Match the visual box on your background
                        if (y1 < 1480) y1 = 1480;
                        if (y1 > 1895) y1 = 1895;
                        if (y2 < 1480) y2 = 1480;
                        if (y2 > 1895) y2 = 1895;

                        g.drawRedLine((int) curX, (int) y1, (int) nextX, (int) y2, 0);
                        curX = nextX;
                        if (curX >= 1600) break;
                    }
                }
            }

            // --- 4. SPEED & REFRESH ---
            // Move forward 17 samples (1000Hz cadence on 60fps screen)
            replayPosition += 17;

            // Loop reset: wait until the 5s signal + 2s screen width have cleared
            if (replayPosition >= (replayRawArray.length + signalBufferLen)) {
                replayPosition = 0;
            }

            // Re-draw immediately for liquid smooth animation
            if (view != null) {
                view.postInvalidate();
            }
        }


        /*
        else if (isReplaying && !replayList.isEmpty()) {
            // --- REPLAY RED LINE ---
            signalPaint.setColor(android.graphics.Color.RED);
            bufferIdx = 0;

            int safePos = Math.min(replayPosition, replayList.size() - 1);
            float yLastRep = centerY - (float)((replayList.get(safePos) - base) * gMult);

            for (int n = 1; n < signalBufferLen; n++) {
                int x1 = xRight - (n - 1);
                int x2 = xRight - n;
                int posIdx = replayPosition - n;

                if (posIdx >= 0 && posIdx < replayList.size()) {
                    float yNextRep = centerY - (float)((replayList.get(posIdx) - base) * gMult);
                    lineBuffer[bufferIdx++] = (float)x1;
                    lineBuffer[bufferIdx++] = yLastRep;
                    lineBuffer[bufferIdx++] = (float)x2;
                    lineBuffer[bufferIdx++] = yNextRep;
                    yLastRep = yNextRep;
                }
                if (x2 <= xLeft || bufferIdx >= lineBuffer.length - 4) break;
            }
            if (bufferIdx > 0) canvas.drawLines(lineBuffer, 0, bufferIdx, signalPaint);

            replayPosition += 17;
            if (replayPosition >= replayList.size() + signalBufferLen) replayPosition = 0;
            if (GameScreen.view != null) GameScreen.view.postInvalidate();
        }
        *
         */
    }

    /////////////////////////// Replay Helper Method ////////////////////////////////////

    // Call this from GameScreenEventLog when a button is pressed
    // Replace/Update your loadSpecificEvent method:
    public void loadSpecificEvent(int id, Context context) {
        replayList.clear();
        replayPosition = 0;
        try {
            File path = context.getExternalFilesDir(null);
            File file = new File(path, "Event_" + id + ".csv");
            if (!file.exists()) return;

            BufferedReader br = new BufferedReader(new FileReader(file));
            String line;
            while ((line = br.readLine()) != null) {
                if (!line.trim().isEmpty()) {
                    replayList.add(Double.parseDouble(line));
                }
            }
            br.close();

            if (!replayList.isEmpty()) {
                // FIX: Convert to primitive array ONCE here
                replayRawArray = new double[replayList.size()];
                for (int i = 0; i < replayList.size(); i++) {
                    replayRawArray[i] = replayList.get(i);
                }

                // Pre-calculate RMS
                replayRMSArray = RMSCalculator.calculateMovingRMS(replayRawArray, 10);
                if (replayRMSArray != null) {
                    replayRMSArray = MovingAverageCalculator.calculateMovingAverage(replayRMSArray, 20);
                }

                isReplaying = true;
                isRecording = false;
                startRecording = 2;
                if (view != null) view.postInvalidate();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    /////////////// LoadReplayData Helper Method ///////////////////////////////////////////////////
    private void loadReplayDataLoop(Context context) {
        replayList.clear();
        replayPosition = 0;
        try {
            File path = context.getExternalFilesDir(null);
            File file = new File(path, fileNameLoop);
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
    public void pause () {
        // This stops the ConnectedThread from trying to redraw a screen that isn't visible
        view = null;
    }

    @Override
    public void resume () {
        // When we come back to the live screen, re-enable drawing
        // 'game' is your AndroidGame instance which holds the SurfaceView
        //  view = ((AndroidGame)game).getSurfaceView();
        view = ((AndroidGame)game).renderView;
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