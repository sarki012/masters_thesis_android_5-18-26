package com.esark.gasp;

import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.util.Log;

import com.esark.framework.Game;
import com.esark.framework.Graphics;
import com.esark.framework.Input;
import com.esark.framework.Pixmap;
import com.esark.framework.Screen;

import static com.esark.framework.AndroidGame.bufferFlag;
import static com.esark.gasp.Assets.gaspMainBackground;
import static com.esark.gasp.FFT.fft;

import static java.lang.Math.sin;
import static java.lang.Math.sqrt;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

public class GameScreen extends Screen implements Input {
    Context context = null;

    int xStart = 0, xStop = 0;
    //public static double[] A2DVal = new double[3500];
    public static double[] A2DVal = new double[500];
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
        g.drawPortraitPixmap(Assets.gaspMainBackground, 0, 0);
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

        //  g.drawRect(1750, 4600, 1550, 400, 0);       //Bluetooth Connect
        // g.drawRect(185, 3500, 1550, 275, 0);       //Start
        //   g.drawRect(900, 3875, 300, 275, 0);       //RMS Height Threshold Text
        //   g.drawRect(1400, 3745, 275, 275, 0);       //Left Up Button
        //   g.drawRect(1400, 4030, 275, 275, 0);       //Left Down Button
        //  g.drawRect(185, 4300, 1550, 299, 0);       //Event Log
        //   g.drawRect(1750, 4300, 1550, 299, 0);       //Last Event
        // g.drawRect(185, 4600, 1550, 400, 0);       //Manual Patient Event
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
        //   for (int y = 1; y < 8; y++) {

        // freqScalar = 0.1534;      //50
        //  freqScalar = 64.34;
        //freqScalar = 0.0155;
        // freqScalar = 3.25;       //100
        // freqScalar = 1.63;      //200
        //  freqScalar = 0.652;      //500
/*
        for(int h = 0; h < 2048; h++){
            sineWave[h] = (int)amplitude*sin(h/freqScalar) + 800;
        }
        if(increasingFlag == 1) {
            amplitude += 50;
            if (amplitude >= 500){
                increasingFlag = 0;
            }
        }
        else if(increasingFlag == 0){
            amplitude -= 50;
            if(amplitude <= 100){
                increasingFlag = 1;
            }
        }
        if(freqIncreasingFlag == 1) {
            freqScalar --;
            if (freqScalar <= 10){
                freqIncreasingFlag = 0;
            }
        }
        else if(freqIncreasingFlag == 0){
            freqScalar ++;
            if (freqScalar >= 100){
                freqIncreasingFlag = 1;
            }
        }
        */
        xStart = 3350;
        xStop = 3335;
        for (int n = 499; n > 2; n --) {
            g.drawBlackLine(xStart, (int) A2DVal[n], xStop, (int) (A2DVal[n - 1]), 0);
            xStart = xStop;
            xStop-= 15;
            if(xStop <= 410){
                break;
            }
        }
        //double[] signal = {1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0}; // Example data
        double fs = 10.0; // Example sampling frequency (Hz)

        //     PowerSpectralDensityCalculator psdCalc = new PowerSpectralDensityCalculator(sineWave, fs);
        //   psdResult = psdCalc.calculatePSD(sineWave, fs);

        PowerSpectralDensityCalculator psdCalc = new PowerSpectralDensityCalculator(A2DVal, fs);
        psdResult = psdCalc.calculatePSD(A2DVal, fs);
        //   PowerSpectralDensityCalculator psdCalc = new PowerSpectralDensityCalculator(sineWave, fs);
        //  psdResult = psdCalc.calculatePSD(sineWave, fs);

        for (int i = 0; i < psdResult.length; i++) {
            psdResult[i] = psdResult[i] * -0.025 + 3233;
            if(psdResult[i] < 2000){
                psdResult[i] = 2000;
            }
            // System.out.println("Frequency Bin " + i + ": PSD = " + psdResult[i]);
        }
        xStart = 400;
        xStop = 410;
        for (int i = 1; i < psdResult.length; i++) {
            g.drawRedLine(xStart, (int) psdResult[i - 1], xStop, (int) psdResult[i], 0);
            xStart = xStop;
            xStop += 10;
            if(xStop >= 3370){
                break;
            }
        }
        /*
        Complex[] cinput = new Complex[2048];        //256 works
        for (int m = 0; m < 2048; m++) {
           // cinput[m] = new Complex(A2DVal[m], 0.0);
            cinput[m] = new Complex(sineWave[m], 0.0);
            xStart = xStop;
            xStop++;
        }
        fft(cinput);

    //    System.out.println("Results:");
        for (Complex c : cinput) {
       //     System.out.println(c);
           // psd[u] = ((c.re * c.re + c.im * c.im) / -6000000) + 3500;
            psd[u] = ((c.re * c.re + c.im * c.im) / -10240000) + 3500;
            //if(psd[u] < 5000){
              //  psd[u] = 3000;
            //}
      //      System.out.println("PSD:");
        //    System.out.println(psd[u]);
            u++;
        }

        xStart = 300;
        xStop = 301;
        for (int i = 1024; i < 2048; i++) {
            g.drawBlackLine(xStart, (int) psd[i - 1], xStop, (int) psd[i], 0);
            xStart = xStop;
            xStop += 3;
        }


        String freqString = String.valueOf(freq);
      //  g.drawText("100", 330, PSDYVAL);
        //g.drawText("200", 785, PSDYVAL);
      //  g.drawText("500", 1700, PSDYVAL);
      //  g.drawText(freqString, 2300, 3700);
      //  while(bufferFlag == 0);


        xStart = 3500;
        xStop = 3499;
        for (int n = 499; n > 2; n --) {
            g.drawBlackLine(xStart, (int) A2DVal[n], xStop, (int) (A2DVal[n - 1]), 0);
            xStart = xStop;
            xStop-= 15;
        }
/*
        //xStart = 3370;
        //xStop = 3369;
        xStart = 2800;
        xStop = 2799;
        for (int n = 2047; n > 0; n -- ) {
            g.drawBlackLine(xStart, (int) sineWave[n], xStop, (int) (sineWave[n - 1]), 0);
            xStart = xStop;
            //xStop-= 5;
            xStop--;
            if(xStart <= 380){
                break;
            }
        }

     //   bufferFlag = 0;
       // SystemClock.sleep(10);
       */


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
