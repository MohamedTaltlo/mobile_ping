package online.mtradius.mtping;

/**
 * Configurable one-tap Ping targets. The editable values are stored by the UI
 * in a dedicated preference file so they are independent from the hosts
 * selected for the current multi-ping session.
 */
final class PingPresets {
    static final String PREFERENCES="ping_presets";
    static final int COUNT=3;

    private static final String[] DEFAULTS={
            "10.0.0.1", "8.8.8.8", "www.google.com"
    };

    private PingPresets(){}

    static String defaultAt(int index) {
        if(index<0 || index>=COUNT)throw new IllegalArgumentException("Invalid preset");
        return DEFAULTS[index];
    }

    static String keyAt(int index) {
        if(index<0 || index>=COUNT)throw new IllegalArgumentException("Invalid preset");
        return "preset_"+index;
    }

    static String validate(String input) {
        String target=input==null?"":input.trim();
        if(!MonitorTargets.validHost(target))
            throw new IllegalArgumentException("أدخل عنوان IP أو اسم موقع صالح");
        return target;
    }

    static int fieldIndex(int lastFocused) {
        return lastFocused>=0 && lastFocused<COUNT?lastFocused:0;
    }
}
