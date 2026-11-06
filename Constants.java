package vmplacement;

import java.util.Random;

/**
 * Constants.java
 * Static PM / VM catalogue (Tables I and II of the report), experiment
 * parameters (Section IV-C) and a helper that samples n VMs from the catalogue.
 *
 * Units used throughout (CloudSim native):
 *   CPU = cores (each core = MIPS_PER_PE MIPS), RAM = MB, BW = Mbps
 */
public final class Constants {

    private Constants() { }

    // ---------------- Physical machines (Table I) ----------------
    public static final String[] PM_NAMES = {"PM1", "PM2", "PM3", "PM4", "PM5"};
    public static final int[]    PM_CPU   = {16, 16, 32, 8, 32};                  // cores
    public static final int[]    PM_RAM   = {32768, 32768, 65536, 16384, 65536};  // MB  (32,32,64,16,64 GB)
    public static final long[]   PM_BW    = {10000, 10000, 20000, 5000, 20000};   // Mbps (10,10,20,5,20 Gbps)
    public static final double[] PM_POWER = {400, 400, 600, 250, 600};            // Watts when active

    // ---------------- VM flavours (Table II) ----------------
    public static final String[] VM_NAMES = {"VM1", "VM2", "VM3", "VM4", "VM5"};
    public static final int[]    VM_CPU   = {4, 2, 8, 6, 2};                      // cores
    public static final int[]    VM_RAM   = {8192, 4096, 16384, 8192, 8192};      // MB  (8,4,16,8,8 GB)
    public static final long[]   VM_BW    = {1000, 500, 2000, 1000, 500};         // Mbps (1,0.5,2,1,0.5 Gbps)

    // ---------------- CloudSim helpers ----------------
    public static final double MIPS_PER_PE  = 1000;
    public static final long   VM_IMAGE_SIZE = 10000;     // MB
    public static final long   HOST_STORAGE  = 1000000;   // MB

    // ---------------- Experiment parameters (Section IV-C) ----------------
    public static final int[] WORKLOAD_SIZES = {5, 10, 15, 20, 25};
    public static final int   RUNS_PER_WORKLOAD = 5;
    public static final int   ITERATIONS = 150;   // generations / iterations for all three
    public static final double LAMBDA = 50.0;     // infeasibility penalty

    // GA
    public static final int    GA_POPULATION = 60;
    public static final int    GA_TOURNAMENT_K = 3;
    public static final double GA_CROSSOVER_RATE = 0.85;
    public static final double GA_MUTATION_RATE = 0.15;

    // PSO
    public static final int    PSO_SWARM = 60;
    public static final double PSO_W  = 0.7;
    public static final double PSO_C1 = 1.5;
    public static final double PSO_C2 = 1.5;

    // ACO
    public static final int    ACO_ANTS = 40;
    public static final double ACO_ALPHA = 1.0;
    public static final double ACO_BETA  = 2.0;
    public static final double ACO_RHO   = 0.3;   // evaporation rate
    public static final double ACO_Q     = 10.0;  // deposit constant (deposit = Q / Fbest)

    /**
     * Samples n VM flavour indices uniformly (with replacement) from the 5 flavours.
     * Passing a Random with a fixed seed gives every algorithm the identical workload.
     */
    public static int[] sampleVms(int n, Random rnd) {
        int[] types = new int[n];
        for (int i = 0; i < n; i++) {
            types[i] = rnd.nextInt(VM_CPU.length);
        }
        return types;
    }
}
