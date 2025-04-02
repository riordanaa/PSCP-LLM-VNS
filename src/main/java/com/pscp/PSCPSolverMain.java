package com.pscp;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.nio.file.*;
import java.text.SimpleDateFormat;

/**
 * Main program for testing and benchmarking the PSCP Variable Neighborhood Search implementation.
 * Features:
 * - Efficient file I/O
 * - VNS solver execution with configurable parameters
 * - Performance metrics reporting
 * - Benchmarking capabilities
 * - Optimized thread and memory usage
 */
public class PSCPSolverMain {
    // Default parameters
    private static final int DEFAULT_MAX_ITERATIONS = 100000;
    private static final long DEFAULT_TIME_LIMIT_MS = 120000; // 2 minutes
    private static final double DEFAULT_COVERAGE_RATIO = 0.90; // 90% coverage
    private static final boolean DEFAULT_VERBOSE = false;
    
    // Execution modes
    private static final String MODE_SOLVE = "solve";
    private static final String MODE_BENCHMARK = "benchmark";
    private static final String MODE_MULTISTART = "multistart";
    
    /**
     * Main entry point for the PSCP solver program.
     */
    public static void main(String[] args) {
        try {
            // Parse command line arguments
            CommandLineOptions options = parseCommandLineArgs(args);
            
            // Execute in the appropriate mode
            switch (options.mode) {
                case MODE_SOLVE:
                    solveSingleInstance(options);
                    break;
                case MODE_BENCHMARK:
                    runBenchmark(options);
                    break;
                case MODE_MULTISTART:
                    runMultiStartSolver(options);
                    break;
                default:
                    System.err.println("Unknown mode: " + options.mode);
                    printUsage();
                    System.exit(1);
            }
            
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
            printUsage();
            System.exit(1);
        }
    }
    
    /**
     * Solves a single PSCP instance.
     */
    private static void solveSingleInstance(CommandLineOptions options) throws IOException {
        System.out.println("Loading instance: " + options.instanceFile);
        
        // Configure JVM for optimal performance
        configureJVM(options);
        
        // Load the instance
        long startLoadTime = System.currentTimeMillis();
        PSCPInstance instance = PSCPFileIO.loadInstance(
            options.instanceFile, options.coverageRatio);
        long loadTime = System.currentTimeMillis() - startLoadTime;
        
        System.out.println("Instance loaded in " + loadTime + " ms");
        System.out.println(PSCPFileIO.getInstanceSummary(instance));
        
        // Create and run the solver
        System.out.println("Solving with VNS...");
        System.out.println("Parameters: maxIterations=" + options.maxIterations + 
                          ", timeLimit=" + options.timeLimit + "ms, verbose=" + options.verbose);
        
        VNSForPSCP solver = new VNSForPSCP(
            instance, options.maxIterations, options.timeLimit, options.verbose);
        
        long startSolveTime = System.currentTimeMillis();
        Solution solution = solver.solve();
        long solveTime = System.currentTimeMillis() - startSolveTime;
        
        // Report results
        System.out.println("\nSolution found in " + solveTime + " ms");
        System.out.println(PSCPFileIO.getSolutionSummary(solution));
        
        // Save the solution if requested
        if (options.outputFile != null) {
            PSCPFileIO.saveSolution(solution, options.outputFile);
            System.out.println("Solution saved to: " + options.outputFile);
        }
    }
    
    /**
     * Runs a benchmark comparing VNS performance across multiple instances.
     */
    private static void runBenchmark(CommandLineOptions options) throws IOException {
        System.out.println("Running benchmark on directory: " + options.instanceDirectory);
        
        // Validate directory
        File directory = new File(options.instanceDirectory);
        if (!directory.isDirectory()) {
            throw new IllegalArgumentException("Not a valid directory: " + options.instanceDirectory);
        }
        
        // Configure JVM for optimal performance
        configureJVM(options);
        
        // Collect instance files
        List<File> instanceFiles = new ArrayList<>();
        Files.walk(directory.toPath(), 1)
            .filter(Files::isRegularFile)
            .filter(path -> path.toString().endsWith(".scp") || 
                           path.toString().endsWith(".txt"))
            .forEach(path -> instanceFiles.add(path.toFile()));
        
        if (instanceFiles.isEmpty()) {
            throw new IllegalArgumentException("No instance files found in directory");
        }
        
        System.out.println("Found " + instanceFiles.size() + " instance files");
        
        // Create output directory if needed
        if (options.outputDirectory != null) {
            File outputDir = new File(options.outputDirectory);
            if (!outputDir.exists()) {
                outputDir.mkdirs();
            }
        }
        
        // Create thread pool for parallel execution if multiple cores available
        int numThreads = Math.min(Runtime.getRuntime().availableProcessors(), options.threads);
        ExecutorService executor = null;
        
        if (numThreads > 1 && options.parallelBenchmark) {
            executor = Executors.newFixedThreadPool(numThreads);
            System.out.println("Using " + numThreads + " threads for parallel benchmark");
        }
        
        // Results collection
        List<BenchmarkResult> results = Collections.synchronizedList(new ArrayList<>());
        
        // Process each instance
        if (executor != null) {
            // Parallel execution
            List<Future<?>> futures = new ArrayList<>();
            
            for (File file : instanceFiles) {
                futures.add(executor.submit(() -> {
                    try {
                        BenchmarkResult result = solveInstanceForBenchmark(file, options);
                        results.add(result);
                        
                        // Print progress
                        synchronized(System.out) {
                            System.out.println("Completed: " + file.getName() + " - " + 
                                              result.solutionSize + " subsets, " + 
                                              result.solutionTime + " ms");
                        }
                        
                    } catch (Exception e) {
                        synchronized(System.err) {
                            System.err.println("Error processing " + file.getName() + ": " + e.getMessage());
                            e.printStackTrace();
                        }
                    }
                }));
            }
            
            // Wait for all tasks to complete
            for (Future<?> future : futures) {
                try {
                    future.get();
                } catch (Exception e) {
                    System.err.println("Error in benchmark task: " + e.getMessage());
                }
            }
            
            // Shutdown the executor
            executor.shutdown();
            
        } else {
            // Sequential execution
            for (File file : instanceFiles) {
                try {
                    BenchmarkResult result = solveInstanceForBenchmark(file, options);
                    results.add(result);
                    
                    System.out.println("Completed: " + file.getName() + " - " + 
                                      result.solutionSize + " subsets, " + 
                                      result.solutionTime + " ms");
                    
                } catch (Exception e) {
                    System.err.println("Error processing " + file.getName() + ": " + e.getMessage());
                    e.printStackTrace();
                }
            }
        }
        
        // Sort results by instance name
        results.sort(Comparator.comparing(r -> r.instanceName));
        
        // Generate benchmark report
        System.out.println("\nBenchmark Report:");
        System.out.println("==================================================");
        System.out.println(String.format("%-20s %-10s %-10s %-10s", 
                                        "Instance", "Subsets", "Elements", "Time (ms)"));
        System.out.println("--------------------------------------------------");
        
        for (BenchmarkResult result : results) {
            System.out.println(String.format("%-20s %-10d %-10d %-10d", 
                                           result.instanceName, 
                                           result.solutionSize, 
                                           result.coveredElements,
                                           result.solutionTime));
        }
        
        System.out.println("==================================================");
        
        // Calculate averages
        double avgTime = results.stream().mapToLong(r -> r.solutionTime).average().orElse(0);
        double avgSize = results.stream().mapToInt(r -> r.solutionSize).average().orElse(0);
        
        System.out.println("Average solution size: " + String.format("%.2f", avgSize) + " subsets");
        System.out.println("Average solution time: " + String.format("%.2f", avgTime) + " ms");
        
        // Save detailed results if requested
        if (options.outputDirectory != null) {
            saveBenchmarkResults(results, options);
        }
    }
    
    /**
     * Runs the VNS solver in multi-start mode.
     */
    private static void runMultiStartSolver(CommandLineOptions options) throws IOException {
        System.out.println("Running multi-start VNS on: " + options.instanceFile);
        
        // Configure JVM for optimal performance
        configureJVM(options);
        
        // Load the instance
        PSCPInstance instance = PSCPFileIO.loadInstance(
            options.instanceFile, options.coverageRatio);
        
        System.out.println(PSCPFileIO.getInstanceSummary(instance));
        
        // Create and run the solver
        System.out.println("Solving with Multi-Start VNS...");
        System.out.println("Parameters: numStarts=" + options.numStarts + 
                          ", iterationsPerStart=" + options.iterationsPerStart + 
                          ", timeLimit=" + options.timeLimit + "ms");
        
        VNSForPSCP solver = new VNSForPSCP(
            instance, options.maxIterations, options.timeLimit, options.verbose);
        
        long startSolveTime = System.currentTimeMillis();
        Solution solution = solver.solveMultiStart(options.numStarts, options.iterationsPerStart);
        long solveTime = System.currentTimeMillis() - startSolveTime;
        
        // Report results
        System.out.println("\nSolution found in " + solveTime + " ms");
        System.out.println(PSCPFileIO.getSolutionSummary(solution));
        
        // Save the solution if requested
        if (options.outputFile != null) {
            PSCPFileIO.saveSolution(solution, options.outputFile);
            System.out.println("Solution saved to: " + options.outputFile);
        }
    }
    
    /**
     * Solves a single instance for benchmarking purposes.
     */
    private static BenchmarkResult solveInstanceForBenchmark(File instanceFile, 
                                                         CommandLineOptions options) throws IOException {
        // Load instance
        PSCPInstance instance = PSCPFileIO.loadInstance(
            instanceFile.getPath(), options.coverageRatio);
        
        // Create and run solver
        VNSForPSCP solver = new VNSForPSCP(
            instance, options.maxIterations, options.timeLimit, false);
        
        long startTime = System.currentTimeMillis();
        Solution solution = solver.solve();
        long solutionTime = System.currentTimeMillis() - startTime;
        
        // Save solution if requested
        if (options.outputDirectory != null) {
            String outputFileName = instanceFile.getName().replaceAll("\\.[^.]+$", "") + "_solution.txt";
            File outputFile = new File(options.outputDirectory, outputFileName);
            PSCPFileIO.saveSolution(solution, outputFile.getPath());
        }
        
        // Create and return benchmark result
        return new BenchmarkResult(
            instanceFile.getName(),
            solution.getSolutionSize(),
            solution.getTotalCoveredElements(),
            instance.getNumElements(),
            instance.getMinCoverageRequired(),
            solutionTime
        );
    }
    
    /**
     * Compares performance with other algorithms or configurations.
     */
    public static void compareWithOtherApproaches(String instanceFile, double coverageRatio,
                                               boolean useMultiStart, boolean useShaking) throws IOException {
        // Load instance
        PSCPInstance instance = PSCPFileIO.loadInstance(instanceFile, coverageRatio);
        
        System.out.println("Comparing VNS configurations on: " + instanceFile);
        System.out.println(PSCPFileIO.getInstanceSummary(instance));
        
        // Prepare configuration variants
        int maxIterations = 100000;
        long timeLimit = 120000; // 2 minutes
        
        // Standard VNS
        VNSForPSCP standardVNS = new VNSForPSCP(instance, maxIterations, timeLimit, false);
        
        // Multi-start VNS
        VNSForPSCP multiStartVNS = new VNSForPSCP(instance, maxIterations, timeLimit, false);
        
        // VNS with shaking
        VNSForPSCP shakingVNS = new VNSForPSCP(instance, maxIterations, timeLimit, false);
        
        // Run and measure each configuration
        System.out.println("\nRunning Standard VNS...");
        long standardStart = System.currentTimeMillis();
        Solution standardSolution = standardVNS.solve();
        long standardTime = System.currentTimeMillis() - standardStart;
        
        System.out.println("Running VNS with Shaking...");
        long shakingStart = System.currentTimeMillis();
        Solution shakingSolution = shakingVNS.solveWithShaking(500, 3);
        long shakingTime = System.currentTimeMillis() - shakingStart;
        
        System.out.println("Running Multi-Start VNS...");
        long multiStartStart = System.currentTimeMillis();
        Solution multiStartSolution = multiStartVNS.solveMultiStart(5, 20000);
        long multiStartTime = System.currentTimeMillis() - multiStartStart;
        
        // Report results
        System.out.println("\nComparison Results:");
        System.out.println("==================================================");
        System.out.println(String.format("%-20s %-12s %-12s %-12s", 
                                        "Configuration", "Subsets", "Coverage", "Time (ms)"));
        System.out.println("--------------------------------------------------");
        
        System.out.println(String.format("%-20s %-12d %-12d %-12d", 
                                       "Standard VNS", 
                                       standardSolution.getSolutionSize(),
                                       standardSolution.getTotalCoveredElements(),
                                       standardTime));
        
        System.out.println(String.format("%-20s %-12d %-12d %-12d", 
                                       "VNS with Shaking", 
                                       shakingSolution.getSolutionSize(),
                                       shakingSolution.getTotalCoveredElements(),
                                       shakingTime));
        
        System.out.println(String.format("%-20s %-12d %-12d %-12d", 
                                       "Multi-Start VNS", 
                                       multiStartSolution.getSolutionSize(),
                                       multiStartSolution.getTotalCoveredElements(),
                                       multiStartTime));
        
        System.out.println("==================================================");
    }
    
    /**
     * Configures the JVM for optimal performance.
     */
    private static void configureJVM(CommandLineOptions options) {
        // Set GC parameters if optimization is requested
        if (options.optimizeGC) {
            // Suggest G1 garbage collector for better performance
            if (System.getProperty("java.gc.collector") == null) {
                System.setProperty("java.gc.collector", "G1");
            }
            
            // Set larger young generation size to reduce GC frequency
            if (System.getProperty("java.gc.young.size") == null) {
                System.setProperty("java.gc.young.size", "512m");
            }
        }
        
        // Pre-allocate memory if benchmark will use a lot of instances
        if (options.mode.equals(MODE_BENCHMARK) && options.preAllocateMemory) {
            // Try to allocate up to 70% of available memory
            Runtime runtime = Runtime.getRuntime();
            long maxMemory = runtime.maxMemory();
            long desiredMemory = (long)(maxMemory * 0.7);
            
            // Create and discard a large array to force memory allocation
            try {
                byte[] prealloc = new byte[(int)Math.min(desiredMemory, Integer.MAX_VALUE)];
                // Touch the array to ensure it's allocated
                for (int i = 0; i < prealloc.length; i += 4096) {
                    prealloc[i] = 1;
                }
                // Let it be garbage collected
                prealloc = null;
                
                // Force a GC to compact memory
                System.gc();
                
                if (options.verbose) {
                    System.out.println("Pre-allocated memory: " + formatMemory(desiredMemory));
                }
            } catch (OutOfMemoryError e) {
                System.out.println("Memory pre-allocation failed - continuing with default allocation");
            }
        }
        
        // Report memory status if verbose
        if (options.verbose) {
            Runtime runtime = Runtime.getRuntime();
            System.out.println("Memory status: max=" + formatMemory(runtime.maxMemory()) + 
                              ", allocated=" + formatMemory(runtime.totalMemory()) + 
                              ", free=" + formatMemory(runtime.freeMemory()));
        }
    }
    
    /**
     * Saves benchmark results to a CSV file.
     */
    private static void saveBenchmarkResults(List<BenchmarkResult> results, 
                                         CommandLineOptions options) throws IOException {
        // Create timestamp for unique filenames
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        File outputFile = new File(options.outputDirectory, "benchmark_results_" + timestamp + ".csv");
        
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(outputFile))) {
            // Write header
            writer.write("Instance,SolutionSize,CoveredElements,TotalElements,MinRequired,SolutionTime");
            writer.newLine();
            
            // Write results
            for (BenchmarkResult result : results) {
                writer.write(String.format("%s,%d,%d,%d,%d,%d", 
                                         result.instanceName,
                                         result.solutionSize,
                                         result.coveredElements,
                                         result.totalElements,
                                         result.minRequired,
                                         result.solutionTime));
                writer.newLine();
            }
        }
        
        System.out.println("Benchmark results saved to: " + outputFile.getPath());
    }
    
    /**
     * Parses command line arguments into options.
     */
    private static CommandLineOptions parseCommandLineArgs(String[] args) {
        CommandLineOptions options = new CommandLineOptions();
        
        if (args.length < 1) {
            printUsage();
            System.exit(1);
        }
        
        // Parse the execution mode
        options.mode = args[0].toLowerCase();
        
        // Parse remaining arguments
        for (int i = 1; i < args.length; i++) {
            String arg = args[i];
            
            if (arg.equals("-f") || arg.equals("--file")) {
                if (i + 1 < args.length) {
                    options.instanceFile = args[++i];
                }
            } else if (arg.equals("-d") || arg.equals("--directory")) {
                if (i + 1 < args.length) {
                    options.instanceDirectory = args[++i];
                }
            } else if (arg.equals("-o") || arg.equals("--output")) {
                if (i + 1 < args.length) {
                    options.outputFile = args[++i];
                }
            } else if (arg.equals("-od") || arg.equals("--output-dir")) {
                if (i + 1 < args.length) {
                    options.outputDirectory = args[++i];
                }
            } else if (arg.equals("-c") || arg.equals("--coverage")) {
                if (i + 1 < args.length) {
                    options.coverageRatio = Double.parseDouble(args[++i]);
                }
            } else if (arg.equals("-i") || arg.equals("--iterations")) {
                if (i + 1 < args.length) {
                    options.maxIterations = Integer.parseInt(args[++i]);
                }
            } else if (arg.equals("-t") || arg.equals("--time")) {
                if (i + 1 < args.length) {
                    options.timeLimit = Long.parseLong(args[++i]);
                }
            } else if (arg.equals("-v") || arg.equals("--verbose")) {
                options.verbose = true;
            } else if (arg.equals("-p") || arg.equals("--parallel")) {
                options.parallelBenchmark = true;
            } else if (arg.equals("--threads")) {
                if (i + 1 < args.length) {
                    options.threads = Integer.parseInt(args[++i]);
                }
            } else if (arg.equals("--gc-optimize")) {
                options.optimizeGC = true;
            } else if (arg.equals("--prealloc")) {
                options.preAllocateMemory = true;
            } else if (arg.equals("--starts")) {
                if (i + 1 < args.length) {
                    options.numStarts = Integer.parseInt(args[++i]);
                }
            } else if (arg.equals("--iterations-per-start")) {
                if (i + 1 < args.length) {
                    options.iterationsPerStart = Integer.parseInt(args[++i]);
                }
            } else if (arg.equals("-h") || arg.equals("--help")) {
                printUsage();
                System.exit(0);
            }
        }
        
        // Validate required options based on mode
        validateOptions(options);
        
        return options;
    }
    
    /**
     * Validates command line options based on the execution mode.
     */
    private static void validateOptions(CommandLineOptions options) {
        switch (options.mode) {
            case MODE_SOLVE:
                if (options.instanceFile == null) {
                    throw new IllegalArgumentException("Instance file must be specified for solve mode");
                }
                break;
                
            case MODE_BENCHMARK:
                if (options.instanceDirectory == null) {
                    throw new IllegalArgumentException("Instance directory must be specified for benchmark mode");
                }
                break;
                
            case MODE_MULTISTART:
                if (options.instanceFile == null) {
                    throw new IllegalArgumentException("Instance file must be specified for multistart mode");
                }
                if (options.numStarts <= 0) {
                    throw new IllegalArgumentException("Number of starts must be positive");
                }
                break;
                
            default:
                throw new IllegalArgumentException("Unknown mode: " + options.mode);
        }
    }
    
    /**
     * Prints usage information.
     */
    private static void printUsage() {
        System.out.println("PSCP Solver - Variable Neighborhood Search Implementation");
        System.out.println();
        System.out.println("Usage:");
        System.out.println("  solve -f <instance-file> [options]");
        System.out.println("  benchmark -d <instance-directory> [options]");
        System.out.println("  multistart -f <instance-file> --starts <num-starts> [options]");
        System.out.println();
        System.out.println("Common Options:");
        System.out.println("  -c, --coverage <ratio>       Coverage ratio (default: 0.90)");
        System.out.println("  -i, --iterations <num>       Maximum iterations (default: 100000)");
        System.out.println("  -t, --time <milliseconds>    Time limit in ms (default: 120000)");
        System.out.println("  -v, --verbose                Enable verbose output");
        System.out.println("  -o, --output <file>          Output file for solution");
        System.out.println("  -h, --help                   Show this help message");
        System.out.println();
        System.out.println("Benchmark Options:");
        System.out.println("  -od, --output-dir <dir>      Output directory for solutions");
        System.out.println("  -p, --parallel               Run benchmark in parallel");
        System.out.println("  --threads <num>              Number of threads (default: available processors)");
        System.out.println();
        System.out.println("MultiStart Options:");
        System.out.println("  --starts <num>               Number of starts (default: 10)");
        System.out.println("  --iterations-per-start <num> Iterations per start (default: 10000)");
        System.out.println();
        System.out.println("Advanced Options:");
        System.out.println("  --gc-optimize                Optimize garbage collection");
        System.out.println("  --prealloc                   Pre-allocate memory for large benchmarks");
    }
    
    /**
     * Formats memory size in bytes to a human-readable string.
     */
    private static String formatMemory(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        } else if (bytes < 1024 * 1024) {
            return String.format("%.2f KB", bytes / 1024.0);
        } else if (bytes < 1024 * 1024 * 1024) {
            return String.format("%.2f MB", bytes / (1024.0 * 1024.0));
        } else {
            return String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
        }
    }
    
    /**
     * Class to hold command line options.
     */
    private static class CommandLineOptions {
        // Execution mode
        String mode = MODE_SOLVE;
        
        // Input/output options
        String instanceFile = null;
        String instanceDirectory = null;
        String outputFile = null;
        String outputDirectory = null;
        
        // Algorithm parameters
        double coverageRatio = DEFAULT_COVERAGE_RATIO;
        int maxIterations = DEFAULT_MAX_ITERATIONS;
        long timeLimit = DEFAULT_TIME_LIMIT_MS;
        boolean verbose = DEFAULT_VERBOSE;
        
        // Benchmark options
        boolean parallelBenchmark = false;
        int threads = Runtime.getRuntime().availableProcessors();
        
        // MultiStart options
        int numStarts = 10;
        int iterationsPerStart = 10000;
        
        // Advanced options
        boolean optimizeGC = false;
        boolean preAllocateMemory = false;
    }
    
    /**
     * Class to hold benchmark result information.
     */
    private static class BenchmarkResult {
        final String instanceName;
        final int solutionSize;
        final int coveredElements;
        final int totalElements;
        final int minRequired;
        final long solutionTime;
        
        public BenchmarkResult(String instanceName, int solutionSize, int coveredElements, 
                              int totalElements, int minRequired, long solutionTime) {
            this.instanceName = instanceName;
            this.solutionSize = solutionSize;
            this.coveredElements = coveredElements;
            this.totalElements = totalElements;
            this.minRequired = minRequired;
            this.solutionTime = solutionTime;
        }
    }
}