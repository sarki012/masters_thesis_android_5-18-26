package com.esark.gasp;

import static com.esark.framework.AndroidGame.signalBufferLen;
import static com.esark.gasp.ConnectedThread.*;

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
    public static int signalBufferLen = 1449;   //was 1436
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
    public static int startRecording = 0;
    public static long startTimeMillis = 0;
    public static long totalRecordingTime = 0;
    long recDeltaTimeMillis = 0;
    long currentTimeMillis = 0;
    long minutes = 0;
    long seconds = 0;
    long remainingMilliseconds = 0;
    int rmsThresholdTouch = 0;
    int rmsAreaThreshTouch = 0;
    int leftUpCount = 0, leftDownCount = 0, rightUpCount = 0, rightDownCount = 0;
    private static final double PI = 3.1415927;

    public static final int PSDYVAL = 3850;
    private static final int INVALID_POINTER_ID = -1;
    // The ‘active pointer’ is the one currently moving our object.
    private int mActivePointerId = INVALID_POINTER_ID;
    // public static int len = 0;
    private int len = 0;
    public static String[] timeStamp = new String[150];
    // 0 = True Positive (Default), 1 = False Positive, 2 = False Negative
    public static int[] eventClassification = new int[150];
    public static double[] eventData = new double[100];
    public static int eventCount = 0;
    public static volatile double[] psdResult = new double[signalBufferLen];
    public static double[][] PSDArray = new double[100][signalBufferLen];
    public static double[] lastEventPSDArray = new double[signalBufferLen];
    public static double[][] eventArray = new double [100][signalBufferLen];
    public static double[] lastEventArray = new double[signalBufferLen];
    public static int manualPatientEventUpCount = 0;
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
    public static int[] eventBufferPointers = new int[100]; // Stores the index of each event
    public static int selectedEventPointer = -1;           // Which event we are currently replaying
    // Inside GameScreen.java
    public static GameScreen liveScreen;
    private final ExecutorService saveExecutor = Executors.newSingleThreadExecutor();
    public static String btStatus = "Bluetooth: Disconnected";
    public static double[] replayRMSArray;
    public static double[] replayPSDArray;
    public static double[] replayRawArray; // Add this
    private double lastCalculatedArea = 0; // Stores the area of the most recent burst
    private double stableAreaValue = 0; // Persistent storage for the area
    public static double batVoltage = 0;
    public static double batSOC = 0;
    boolean alertTriggeredThisFrame = false;
    private double lastCompleteAreaMvS = 0;
    private int lastCompleteBurstStart = -1;
    private int lastCompleteBurstEnd = -1;
    // Set these at the class level or in the constructor
    public static float rmsAmpThresh = 400.0f;
    public static float rmsAreaThresh = 100.0f;
    // Add these to your class member variables at the top
    private List<Float> thresholdRollingHistory = new ArrayList<>();
    private List<double[]> burstShapeHistory = new ArrayList<>();
    private int falsePositive = 0, falsePositiveTouch = 0, falsePositiveDownCount = 0;
    private int falseNegative = 0, falseNegativeTouch = 0, falseNegativeDownCount = 0;
    private int truePositive = 0, truePositiveTouch = 0, truePositiveDownCount = 0;
    public static int selectedEventId = 0;
    public static float[] eventAmpThresholds = new float[120]; // Stores the threshold for each event
    private double[] replayMaxPsd = null; // Stores the "screenshot"
    public static float psdInternalScalar = 1.0f;  // The "Volume Knob" for PSD data
    // This array holds the 0-500Hz data for WHATEVER is currently active
    public static double[] activePsdBuffer = new double[512];
    // Constructor
    public GameScreen(Game game) {
        super(game);
        liveScreen = this; // Store this instance
        // Cast the 'game' object to Context.
        // This works because AndroidGame extends Activity, which is a Context.
        this.context = (Context) game;

        // AUTO-RESUME LOGIC:
        // If startTimeMillis is not 0, it means a session is already active.
        if (startTimeMillis != 0) {
            isRecording = true;   // Resume the data flow
            isReplaying = false;   // Ensure we aren't stuck in replay mode
            startRecording = 1;    // Set timer state to running
        }
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
                        // Only set the startTime if it's the very first time starting
                        if (startTimeMillis == 0) {
                            startTimeMillis = System.currentTimeMillis();
                            synchronized (ramRecordBuffer) {
                                ramRecordBufferIdx = 0; // Reset index for new data
                            }
                        }
                        startRecording = 1; // Timer starts counting

                        isRecording = true;
                        isReplaying = false;
                        Log.d("RECORD", "Recording Started");
                        stableAreaValue = 0;
                        thresholdRollingHistory.clear(); // Clear the history for a new session
                        rmsAmpThresh = 400.0f;           // Reset to default
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
                    rmsAreaThreshTouch = 1;
                    if (rightUpCount == 0) {       //Flag so we only increment the delay by 5 once per touch
                        rmsAreaThresh += 5;
                        rightUpCount = 1;
                    }
                }
                //////////////////// Right Down Button ////////////////////////////////////////////////
                else if (event.x > 1560 && event.x < 1715 && event.y > 2220 && event.y < 2325) {
                    //RMS threshold amplitude to trigger event. Left Down Button.
                    rmsAreaThreshTouch = 1;
                    if (rightDownCount == 0) {       //Flag so we only increment the delay by 5 once per touch
                        rmsAreaThresh -= 5;
                        rightDownCount = 1;
                    }
                }
                /////////////////// False Positive (Blue) //////////////////////////////////////////
                else if (event.x > 715 && event.x < 1015 && event.y > 2330 && event.y < 2490) {
                    falsePositiveTouch = 1;
                    if (falsePositiveDownCount == 0 && eventCount < 120) {
                        falsePositiveDownCount = 1;

                        // 1. Mark classification as 1 (Blue)
                        eventClassification[eventCount] = 1;

                        // 2. Capture the Timestamp and Save Data
                        captureAndSaveEvent();
                        falsePositive++;
                        // 3. Move to next slot
                        eventCount++;
                    }
                }
                /////////////////// False Negative //////////////////////////////////////////
                else if (event.x > 1040 && event.x < 1365 && event.y > 2330 && event.y < 2490) {
                    falseNegativeTouch = 1;
                    if (falseNegativeDownCount == 0 && eventCount < 120) {
                        falseNegativeDownCount = 1;

                        // 1. Mark classification as 2 (Purple)
                        eventClassification[eventCount] = 2;

                        // 2. Capture the Timestamp and Save Data
                        captureAndSaveEvent();
                        falseNegative++;
                        // 3. Move to next slot
                        eventCount++;
                    }
                }
                /////////////////// True Positive //////////////////////////////////////////
                else if (event.x > 1390 && event.x < 1700 && event.y > 2330 && event.y < 2490) {
                    truePositiveTouch = 1;
                    if (truePositiveDownCount == 0 && eventCount < 100) {
                        truePositiveDownCount = 1;

                        // 1. Mark classification as 0 (Green/Jpeg)
                        eventClassification[eventCount] = 0;

                        // 2. Capture the Timestamp and Save Data
                        captureAndSaveEvent();

                        // 3. Move to next slot
                        eventCount++;
                        truePositive++;
                    }
                }

                //////////////////// Manual Patient Event (2 Second Window) /////////////////////
                else if (event.x > 10 && event.x < 675 && event.y > 2450 && event.y < 2800) {
                    if (manualPatientEventUpCount == 0 && isRecording && eventCount < 150) {
                        if (manualPatientEventUpCount == 0) {
                            manualPatientEventUpCount = 1;

                            // 1. Force the UI to show the "Touched" state
                            rmsThresholdTouch = 1;

                            rmsThresholdTouch = 1; // Show values on UI

                            // 1. Identify and extract the shape of the most recent burst
                            int n = smoothedRMS.length - 1;
                            // Move back to find the tail of the hill (using a low floor of 10 to capture the whole shape)
                            while (n >= 0 && smoothedRMS[n] <= 10.0) n--;
                            int hillEnd = n;
                            while (n >= 0 && smoothedRMS[n] > 10.0) n--;
                            int hillStart = n + 1;

                            if (hillEnd > hillStart) {
                                // Capture the raw RMS values for this specific hill
                                double[] burstShape = new double[hillEnd - hillStart + 1];
                                System.arraycopy(smoothedRMS, hillStart, burstShape, 0, burstShape.length);

                                burstShapeHistory.add(burstShape);
                                if (burstShapeHistory.size() > 5) {
                                    burstShapeHistory.remove(0); // Maintain rolling 5
                                }

                                // 2. GLOBAL SEARCH: Find the threshold height where the
                                // AVERAGE area of all 5 bursts equals rmsAreaThresh
                                float searchThresh = 600.0f;
                                float bestFitThresh = 10.0f;

                                // Iterate downwards to find the highest threshold that satisfies the area
                                while (searchThresh > 10.0f) {
                                    double totalAreaOfAllBursts = 0;

                                    for (double[] shape : burstShapeHistory) {
                                        double singleBurstSum = 0;
                                        for (double val : shape) {
                                            if (val > searchThresh) {
                                                singleBurstSum += (val * 3.22); // Convert to uV
                                            }
                                        }
                                        totalAreaOfAllBursts += (singleBurstSum * 0.001); // Convert to mV*S
                                    }

                                    double averageArea = totalAreaOfAllBursts / burstShapeHistory.size();

                                    if (averageArea >= rmsAreaThresh) {
                                        bestFitThresh = searchThresh;
                                        break; // Found the highest threshold that yields the target average area
                                    }
                                    searchThresh -= 2.0f; // Step down by 2 for high precision
                                }
                                rmsAmpThresh = bestFitThresh;
                            }

                            // --- Snapshot and Save Logic (Keep your existing save code here) ---
                            final int currentEndIdx = ramRecordBufferIdx;
                            final int currentID = eventCount;
                            final Context threadContext = (Context) game;

                            long delta = System.currentTimeMillis() - startTimeMillis;
                            timeStamp[eventCount] = String.format("%02d:%02d:%03d",
                                    (delta / 60000), (delta / 1000) % 60, (delta % 1000));

                            saveExecutor.execute(() -> {
                                try {
                                    if (threadContext == null) return;
                                    int startIdx = currentEndIdx - 2000;
                                    if (startIdx < 0) startIdx = 0;
                                    File path = threadContext.getExternalFilesDir(null);
                                    File file = new File(path, "Event_" + currentID + ".csv");
                                    PrintWriter pw = new PrintWriter(new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file, false)), 65536));
                                    synchronized (ramRecordBuffer) {
                                        for (int k = startIdx; k < currentEndIdx; k++) {
                                            if (k >= 0 && k < ramRecordBuffer.length) pw.println(ramRecordBuffer[k]);
                                        }
                                    }
                                    pw.flush();
                                    pw.close();
                                } catch (Exception e) {
                                    Log.e("SAVE_ERROR", "Failed to save: " + e.getMessage());
                                }
                            });
                            eventCount++;
                            truePositive++; // Increment the counter shared with True Positive
                            manualPatientEventUpCount = 1;
                        }
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

                ////////////////// Home /////////////////////////////////////////////////////
                else if (event.x > 25 && event.x < 675 && event.y > 2350 && event.y < 2530)
                {
                    // 1. Disable replay mode to return to live waveforms
                    isReplaying = false;

                    // If the timer was ever started, make sure recording is active
                    if (startTimeMillis != 0) {
                        isRecording = true;
                    }

                    // 2. Reset replay-specific variables to free up memory
                    replayPosition = 0;
                    if (replayList != null) {
                        replayList.clear();
                    }

                    // 3. Optional: Reset the timer to 0 so the user can start a new recording
                    // Change startRecording to 0 to show "00:00:000"
                    startRecording = 0;
                    totalRecordingTime = 0;

                    Log.d("HOME", "Returned to Live View");
                }

                if (rmsAmpThresh < 0) {
                    rmsAmpThresh = 0;
                }
                // 3. BACK TO BLUETOOTH (Move this to the VERY BOTTOM of the button checks)
                else if (event.x > 1245 && event.x < 1715 && event.y > 2535 && event.y < 2735) {
                    Intent intent2 = new Intent(context.getApplicationContext(), GaspSemg.class);
                    context.startActivity(intent2);
                    return;
                }
                /////////////////////////////////////////////////////////////////////////
            } // This brace closes the if (TOUCH_DOWN || TOUCH_DRAGGED) block

            else if (event.type == TouchEvent.TOUCH_UP) {
                // Reset flags on any lift to ensure buttons remain responsive
                leftUpCount = 0;
                leftDownCount = 0;
                rightUpCount = 0;
                rightDownCount = 0;
                manualPatientEventUpCount = 0;
                falsePositiveDownCount = 0;
                falseNegativeDownCount = 0;
                truePositiveDownCount = 0;

                // else if (event.x > 720 && event.x < 1190 && event.y > 2535 && event.y < 2735) {
                // 1. EVENT LOG BUTTON (New Logic)
                // Assuming your Event Log button is the one you want to navigate to the log
                if (event.x > 840 && event.x < 1220 && event.y > 2535 && event.y < 2735) {
                    //Event Log Screen
                    // Only create it when the user actually wants to see it
                    if (gameScreenEventLog == null) {
                        gameScreenEventLog = new GameScreenEventLog(game);
                    }
                    // Stop recording or any heavy UI tasks before switching to save memory
                    isRecording = false;
                    game.setScreen(gameScreenEventLog);
                    return;
                }
            }
        } // This brace closes the for-loop

        //   if(landscape == 0) {
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
        // g.drawRect(45, 2000, 800, 100, 0);       //Start
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
        //  g.drawText("Home", 100, 2450);
        // g.drawRect(25, 2350, 650, 180, 0);       //Home

        //   g.drawRect(725, 2400, 285, 150, 0);       //True Positive
        //  g.drawText("50", 880, 2480);    //True Positive Text
        //   g.drawRect(1055, 2400, 285, 150, 0);       //False Positive
        //  g.drawText("50", 1235, 2480);       //False Positive Text
        //   g.drawRect(1400, 2400, 285, 150, 0);       //False Negative
        //  g.drawText("50", 1560, 2480);       //False Negative Text

        //   g.drawRect(1600, 1330, 100, 270, 0);       //Start/Stop Save a Sample
        //  g.drawRect(1600, 1610, 100, 310, 0);       //Replay
        // g.drawRect(715, 2330, 300, 160, 0);     //False Positive
        // g.drawRect(1040, 2330, 325, 160, 0);     //False Negative
        // g.drawRect(1390, 2330, 310, 160, 0);     //True Positive

        //////////////////// Battery Voltage and SOC //////////////////////////////////
        // Inside GameScreen.java -> present()// Draw Battery Voltage
        String vText = String.format("%.2f V", batVoltage);
        g.drawSmallText(vText, 1538, 111);

        // Draw Battery SOC (State of Charge)
        String sText = String.format("%.2f %%", batSOC);
        g.drawSmallText(sText, 1538, 165); // Placed 50 pixels below Voltage
        //////////////////////////////////////////////////////////////////////////////

        //////////////////// RMS Threshold to Trigger Event //////////////////////////////////
        if (rmsThresholdTouch == 0) {
            g.drawText("400.0", 400, 2235);    //Manual RMS Height Above Threshold Text
        } else if (rmsThresholdTouch == 1) {
            String rmsAmpThreshStr = String.valueOf(rmsAmpThresh);
            g.drawText(rmsAmpThreshStr, 400, 2235);    //Manual RMS Height Above Threshold Text
        }

        //////////////////////////////////////////////////////////////////////////////////////

        //////////////////// Manual RMS Area Above Threshold to Trigger Event //////////////////////
        if (rmsAreaThreshTouch == 0) {
            g.drawText("100.0", 1215, 2235);    //Manual RMS Width Above Threshold Text
        } else if (rmsAreaThreshTouch == 1) {
            String rmsAreaThreshStr = String.valueOf(rmsAreaThresh);
            g.drawText(rmsAreaThreshStr, 1215, 2235);    //Manual RMS Width Above Threshold Text
        }
        //////////////////// False Positive Button ///////////////////////////////////////////////////////////
        if (falsePositiveTouch == 0) {
            g.drawText("0", 895, 2415);    //Manual RMS Width Above Threshold Text
        } else if (falsePositiveTouch == 1) {
            String falsePositiveStr = String.valueOf(falsePositive);
            g.drawText(falsePositiveStr, 895, 2415);    //Manual RMS Width Above Threshold Text
        }
        //////////////////// False Negative Button ///////////////////////////////////////////////////////////
        if (falseNegativeTouch == 0) {
            g.drawText("0", 1240, 2415);    //Manual RMS Width Above Threshold Text
        } else if (falseNegativeTouch == 1) {
            String falseNegativeStr = String.valueOf(falseNegative);
            g.drawText(falseNegativeStr, 1240, 2415);    //Manual RMS Width Above Threshold Text
        }
        //////////////////// True Positive Button ///////////////////////////////////////////////////////////
        if (truePositiveTouch == 0) {
            g.drawText("0", 1565, 2415);    //Manual RMS Width Above Threshold Text
        } else if (truePositiveTouch == 1) {
            String truePositiveStr = String.valueOf(truePositive);
            g.drawText(truePositiveStr, 1565, 2415);    //Manual RMS Width Above Threshold Text
        }

        String patientEventStr = String.valueOf(truePositive);
        g.drawText(patientEventStr, 570, 2660);

        // --- STOPWATCH DRAWING (Fixed for persistence) ---
        String timeStr = "00:00:000";

        if (isReplaying) {
            // 1. REPLAY MODE: Show the timestamp of the specific event being viewed
            if (selectedEventId >= 0 && timeStamp[selectedEventId] != null) {
                timeStr = timeStamp[selectedEventId];
            }
        } else if (startTimeMillis != 0) {
            // 2. LIVE MODE: Show elapsed time if we have a valid start time
            long displayTime;
            if (isRecording) {
                displayTime = System.currentTimeMillis() - startTimeMillis;
            } else {
                // If we stopped, show the frozen time.
                // If we just returned home, show the current elapsed time.
                displayTime = (totalRecordingTime > 0) ? totalRecordingTime : (System.currentTimeMillis() - startTimeMillis);
            }

            timeStr = String.format("%02d:%02d:%03d",
                    (displayTime / 60000),
                    (displayTime / 1000) % 60,
                    (displayTime % 1000));
        }

        g.drawText(timeStr, 245, 2070);

// --- LIVE RMS & PSD (Only shows when NOT replaying) ---
        if (!isReplaying) {
            int blueCenterY = 1550;
            float rmsYScale = 1.5f;
            int xRightLimit = 1574;
            int xLeftLimit = 130;
            float totalPixelWidth = (float) (xRightLimit - xLeftLimit);
            float stretchFactor = totalPixelWidth / (float) (smoothedRMS.length - 1);


            // If the data acquisition thread stopped, jCount will stop increasing
            // Let's force a refresh or check if we are getting data
            //    int currentCount = ConnectedThread.jCount.get();
            // If btStatus is "BT: Connected" but waves aren't moving,
            // it means rxThread is dead.

            // --- 1. CALCULATE LIVE AREA FIRST (To determine color) ---
            // --- 1. PRE-PASS: IDENTIFY ALL SPASM "ISLANDS" IN THE BUFFER ---
            double areaFactor = 0.00322;
            boolean[] spasmMap = new boolean[smoothedRMS.length];
            alertTriggeredThisFrame = false;

            int iScan = smoothedRMS.length - 1;
            while (iScan >= 0) {
                // Look for the start of an island (working right to left)
                if (smoothedRMS[iScan] > rmsAmpThresh) {
                    int islandEnd = iScan;
                    double islandSum = 0;
                    int islandWidth = 0;

                    // Measure this specific island
                    while (iScan >= 0) {
                        if (smoothedRMS[iScan] > rmsAmpThresh) {
                            islandSum += (smoothedRMS[iScan] * areaFactor);
                            islandWidth++;
                            iScan--;
                        } else {
                            // 5ms Hysteresis to bridge tiny noise-dips
                            boolean flicker = false;
                            for (int h = 1; h <= 5; h++) {
                                if (iScan - h >= 0 && smoothedRMS[iScan - h] > rmsAmpThresh) {
                                    flicker = true;
                                    iScan -= h;
                                    break;
                                }
                            }
                            if (!flicker) break; // Real end of island found
                        }
                    }
                    int islandStart = iScan + 1;
                    double islandArea = islandSum;

                    // If this island qualifies as a spasm, mark it in the map
                    if (islandArea >= rmsAreaThresh && islandWidth > 0) {
                        for (int k = Math.max(0, islandStart); k <= islandEnd; k++) {
                            spasmMap[k] = true;
                        }
                        // Trigger alert only if this island is currently touching the right edge
                        if (islandEnd >= smoothedRMS.length - 10) {
                            alertTriggeredThisFrame = true;
                        }
                    }
                } else {
                    iScan--;
                }
            }

            if (smoothedRMS.length > 2) {
                thresholdY = (int) (blueCenterY - (rmsAmpThresh * rmsYScale));
                g.drawGreenLine(xLeftLimit, thresholdY, xRightLimit, thresholdY, 0);

                final int CEILING = 835;
                final int FLOOR = 1300;
                final int STROKE_OFFSET = 4;

                // --- 2. PASS 1: SHADED FILL (Using the Spasm Map for Persistence) ---
                for (int n = 0; n < smoothedRMS.length; n++) {
                    int xCurrent = (int) (xRightLimit - (n * stretchFactor));
                    if (xCurrent < xLeftLimit) break;

                    int dataIdx = (smoothedRMS.length - 1) - n;
                    if (dataIdx < 0) break;

                    int yVal = (int) (blueCenterY - smoothedRMS[dataIdx] * rmsYScale);
                    if (yVal < CEILING) yVal = CEILING;
                    if (yVal > FLOOR) yVal = FLOOR;

                    if (yVal < thresholdY) {
                        // If this index was marked as part of a spasm island, draw GREEN
                        if (spasmMap[dataIdx]) {
                            g.drawGreenLine(xCurrent, yVal + STROKE_OFFSET, xCurrent, thresholdY, 0);
                        } else {
                            // Otherwise draw YELLOW
                            g.drawYellowLine(xCurrent, yVal + STROKE_OFFSET, xCurrent, thresholdY, 0);
                        }
                    }
                }

                // --- 3. PASS 2: BLUE RMS LINE ---
                for (int n = 0; n < smoothedRMS.length - 1; n++) {
                    int x1 = (int) (xRightLimit - (n * stretchFactor));
                    int x2 = (int) (xRightLimit - ((n + 1) * stretchFactor));
                    if (x2 < xLeftLimit) x2 = xLeftLimit;

                    int dataIdx1 = (smoothedRMS.length - 1) - n;
                    int dataIdx2 = (smoothedRMS.length - 1) - (n + 1);

                    int ry1 = (int) (blueCenterY - smoothedRMS[dataIdx1] * rmsYScale);
                    int ry2 = (int) (blueCenterY - smoothedRMS[dataIdx2] * rmsYScale);

                    if (ry1 < CEILING) ry1 = CEILING;
                    if (ry1 > FLOOR) ry1 = FLOOR;
                    if (ry2 < CEILING) ry2 = CEILING;
                    if (ry2 > FLOOR) ry2 = FLOOR;

                    g.drawBlueLine(x1, ry1, x2, ry2, 0);
                    if (x2 <= xLeftLimit) break;
                }


                // --- (Rest of Area Latch and PSD logic remains the same) ---
                // --- 1. LIVE ALERT CHECK (Synchronized uV*mS) ---
                // --- 1. LIVE ALERT CHECK (Units: mV*s) ---
// Math: ADC_Count * 3.22 (mV/count) * 0.001 (seconds per sample) = 0.00322
                alertTriggeredThisFrame = false;
                double liveAreaMvS = 0;
                int liveWidth = 0;
                int liveIdx = smoothedRMS.length - 1;

                while (liveIdx >= 0) {
                    if (smoothedRMS[liveIdx] > rmsAmpThresh) {
                        // Accumulate area directly in mV*s
                        liveAreaMvS += (smoothedRMS[liveIdx] * areaFactor);
                        liveWidth++;
                        liveIdx--;
                    } else {
                        // HYSTERESIS: Look back 5ms to bridge noise gaps
                        boolean noiseFlicker = false;
                        for (int h = 1; h <= 5; h++) {
                            if (liveIdx - h >= 0 && smoothedRMS[liveIdx - h] > rmsAmpThresh) {
                                noiseFlicker = true;
                                liveIdx -= h;
                                break;
                            }
                        }
                        if (!noiseFlicker) break;
                    }
                }

// TRIGGER ALERT: Use the same mV*s units
// Note: You may need to adjust your UI slider to smaller numbers (e.g., 0.50 to 5.00)
                if (liveAreaMvS >= rmsAreaThresh && liveWidth > 0) {
                    alertTriggeredThisFrame = true;
                }

// --- 2. STABLE DISPLAY LOGIC (Units: mV*s) ---
                int n = smoothedRMS.length - 1;
                while (n >= 0 && smoothedRMS[n] > rmsAmpThresh) {
                    n--;
                }
                while (n >= 0 && smoothedRMS[n] <= rmsAmpThresh) {
                    n--;
                }

                if (n >= 0) {
                    double islandSumMvS = 0;
                    int islandWidth = 0;
                    while (n >= 0) {
                        if (smoothedRMS[n] > rmsAmpThresh) {
                            islandSumMvS += (smoothedRMS[n] * areaFactor);
                            islandWidth++;
                            n--;
                        } else {
                            // Hysteresis for the stable island
                            boolean flicker = false;
                            for (int h = 1; h <= 5; h++) {
                                if (n - h >= 0 && smoothedRMS[n - h] > rmsAmpThresh) {
                                    flicker = true;
                                    n -= h;
                                    break;
                                }
                            }
                            if (!flicker) break;
                        }
                    }
                    if (islandWidth > 0) {
                        stableAreaValue = islandSumMvS;
                    }
                }

                // Draw the result using the persistent variable
                String areaText1 = "Area of Incoming Green";
                String areaText2 = String.format("Shaded Region: %.1f", stableAreaValue);
                String areaText3 = "uV*mS";

                g.drawSmallText(areaText1, 1280, 760);
                g.drawSmallText(areaText2, 1280, 805);
                g.drawSmallText(areaText3, 1630, 805);

            }
        }
        // --- PSD SWITCHBOARD ---
        if (isReplaying) {
            if (replayMaxPsd != null) {
                // Copy the "Screenshot" into the active buffer
                System.arraycopy(replayMaxPsd, 0, activePsdBuffer, 0, 512);
            }
        } else {
            if (psdResult != null) {
                // Copy the Live Result into the active buffer
                // Live uses the real-time buffer from ConnectedThread
                System.arraycopy(psdResult, 0, activePsdBuffer, 0, Math.min(psdResult.length, 512));
            }
        }
        // --- UNIFIED PSD DRAWING (Restoring the missing pixels) ---        // This block draws whatever data is in activePsdBuffer (Live or Replay)
        float psdGlobalGain = 10.0f;    // Set to 5.0f for the 5x higher amplitude you requested
        float yPsdOffset = 1695.0f;    // Sets the baseline floor to 1905
        float xPsdStart = 140;
        float xPsdEnd = 1582;
        float drawBase = 3600f;

        int hLen = 512;
        float xStep = (xPsdEnd - xPsdStart) / (float) hLen;
        float curX = xPsdStart;

        for (int i = 1; i < hLen; i++) {
            float nextX = xPsdStart + (i * xStep);

            // Connected Line Math (Waveform look)
            float y1 = (float) (activePsdBuffer[i - 1] * -psdGlobalGain + drawBase) - yPsdOffset;
            float y2 = (float) (activePsdBuffer[i] * -psdGlobalGain + drawBase) - yPsdOffset;

            // Unified Clamping to the PSD Box boundaries
            if (y1 < 1445) y1 = 1445;
            if (y1 > 1905) y1 = 1905;
            if (y2 < 1445) y2 = 1445;
            if (y2 > 1905) y2 = 1905;

            // Draw the waveform segments
            g.drawRedLine((int) curX, (int) y1, (int) nextX, (int) y2, 0);

            curX = nextX;
            if (curX >= xPsdEnd) break;
        }

        // --- AREA-BASED ALERT LOGIC ---
        // Convert stableAreaValue to uV*S for the comparison (multiply by 1000)
        // if your rmsAreaThresh is set in uV*S units.
        // double areaInUvS = stableAreaValue * 1000.0;
        double areaInUvS = stableAreaValue;

        // Trigger alert if the most recent completed burst exceeds the Area Threshold
        // --- REWRITTEN ALERT LOGIC ---
        if (alertTriggeredThisFrame) {
            if (!isAlertPlaying && alertSound != null) {
                // Trigger INSTANTLY when threshold is crossed, even if burst isn't finished
                alertSound.play(5.0f);
                isAlertPlaying = true;
            }
        } else {
            isAlertPlaying = false;
        }


        // --- RAW SIGNAL CONSTANTS ---
        final int xRight = 1574;
        final int xLeft = 140;
        final float centerY = 565.0f;
        final float gMult = 0.15f;
        final float base = 410.0f;
        int bufferIdx = 0;

        if (!isReplaying) {
            // --- LIVE BLACK SIGNAL (Stretched) ---
            signalPaint.setColor(android.graphics.Color.BLACK);
            signalPaint.setStrokeWidth(2.5f);
            synchronized (A2DVal) {
                System.arraycopy(A2DVal, 0, drawingSnapshot, 0, signalBufferLen);
            }

            float stretchFactorBlack = 1452.0f / (float) (signalBufferLen - 1);
            float yLast = centerY - ((float) drawingSnapshot[signalBufferLen - 1] - base) * gMult;

            bufferIdx = 0;
            for (int n = 1; n < signalBufferLen; n++) {
                float x1 = 1574 - ((n - 1) * stretchFactorBlack);
                float x2 = 1574 - (n * stretchFactorBlack);

                float yNext = centerY - ((float) drawingSnapshot[(signalBufferLen - 1) - n] - base) * gMult;

                if (yNext < 222) yNext = 222;
                if (yNext > 680) yNext = 680;

                lineBuffer[bufferIdx++] = x1;
                lineBuffer[bufferIdx++] = yLast;
                lineBuffer[bufferIdx++] = x2;
                lineBuffer[bufferIdx++] = yNext;
                yLast = yNext;

                if (x2 <= 122 || bufferIdx >= lineBuffer.length - 4) break;
            }
            if (bufferIdx > 0) canvas.drawLines(lineBuffer, 0, bufferIdx, signalPaint);
        }

        if (isReplaying && !replayList.isEmpty() && replayRawArray != null) {
            // --- 1. REPLAY RAW SIGNAL (RED) ---
            signalPaint.setColor(android.graphics.Color.RED);
            signalPaint.setStrokeWidth(2.5f);    // Match the Live signal thickness
            bufferIdx = 0;

// Constants matched to your Live Black Signal logic
            final float centerYRep = 565.0f;
            final float gMultRep = 0.15f;
            final float baseRep = 410.0f;

// Determine the starting Y point based on the current playback head
            int startPos = Math.min(replayPosition, replayRawArray.length - 1);
            float yLastRep = centerYRep - ((float) replayRawArray[startPos] - baseRep) * gMultRep;

// Draw up to 1444 pixels (the width of the box)
            for (int n = 1; n < 1444; n++) {
                float x1 = 1574 - (n - 1);
                float x2 = 1574 - n;
                int dataIdx = replayPosition - n;

                if (dataIdx >= 0 && dataIdx < replayRawArray.length) {
                    float yNext = centerYRep - ((float) replayRawArray[dataIdx] - baseRep) * gMultRep;

                    // Clamping to stay inside the Raw Signal box (Ceiling 222, Floor 680)
                    if (yNext < 222) yNext = 222;
                    if (yNext > 680) yNext = 680;

                    // Load coordinates into the high-performance line buffer
                    lineBuffer[bufferIdx++] = x1;
                    lineBuffer[bufferIdx++] = yLastRep;
                    lineBuffer[bufferIdx++] = x2;
                    lineBuffer[bufferIdx++] = yNext;
                    yLastRep = yNext;
                }

                // Stop if we hit the left limit or the line buffer is full
                if (x2 <= 140 || bufferIdx >= lineBuffer.length - 4) break;
            }

// Draw all segments at once for maximum performance
            if (bufferIdx > 0) {
                canvas.drawLines(lineBuffer, 0, bufferIdx, signalPaint);
            }

            // --- 2. REPLAY RMS (BLUE & FILLS) ---
            // --- 2. REPLAY RMS (BLUE & FILLS) ---
            if (replayRMSArray != null) {
                final int blueCenterY = 1550;
                final float rmsYScale = 1.5f;

                // Retrieve the threshold specific to this event
                float savedThresh = eventAmpThresholds[selectedEventId];
                if (savedThresh == 0) savedThresh = rmsAmpThresh;

                // Calculate vertical position of the green threshold line
                int thresholdYRep = (int) (blueCenterY - (savedThresh * rmsYScale));

                // --- ADJUSTED CONSTANTS ---
                final int CEILING = 835;
                // Updated FLOOR to 1296 to cutoff the signal lower on the screen
                final int FLOOR = 1296;
                final int STROKE_OFFSET = 4;

                // Ensure the threshold line itself isn't clamped off-screen
                if (thresholdYRep > FLOOR) thresholdYRep = FLOOR;
                if (thresholdYRep < CEILING) thresholdYRep = CEILING;

                // Draw Threshold Line
                g.drawGreenLine(130, thresholdYRep, 1574, thresholdYRep, 0);

                // --- FIX: PRE-CALCULATE ryLast TO REMOVE BLUE TAIL ---
                int ryLast = blueCenterY;
                int startIdxForTail = replayPosition;
                if (startIdxForTail >= 0 && startIdxForTail < replayRMSArray.length) {
                    ryLast = (int) (blueCenterY - replayRMSArray[startIdxForTail] * rmsYScale);
                    // Apply same clamping as inside the loop
                    if (ryLast < CEILING) ryLast = CEILING;
                    if (ryLast > FLOOR) ryLast = FLOOR;
                }

                // --- FIX: USE FLAG TO REMOVE BLUE TAIL ---
                boolean firstPointFound = false;

                for (int n = 0; n < 1444; n++) {
                    int x = 1574 - n;
                    int dIdx = replayPosition - n;

                    if (dIdx >= 0 && dIdx < replayRMSArray.length) {
                        int yVal = (int) (blueCenterY - replayRMSArray[dIdx] * rmsYScale);

                        // Apply Clamping: Ceiling at 835, Floor at 1296
                        if (yVal < CEILING) yVal = CEILING;
                        if (yVal > FLOOR) yVal = FLOOR;

                        // --- DRAW FILLS (Yellow or Green) ---
                        if (yVal < thresholdYRep) {
                            if (eventClassification[selectedEventId] == 0) {
                                g.drawGreenLine(x, yVal + STROKE_OFFSET, x, thresholdYRep, 0);
                            } else {
                                g.drawYellowLine(x, yVal + STROKE_OFFSET, x, thresholdYRep, 0);
                            }
                        }

                        // --- DRAW BLUE RMS LINE ---
                        if (!firstPointFound) {
                            // This is the very first valid data point on the right.
                            // We set ryLast but do NOT draw a line yet to prevent the tail.
                            ryLast = yVal;
                            firstPointFound = true;
                        } else {
                            // Draw segment from previous valid point to current point
                            g.drawBlueLine(x + 1, ryLast, x, yVal, 0);
                            ryLast = yVal;
                        }
                    }
                    if (x <= 130) break;
                }

                // Replay controls and post-processing
                replayPosition += 15;
                if (replayPosition >= replayRawArray.length + 1444) {
                    replayPosition = 0;
                }
                if (replayMaxPsd != null) {
                    System.arraycopy(replayMaxPsd, 0, activePsdBuffer, 0, 512);
                }
                view.postInvalidate();
            }
//
        } // This closes the "public void present(float deltaTime)" method
    }

    /////////////////////////// Replay Helper Method ////////////////////////////////////

    // Call this from GameScreenEventLog when a button is pressed
    // Replace/Update your loadSpecificEvent method:

    public void loadSpecificEvent(int id, Context context) {
        this.selectedEventId = id;
        replayList.clear();
        replayPosition = 0;
        replayMaxPsd = new double[512]; // Initialize screenshot array
        NotchFilter replayNotch = new NotchFilter();

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
                replayRawArray = new double[replayList.size()];
                // Warm up filter
                double firstVal = replayList.get(0);
                for(int i = 0; i < 200; i++) { replayNotch.filter(firstVal); }

                double maxAbsVal = -1;
                int peakIdx = 0;
                double sum = 0;

                for (int i = 0; i < replayList.size(); i++) {
                    double val = replayNotch.filter(replayList.get(i));
                    replayRawArray[i] = val;
                    sum += val;
                    if (Math.abs(val) > maxAbsVal) {
                        maxAbsVal = Math.abs(val);
                        peakIdx = i;
                    }
                }

                // --- 1. CALCULATE REPLAY RMS ---
                double mean = sum / replayRawArray.length;
                double[] bipolar = new double[replayRawArray.length];
                for (int i = 0; i < bipolar.length; i++) bipolar[i] = replayRawArray[i] - mean;

                replayRMSArray = RMSCalculator.calculateMovingRMS(bipolar, 40);
                if (replayRMSArray != null) {
                    for (int k = 0; k < replayRMSArray.length; k++) replayRMSArray[k] *= 1.75;
                    replayRMSArray = MovingAverageCalculator.calculateMovingAverage(replayRMSArray, 80);
                }

                // --- 2. CAPTURE PSD "SCREENSHOT" AT PEAK ---
                int psdWin = 1024;
                if (replayRawArray.length >= psdWin) {
                    double[] psdBuf = new double[psdWin];
                    int start = peakIdx - 512;
                    if (start < 0) start = 0;
                    if (start > replayRawArray.length - psdWin) start = replayRawArray.length - psdWin;

                    double localSum = 0;
                    for (int i = 0; i < psdWin; i++) {
                        psdBuf[i] = replayRawArray[start + i] / 3.0; // Scale to match Live
                        localSum += psdBuf[i];
                    }
                    double localMean = localSum / psdWin;
                    for (int i = 0; i < psdWin; i++) {
                        double v = (psdBuf[i] - localMean) * (0.5 * (1.0 - Math.cos(2.0 * Math.PI * i / 1023)));
                        psdBuf[i] = v;
                    }
                    PowerSpectralDensityCalculator psdCalc = new PowerSpectralDensityCalculator(psdBuf, 1000);
                    double[] fullPsd = psdCalc.calculatePSD(psdBuf, 1000);
                    System.arraycopy(fullPsd, 0, replayMaxPsd, 0, 512);
                }
                isReplaying = true;
                isRecording = false;
            }
        } catch (Exception e) { Log.e("REPLAY", "Error: " + e.getMessage()); }
    }
    /////////////// Capture and Save Event /////////////////////////
    private void captureAndSaveEvent() {
        // Define threadContext locally so the executor can access it
        final Context threadContext = (Context) game;

        // Capture time
        long delta = System.currentTimeMillis() - startTimeMillis;
        timeStamp[eventCount] = String.format("%02d:%02d:%03d",
                (delta / 60000), (delta / 1000) % 60, (delta % 1000));

        // --- ADD THIS LINE ---
        // Save the current threshold so we can recreate the fills exactly during replay
        eventAmpThresholds[eventCount] = rmsAmpThresh;

        // Trigger your existing CSV save logic
        final int currentID = eventCount;
        final int currentEndIdx = ramRecordBufferIdx;

        saveExecutor.execute(() -> {
            try {
                // Now threadContext is recognized
                if (threadContext == null) return;

                int startIdx = currentEndIdx - 2000;
                if (startIdx < 0) startIdx = 0;

                File path = threadContext.getExternalFilesDir(null);
                File file = new File(path, "Event_" + currentID + ".csv");

                PrintWriter pw = new PrintWriter(new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file, false)), 65536));
                synchronized (ramRecordBuffer) {
                    for (int k = startIdx; k < currentEndIdx; k++) {
                        if (k >= 0 && k < ramRecordBuffer.length) {
                            pw.println(ramRecordBuffer[k]);
                        }
                    }
                }
                pw.flush();
                pw.close();
            } catch (Exception e) {
                Log.e("SAVE_ERROR", "Failed to save: " + e.getMessage());
            }
        });
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