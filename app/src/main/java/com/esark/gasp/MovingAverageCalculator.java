package com.esark.gasp;

public class MovingAverageCalculator {

    /**
     * Calculates the simple moving average of a data array for a given window size.
     *
     * @param data The input array of double values.
     * @param windowSize The number of elements to average in each window.
     * @return A new array of double values representing the moving averages.
     */
    public static double[] calculateMovingAverage(double[] data, int windowSize) {
        int dataLength = data.length;
        if (windowSize <= 0 || windowSize > dataLength) {
            throw new IllegalArgumentException("Window size must be greater than 0 and less than or equal to data length.");
        }

        // The output array will have dataLength - windowSize + 1 elements
        double[] movingAverages = new double[dataLength - windowSize + 1];
        double windowSum = 0.0;

        // Calculate the sum of the first window
        for (int i = 0; i < windowSize; i++) {
            windowSum += data[i];
        }

        // Calculate the first average
        movingAverages[0] = windowSum / windowSize;

        // Use a sliding window to calculate the rest of the averages efficiently
        for (int i = windowSize; i < dataLength; i++) {
            // Subtract the oldest element (which is leaving the window)
            windowSum -= data[i - windowSize];
            // Add the newest element (which is entering the window)
            windowSum += data[i];
            // Calculate and store the new average
            movingAverages[i - windowSize + 1] = windowSum / windowSize;
        }

        return movingAverages;
    }

    public static void main(String[] args) {
        double[] data = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10};
        int windowSize = 3;
        double[] averages = calculateMovingAverage(data, windowSize);

        System.out.println("Original Data: " + java.util.Arrays.toString(data));
        System.out.println("Window Size: " + windowSize);
        System.out.println("Moving Averages: " + java.util.Arrays.toString(averages));
        // Expected output for window size 3: [2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0]
    }
}

