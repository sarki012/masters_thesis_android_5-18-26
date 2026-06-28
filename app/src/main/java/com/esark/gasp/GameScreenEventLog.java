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
    // --- GRID CONSTANTS (64 Buttons: 4 cols x 16 rows) ---
    private static final int COLS = 4;
    private static final int ROWS = 16;
    private static final int MAX_CAPACITY = 64;

    private static final int START_X = 95;
    private static final int START_Y = 165;
    private static final int BTN_W = 350;
    private static final int BTN_H = 100;
    private static final int SPACING_X = 50;
    private static final int SPACING_Y = 20;

    // DO NOT initialize here. Initializing other screens in fields
    // is what causes the "20 event crash" due to memory recursion.
    private GameScreenEvent gameScreenEvent = null;

    public GameScreenEventLog(Game game) {
        super(game);
        loadAssets();
    }

    private void loadAssets() {
        Graphics g = game.getGraphics();
        if (g == null) return;
        try {
            // RGB565 uses the least amount of RAM possible for images
            if (Assets.eventLogBackground == null) {
                Assets.eventLogBackground = g.newPixmap("eventLogBackground.png", Graphics.PixmapFormat.RGB565);
            }
            if (Assets.eventLogButtonJpeg == null) {
                Assets.eventLogButtonJpeg = g.newPixmap("eventLogButtonJpeg.jpg", Graphics.PixmapFormat.RGB565);
            }
        } catch (Exception e) {
            Log.e("EventLog", "Memory Error: " + e.getMessage());
        }
    }

    @Override
    public void update(float deltaTime, Context context) {
        List<TouchEvent> touchEvents = game.getInput().getTouchEvents();
        if (touchEvents == null) return;

        // Take a snapshot of the count so it doesn't change during the loop
        int currentTotal;
        synchronized (timeStamp) {
            currentTotal = eventCount;
        }

        for (int i = 0; i < touchEvents.size(); i++) {
            TouchEvent event = touchEvents.get(i);
            if (event.type == TouchEvent.TOUCH_UP) {
                // 1. Back Button
                if (event.x > 25 && event.x < 675 && event.y > 2583) {
                    game.setScreen(game.getStartScreen());
                    return;
                }

                // 2. Grid Detection
                int limit = Math.min(currentTotal, MAX_CAPACITY);
                for (int j = 0; j < limit; j++) {
                    int row = j / COLS;
                    int col = j % COLS;
                    int x = START_X + col * (BTN_W + SPACING_X);
                    int y = START_Y + row * (BTN_H + SPACING_Y);

                    if (event.x > x && event.x < x + BTN_W && event.y > y && event.y < y + BTN_H) {
                        // LAZY INITIALIZATION: Create the sub-screen ONLY when clicked
                        if (gameScreenEvent == null) {
                            gameScreenEvent = new GameScreenEvent(game);
                        }

                        // Pass the index to GameScreen to load the specific CSV
                        GameScreen gs = GameScreen.liveScreen;
                        if (gs != null) {
                            gs.loadSpecificEvent(j, context);
                            game.setScreen(gs);
                        }
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

        // Draw Background
        if (Assets.eventLogBackground != null) {
            g.drawPortraitPixmap(Assets.eventLogBackground, 0, 0);
        }

        // THREAD-SAFE DRAWING
        String[] localLabels = new String[MAX_CAPACITY];
        int displayCount = 0;

        synchronized (timeStamp) {
            displayCount = Math.min(eventCount, MAX_CAPACITY);
            for (int k = 0; k < displayCount; k++) {
                if (k < timeStamp.length) {
                    localLabels[k] = timeStamp[k];
                }
            }
        }

        if (displayCount == 0) {
            g.drawText("No Events Recorded", 170, 400);
            return;
        }

        if (Assets.eventLogButtonJpeg != null) {
            for (int i = 0; i < displayCount; i++) {
                int row = i / COLS;
                int col = i % COLS;
                int x = START_X + col * (BTN_W + SPACING_X);
                int y = START_Y + row * (BTN_H + SPACING_Y);

                // Stop if we go off the visual area of the background
                if (y > 2500) break;

                g.drawEventLogButtonPixmap(Assets.eventLogButtonJpeg, x, y);

                String label = localLabels[i];
                if (label != null && label.length() > 0) {
                    // 1. TRUNCATE: Remove the right-most digit (the 3rd millisecond digit)
                    // e.g., "00:00:000" becomes "00:00:00"
                    String truncatedLabel = label.substring(0, label.length() - 1);

                    // 2. CENTER: Adjust the offsets.
                    // Button width is 350. Text length is roughly 200px.
                    // (350 - 180) / 2 = ~85.
                    // x + 85 centers it horizontally. y + 68 centers it vertically.
                    g.drawText(truncatedLabel, x + 42, y + 72);
                }
            }
        }
    }

    @Override public void resume() { System.gc(); loadAssets(); }
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