package online.mtradius.mtping;

/**
 * Pure state comparison shared by every Activity.
 *
 * An Activity below another screen remains alive after the preference changes.
 * It must rebuild not only when colors differ but ALSO when the selection
 * changes from "system" to "day" while both currently resolve to light;
 * otherwise the icon displays the old mode.
 */
final class AppearanceState {
    private AppearanceState(){}

    static boolean needsRebuild(String appliedMode, boolean appliedDark,
                                String latestMode, boolean latestDark) {
        if(appliedMode==null || latestMode==null)return true;
        return !appliedMode.equals(latestMode) || appliedDark!=latestDark;
    }
}
