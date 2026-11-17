package com.example.sensorae.ui.d1;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Shared selection data. Coordinates are WGS84 degrees, never image pixels. */
public final class Destination {
    public final String id, name, category, description;
    public final Double latitude, longitude;
    public final boolean isDemo;
    public final List<String> aliases;

    public Destination(String id, String name, String category, String description,
                       Double latitude, Double longitude, boolean isDemo, List<String> aliases) {
        if (id == null || id.trim().isEmpty() || name == null || name.trim().isEmpty())
            throw new IllegalArgumentException("Destination ID and name are required");
        if ((latitude == null) != (longitude == null)
                || (latitude != null && (!Double.isFinite(latitude) || Math.abs(latitude) > 90
                || !Double.isFinite(longitude) || Math.abs(longitude) > 180)))
            throw new IllegalArgumentException("Both coordinates must be absent or valid WGS84 degrees");
        this.id = id; this.name = name; this.category = category == null ? "" : category;
        this.description = description == null ? "" : description;
        this.latitude = latitude; this.longitude = longitude; this.isDemo = isDemo;
        this.aliases = Collections.unmodifiableList(new ArrayList<>(aliases == null ? Collections.emptyList() : aliases));
    }

    public boolean hasCoordinates() { return latitude != null && longitude != null; }
}
