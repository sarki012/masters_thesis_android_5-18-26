package com.esark.framework;

import android.content.Context;

public abstract class Screen {
    protected final Game game;
    public Screen(Game game) {
        this.game = game;
    }

    public abstract void update(float deltaTime, Context context);

    public abstract void present(float deltaTime);

    public abstract void pause();

    public abstract void resume();

    public abstract void dispose();
}