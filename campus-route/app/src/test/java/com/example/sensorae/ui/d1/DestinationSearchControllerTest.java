package com.example.sensorae.ui.d1;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class DestinationSearchControllerTest {
    private DestinationSearchController controller() { return new DestinationSearchController(DemoDestinations.create()); }
    private static final class MapTarget implements DestinationSelectionTarget {
        Destination selected; int clears;
        public void onDestinationSelected(Destination d) { selected = d; }
        public void onSelectionCleared() { selected = null; clears++; }
    }
    @Test public void searchesKoreanNamesAndAliasesWithoutWhitespaceOrCaseSensitivity() {
        DestinationSearchController c = controller();
        c.setQuery("학 생 회 관"); assertEquals("demo-student-center", c.results().get(0).id);
        c.setQuery("중앙도서관"); assertEquals("demo-library", c.results().get(0).id);
        c.setQuery("ＬＩＢＲＡＲＹ"); assertEquals("demo-library", c.results().get(0).id);
        c.setQuery("존재하지않는장소"); assertTrue(c.results().isEmpty());
    }
    @Test public void blankQueryShowsWholeCatalogAndAllFixturesHaveNoInventedCoordinates() {
        DestinationSearchController c = controller(); c.setQuery("   ");
        assertEquals(6, c.results().size());
        for (Destination d : c.results()) { assertTrue(d.isDemo); assertTrue(d.id.startsWith("demo-")); assertFalse(d.hasCoordinates()); }
    }
    @Test public void selectionAndCancellationReachMapWithTheSameDestinationId() {
        DestinationSearchController c = controller(); MapTarget target = new MapTarget(); c.setTarget(target);
        assertTrue(c.select("demo-library")); assertEquals(c.selected().id, target.selected.id);
        c.clearSelection(); assertNull(c.selected()); assertNull(target.selected);
    }
    @Test public void editingSearchClearsOldSelectionSoMapCannotKeepStaleBuilding() {
        DestinationSearchController c = controller(); MapTarget target = new MapTarget(); c.setTarget(target);
        c.select("demo-library"); c.setQuery("공학");
        assertNull(c.selected()); assertNull(target.selected); assertEquals("demo-engineering", c.results().get(0).id);
    }
    @Test public void invalidMapSelectionDoesNotChangeExistingDestination() {
        DestinationSearchController c = controller(); c.select("demo-library");
        assertFalse(c.select("unregistered-building")); assertEquals("demo-library", c.selected().id);
    }
    @Test public void mapAttachingLateReceivesExistingSelectionAndCanBeDetached() {
        DestinationSearchController c = controller(); c.select("demo-library");
        MapTarget first = new MapTarget(); c.setTarget(first); assertEquals("demo-library", first.selected.id);
        c.setTarget(null); c.select("demo-gym"); assertEquals("demo-library", first.selected.id);
        MapTarget second = new MapTarget(); c.setTarget(second); assertEquals("demo-gym", second.selected.id);
    }
    @Test public void restoredSelectionIsValidatedAgainstCurrentCatalog() {
        DestinationSearchController c = controller(); MapTarget target = new MapTarget(); c.setTarget(target);
        c.restore("도서", "demo-library"); assertEquals("도서", c.query()); assertEquals("demo-library", target.selected.id);
        c.restore("도서", "deleted-destination"); assertNull(c.selected()); assertNull(target.selected);
    }
    @Test public void catalogReplacementClearsMissingSelectionAndDefensivelyCopies() {
        DestinationSearchController c = controller(); MapTarget target = new MapTarget(); c.setTarget(target); c.select("demo-library");
        List<Destination> replacement = new ArrayList<>(Collections.singletonList(DemoDestinations.create().get(1)));
        c.setDestinations(replacement); replacement.clear();
        assertEquals(1, c.destinations().size()); assertNull(c.selected()); assertNull(target.selected);
    }
    @Test public void catalogRefreshUsesUpdatedSelectedObject() {
        DestinationSearchController c = controller(); MapTarget target = new MapTarget(); c.setTarget(target); c.select("demo-library");
        Destination updated = new Destination("demo-library", "새 도서관 예시", "", "", null, null, true, Collections.emptyList());
        c.setDestinations(Collections.singletonList(updated)); assertSame(updated, c.selected()); assertSame(updated, target.selected);
    }
    @Test(expected=IllegalArgumentException.class) public void duplicateIdsCannotMakeSelectionAmbiguous() {
        Destination d = DemoDestinations.create().get(0); new DestinationSearchController(Arrays.asList(d, d));
    }
    @Test(expected=IllegalArgumentException.class) public void coordinatePairsCannotBeHalfPresent() {
        new Destination("test", "test", "", "", 36.0, null, false, Collections.emptyList());
    }
    @Test(expected=IllegalArgumentException.class) public void nonFiniteCoordinatesAreRejected() {
        new Destination("test", "test", "", "", Double.NaN, 127.0, false, Collections.emptyList());
    }
}
