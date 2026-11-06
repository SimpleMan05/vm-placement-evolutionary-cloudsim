# Comparative Analysis of Evolutionary Algorithms for VM Placement (CloudSim)

GA vs PSO vs ACO for placing virtual machines onto physical servers in a
simulated cloud data centre. Cloud Computing Lab project (Question 7),
BIT Mesra.

---

## 1. What this project does

A cloud provider owns a few physical machines (PMs). When a batch of VM
requests arrives, each VM must be assigned to one PM without exceeding that
PM's CPU, RAM or bandwidth. Packing VMs onto **as few PMs as possible** lets
the unused servers be switched off, which saves power and cooling. This is a
multi-dimensional bin-packing problem (NP-hard), so we use three
nature-inspired search methods instead of brute force:

| Algorithm | Idea | Expected behaviour (from the report) |
|-----------|------|--------------------------------------|
| **GA**  (Genetic Algorithm) | evolves a population of placements by selection, crossover, mutation | middle ground on speed and quality |
| **PSO** (Particle Swarm Optimisation) | particles move through the search space towards best-known positions | fastest, fewest servers, but uneven load |
| **ACO** (Ant Colony Optimisation) | ants build placements guided by pheromone and remaining capacity | most balanced load, about 10x slower |

All three use the **same fitness function**, the same PMs and the same VM
workloads, so the comparison is fair. They are compared on:

1. Number of active servers
2. Average resource utilisation
3. Load imbalance (std. deviation of per-server utilisation)
4. Execution time
5. Total power draw

### What use is it?
- It shows an operator which meta-heuristic fits which goal: quick
  consolidation (PSO), balanced load (ACO), or a safe default (GA).
- It is a reusable CloudSim framework: the three custom
  `VmAllocationPolicy` classes can be reused or extended (hybrid
  algorithms, NSGA-II, dynamic arrivals, bigger clusters).
- It generates CSV data for the report's tables and graphs (Table III,
  Figures 2-7).

---

## 2. Project files

```
vmplacement/
├── Constants.java            PM/VM specs (Tables I, II), algorithm parameters, VM sampler
├── Metrics.java              the 5 evaluation functions (active servers, utilisation,
│                             imbalance, power, fitness)
├── GAAllocationPolicy.java   Genetic Algorithm, extends VmAllocationPolicy
├── PSOAllocationPolicy.java  Particle Swarm Optimisation, extends VmAllocationPolicy
├── ACOAllocationPolicy.java  Ant Colony Optimisation, extends VmAllocationPolicy
├── SimulationRunner.java     main(): runs all 75 experiments and writes CSVs
└── README.md
```

All files declare `package vmplacement;`.

---

## 3. Requirements

- **JDK 8** (Temurin 8 works; same as the CloudSim lab)
- **CloudSim 5.0** (built from the `5.0` tag, Maven version `4.0`) plus its
  dependency **commons-math3**
- **IntelliJ IDEA** (any edition) or plain `javac`
- Windows, Linux or macOS

---

## 4. Setup

### Option A: inside your existing CloudSim 5 project (recommended)

This is the setup used in the lab: CloudSim `5.0` tag cloned and building in
IntelliJ with JDK 8.

1. Open your CloudSim project in IntelliJ. Confirm **File > Project
   Structure > Project SDK** is JDK 8.
2. Create a new package `vmplacement` under a module that already has
   CloudSim on its classpath, for example
   `modules/cloudsim-examples/src/main/java/vmplacement/`.
3. Copy all six `.java` files into that package.
4. Let Maven reload (right-click `pom.xml` > **Maven > Reload project**).
   Build with **Build > Rebuild Project**. There should be no errors.

### Option B: standalone Maven project

1. **File > New > Project > Maven**, JDK 8.
2. Put the six files in `src/main/java/vmplacement/`.
3. Add this to `pom.xml`:

```xml
<properties>
    <maven.compiler.source>8</maven.compiler.source>
    <maven.compiler.target>8</maven.compiler.target>
</properties>

<dependencies>
    <dependency>
        <groupId>org.cloudbus.cloudsim</groupId>
        <artifactId>cloudsim</artifactId>
        <version>4.0</version>
    </dependency>
    <dependency>
        <groupId>org.apache.commons</groupId>
        <artifactId>commons-math3</artifactId>
        <version>3.6.1</version>
    </dependency>
</dependencies>
```

4. Reload Maven. If the dependency does not resolve, use Option A.

### Option C: command line (no IDE)

```bash
# from the folder that CONTAINS the vmplacement/ folder
# cloudsim.jar and commons-math3.jar are the jars from your CloudSim build
javac -cp "cloudsim.jar;commons-math3-3.6.1.jar" -d out vmplacement/*.java
java  -cp "out;cloudsim.jar;commons-math3-3.6.1.jar" vmplacement.SimulationRunner
```

On Linux/macOS replace `;` with `:`.

---

## 5. How to run

1. Open `SimulationRunner.java`.
2. Click the green arrow next to `main` > **Run 'SimulationRunner.main()'**.
3. The console prints one line per run, for example:

```
GA  n=15 run=1  active=3 util=0.874 imb=0.212 time=0.318s power=1400W feasible=true
PSO n=15 run=1  active=2 util=0.990 imb=0.301 time=0.201s power=1000W feasible=true
ACO n=15 run=1  active=4 util=0.690 imb=0.112 time=1.870s power=1650W feasible=true
```

   and at the end the grand averages (the equivalent of Table III).

4. Total: 5 workload sizes (5, 10, 15, 20, 25 VMs) x 5 runs x 3
   algorithms = **75 runs**. Expect a minute or two on a normal laptop.

The working directory matters for where `results/` appears. In IntelliJ it is
the project root by default (Run > Edit Configurations > Working directory).

---

## 6. Output files (`./results/`)

| File | Contents | Use in report |
|------|----------|---------------|
| `results.csv` | one row per run: active servers, utilisation, imbalance, time, power, feasible, VMs created | raw data |
| `summary_by_size.csv` | averages per algorithm and VM count | Figures 3-7 |
| `summary_overall.csv` | grand average per algorithm | Table III |
| `convergence.csv` | best fitness per iteration (15 VMs, run 1) | Figure 2 |

Open the CSVs in Excel or plot them with Python/matplotlib.

---

## 7. How it works (short version)

1. `SimulationRunner` creates a CloudSim datacenter with the 5 PMs from
   Table I and a broker.
2. VMs are sampled from the 5 flavours in Table II using a **fixed seed per
   (VM count, run)**, so GA, PSO and ACO see the identical workload and
   every run is reproducible.
3. Each policy's `computePlacement()` optimises the whole batch **before**
   the simulation starts and times only the algorithm itself.
4. CloudSim then calls `allocateHostForVm()`, which binds each VM to its
   planned host (falling back to first-fit if CloudSim rejects it).
5. `Metrics` evaluates the final placement and results go to CSV.

**Fitness (Eq. 1):** `F(x) = number of active PMs + 50 * [placement infeasible]`

---

## 8. Changing the experiment

Everything tunable is in `Constants.java`:

- `WORKLOAD_SIZES`, `RUNS_PER_WORKLOAD`: experiment size
- `ITERATIONS`, `GA_POPULATION`, `PSO_SWARM`, `ACO_ANTS`: search effort
- `GA_*`, `PSO_*`, `ACO_*`: algorithm coefficients
- `PM_*` and `VM_*` arrays: server and VM specs
- `LAMBDA`: infeasibility penalty

---

## 9. Troubleshooting

| Problem | Fix |
|---------|-----|
| `package org.cloudbus.cloudsim does not exist` | CloudSim is not on the module's classpath. Use Option A, or check the Maven dependency. |
| `package org.apache.commons.math3... does not exist` | Add commons-math3 3.6.1 (see Option B). |
| `Unsupported class file major version` or similar | Project SDK or language level is not 8. Set both to 8. |
| `results/` is created in an unexpected folder | Set the run configuration's working directory. |
| Very noisy console output | `Log.disable()` is already called in `main`; remove other prints you added. |
| Results differ between machines | Fitness and placements are reproducible, but **execution times** depend on your CPU. Compare ratios, not absolute seconds. |

---

## 10. Notes and limitations

- The GA keeps the best individual of each generation (elitism). Remove the
  `next[k++] = best.clone();` line in `GAAllocationPolicy` for the plain
  Algorithm 1 version.
- Only 5 PMs, a single static batch, and a binary on/off power model are
  simulated (see the report's Limitations section).
- At 20-25 VMs total demand approaches cluster capacity, so GA and PSO may
  occasionally return infeasible placements. The `feasible` column in
  `results.csv` flags these.
- The report mentions CloudSim 3.0.3. If you ran on 5.0, state that in the
  report's software environment so it matches what you demonstrate.

---

## 11. References

Garey and Johnson (1979); Coello Coello (2002); Beloglazov et al. (2012);
Feller et al. (2011); Kennedy and Eberhart (1995); Dorigo et al. (1996);
Goldberg (1989); Mann (2015). Full list in the project report.

**Authors:** Alok Pandey, Soumyadeep Dutta, Ayush Rai. B.Tech CSE 7th Semester, BIT Mesra.
