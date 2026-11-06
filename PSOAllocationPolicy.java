package vmplacement;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.cloudbus.cloudsim.Host;
import org.cloudbus.cloudsim.Vm;
import org.cloudbus.cloudsim.VmAllocationPolicy;

/**
 * PSOAllocationPolicy.java
 * Particle Swarm Optimisation VM placement (Eq. 2 in the report).
 *
 *  - particle position z in R^n; decoded to a placement by rounding each
 *    component and clamping it to [0, |P|-1]
 *  - velocity: v = w*v + c1*r1*(pbest - z) + c2*r2*(gbest - z)
 *  - w = 0.7, c1 = c2 = 1.5, swarm = 60, 150 iterations
 *
 * Same interface as PSOAllocationPolicy: computePlacement() runs before the
 * simulation, allocateHostForVm() binds each VM to its pre-computed Host.
 */
public class PSOAllocationPolicy extends VmAllocationPolicy {

    private final List<Host> pmList;
    private final Map<String, Host> vmTable = new HashMap<String, Host>();   // vm uid -> host
    private final Map<Integer, Integer> plan = new HashMap<Integer, Integer>();   // vm id -> PM index (planned)
    private final Map<Integer, Integer> actual = new HashMap<Integer, Integer>(); // vm id -> PM index (created)
    private double[] convergence = new double[0];
    private double runtimeSeconds;

    public PSOAllocationPolicy(List<? extends Host> list) {
        super(list);
        this.pmList = new ArrayList<Host>(list);
    }

    /* ======================= optimisation ======================= */

    /** Runs the GA on the whole batch and stores the plan. Returns x (VM -> PM index). */
    public int[] computePlacement(List<? extends Vm> vms, long seed) {
        int n = vms.size();
        double[][] demand = new double[n][3];
        for (int i = 0; i < n; i++) {
            Vm vm = vms.get(i);
            demand[i][0] = vm.getNumberOfPes();
            demand[i][1] = vm.getRam();
            demand[i][2] = vm.getBw();
        }
        double[][] cap = new double[pmList.size()][3];
        for (int j = 0; j < cap.length; j++) {
            Host h = pmList.get(j);
            cap[j][0] = h.getNumberOfPes();
            cap[j][1] = h.getRam();
            cap[j][2] = h.getBw();
        }

        long t0 = System.nanoTime();
        int[] best = runPSO(n, cap.length, demand, cap, new Random(seed));
        runtimeSeconds = (System.nanoTime() - t0) / 1e9;

        plan.clear();
        for (int i = 0; i < n; i++) {
            plan.put(vms.get(i).getId(), best[i]);
        }
        return best;
    }

    private static int[] decode(double[] z, int m) {
        int[] x = new int[z.length];
        for (int i = 0; i < z.length; i++) {
            int v = (int) Math.round(z[i]);
            x[i] = Math.max(0, Math.min(m - 1, v));
        }
        return x;
    }

    private int[] runPSO(int n, int m, double[][] demand, double[][] cap, Random rnd) {
        final int S = Constants.PSO_SWARM;
        final int T = Constants.ITERATIONS;
        final double hi = m - 1;
        final double vmax = hi;                    // velocity clamp

        double[][] z = new double[S][n];
        double[][] v = new double[S][n];
        double[][] pbest = new double[S][];
        double[] pbestFit = new double[S];
        double[] gbest = null;
        double gbestFit = Double.MAX_VALUE;
        convergence = new double[T];

        for (int s = 0; s < S; s++) {
            for (int i = 0; i < n; i++) {
                z[s][i] = rnd.nextDouble() * hi;
                v[s][i] = (rnd.nextDouble() * 2 - 1);
            }
            double f = Metrics.fitness(decode(z[s], m), demand, cap);
            pbest[s] = z[s].clone();
            pbestFit[s] = f;
            if (f < gbestFit) {
                gbestFit = f;
                gbest = z[s].clone();
            }
        }

        for (int t = 0; t < T; t++) {
            for (int s = 0; s < S; s++) {
                for (int i = 0; i < n; i++) {
                    double r1 = rnd.nextDouble();
                    double r2 = rnd.nextDouble();
                    v[s][i] = Constants.PSO_W * v[s][i]
                            + Constants.PSO_C1 * r1 * (pbest[s][i] - z[s][i])
                            + Constants.PSO_C2 * r2 * (gbest[i] - z[s][i]);
                    v[s][i] = Math.max(-vmax, Math.min(vmax, v[s][i]));
                    z[s][i] = Math.max(0, Math.min(hi, z[s][i] + v[s][i]));
                }
                double f = Metrics.fitness(decode(z[s], m), demand, cap);
                if (f < pbestFit[s]) {
                    pbestFit[s] = f;
                    pbest[s] = z[s].clone();
                }
                if (f < gbestFit) {
                    gbestFit = f;
                    gbest = z[s].clone();
                }
            }
            convergence[t] = gbestFit;
        }
        return decode(gbest, m);
    }

    /* ======================= results for the runner ======================= */

    public double getRuntimeSeconds() { return runtimeSeconds; }
    public double[] getConvergence()  { return convergence; }
    public int getCreatedCount()      { return actual.size(); }

    /* ======================= CloudSim VmAllocationPolicy API ======================= */

    @Override
    public boolean allocateHostForVm(Vm vm) {
        if (vmTable.containsKey(vm.getUid())) {
            return false;
        }
        Integer planned = plan.get(vm.getId());
        if (planned != null && tryCreate(vm, planned)) {
            return true;
        }
        // planned PM missing or could not really take the VM -> first-fit fallback
        for (int j = 0; j < pmList.size(); j++) {
            if (tryCreate(vm, j)) {
                return true;
            }
        }
        return false;
    }

    // (no @Override: this overload only exists in newer CloudSim versions)
    public boolean allocateHostForVm(Vm vm, Host host) {
        if (host.vmCreate(vm)) {
            vmTable.put(vm.getUid(), host);
            actual.put(vm.getId(), pmList.indexOf(host));
            return true;
        }
        return false;
    }

    private boolean tryCreate(Vm vm, int pmIndex) {
        return allocateHostForVm(vm, pmList.get(pmIndex));
    }

    @Override
    public List<Map<String, Object>> optimizeAllocation(List<? extends Vm> vmList) {
        return null;   // single batch, no migration
    }

    @Override
    public void deallocateHostForVm(Vm vm) {
        Host host = vmTable.remove(vm.getUid());
        if (host != null) {
            host.vmDestroy(vm);
        }
    }

    @Override
    public Host getHost(Vm vm) {
        return vmTable.get(vm.getUid());
    }

    @Override
    public Host getHost(int vmId, int userId) {
        return vmTable.get(Vm.getUid(userId, vmId));
    }
}
