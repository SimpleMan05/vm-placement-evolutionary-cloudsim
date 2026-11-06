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
 * ACOAllocationPolicy.java
 * Ant Colony Optimisation VM placement (Eq. 3 in the report).
 *
 *  - pheromone matrix tau[i][j] for every (VM i, PM j) pair (2-D Java array)
 *  - 40 ants; each ant visits the VMs in order and picks a PM with probability
 *        p_ij = tau_ij^alpha * eta_ij^beta / sum_k( tau_ik^alpha * eta_ik^beta )
 *    where eta_ij = normalised remaining capacity of PM j (mean of CPU, RAM, BW)
 *    given the VMs this ant has already placed. alpha = 1, beta = 2.
 *  - evaporation rho = 0.3; the best ant of the iteration deposits Q / Fbest
 *    on every (VM, PM) edge of its path; 150 iterations.
 *
 * The (VM x PM) heuristic evaluation inside every ant's construction loop is
 * the nested loop that makes ACO roughly an order of magnitude slower than
 * GA / PSO.
 */
public class ACOAllocationPolicy extends VmAllocationPolicy {

    private final List<Host> pmList;
    private final Map<String, Host> vmTable = new HashMap<String, Host>();   // vm uid -> host
    private final Map<Integer, Integer> plan = new HashMap<Integer, Integer>();   // vm id -> PM index (planned)
    private final Map<Integer, Integer> actual = new HashMap<Integer, Integer>(); // vm id -> PM index (created)
    private double[] convergence = new double[0];
    private double runtimeSeconds;

    public ACOAllocationPolicy(List<? extends Host> list) {
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
        int[] best = runACO(n, cap.length, demand, cap, new Random(seed));
        runtimeSeconds = (System.nanoTime() - t0) / 1e9;

        plan.clear();
        for (int i = 0; i < n; i++) {
            plan.put(vms.get(i).getId(), best[i]);
        }
        return best;
    }

    private int[] runACO(int n, int m, double[][] demand, double[][] cap, Random rnd) {
        final int A = Constants.ACO_ANTS;
        final int T = Constants.ITERATIONS;
        final double TAU_MIN = 1e-6;

        double[][] tau = new double[n][m];
        for (double[] row : tau) java.util.Arrays.fill(row, 1.0);

        int[] gBest = null;
        double gBestFit = Double.MAX_VALUE;
        convergence = new double[T];

        for (int it = 0; it < T; it++) {
            int[] itBest = null;
            double itBestFit = Double.MAX_VALUE;

            for (int a = 0; a < A; a++) {
                int[] x = new int[n];
                double[][] used = new double[m][3];

                for (int i = 0; i < n; i++) {
                    double[] w = new double[m];
                    double sum = 0;
                    for (int j = 0; j < m; j++) {
                        double remaining = 0;
                        boolean fits = true;
                        for (int d = 0; d < 3; d++) {
                            remaining += Math.max(0, cap[j][d] - used[j][d]) / cap[j][d];
                            if (used[j][d] + demand[i][d] > cap[j][d]) fits = false;
                        }
                        remaining /= 3.0;
                        double eta = fits ? Math.max(remaining, 1e-3) : 1e-6;
                        w[j] = Math.pow(tau[i][j], Constants.ACO_ALPHA) * Math.pow(eta, Constants.ACO_BETA);
                        sum += w[j];
                    }

                    // roulette-wheel selection
                    int chosen = m - 1;
                    if (sum <= 0) {
                        chosen = rnd.nextInt(m);
                    } else {
                        double r = rnd.nextDouble() * sum;
                        double acc = 0;
                        for (int j = 0; j < m; j++) {
                            acc += w[j];
                            if (r <= acc) {
                                chosen = j;
                                break;
                            }
                        }
                    }
                    x[i] = chosen;
                    for (int d = 0; d < 3; d++) used[chosen][d] += demand[i][d];
                }

                double f = Metrics.fitness(x, demand, cap);
                if (f < itBestFit) {
                    itBestFit = f;
                    itBest = x;
                }
            }

            // evaporation
            for (int i = 0; i < n; i++) {
                for (int j = 0; j < m; j++) {
                    tau[i][j] = Math.max(TAU_MIN, tau[i][j] * (1.0 - Constants.ACO_RHO));
                }
            }
            // deposit by the best ant of this iteration
            double deposit = Constants.ACO_Q / itBestFit;
            for (int i = 0; i < n; i++) {
                tau[i][itBest[i]] += deposit;
            }

            if (itBestFit < gBestFit) {
                gBestFit = itBestFit;
                gBest = itBest.clone();
            }
            convergence[it] = gBestFit;
        }
        return gBest;
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
