# Ensure directories under src are set up for Java packages
mkdir -Force src\main\java\com\pscp\algorithm\neighborhoods
mkdir -Force src\main\java\com\pscp\core
mkdir -Force src\main\java\com\pscp\initialization
mkdir -Force src\main\java\com\pscp\io

# Create results directory to store output files
mkdir -Force results\benchmarks

# Your existing directories (data, bin, lib, test) are preserved as-is.

# Create a simple README (overwrite existing if you want)
@"
# PSCP Variable Neighborhood Search (VNS)

## Project Structure
- **src/**: Java source code
  - **main/java/com/pscp/**
    - **algorithm/**: VNS and neighborhood implementations
    - **core/**: Problem and solution structures
    - **initialization/**: Initial solution generation
    - **io/**: Efficient file handling
- **data/**: Benchmark instances (existing)
- **bin/**: Compiled binaries (existing)
- **lib/**: External dependencies (existing)
- **test/**: Unit tests (existing)
- **results/**: Outputs of runs (new)

## Usage
See `PSCPSolverMain.java` for usage details.
"@ | Out-File -Encoding utf8 -Force README.md

Write-Host "✅ Directory structure successfully created!" -ForegroundColor Green
