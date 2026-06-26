package com.esark.gasp;

import static com.esark.gasp.GameScreen.eventCount;
import static com.esark.gasp.GameScreen.timeStamp;

import android.content.Context;
import android.util.Log;

import com.esark.framework.Game;
import com.esark.framework.Graphics;
import com.esark.framework.Input;
import com.esark.framework.Input.TouchEvent;
import com.esark.framework.Screen;

import java.util.List;

public class GameScreenEventLog extends Screen implements Input {
    private static final int COLS = 4;
    private static final int ROWS = 16;
    private static final int MAX_EVENTS = 64;

    private static final int START_X = 65;
    private static final int START_Y = 120;
    private static final int BTN_W = 350;
    private static final int BTN_H = 100;
    private static final int SPACING_X = 50;
    private static final int SPACING_Y = 20;

    public GameScreenEventLog(Game game) {
        super(game);
        loadAssets();
    }

    private void loadAssets() {
        Graphics g = game.getGraphics();
        if (g == null) return;
        try {
            // Using RGB565 uses 50% less RAM than default
            if (Assets.eventLogBackground == null) {
                Assets.eventLogBackground = g.newPixmap("eventLogBackground.png", Graphics.PixmapFormat.RGB565);
            }
            if (Assets.eventLogButtonJpeg == null) {
                Assets.eventLogButtonJpeg = g.newPixmap("eventLogButtonJpeg.jpg", Graphics.PixmapFormat.RGB565);
            }
        } catch (Exception e) {
            Log.e("EventLog", "Bitmap Load Error: " + e.getMessage());
        }
    }

    @Override
    public void update(float deltaTime, Context context) {
        List<TouchEvent> touchEvents = game.getInput().getTouchEvents();
        if (touchEvents == null) return;

        // SNAPSHOT variables once per frame to prevent race conditions
        int currentCount;
        synchronized (timeStamp) {
            currentCount = eventCount;
        }

        for (int i = 0; i < touchEvents.size(); i++) {
            TouchEvent event = touchEvents.get(i);
            if (event.type == TouchEvent.TOUCH_UP) {
                // Back button
                if (event.x > 25 && event.x < 675 && event.y > 2583 && event.y < 2780) {
                    game.setScreen(game.getStartScreen());
                    return;
                }

                // Grid detection
                int limit = Math.min(currentCount, MAX_EVENTS);
                for (int j = 0; j < limit; j++) {
                    int row = j / COLS;
                    int col = j % COLS;
                    int x = START_X + col * (BTN_W + SPACING_X);
                    int y = START_Y + row * (BTN_H + SPACING_Y);

                    if (event.x > x && event.x < x + BTN_W && event.y > y && event.y < y + BTN_H) {
                        GameScreen gs = GameScreen.liveScreen;
                        if (gs == null) gs = new GameScreen(game);

                        gs.loadSpecificEvent(j, context);
                        game.setScreen(gs);
                        return;
                    }
                }
            }
        }
    }

    @Override
    public void present(float deltaTime) {
        Graphics g = game.getGraphics();
        if (g == null) return;

        if (Assets.eventLogBackground == null || Assets.eventLogButtonJpeg == null) {
            loadAssets();
        }

        // 1. Draw Background
        if (Assets.eventLogBackground != null) {
            g.drawPortraitPixmap(Assets.eventLogBackground, 0, 0);
        }

        // 2. SNAPSHOT DATA (The most important part for stability)
        // We copy the data we need and release the lock immediately
        int displayLimit;
        String[] labels = new String[MAX_EVENTS];
        synchronized (timeStamp) {
            displayLimit = Math.min(eventCount, MAX_EVENTS);
            for (int k = 0; k < displayLimit; k++) {
                labels[k] = timeStamp[k];
            }
        }

        if (displayLimit == 0) {
            g.drawText("No Events Recorded", 170, 400);
            return;
        }

        // 3. DRAWING LOOP (Now thread-safe and fast)
        if (Assets.eventLogButtonJpeg != null) {
            for (int i = 0; i < displayLimit; i++) {
                int row = i / COLS;
                int col = i % COLS;
                int x = START_X + col * (BTN_W + SPACING_X);
                int y = START_Y + row * (BTN_H + SPACING_Y);

                // Boundary check to prevent framework math crash
                if (y > 2500) break;

                g.drawEventLogButtonPixmap(Assets.eventLogButtonJpeg, x, y);

                String label = labels[i];
                if (label != null) {
                    g.drawText(label, x + 70, y + 68);
                } else {
                    // Draw a placeholder so the app doesn't crash on drawText(null)
                    g.drawText("Recording...", x + 70, y + 65);
                }
            }
        }
    }

    @Override public void resume() {
        System.gc(); // Clear the massive recording arrays from RAM
        loadAssets();
    }
    @Override public void pause() {}
    @Override public void dispose() {}
    @Override public boolean isTouchDown(int p) { return false; }
    @Override public int getTouchX(int p) { return 0; }
    @Override public int getTouchY(int p) { return 0; }
    @Override public float getAccelX() { return 0; }
    @Override public float getAccelY() { return 0; }
    @Override public float getAccelZ() { return 0; }
    @Override public List<TouchEvent> getTouchEvents() { return null; }
}