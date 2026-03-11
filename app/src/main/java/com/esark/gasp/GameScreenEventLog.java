package com.esark.gasp;

//import static com.esark.gasp.GameScreen.lastEventArray;
import static com.esark.gasp.GameScreen.eventCount;
import static com.esark.gasp.GameScreen.lastEventArray;
import static com.esark.gasp.GameScreen.lastEventPSDArray;
import static com.esark.gasp.GameScreen.len;
import static com.esark.gasp.GameScreen.timeStamp;

import android.content.Context;

import com.esark.framework.Game;
import com.esark.framework.Graphics;
import com.esark.framework.Input;
import com.esark.framework.Screen;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class GameScreenEventLog extends Screen implements Input {
    Context context = null;

    int xStart = 0, xStop = 0;
    //public static double[] A2DVal = new double[3500];
    public static double[] A2DVal = new double[3500];
    double[] psd = new double[2048];

    double[] sineWave = new double[2048];

    double[] psdResult = new double[2048];
    private static final int INVALID_POINTER_ID = -1;
    // The ‘active pointer’ is the one currently moving our object.
    private int mActivePointerId = INVALID_POINTER_ID;


    //Constructor
    public GameScreenEventLog(Game game) {
        super(game);
    }
    public GameScreenEvent gameScreenEvent = new GameScreenEvent(game);
    @Override
    public void update(float deltaTime, Context context) {
        //framework.input
        List<TouchEvent> touchEvents = game.getInput().getTouchEvents();
        updateRunning(touchEvents, deltaTime, context);
    }

    private void updateRunning(List<TouchEvent> touchEvents, float deltaTime, Context context) {
        //updateRunning() contains controller code of our MVC scheme
        Graphics g = game.getGraphics();
        Assets.eventLogBackground = g.newPixmap("eventLogBackground.png", Graphics.PixmapFormat.ARGB4444);
        Assets.eventLogButton = g.newPixmap("eventLogButton.png", Graphics.PixmapFormat.ARGB4444);
        len = touchEvents.size();
        //Check to see if paused
        for (int i = 0; i < len; i++) {
            TouchEvent event = touchEvents.get(i);
           // if (event.type == TouchEvent.TOUCH_UP) {
           // }
            if (event.type == TouchEvent.TOUCH_UP || event.type == TouchEvent.TOUCH_DRAGGED || event.type == TouchEvent.TOUCH_DOWN) {
                if (event.x > 185 && event.x < 1735 && event.y > 4700 && event.y < 4975) {
                    //Artifact/PSD Screen
                    game.setScreen(game.getStartScreen());
                }
                //Button 1
                if (event.x > 150 && event.x < 1550 && event.y > 500 && event.y < 1000) {
                    eventCount = 0;
                    game.setScreen(gameScreenEvent);
                }
                //Button 2
                if (event.x > 1900 && event.x < 3300 && event.y > 500 && event.y < 1000) {
                    eventCount = 1;
                    game.setScreen(gameScreenEvent);
                }
                //Button 3
                if (event.x > 150 && event.x < 1550 && event.y > 1050 && event.y < 1550) {
                    eventCount = 2;
                    game.setScreen(gameScreenEvent);
                }
                //Button 4
                if (event.x > 1900 && event.x < 3300 && event.y > 1050 && event.y < 1550) {
                    eventCount = 3;
                    game.setScreen(gameScreenEvent);
                }
            }
        }
        g.drawPortraitPixmap(Assets.eventLogBackground, 0, 0);

      //  long tsLong = System.currentTimeMillis() / 1000;
        //String ts = tsLong.toString();
      //  String timeStamp = String.valueOf(TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis()));

        switch (eventCount) {
            case 1:
                g.drawEventLogButtonPixmap(Assets.eventLogButton, 150, 500);
                g.drawText(timeStamp[0], 250, 800);
                break; // Optional: exits the switch statement
            case 2:
                g.drawEventLogButtonPixmap(Assets.eventLogButton, 150, 500);
                g.drawText(timeStamp[0], 250, 800);

                g.drawEventLogButtonPixmap(Assets.eventLogButton, 1900, 500);
                g.drawText(timeStamp[1], 2050, 800);
                break;
            case 3:
                g.drawEventLogButtonPixmap(Assets.eventLogButton, 150, 500);
                g.drawText(timeStamp[0], 250, 800);

                g.drawEventLogButtonPixmap(Assets.eventLogButton, 1900, 500);
                g.drawText(timeStamp[1], 2050, 800);

                g.drawEventLogButtonPixmap(Assets.eventLogButton, 150, 1050);
                g.drawText(timeStamp[2], 250, 1350);
                break; // Optional: exits the switch statement
            case 4:
                g.drawEventLogButtonPixmap(Assets.eventLogButton, 150, 500);
                g.drawText(timeStamp[0], 250, 800);

                g.drawEventLogButtonPixmap(Assets.eventLogButton, 1900, 500);
                g.drawText(timeStamp[1], 2050, 800);

                g.drawEventLogButtonPixmap(Assets.eventLogButton, 150, 1050);
                g.drawText(timeStamp[2], 250, 1350);

                g.drawEventLogButtonPixmap(Assets.eventLogButton, 1900, 1050);
                g.drawText(timeStamp[3], 2050, 1350);
                break;
            default:
                // Code to execute if no case matches (optional)
                break;
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