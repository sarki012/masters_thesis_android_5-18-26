package com.esark.gasp;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.util.Log;

import com.esark.framework.Game;
import com.esark.framework.Graphics;
import com.esark.framework.Screen;
import com.esark.framework.Graphics.PixmapFormat;

import java.io.IOException;
import java.util.UUID;

public class LoadingScreen extends Screen {
    private final String HC05_MAC = "98:D3:02:96:BA:26";
    private final UUID MY_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private boolean connectionStarted = false;
    private BluetoothAdapter btAdapter;

    public LoadingScreen(Game game) {
        super(game);
        btAdapter = BluetoothAdapter.getDefaultAdapter();
    }

    @Override
    public void update(float deltaTime, Context context) {
        Graphics g = game.getGraphics();

        // Load Background Assets
        Assets.laryngospasmBackgroundMain = g.newPixmap("gaspMainBackground.png", PixmapFormat.ARGB4444);

        // 2. Transition to GameScreen
        game.setScreen(new GameScreen(game));
    }



    @Override public void present(float deltaTime) {}
    @Override public void pause() {}
    @Override public void resume() {}
    @Override public void dispose() {}
}