package com.esark.gasp;

import static com.esark.framework.AndroidGame.signalBufferLen;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.esark.framework.Game;
import com.esark.framework.Graphics;
import com.esark.framework.Input;
import com.esark.framework.Input.TouchEvent;
import com.esark.framework.Screen;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

public class GameScreen extends Screen implements Input {
    Context context = null;
    private static final String TAG = "GameScreen";
    int xStart = 0, xStop = 0;
    //public static double[] A2DVal = new double[3500];
    public static double[] A2DVal = new double[287];   //was 1435
    public double[] A2DValCopy = new double[287];
    double[] psd = new double[2048];

    double[] sineWave = new double[2048];
    public static double[][] eventArray = new double [20][2048];
    public static double[] lastEventArray = new double[2048];
    double[] psdResult = new double[2048];
    public static double[][] PSDArray = new double[20][2048];
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
    int rmsAmpThresh = 50, rmsWidthThresh = 0;
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

    //Constructor
    public GameScreen(Game game) {
        super(game);
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
        g.drawPortraitPixmap(Assets.laryngospasmBackgroundMain , 0, 0);
        len = touchEvents.size();
        //Check to see if paused
        for (int i = 0; i < len; i++) {
            TouchEvent event = touchEvents.get(i);
            if (event.type == TouchEvent.TOUCH_DRAGGED || event.type == TouchEvent.TOUCH_DOWN) {
                if (event.x > 1750 && event.x < 3300 && event.y > 4600 && event.y < 5000) {
                    //Back Button Code Here
                    Intent intent2 = new Intent(context.getApplicationContext(), GaspSemg.class);
                    context.startActivity(intent2);
                    return;
                }
                else if (event.x > 185 && event.x < 1735 && event.y > 3500 && event.y < 3775) {
                    //Start
                    startTimeMillis = System.currentTimeMillis();
                    startRecording = 1;
                }
                else if (event.x > 1400 && event.x < 1675 && event.y > 3745 && event.y < 4020) {
                    //RMS threshold amplitude to trigger event. Left Up Button.
                    rmsThresholdTouch = 1;
                    if (leftUpCount == 0) {       //Flag so we only increment the delay by 5 once per touch
                        rmsAmpThresh += 5;
                        leftUpCount = 1;
                    }
                }
                else if (event.x > 1400 && event.x < 1675 && event.y > 4030 && event.y < 4305) {
                    //RMS threshold amplitude to trigger event. Left Down Button.
                    rmsThresholdTouch = 1;
                    if (leftDownCount == 0) {       //Flag so we only increment the delay by 5 once per touch
                        rmsAmpThresh -= 5;
                        leftDownCount = 1;
                    }
                }
                else if (event.x > 185 && event.x < 1735 && event.y > 4300 && event.y < 4599) {
                    //Event Log Screen
                    game.setScreen(gameScreenEventLog);
                }
                else if (event.x > 1750 && event.x < 3300 && event.y > 4300 && event.y < 4599) {
                    //Last Event. (Now clear events
                    //game.setScreen(gameScreenLastEvent);
                    eventCount = 0;
                }
                else if (event.x > 185 && event.x < 1735 && event.y > 4600 && event.y < 5000) {
                    //Manual Patient Event
                    if (manualPatientEventUpCount == 0) {       //Flag so we only increment the delay by 5 once per touch
                        for(int r = 0; r < 2048; r++){
                            eventArray[eventCount][r] = sineWave[r];
                        }
                        for(int w = 0; w < psdResult.length; w++){
                            PSDArray[eventCount][w] = psdResult[w];
                        }
                        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
                        timeStamp[eventCount]  = dateFormat.format(new Date());
                        eventCount++;
                        manualPatientEventUpCount = 1;
                    }
                }
                if(rmsAmpThresh < 0){
                    rmsAmpThresh = 0;
                }
                //else if (landscape == 1 && event.x < 100 && event.y > 230)
            }
            else if(event.type == TouchEvent.TOUCH_UP){
                if (event.x > 1400 && event.x < 1675 && event.y > 3745 && event.y < 4020) {
                    //RMS threshold amplitude to trigger event. Left up button.
                    leftUpCount = 0;       //Flag so we only increment the delay by 5 once per touch
                }
                else if (event.x > 1400 && event.x < 1675 && event.y > 4030 && event.y < 4305) {
                    //RMS threshold amplitude to trigger event
                    leftDownCount = 0;       //Flag so we only increment the delay by 5 once per touch
                }
                else if(event.x > 185 && event.x < 1735 && event.y > 4600 && event.y < 5000) {
                    //Manual Patient Event
                    manualPatientEventUpCount = 0;
                }
            }
        }

        //   if(landscape == 0) {

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



        String eventCountStr = String.valueOf(eventCount);
        g.drawText(eventCountStr, 1550, 4800);
        ////////////////// Start / Stop Recording //////////////////////////////////////////
        if(startRecording == 0){
            recDeltaTimeMillis = 0;
            minutes = 0;
            seconds = 0;
            remainingMilliseconds = 0;
            String formattedTime = String.format("%02d:%02d:%03d", minutes, seconds, remainingMilliseconds);
            g.drawText(formattedTime, 1000, 3700);
        }
        else if(startRecording == 1){
            currentTimeMillis = System.currentTimeMillis();
            recDeltaTimeMillis = (int) (currentTimeMillis - startTimeMillis);
            minutes = (int) recDeltaTimeMillis/60000;
            seconds = (int) recDeltaTimeMillis/1000;
            remainingMilliseconds = (int) recDeltaTimeMillis % 1000;
            String formattedTime = String.format("%02d:%02d:%03d", minutes, seconds, remainingMilliseconds);
            g.drawText(formattedTime, 1000, 3700);
        }

        //////////////////// RMS Threshold to Trigger Event //////////////////////////////////
        if(rmsThresholdTouch == 0) {
            g.drawText("50", 940, 4090);
        }
        else if(rmsThresholdTouch == 1){
            String rmsAmpThreshStr = String.valueOf(rmsAmpThresh);
            g.drawText(rmsAmpThreshStr, 940, 4090);
        }

        //////////////////////////////////////////////////////////////////////////////////////
        //    xStart = 300;
        //  xStop = 301;
        int u = 0;

        xStart = 1600;
        int xStep = 5;      // Increase this to make the signal move faster across the screen (pixels per sample)
        xStop = xStart - xStep;
        for (int n = signalBufferLen - 1; n > 0; n--) {
            // Using n-- (step of 1) makes the signal much smoother
            g.drawBlackLine(xStart, (int) A2DVal[n] - 50, xStop, (int) (A2DVal[n - 1]) - 50, 0);
            xStart = xStop;
            xStop -= xStep;
            if (xStop <= 165) {
                break;
            }
        }

        /*
        xStart = 1600;
        int xStopLimit = 165;
        int totalGraphWidth = xStart - xStopLimit;

        // numDisplayedSamples controls how many data points from A2DVal cover the screen
        // Decrease this number to zoom in further, increase to zoom out.
        // If 45 xStep showed 2 periods, then 1435/45 = ~32 samples.
        double numDisplayedSamples = 128;

        float yScale = 2.5f;
        int baseline = 410;

        int prevY = -1;
        for (int x = xStart; x >= xStopLimit; x--) {
            // Calculate the fractional index in the A2DVal buffer for this pixel
            double bufferIndex = 1434 - (((double)(xStart - x) / totalGraphWidth) * numDisplayedSamples);

            if (bufferIndex < 1) bufferIndex = 1;

            int i0 = (int) bufferIndex;
            int i1 = i0 - 1;
            double fraction = bufferIndex - i0;

            // Cosine Interpolation for a smooth curve
            double mu2 = (1 - Math.cos(fraction * Math.PI)) / 2;
            double interpolatedVal = A2DVal[i0] * (1 - mu2) + A2DVal[i1] * mu2;

            int currentY = (int) ((interpolatedVal - baseline) * yScale + baseline) - 50;

            if (prevY != -1) {
                g.drawBlackLine(x + 1, prevY, x, currentY, 0);
            }
            prevY = currentY;
        }
*/
        System.arraycopy(A2DVal, 0, A2DValCopy, 0, A2DVal.length);
        // Subtract DC offset (410) so RMS represents actual signal strength fluctuations




        // ++++++++++++++++++ RMS (Root-Mean Square) Visualization ++++++++++++++++++++++++++
        double[] movingRMS = RMSCalculator.calculateMovingRMS(A2DValCopy, 20);
        double[] smoothedRMS = MovingAverageCalculator.calculateMovingAverage(movingRMS, 10);        // +++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
/*
        for (int i = 0; i < smoothedRMS.length; i++) {
            smoothedRMS[i] = 410.0 - smoothedRMS[i];
        }
*/
        if (smoothedRMS.length > 2) {
            xStart = 1600;
            xStop = 1585;
            //xStop = 3235;
            for (int n = smoothedRMS.length - 1; n > 1; n--) {
                // 1. Calculate the RMS amplitude (deviation from the 1360 baseline)
                // 2. Plot it relative to your target y = 1050 (Centered symmetrically)
                //double rmsAmplitude = Math.abs(smoothedRMS[n]);
              //  double rmsAmplitudeNext = Math.abs(smoothedRMS[n - 1]);

                Log.d(TAG, "rmsAmplitude: " + smoothedRMS[n]);
                Log.d(TAG, "rmsAmplitudeNext: " + smoothedRMS[n-1]);

                if(smoothedRMS[n] > 423){
                    smoothedRMS[n] = 423;
                }
                if(smoothedRMS[n-1] > 423){
                    smoothedRMS[n-1] = 423;
                }
                if(smoothedRMS[n] < 273){
                    smoothedRMS[n] = 273;
                }
                if(smoothedRMS[n-1] < 273){
                    smoothedRMS[n-1] = 273;
                }

                g.drawBlueLine(xStart, (int) ((3*smoothedRMS[n])), xStop, (int) ((3*smoothedRMS[n-1])), 0);

                xStart = xStop;
                xStop -= 15;
                if (xStop <= 180) {
                    break;
                }
            }
        }

        /*
        if (smoothedRMS.length > 2) {
            xStart = 1600;
            xStop = 1585;
            //xStop = 3235;
            for (int n = smoothedRMS.length - 1; n > 1; n--) {
                // 1. Calculate the RMS amplitude (deviation from the 1360 baseline)
                // 2. Plot it relative to your target y = 1050 (Centered symmetrically)
                double rmsAmplitude = Math.abs(smoothedRMS[n] - 500);
                double rmsAmplitudeNext = Math.abs(smoothedRMS[n - 1] - 500);

                g.drawBlueLine(xStart, (int) (rmsAmplitude), xStop, (int) (rmsAmplitudeNext), 0);

                xStart = xStop;
                xStop -= 15;
                if (xStop <= 180) {
                    break;
                }
            }
        }
*/
        /*
        if (smoothedRMS.length > 2) {
            xStart = 1600;
            xStop = 1585;
            // Target center for the blue line
            int blueCenterY = -500;
            // Baseline for RMS values when the signal is centered at 410
            // The RMS of a constant signal 410 is 410.
            double rmsBaseline = 410.0;
            // Scale factor to make the line move visibly
            float rmsYScale = 15.0f;

            for (int n = smoothedRMS.length - 1; n > 1; n--) {
                // Calculate deviation from baseline and scale it
                double rmsAmplitude = (smoothedRMS[n] - rmsBaseline) * rmsYScale;
                double rmsAmplitudeNext = (smoothedRMS[n - 1] - rmsBaseline) * rmsYScale;

                // Draw centered at blueCenterY
                int y1 = (int) (blueCenterY - rmsAmplitude);
                int y2 = (int) (blueCenterY - rmsAmplitudeNext);
                
                // Draw the blue line
                g.drawBlueLine(xStart, y1, xStop, y2, 0);


                xStart = xStop;
                xStop -= 15;
                if (xStop <= 180) {
                    break;
                }
            }
        }
        */


        // ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
        //double[] signal = {1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0}; // Example data
        double fs = 125.0; // Example sampling frequency (Hz)

        //     PowerSpectralDensityCalculator psdCalc = new PowerSpectralDensityCalculator(sineWave, fs);
        //   psdResult = psdCalc.calculatePSD(sineWave, fs);

        PowerSpectralDensityCalculator psdCalc = new PowerSpectralDensityCalculator(A2DVal, fs);
        psdResult = psdCalc.calculatePSD(A2DVal, fs);
        //   PowerSpectralDensityCalculator psdCalc = new PowerSpectralDensityCalculator(sineWave, fs);
        //  psdResult = psdCalc.calculatePSD(sineWave, fs);

        for (int i = 0; i < psdResult.length; i++) {
            psdResult[i] = psdResult[i] * -0.15 + 3575;
            // Red line (PSD result) is drawn later as psdResult[i] - 1695.
            // If we want the drawn y-value to not go above 1460, then psdResult[i] - 1695 >= 1460
            // Because screen coordinates are 0 at the top, "above" 1460 means y < 1460.
            // So we want psdResult[i] - 1695 >= 1460 => psdResult[i] >= 3155
            if(psdResult[i] < 3155){
                psdResult[i] = 3155;
            }
            // System.out.println("Frequency Bin " + i + ": PSD = " + psdResult[i]);
        }
        xStart = 170;
        xStop = 180;
        for (int i = 1; i < psdResult.length; i++) {
            g.drawRedLine(xStart, (int) psdResult[i - 1] - 1695, xStop, (int) psdResult[i] - 1695, 0);
            xStart = xStop;
            xStop += 10;
            if(xStop >= 1600){
                break;
            }
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
