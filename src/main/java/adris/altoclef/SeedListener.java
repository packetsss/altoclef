package adris.altoclef;

import kaptainwutax.seedcrackerx.api.SeedCrackerAPI;

/**
 * Simple class for listening to the SeedCrackerAPI world seed pushes
 */
public class SeedListener implements SeedCrackerAPI {

    private static Long seed = null;

    @Override
    public void pushWorldSeed(long l) {
        seed = l;
    }

    public static Long getSeed() {
        return seed;
    }

    public static void reset() {
        seed = null;
    }


}
