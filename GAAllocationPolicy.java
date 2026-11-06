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
 * GAAllocationPolicy.java
 * Genetic Algorithm VM placement (Algorithm 1 in the report).
 *
 *  - chromosome  : int[n], gene i = index of the PM hosting VM i
 *  - population  : 60, random initialisation, 150 generations
 *  - selection   : tournament, k = 3 (lowest fitness wins)
 *  - crossover   : uniform, rate 0.85
 *  - mutation    : per-gene reassignment to a random PM, p = 0.15
 *  - elitism     : the best individual so far is copied unchanged into each
 *                  new generation (so "best fitness" never gets worse).
 *
 * The whole batch is optimised in computePlacement() BEFORE the simulation starts;
 * allocateHostForVm() then simply binds each VM to its pre-computed Host.
 */
public class GAAllocationPolicy extends VmAllocationPolicy {

    private final List<Host> pmList;
    private final Map<String, Host> vmTable = new HashMap<String, Host>();   // vm uid -> host
    private final Map<Integer, Integer> plan = new HashMap<Integer, Integer>();   // vm id -> PM index (planned)
    private final Map<Integer, Integer> actual = new HashMap<Integer, Integer>(); // vm id -> PM index (created)
    private double[] convergence = new double[0];
    private double runtimeSeconds;

    public GAAllocationPolicy(List<? extends Host> list) {
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
        int[] best = runGA(n, cap.length, demand, cap, new Random(seed));
        runtimeSeconds = (System.nanoTime() - t0) / 1e9;

        plan.clear();
        for (int i = 0; i < n; i++) {
            plan.put(vms.get(i).getId(), best[i]);
        }
        return best;
    }

    private int[] runGA(int n, int m, double[][] demand, double[][] cap, Random rnd) {
        final int N = Constants.GA_POPULATION;
        final int G = Constants.ITERATIONS;

        int[][] pop = new int[N][n];
        double[] fit = new double[N];
        for (int p = 0; p < N; p++) {
            for (int i = 0; i < n; i++) {
                pop[p][i] = rnd.nextInt(m);
            }
            fit[p] = Metrics.fitness(pop[p], demand, cap);
        }

        int[] best = null;
        double bestFit = Double.MAX_VALUE;
        convergence = new double[G];

        for (int g = 0; g < G; g++) {
            // remember the best individual of the current population
            for (int p = 0; p < N; p++) {
                if (fit[p] < bestFit) {
                    bestFit = fit[p];
                    best = pop[p].clone();
                }
            }
            convergence[g] = bestFit;

            int[][] next = new int[N][];
            int k = 0;
            next[k++] = best.clone();                       // elitism
            while (k < N) {
                int[] p1 = pop[tournament(fit, rnd)];
                int[] p2 = pop[tournament(fit, rnd)];
                int[] c1 = p1.clone();
                int[] c2 = p2.clone();

                if (rnd.nextDouble() < Constants.GA_CROSSOVER_RATE) {   // uniform crossover
                    for (int i = 0; i < n; i++) {
                        if (rnd.nextBoolean()) {
                            int tmp = c1[i];
                            c1[i] = c2[i];
                            c2[i] = tmp;
                        }
                    }
                }
                mutate(c1, m, rnd);
                mutate(c2, m, rnd);

                next[k++] = c1;
                if (k < N) next[k++] = c2;
            }
            pop = next;
            for (int p = 0; p < N; p++) {
                fit[p] = Metrics.fitness(pop[p], demand, cap);
            }
        }

        // final population may hold a better individual than the last remembered one
        for (int p = 0; p < N; p++) {
            if (fit[p] < bestFit) {
                bestFit = fit[p];
                best = pop[p].clone();
            }
        }
        convergence[G - 1] = bestFit;
        return best;
    }

    private int tournament(double[] fit, Random rnd) {
        int winner = rnd.nextInt(fit.length);
        for (int t = 1; t < Constants.GA_TOURNAMENT_K; t++) {
            int c = rnd.nextInt(fit.length);
            if (fit[c] < fit[winner]) winner = c;
        }
        return winner;
    }

    private void mutate(int[] c, int m, Random rnd) {
        for (int i = 0; i < c.length; i++) {
            if (rnd.nextDouble() < Constants.GA_MUTATION_RATE) {
                c[i] = rnd.nextInt(m);
            }
        }
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
