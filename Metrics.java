package vmplacement;

/**
 * Metrics.java
 * The five evaluation functions used by all three algorithms and by the final
 * analysis:
 *   1) activeServers   - number of non-empty PMs
 *   2) avgUtilisation  - mean utilisation over active PMs
 *   3) loadImbalance   - std. deviation of per-PM utilisation (active PMs)
 *   4) totalPower      - sum of rated power of active PMs
 *   5) fitness         - Eq. (1): active servers + LAMBDA * [infeasible]
 *
 * A placement is an int[] x where x[i] = index of the PM hosting VM i.
 * demand[i] = {cores, ramMB, bwMbps} of VM i; cap[j] = same triple for PM j.
 * Utilisation of a PM = mean of (CPU, RAM, BW) usage fractions.
 */
public final class Metrics {

    private Metrics() { }

    /* ---------- helpers ---------- */

    private static double[][] usage(int[] x, double[][] demand, int m) {
        double[][] used = new double[m][3];
        for (int i = 0; i < x.length; i++) {
            for (int d = 0; d < 3; d++) {
                used[x[i]][d] += demand[i][d];
            }
        }
        return used;
    }

    private static boolean[] activeMask(int[] x, int m) {
        boolean[] on = new boolean[m];
        for (int pm : x) {
            on[pm] = true;
        }
        return on;
    }

    /** True if no PM's CPU, RAM or bandwidth capacity is exceeded. */
    public static boolean isFeasible(int[] x, double[][] demand, double[][] cap) {
        double[][] used = usage(x, demand, cap.length);
        for (int j = 0; j < cap.length; j++) {
            for (int d = 0; d < 3; d++) {
                if (used[j][d] > cap[j][d] + 1e-9) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Utilisation of every active PM (inactive PMs are skipped). */
    public static double[] serverUtilisation(int[] x, double[][] demand, double[][] cap) {
        int m = cap.length;
        boolean[] on = activeMask(x, m);
        double[][] used = usage(x, demand, m);
        int count = 0;
        for (boolean b : on) {
            if (b) count++;
        }
        double[] u = new double[count];
        int k = 0;
        for (int j = 0; j < m; j++) {
            if (!on[j]) continue;
            double s = 0;
            for (int d = 0; d < 3; d++) {
                s += used[j][d] / cap[j][d];
            }
            u[k++] = s / 3.0;
        }
        return u;
    }

    /* ---------- the five evaluation functions ---------- */

    /** 1) Number of active (non-empty) servers. */
    public static int activeServers(int[] x, int m) {
        int c = 0;
        for (boolean b : activeMask(x, m)) {
            if (b) c++;
        }
        return c;
    }

    /** 2) Average resource utilisation across active servers. */
    public static double avgUtilisation(int[] x, double[][] demand, double[][] cap) {
        double[] u = serverUtilisation(x, demand, cap);
        if (u.length == 0) return 0;
        double s = 0;
        for (double v : u) s += v;
        return s / u.length;
    }

    /** 3) Load imbalance = (population) standard deviation of per-server utilisation. */
    public static double loadImbalance(int[] x, double[][] demand, double[][] cap) {
        double[] u = serverUtilisation(x, demand, cap);
        if (u.length <= 1) return 0;
        double mean = 0;
        for (double v : u) mean += v;
        mean /= u.length;
        double var = 0;
        for (double v : u) var += (v - mean) * (v - mean);
        return Math.sqrt(var / u.length);
    }

    /** 4) Total power draw: rated power of each active PM, zero for empty PMs. */
    public static double totalPower(int[] x, int m) {
        boolean[] on = activeMask(x, m);
        double p = 0;
        for (int j = 0; j < m; j++) {
            if (on[j]) p += Constants.PM_POWER[j];
        }
        return p;
    }

    /** 5) Fitness to be minimised, Eq. (1):  |active PMs| + LAMBDA * 1[infeasible]. */
    public static double fitness(int[] x, double[][] demand, double[][] cap) {
        double f = activeServers(x, cap.length);
        if (!isFeasible(x, demand, cap)) {
            f += Constants.LAMBDA;
        }
        return f;
    }
}
