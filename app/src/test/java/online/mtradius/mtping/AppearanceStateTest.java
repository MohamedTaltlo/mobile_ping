package online.mtradius.mtping;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Regressions for global theme propagation through retained Activity stacks:
 * Tools -> change appearance -> Android Back -> old MainActivity resumes.
 */
public class AppearanceStateTest {
    @Test public void nestedPageLightModeForcesStaleMainToRebuild() {
        assertTrue(AppearanceState.needsRebuild("dark",true,"light",false));
    }

    @Test public void nestedPageDarkModeForcesStaleMainToRebuild() {
        assertTrue(AppearanceState.needsRebuild("light",false,"dark",true));
    }

    @Test public void sameColorsStillRebuildWhenSelectorChanged() {
        // System happens to be light. The header icon must become a sun.
        assertTrue(AppearanceState.needsRebuild("system",false,"light",false));
        assertTrue(AppearanceState.needsRebuild("light",false,"system",false));
    }

    @Test public void systemAppearanceChangeRefreshesRetainedPage() {
        assertTrue(AppearanceState.needsRebuild("system",true,"system",false));
    }

    @Test public void unchangedPageDoesNotRecreateInLoop() {
        assertFalse(AppearanceState.needsRebuild("dark",true,"dark",true));
        assertFalse(AppearanceState.needsRebuild("system",false,"system",false));
    }

    @Test public void missingCaptureTriggersSafeRefresh() {
        assertTrue(AppearanceState.needsRebuild(null,true,"light",false));
    }
}
