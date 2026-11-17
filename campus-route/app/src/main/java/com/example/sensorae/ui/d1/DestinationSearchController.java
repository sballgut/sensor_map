package com.example.sensorae.ui.d1;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Pure Java search/selection state; deliberately has no GPS, network or Android dependency. */
public final class DestinationSearchController {
    private List<Destination> destinations = Collections.emptyList();
    private String query = "";
    private Destination selected;
    private DestinationSelectionTarget target;

    public DestinationSearchController(List<Destination> values) { setDestinations(values); }

    public void setDestinations(List<Destination> values) {
        if (values == null) throw new IllegalArgumentException("Destinations are required");
        List<Destination> copy = new ArrayList<>(values);
        Set<String> ids = new HashSet<>();
        for (Destination d : copy)
            if (d == null || !ids.add(d.id)) throw new IllegalArgumentException("Non-null destinations need unique IDs");
        String previous = selected == null ? null : selected.id;
        destinations = Collections.unmodifiableList(copy);
        selected = find(previous);
        notifySelection();
    }

    public void setTarget(DestinationSelectionTarget value) {
        target = value;
        // A map that attaches late must show the same selection as the bottom sheet.
        notifySelection();
    }

    public String query() { return query; }
    public Destination selected() { return selected; }
    public List<Destination> destinations() { return destinations; }

    public void setQuery(String value) {
        String next = value == null ? "" : value;
        if (!next.equals(query)) {
            query = next;
            if (selected != null) { selected = null; notifySelection(); }
        }
    }

    public List<Destination> results() {
        String needle = normalize(query);
        List<Destination> found = new ArrayList<>();
        for (Destination d : destinations) {
            boolean matches = normalize(d.name).contains(needle);
            for (String alias : d.aliases) if (alias != null && normalize(alias).contains(needle)) matches = true;
            if (matches) found.add(d);
        }
        return Collections.unmodifiableList(found);
    }

    public boolean select(String id) {
        Destination d = find(id);
        if (d == null) return false;
        selected = d; notifySelection(); return true;
    }

    public void clearSelection() { selected = null; notifySelection(); }

    public void restore(String savedQuery, String savedId) {
        query = savedQuery == null ? "" : savedQuery;
        selected = find(savedId); notifySelection();
    }

    private Destination find(String id) {
        if (id != null) for (Destination d : destinations) if (d.id.equals(id)) return d;
        return null;
    }

    private void notifySelection() {
        if (target == null) return;
        if (selected == null) target.onSelectionCleared(); else target.onDestinationSelected(selected);
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }
}
