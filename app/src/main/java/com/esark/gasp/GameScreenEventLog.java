package com.esark.gasp;

import static com.esark.gasp.GameScreen.eventCount;
import static com.esark.gasp.GameScreen.timeStamp;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.esark.framework.AndroidGame;
import com.esark.framework.Game;
import com.esark.framework.Graphics;
import com.esark.framework.Input;
import com.esark.framework.Input.TouchEvent;
import com.esark.framework.Screen;

import java.util.List;
import com.esark.gasp.GaspSemg;

public class GameScreenEventLog extends Screen implements Input {
    // --- GRID CONSTANTS (8 cols x 16 rows = 128 max) ---
    private static final int COLS = 8;
    private static final int MAX_CAPACITY = 128;

    private static final int START_X = 55;
    private static final int START_Y = 165;
    private static final int BTN_W = 175;
    private static final int BTN_H = 100;
    private static final int SPACING_X = 30;
    private static final int SPACING_Y = 20;

    private GameScreenEvent gameScreenEvent = null;

    public GameScreenEventLog(Game game) {
        super(game);
        loadAssets();
    }

    private void loadAssets() {
        Graphics g = game.getGraphics();
        if (g == null) return;
        try {
            if (Assets.eventLogBackground == null) {
                Assets.eventLogBackground = g.newPixmap("eventLogBackground.png", Graphics.PixmapFormat.RGB565);
            }
            if (Assets.eventLogButtonJpeg == null) {
                Assets.eventLogButtonJpeg = g.newPixmap("eventLogButtonJpeg.jpg", Graphics.PixmapFormat.RGB565);
            }
            if (Assets.eventLogButtonBlue == null) {
                Assets.eventLogButtonBlue = g.newPixmap("eventLogButtonBlue.png", Graphics.PixmapFormat.RGB565);
            }
            if (Assets.eventLogButtonPurple == null) {
                Assets.eventLogButtonPurple = g.newPixmap("eventLogButtonPurple.png", Graphics.PixmapFormat.RGB565);
            }
        } catch (Exception e) {
            Log.e("EventLog", "Asset Loading Error: " + e.getMessage());
        }
    }

    @Override
    public void update(float deltaTime, Context context) {
        List<TouchEvent> touchEvents = game.getInput().getTouchEvents();
        if (touchEvents == null) return;

        int currentTotal;
        synchronized (timeStamp) {
            currentTotal = eventCount;
        }

        for (int i = 0; i < touchEvents.size(); i++) {
            TouchEvent event = touchEvents.get(i);
            if (event.type == TouchEvent.TOUCH_UP) {
                // 1. Navigation: Back to Live View
                if (event.x > 25 && event.x < 850 && event.y > 2510) {
                    game.setScreen(game.getStartScreen());
                    return;
                }
                // 2. Navigation: Reconnect Bluetooth (Fixed to prevent frozen waves)
                else if (event.x > 1300 && event.y > 2510) {
                    // Stop the current thread to prevent memory leaks/crashes
                    if (com.esark.framework.AndroidGame.mConnectedThread != null) {
                        com.esark.framework.AndroidGame.mConnectedThread.cancel();
                    }

                    // FIX: Use GaspSemg.class (the Activity) instead of AndroidGame.class
                    Intent intent2 = new Intent(context.getApplicationContext(), GaspSemg.class);

                    // Clear the stack so the app doesn't try to "resume" into a broken state
                    intent2.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    context.startActivity(intent2);
                    return;
                }

                // 3. Grid Selection Detection
                int limit = Math.min(currentTotal, MAX_CAPACITY);
                for (int j = 0; j < limit; j++) {
                    int row = j / COLS;
                    int col = j % COLS;
                    int x = START_X + col * (BTN_W + SPACING_X);
                    int y = START_Y + row * (BTN_H + SPACING_Y);

                    if (event.x > x && event.x < x + BTN_W && event.y > y && event.y < y + BTN_H) {
                        GameScreen gs = GameScreen.liveScreen;
                        if (gs != null) {
                            // selectedEventId is updated inside loadSpecificEvent
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

        if (Assets.eventLogBackground == null) loadAssets();

        if (Assets.eventLogBackground != null) {
            g.drawPortraitPixmap(Assets.eventLogBackground, 0, 0);
        }

        // Thread-safe copy of labels
        String[] localLabels = new String[MAX_CAPACITY];
        int[] localClass = new int[MAX_CAPACITY];
        int displayCount;

        synchronized (timeStamp) {
            displayCount = Math.min(eventCount, MAX_CAPACITY);
            for (int k = 0; k < displayCount; k++) {
                localLabels[k] = timeStamp[k];
                localClass[k] = GameScreen.eventClassification[k];
            }
        }

        if (displayCount == 0) {
            g.drawText("No Events Recorded", 600, 1400);
            return;
        }

        // DRAWING LOOP
        for (int i = 0; i < displayCount; i++) {
            int row = i / COLS;
            int col = i % COLS;
            int x = START_X + col * (BTN_W + SPACING_X);
            int y = START_Y + row * (BTN_H + SPACING_Y);

            if (y > 2500) break;

            // 1. Pick Button Pixmap based on Classification
            com.esark.framework.Pixmap btnPixmap;
            int classification = localClass[i];

            if (classification == 1) {
                btnPixmap = Assets.eventLogButtonBlue;   // False Positive
            } else if (classification == 2) {
                btnPixmap = Assets.eventLogButtonPurple; // False Negative
            } else {
                btnPixmap = Assets.eventLogButtonJpeg;   // True Positive / Default Green
            }

            if (btnPixmap != null) {
                g.drawEventLogButtonPixmap(btnPixmap, x, y);
            }

            // 2. Draw "Seconds Only" Timestamp
            String label = localLabels[i];
            if (label != null && label.contains(":")) {
                try {
                    String[] parts = label.split(":");
                    if (parts.length >= 2) {
                        int minutes = Integer.parseInt(parts[0]);
                        int seconds = Integer.parseInt(parts[1]);
                        int totalSeconds = (minutes * 60) + seconds;

                       // String finalLabel = totalSeconds + " s";
                        String finalLabel = String.format("%02d:%02d", minutes, seconds) + " m:s";

                        // Standardized text centering
                        g.drawSmallText(finalLabel, x + 10, y + 58);
                    }
                } catch (Exception e) {
                    g.drawSmallText(label, x + 10, y + 58);
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