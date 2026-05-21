package com.esark.gasp;

import android.graphics.Bitmap;
import android.util.LruCache;

public final class GetLruCache extends LruCache<String, Bitmap> {

    private static GetLruCache instance1;

    private GetLruCache(final int maxSize) {
        super(maxSize);
    }

    public static GetLruCache get() {
        if (instance1 == null) {
            // Get max available VM memory, exceeding this amount will throw an
            // OutOfMemory exception. Stored in kilobytes as LruCache takes an
            // int in its constructor.
            final int maxMemory = (int) (Runtime.getRuntime().maxMemory() / 1024);

            // Use 1/8th of the available memory for this memory cache.
            final int cacheSize = maxMemory / 7;        //Was 8

            instance1 = new GetLruCache(cacheSize);
        }
        return instance1;
    }
    @Override
    protected int sizeOf(String key, Bitmap bitmap) {
        // The cache size will be measured in kilobytes rather than
        // number of items.
        return bitmap.getByteCount() / 1024;
    }
/*
    @Override
    protected int sizeOf(final String key, final Bitmap value) {
        return value.getRowBytes() * value.getHeight();
    }
*/
}
