package vmplacement;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

import org.cloudbus.cloudsim.CloudletSchedulerTimeShared;
import org.cloudbus.cloudsim.Datacenter;
import org.cloudbus.cloudsim.DatacenterBroker;
import org.cloudbus.cloudsim.DatacenterCharacteristics;
import org.cloudbus.cloudsim.Host;
import org.cloudbus.cloudsim.Log;
import org.cloudbus.cloudsim.Pe;
import org.cloudbus.cloudsim.Storage;
import org.cloudbus.cloudsim.Vm;
import org.cloudbus.cloudsim.VmAllocationPolicy;
import org.cloudbus.cloudsim.VmSchedulerTimeShared;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.provisioners.BwProvisionerSimple;
import org.cloudbus.cloudsim.provisioners.PeProvisionerSimple;
import org.cloudbus.cloudsim.provisioners.RamProvisionerSimple;

/**
 * SimulationRunner.java
 * Orchestrates the batch experiments of Section VI:
 *   5 workload sizes x 5 runs x 3 algorithms = 75 runs.
 * For every (VM count, run) pair the workload seed is fixed, so GA, PSO and ACO
 * all see the identical VM batch and are reproducible.
 *
 * Output (folder ./results):
 *   results.csv           one row per run (all five metrics)
 *   summary_by_size.csv   averages per (algorithm, VM count)  -> Figs 3-7
 *   summary_overall.csv   grand averages per algorithm        -> Table III
 *   convergence.csv       best fitness per iteration, 15 VMs, run 1 -> Fig 2
 */
public class SimulationRunner {

    private static final String[] ALGOS = {"GA", "PSO", "ACO"};

    /** One row of results. */
    private static class Row {
        String algo; int vms; int run;
        int active; double util; double imbalance; double timeSec; double power;
        boolean feasible; int created;
    }

    public static void main(String[] args) throws Exception {
        Log.disable();                                   // silence CloudSim's console logging
        new File("results").mkdirs();

        // JIT warm-up so the first timed run is not unfairly slow
        for (String a : ALGOS) {
            runOne(a, 10, 12345L, null);
        }

        List<Row> rows = new ArrayList<Row>();
        Map<String, double[]> convergence = new LinkedHashMap<String, double[]>();

        PrintWriter csv = new PrintWriter(new FileWriter("results/results.csv"));
        csv.println("algorithm,vms,run,active_servers,avg_utilisation,load_imbalance,time_s,power_w,feasible,vms_created");

        for (int n : Constants.WORKLOAD_SIZES) {
            for (int run = 1; run <= Constants.RUNS_PER_WORKLOAD; run++) {
                long seed = n * 1000L + run;             // fixed per (VM count, run index)
                for (String algo : ALGOS) {
                    double[][] convOut = new double[1][];
                    Row r = runOne(algo, n, seed, convOut);
                    r.run = run;
                    rows.add(r);
                    csv.println(String.format(Locale.US, "%s,%d,%d,%d,%.4f,%.4f,%.4f,%.1f,%b,%d",
                            r.algo, r.vms, r.run, r.active, r.util, r.imbalance,
                            r.timeSec, r.power, r.feasible, r.created));
                    if (n == 15 && run == 1) {
                        convergence.put(algo, convOut[0]);
                    }
                    System.out.println(String.format(Locale.US,
                            "%-3s n=%2d run=%d  active=%d util=%.3f imb=%.3f time=%.3fs power=%.0fW feasible=%b",
                            r.algo, r.vms, r.run, r.active, r.util, r.imbalance, r.timeSec, r.power, r.feasible));
                }
            }
        }
        csv.close();

        writeConvergence(convergence);
        writeSummaries(rows);
        System.out.println("\nDone. Results written to the ./results folder.");
    }

    /* ======================= one experiment ======================= */

    private static Row runOne(String algo, int n, long seed, double[][] convOut) throws Exception {
        CloudSim.init(1, Calendar.getInstance(), false);

        List<Host> hosts = createHosts();

        GAAllocationPolicy ga = null;
        PSOAllocationPolicy pso = null;
        ACOAllocationPolicy aco = null;
        VmAllocationPolicy policy;
        if (algo.equals("GA")) {
            ga = new GAAllocationPolicy(hosts);  policy = ga;
        } else if (algo.equals("PSO")) {
            pso = new PSOAllocationPolicy(hosts); policy = pso;
        } else {
            aco = new ACOAllocationPolicy(hosts); policy = aco;
        }

        createDatacenter("Datacenter_0", hosts, policy);
        DatacenterBroker broker = new DatacenterBroker("Broker_0");

        // identical workload for every algorithm: same seed -> same VM flavours
        int[] types = Constants.sampleVms(n, new Random(seed));
        List<Vm> vms = new ArrayList<Vm>();
        for (int i = 0; i < n; i++) {
            int t = types[i];
            vms.add(new Vm(i, broker.getId(), Constants.MIPS_PER_PE, Constants.VM_CPU[t],
                    Constants.VM_RAM[t], Constants.VM_BW[t], Constants.VM_IMAGE_SIZE,
                    "Xen", new CloudletSchedulerTimeShared()));
        }

        // ---- optimisation happens here, before CloudSim binds the VMs ----
        int[] x; double secs; double[] conv;
        if (ga != null)       { x = ga.computePlacement(vms, seed);  secs = ga.getRuntimeSeconds();  conv = ga.getConvergence(); }
        else if (pso != null) { x = pso.computePlacement(vms, seed); secs = pso.getRuntimeSeconds(); conv = pso.getConvergence(); }
        else                  { x = aco.computePlacement(vms, seed); secs = aco.getRuntimeSeconds(); conv = aco.getConvergence(); }

        broker.submitVmList(vms);
        CloudSim.startSimulation();
        CloudSim.stopSimulation();

        int created = (ga != null) ? ga.getCreatedCount()
                    : (pso != null) ? pso.getCreatedCount() : aco.getCreatedCount();

        // ---- metrics on the placement chosen by the algorithm ----
        double[][] demand = new double[n][3];
        for (int i = 0; i < n; i++) {
            demand[i][0] = Constants.VM_CPU[types[i]];
            demand[i][1] = Constants.VM_RAM[types[i]];
            demand[i][2] = Constants.VM_BW[types[i]];
        }
        double[][] cap = new double[Constants.PM_CPU.length][3];
        for (int j = 0; j < cap.length; j++) {
            cap[j][0] = Constants.PM_CPU[j];
            cap[j][1] = Constants.PM_RAM[j];
            cap[j][2] = Constants.PM_BW[j];
        }

        Row r = new Row();
        r.algo = algo;
        r.vms = n;
        r.active = Metrics.activeServers(x, cap.length);
        r.util = Metrics.avgUtilisation(x, demand, cap);
        r.imbalance = Metrics.loadImbalance(x, demand, cap);
        r.timeSec = secs;
        r.power = Metrics.totalPower(x, cap.length);
        r.feasible = Metrics.isFeasible(x, demand, cap);
        r.created = created;
        if (convOut != null) convOut[0] = conv;
        return r;
    }

    /* ======================= CloudSim set-up ======================= */

    private static List<Host> createHosts() {
        List<Host> list = new ArrayList<Host>();
        for (int j = 0; j < Constants.PM_CPU.length; j++) {
            List<Pe> pes = new ArrayList<Pe>();
            for (int c = 0; c < Constants.PM_CPU[j]; c++) {
                pes.add(new Pe(c, new PeProvisionerSimple(Constants.MIPS_PER_PE)));
            }
            list.add(new Host(j,
                    new RamProvisionerSimple(Constants.PM_RAM[j]),
                    new BwProvisionerSimple(Constants.PM_BW[j]),
                    Constants.HOST_STORAGE, pes, new VmSchedulerTimeShared(pes)));
        }
        return list;
    }

    private static Datacenter createDatacenter(String name, List<Host> hosts, VmAllocationPolicy policy)
            throws Exception {
        DatacenterCharacteristics ch = new DatacenterCharacteristics(
                "x86", "Linux", "Xen", hosts, 5.5, 3.0, 0.05, 0.001, 0.0);
        return new Datacenter(name, ch, policy, new LinkedList<Storage>(), 0);
    }

    /* ======================= output ======================= */

    private static void writeConvergence(Map<String, double[]> conv) throws IOException {
        PrintWriter w = new PrintWriter(new FileWriter("results/convergence.csv"));
        w.println("iteration,GA,PSO,ACO");
        for (int t = 0; t < Constants.ITERATIONS; t++) {
            w.println(String.format(Locale.US, "%d,%.2f,%.2f,%.2f", t + 1,
                    conv.get("GA")[t], conv.get("PSO")[t], conv.get("ACO")[t]));
        }
        w.close();
    }

    /** mean of {active, util, imbalance, time, power, infeasibleRuns}; vms < 0 means all sizes. */
    private static double[] mean(List<Row> rows, String algo, int vms) {
        double[] s = new double[6];
        int c = 0;
        for (Row r : rows) {
            if (!r.algo.equals(algo) || (vms >= 0 && r.vms != vms)) continue;
            s[0] += r.active; s[1] += r.util; s[2] += r.imbalance;
            s[3] += r.timeSec; s[4] += r.power;
            if (!r.feasible) s[5] += 1;
            c++;
        }
        for (int i = 0; i < 5; i++) s[i] /= c;
        return s;
    }

    private static void writeSummaries(List<Row> rows) throws IOException {
        PrintWriter bySize = new PrintWriter(new FileWriter("results/summary_by_size.csv"));
        bySize.println("algorithm,vms,active_servers,avg_utilisation,load_imbalance,time_s,power_w,infeasible_runs");
        for (String a : ALGOS) {
            for (int n : Constants.WORKLOAD_SIZES) {
                double[] m = mean(rows, a, n);
                bySize.println(String.format(Locale.US, "%s,%d,%.2f,%.4f,%.4f,%.4f,%.1f,%d",
                        a, n, m[0], m[1], m[2], m[3], m[4], (int) m[5]));
            }
        }
        bySize.close();

        PrintWriter all = new PrintWriter(new FileWriter("results/summary_overall.csv"));
        all.println("algorithm,active_servers,avg_utilisation,load_imbalance,time_s,power_w,infeasible_runs");
        System.out.println("\n=== Grand averages over all 25 runs per algorithm (Table III) ===");
        System.out.println("Algo  Active  Util    Imbal   Time(s)  Power(W)  InfeasibleRuns");
        for (String a : ALGOS) {
            double[] m = mean(rows, a, -1);
            all.println(String.format(Locale.US, "%s,%.2f,%.4f,%.4f,%.4f,%.1f,%d",
                    a, m[0], m[1], m[2], m[3], m[4], (int) m[5]));
            System.out.println(String.format(Locale.US, "%-4s  %.2f    %.4f  %.4f  %.4f   %.0f      %d",
                    a, m[0], m[1], m[2], m[3], m[4], (int) m[5]));
        }
        all.close();
    }
}
