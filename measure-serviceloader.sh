#!/bin/bash
# Phase 0 & 5: ServiceLoader measurement harness
# Usage: ./measure-serviceloader.sh [--with-flag]
#
# Builds the jpa-h2 integration test as fast-jar and measures:
# 1. Boot time (median of 10 runs)
# 2. ServiceLoader.load invocation count per service type
# 3. JFR profile of ServiceLoader-related costs
#
# Pass --with-flag to enable quarkus.bootstrap.service-loader-short-circuit=true

set -euo pipefail

APP_DIR="integration-tests/jpa-h2"
RUNS=10
WITH_FLAG=""

if [[ "${1:-}" == "--with-flag" ]]; then
    WITH_FLAG="-Dquarkus.bootstrap.service-loader-short-circuit=true"
    echo "=== Measuring WITH ServiceLoader short-circuit ==="
else
    echo "=== Measuring WITHOUT ServiceLoader short-circuit (baseline) ==="
fi

# Step 1: Build the app as fast-jar
echo "Building $APP_DIR as fast-jar..."
./mvnw package -f "$APP_DIR" -DskipTests $WITH_FLAG -Dquarkus.package.jar.type=fast-jar --batch-mode -q

TARGET_DIR="$APP_DIR/target/quarkus-app"
if [[ ! -f "$TARGET_DIR/quarkus-run.jar" ]]; then
    echo "ERROR: fast-jar not found at $TARGET_DIR/quarkus-run.jar"
    exit 1
fi

# Step 2: Measure boot time
echo ""
echo "Measuring boot time ($RUNS runs)..."
TIMES=()
for i in $(seq 1 $RUNS); do
    # Boot the app and measure time to "started in" message
    START=$(date +%s%N)
    timeout 30 java -jar "$TARGET_DIR/quarkus-run.jar" \
        -Dquarkus.http.port=0 \
        -Dquarkus.datasource.jdbc.url=jdbc:h2:mem:test \
        -Dquarkus.log.level=WARN \
        2>&1 | while IFS= read -r line; do
            if echo "$line" | grep -q "started in\|Listening on"; then
                END=$(date +%s%N)
                ELAPSED=$(( (END - START) / 1000000 ))
                echo "$ELAPSED"
                # Kill the app
                pkill -f "quarkus-run.jar" 2>/dev/null || true
                break
            fi
        done &
    BGPID=$!
    # Wait for measurement or timeout
    RESULT=$(wait $BGPID 2>/dev/null || echo "")
    if [[ -n "$RESULT" ]]; then
        TIMES+=($RESULT)
        echo "  Run $i: ${RESULT}ms"
    else
        echo "  Run $i: timeout/error"
        pkill -f "quarkus-run.jar" 2>/dev/null || true
    fi
    sleep 1
done

# Calculate median
if [[ ${#TIMES[@]} -gt 0 ]]; then
    IFS=$'\n' SORTED=($(sort -n <<<"${TIMES[*]}")); unset IFS
    MID=$(( ${#SORTED[@]} / 2 ))
    MEDIAN=${SORTED[$MID]}
    echo ""
    echo "Boot time median: ${MEDIAN}ms (${#TIMES[@]} successful runs)"
    echo "  Range: ${SORTED[0]}ms - ${SORTED[${#SORTED[@]}-1]}ms"
fi

# Step 3: Count ServiceLoader.load invocations via JFR
echo ""
echo "Collecting JFR profile (single run)..."
JFR_FILE="/tmp/quarkus-serviceloader-$$.jfr"
timeout 30 java \
    -XX:StartFlightRecording=filename="$JFR_FILE",settings=profile,dumponexit=true \
    -jar "$TARGET_DIR/quarkus-run.jar" \
    -Dquarkus.http.port=0 \
    -Dquarkus.datasource.jdbc.url=jdbc:h2:mem:test \
    -Dquarkus.log.level=WARN \
    2>&1 | while IFS= read -r line; do
        if echo "$line" | grep -q "started in\|Listening on"; then
            sleep 1
            pkill -f "quarkus-run.jar" 2>/dev/null || true
            break
        fi
    done &
wait $! 2>/dev/null || true
sleep 2

if [[ -f "$JFR_FILE" ]]; then
    echo "JFR recording saved to: $JFR_FILE"
    echo "Analyze with: jfr print --events jdk.ExecutionSample $JFR_FILE | grep -c ServiceLoader"
fi

# Step 4: Record mode (if flag is set)
if [[ -n "$WITH_FLAG" ]]; then
    echo ""
    echo "Running with ServiceLoader recording enabled..."
    RECORD_FILE="/tmp/quarkus-serviceloader-record-$$.txt"
    timeout 30 java \
        -Dquarkus.serviceloader.record="$RECORD_FILE" \
        -jar "$TARGET_DIR/quarkus-run.jar" \
        -Dquarkus.http.port=0 \
        -Dquarkus.datasource.jdbc.url=jdbc:h2:mem:test \
        -Dquarkus.log.level=WARN \
        2>&1 | while IFS= read -r line; do
            if echo "$line" | grep -q "started in\|Listening on"; then
                sleep 1
                pkill -f "quarkus-run.jar" 2>/dev/null || true
                break
            fi
        done &
    wait $! 2>/dev/null || true
    sleep 2

    if [[ -f "$RECORD_FILE" ]]; then
        echo ""
        echo "=== Fallback recording ==="
        head -50 "$RECORD_FILE"
        echo ""
        echo "Total fallbacks: $(grep -c FALLBACK "$RECORD_FILE" 2>/dev/null || echo 0)"
    fi
fi

echo ""
echo "=== Measurement complete ==="

# Check for build reports
TABLE_FILE=$(find "$APP_DIR/target" -name "quarkus-serviceloader-table.txt" 2>/dev/null | head -1)
REPORT_FILE=$(find "$APP_DIR/target" -name "quarkus-serviceloader-transform-report.txt" 2>/dev/null | head -1)

if [[ -n "$TABLE_FILE" ]]; then
    echo ""
    echo "=== Provider Table ==="
    head -40 "$TABLE_FILE"
    echo "  ($(wc -l < "$TABLE_FILE") lines total)"
fi

if [[ -n "$REPORT_FILE" ]]; then
    echo ""
    echo "=== Transform Report ==="
    cat "$REPORT_FILE"
fi
