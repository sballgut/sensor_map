package com.example.sensorae.ui.d1;

/** D-2 implements this on the UI thread. No BLE listener or positioning ownership here. */
public interface DestinationSelectionTarget {
    void onDestinationSelected(Destination destination);
    void onSelectionCleared();
}
