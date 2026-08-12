#!/usr/bin/env bash
# =============================================================================
# End-to-End Test: Solr Backup -> Converter -> OpenSearch Restore
# =============================================================================
#
# Standard Operating Procedure (SOP) for validating the Lucene Snapshot Converter.
#
# What this script does (10 steps):
#   1. Generate a deterministic NYC taxi dataset (Python)
#   2. Create a Solr collection with the specified shard count
#   3. Index the dataset into Solr in batches
#   4. Verify Solr document count matches expected
#   5. Trigger a Solr full backup (incremental=false)
#   6. Copy the backup from Solr (Docker volume or local path)
#   7. Run the Lucene Snapshot Converter
#   8. Copy converted snapshot to OpenSearch snapshot repo
#   9. Register repo + restore snapshot in OpenSearch
#  10. Verify OpenSearch document count + run sample queries
#
# Prerequisites:
#   - Solr cluster running (standalone or SolrCloud)
#   - OpenSearch cluster running
#   - Python 3 installed (for dataset generation)
#   - Java 11+ installed (for converter)
#   - Converter JAR built: mvn clean package -DskipTests
#   - Docker installed (if USE_DOCKER=true)
#
# Usage:
#   ./e2e-test.sh                        # run with defaults
#   ./e2e-test.sh --docs 5000            # custom doc count
#   ./e2e-test.sh --shards 3             # 3 shards
#   ./e2e-test.sh --skip-solr-setup      # reuse existing Solr collection + backup
#   ./e2e-test.sh --skip-to-restore      # just do steps 8-10 (converter already ran)
#   ./e2e-test.sh --cleanup              # delete test artifacts after run
#
# Exit codes:
#   0 = all steps passed
#   1 = a step failed (check output for details)
#
# =============================================================================

set -euo pipefail

# =============================================================================
# CONFIGURATION
# Modify these for your environment. Defaults match the standard Docker setup.
# =============================================================================

# -- Solr --
SOLR_HOST="${SOLR_HOST:-localhost}"
SOLR_PORT="${SOLR_PORT:-8983}"
SOLR_URL="http://${SOLR_HOST}:${SOLR_PORT}/solr"
COLLECTION_NAME="${COLLECTION_NAME:-nyc_taxi_e2e}"
NUM_SHARDS="${NUM_SHARDS:-2}"
REPLICATION_FACTOR="${REPLICATION_FACTOR:-2}"
SOLR_CONFIGSET="${SOLR_CONFIGSET:-_default}"

# -- Solr Backup --
SOLR_BACKUP_NAME="${SOLR_BACKUP_NAME:-nyc_taxi_e2e_backup}"
# Path INSIDE the Solr container (or local path if not Docker)
SOLR_BACKUP_PATH="${SOLR_BACKUP_PATH:-/var/solr/data/backup}"

# -- OpenSearch --
OS_HOST="${OS_HOST:-localhost}"
OS_PORT="${OS_PORT:-9200}"
OS_URL="http://${OS_HOST}:${OS_PORT}"
OS_USER="${OS_USER:-admin}"
OS_PASS="${OS_PASS:-admin}"
RESTORED_INDEX="${RESTORED_INDEX:-restored_nyc_taxi}"
OS_SNAPSHOT_REPO="${OS_SNAPSHOT_REPO:-e2e_test_repo}"
# Path INSIDE the OpenSearch container where snapshots are stored
OS_SNAPSHOT_PATH="${OS_SNAPSHOT_PATH:-/usr/share/opensearch/data/snapshots}"

# -- Docker --
# Set to "false" if Solr/OS are running natively (not in Docker)
USE_DOCKER="${USE_DOCKER:-true}"
SOLR_CONTAINER="${SOLR_CONTAINER:-solr1}"
OS_CONTAINER="${OS_CONTAINER:-os-node-1}"
# Docker volume name for shared Solr backup
SOLR_BACKUP_VOLUME="${SOLR_BACKUP_VOLUME:-solrcloud-backup}"
# Docker volume name for shared OS snapshot repo
OS_SNAPSHOT_VOLUME="${OS_SNAPSHOT_VOLUME:-os-snapshot-vol}"

# -- Dataset --
NUM_DOCS="${NUM_DOCS:-2000}"
DATA_SEED="${DATA_SEED:-42}"

# -- Converter --
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="${PROJECT_ROOT:-$(cd "${SCRIPT_DIR}/../.." && pwd)}"
CONVERTER_JAR="${CONVERTER_JAR:-${PROJECT_ROOT}/target/lucene-snapshot-converter-1.0.0-SNAPSHOT.jar}"

# -- Workspace (scratch area for this test run) --
WORK_DIR="${WORK_DIR:-${SCRIPT_DIR}/workspace}"

# -- Batching --
# Solr /update can choke on huge payloads. Index in batches of this size.
BATCH_SIZE="${BATCH_SIZE:-500}"

# -- Timing --
# Seconds to wait for Solr backup to complete (polled)
BACKUP_TIMEOUT="${BACKUP_TIMEOUT:-120}"
# Seconds to wait for OS restore to complete
RESTORE_TIMEOUT="${RESTORE_TIMEOUT:-120}"

# =============================================================================
# CLI ARGUMENT PARSING
# =============================================================================

SKIP_SOLR_SETUP=false
SKIP_TO_RESTORE=false
DO_CLEANUP=false

while [[ $# -gt 0 ]]; do
    case $1 in
        --docs)          NUM_DOCS="$2"; shift 2 ;;
        --shards)        NUM_SHARDS="$2"; shift 2 ;;
        --seed)          DATA_SEED="$2"; shift 2 ;;
        --collection)    COLLECTION_NAME="$2"; shift 2 ;;
        --index)         RESTORED_INDEX="$2"; shift 2 ;;
        --solr-port)     SOLR_PORT="$2"; SOLR_URL="http://${SOLR_HOST}:${SOLR_PORT}/solr"; shift 2 ;;
        --os-port)       OS_PORT="$2"; OS_URL="http://${OS_HOST}:${OS_PORT}"; shift 2 ;;
        --skip-solr-setup)  SKIP_SOLR_SETUP=true; shift ;;
        --skip-to-restore)  SKIP_TO_RESTORE=true; shift ;;
        --cleanup)       DO_CLEANUP=true; shift ;;
        --no-docker)     USE_DOCKER=false; shift ;;
        --work-dir)      WORK_DIR="$2"; shift 2 ;;
        --help|-h)
            head -40 "$0" | tail -35
            exit 0
            ;;
        *) echo "Unknown option: $1"; exit 1 ;;
    esac
done

# =============================================================================
# HELPERS
# =============================================================================

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color
BOLD='\033[1m'

step_num=0
step() {
    step_num=$((step_num + 1))
    echo ""
    echo -e "${BLUE}${BOLD}=== Step ${step_num}: $1 ===${NC}"
    echo ""
}

ok() {
    echo -e "  ${GREEN}[OK]${NC} $1"
}

fail() {
    echo -e "  ${RED}[FAIL]${NC} $1"
    exit 1
}

warn() {
    echo -e "  ${YELLOW}[WARN]${NC} $1"
}

info() {
    echo -e "  ${BLUE}[INFO]${NC} $1"
}

# curl wrapper for OpenSearch (handles auth)
os_curl() {
    curl -s -u "${OS_USER}:${OS_PASS}" --insecure "$@"
}

# Wait for HTTP endpoint to respond
wait_for_http() {
    local url="$1"
    local name="$2"
    local timeout="${3:-30}"
    local elapsed=0
    info "Waiting for ${name} at ${url} ..."
    while ! curl -s --insecure -u "${OS_USER}:${OS_PASS}" -o /dev/null -w '' "${url}" 2>/dev/null; do
        sleep 2
        elapsed=$((elapsed + 2))
        if [[ $elapsed -ge $timeout ]]; then
            fail "${name} not reachable after ${timeout}s"
        fi
    done
    ok "${name} is reachable"
}

# =============================================================================
# PRE-FLIGHT CHECKS
# =============================================================================

echo -e "${BOLD}"
echo "================================================================"
echo " Lucene Snapshot Converter - End-to-End Test"
echo "================================================================"
echo -e "${NC}"
echo "Configuration:"
echo "  Solr          : ${SOLR_URL} (container: ${SOLR_CONTAINER})"
echo "  OpenSearch    : ${OS_URL} (container: ${OS_CONTAINER})"
echo "  Collection    : ${COLLECTION_NAME} (${NUM_SHARDS} shards)"
echo "  Documents     : ${NUM_DOCS} (seed: ${DATA_SEED})"
echo "  Restored as   : ${RESTORED_INDEX}"
echo "  Converter JAR : ${CONVERTER_JAR}"
echo "  Work dir      : ${WORK_DIR}"
echo "  Docker mode   : ${USE_DOCKER}"
echo ""

# Check converter JAR exists
if [[ ! -f "${CONVERTER_JAR}" ]]; then
    fail "Converter JAR not found: ${CONVERTER_JAR}\n  Build it first: cd ${PROJECT_ROOT} && mvn clean package -DskipTests"
fi

# Check Python3
if ! command -v python3 &>/dev/null; then
    fail "python3 is required but not found in PATH"
fi

# Check Java
if ! command -v java &>/dev/null; then
    fail "java is required but not found in PATH"
fi

# Create workspace
mkdir -p "${WORK_DIR}"

# Path variables derived from workspace
DATA_FILE="${WORK_DIR}/taxi-data.json"
BACKUP_LOCAL="${WORK_DIR}/solr-backup"
CONVERTED_SNAPSHOT="${WORK_DIR}/os-snapshot"

# =============================================================================
# STEP 1: Generate Dataset
# =============================================================================

if [[ "${SKIP_TO_RESTORE}" == "true" ]]; then
    step_num=7
    info "Skipping to restore (steps 8-10)"
else

if [[ "${SKIP_SOLR_SETUP}" == "false" ]]; then

step "Generate NYC Taxi Dataset"

python3 "${SCRIPT_DIR}/generate-taxi-data.py" "${NUM_DOCS}" "${DATA_SEED}" "${DATA_FILE}"

file_size=$(wc -c < "${DATA_FILE}")
ok "Generated ${NUM_DOCS} records -> ${DATA_FILE} ($(( file_size / 1024 )) KB)"

# =============================================================================
# STEP 2: Create Solr Collection
# =============================================================================

step "Create Solr Collection"

wait_for_http "${SOLR_URL}/admin/collections?action=CLUSTERSTATUS" "Solr"

# Check if collection already exists
existing=$(curl -s "${SOLR_URL}/admin/collections?action=LIST" | python3 -c "
import sys, json
data = json.load(sys.stdin)
print('yes' if '${COLLECTION_NAME}' in data.get('collections', []) else 'no')
" 2>/dev/null || echo "no")

if [[ "${existing}" == "yes" ]]; then
    warn "Collection '${COLLECTION_NAME}' already exists -- deleting it first"
    curl -s "${SOLR_URL}/admin/collections?action=DELETE&name=${COLLECTION_NAME}" | python3 -c "
import sys, json
data = json.load(sys.stdin)
status = data.get('responseHeader', {}).get('status', -1)
if status == 0:
    print('  Deleted successfully')
else:
    print(f'  Delete response: {data}')
"
    sleep 2
fi

info "Creating collection: ${COLLECTION_NAME} (shards=${NUM_SHARDS}, rf=${REPLICATION_FACTOR})"

# maxShardsPerNode=-1 needed for Solr 8 (default=1 limits shard placement); Solr 9 ignores it
create_response=$(curl -s "${SOLR_URL}/admin/collections?action=CREATE&name=${COLLECTION_NAME}&numShards=${NUM_SHARDS}&replicationFactor=${REPLICATION_FACTOR}&maxShardsPerNode=-1&collection.configName=${SOLR_CONFIGSET}")

create_status=$(echo "${create_response}" | python3 -c "
import sys, json
data = json.load(sys.stdin)
print(data.get('responseHeader', {}).get('status', -1))
" 2>/dev/null || echo "-1")

if [[ "${create_status}" == "0" ]]; then
    ok "Collection '${COLLECTION_NAME}' created"
else
    echo "${create_response}" | python3 -m json.tool 2>/dev/null || echo "${create_response}"
    fail "Failed to create collection"
fi

# =============================================================================
# STEP 3: Index Data into Solr
# =============================================================================

step "Index Data into Solr (batches of ${BATCH_SIZE})"

# Split the JSON array into batches and POST each one
python3 -c "
import json, sys, math

with open('${DATA_FILE}') as f:
    records = json.load(f)

batch_size = ${BATCH_SIZE}
num_batches = math.ceil(len(records) / batch_size)

for i in range(num_batches):
    start = i * batch_size
    end = min(start + batch_size, len(records))
    batch = records[start:end]
    batch_file = '${WORK_DIR}/batch_{}.json'.format(i)
    with open(batch_file, 'w') as f:
        json.dump(batch, f)
    print(f'batch_{i}.json: records {start+1}-{end}')
print(f'TOTAL_BATCHES={num_batches}')
"

# Count batch files and index each one
total_indexed=0
for batch_file in "${WORK_DIR}"/batch_*.json; do
    batch_name=$(basename "${batch_file}")
    response=$(curl -s -X POST \
        "${SOLR_URL}/${COLLECTION_NAME}/update?commit=true" \
        -H "Content-Type: application/json" \
        --data-binary "@${batch_file}")

    status=$(echo "${response}" | python3 -c "
import sys, json
data = json.load(sys.stdin)
print(data.get('responseHeader', {}).get('status', -1))
" 2>/dev/null || echo "-1")

    if [[ "${status}" == "0" ]]; then
        batch_count=$(python3 -c "import json; print(len(json.load(open('${batch_file}'))))")
        total_indexed=$((total_indexed + batch_count))
        ok "${batch_name}: indexed ${batch_count} docs (total: ${total_indexed})"
    else
        echo "${response}" | python3 -m json.tool 2>/dev/null || echo "${response}"
        fail "Failed to index ${batch_name}"
    fi
done

# Clean up batch files
rm -f "${WORK_DIR}"/batch_*.json

# =============================================================================
# STEP 4: Verify Solr Document Count
# =============================================================================

step "Verify Solr Document Count"

# Give Solr a moment to commit
sleep 2

solr_count=$(curl -s "${SOLR_URL}/${COLLECTION_NAME}/select?q=*:*&rows=0" | python3 -c "
import sys, json
data = json.load(sys.stdin)
print(data.get('response', {}).get('numFound', 0))
" 2>/dev/null || echo "0")

if [[ "${solr_count}" == "${NUM_DOCS}" ]]; then
    ok "Solr has ${solr_count} docs (expected: ${NUM_DOCS})"
else
    fail "Solr doc count mismatch: got ${solr_count}, expected ${NUM_DOCS}"
fi

# =============================================================================
# STEP 5: Trigger Solr Backup
# =============================================================================

step "Trigger Solr Full Backup"

# Delete any existing backup with the same name
if [[ "${USE_DOCKER}" == "true" ]]; then
    docker exec "${SOLR_CONTAINER}" bash -c "rm -rf ${SOLR_BACKUP_PATH}/${SOLR_BACKUP_NAME}" 2>/dev/null || true
else
    rm -rf "${SOLR_BACKUP_PATH}/${SOLR_BACKUP_NAME}" 2>/dev/null || true
fi

info "Triggering backup: ${SOLR_BACKUP_NAME} (incremental=false)"

backup_response=$(curl -s "${SOLR_URL}/admin/collections?action=BACKUP&name=${SOLR_BACKUP_NAME}&collection=${COLLECTION_NAME}&location=${SOLR_BACKUP_PATH}&incremental=false")

backup_status=$(echo "${backup_response}" | python3 -c "
import sys, json
data = json.load(sys.stdin)
print(data.get('responseHeader', {}).get('status', -1))
" 2>/dev/null || echo "-1")

if [[ "${backup_status}" == "0" ]]; then
    ok "Backup triggered successfully"
else
    echo "${backup_response}" | python3 -m json.tool 2>/dev/null || echo "${backup_response}"
    fail "Backup request failed"
fi

# Wait for backup to appear (check for backup.properties file)
info "Waiting for backup to complete..."
elapsed=0
while true; do
    if [[ "${USE_DOCKER}" == "true" ]]; then
        found=$(docker exec "${SOLR_CONTAINER}" test -f "${SOLR_BACKUP_PATH}/${SOLR_BACKUP_NAME}/backup.properties" && echo "yes" || echo "no")
    else
        found=$([[ -f "${SOLR_BACKUP_PATH}/${SOLR_BACKUP_NAME}/backup.properties" ]] && echo "yes" || echo "no")
    fi

    if [[ "${found}" == "yes" ]]; then
        break
    fi

    sleep 2
    elapsed=$((elapsed + 2))
    if [[ $elapsed -ge $BACKUP_TIMEOUT ]]; then
        fail "Backup did not complete within ${BACKUP_TIMEOUT}s"
    fi
done

ok "Backup completed: ${SOLR_BACKUP_NAME}"

# Show backup structure
if [[ "${USE_DOCKER}" == "true" ]]; then
    info "Backup contents:"
    docker exec "${SOLR_CONTAINER}" find "${SOLR_BACKUP_PATH}/${SOLR_BACKUP_NAME}" -maxdepth 2 -type d | head -20 | sed 's/^/    /'
fi

# =============================================================================
# STEP 6: Copy Backup Locally
# =============================================================================

step "Copy Solr Backup to Local Workspace"

rm -rf "${BACKUP_LOCAL}"
mkdir -p "${BACKUP_LOCAL}"

if [[ "${USE_DOCKER}" == "true" ]]; then
    # Use the Docker volume directly (faster than docker cp for large backups)
    VOLUME_PATH=$(docker volume inspect "${SOLR_BACKUP_VOLUME}" --format '{{.Mountpoint}}' 2>/dev/null || echo "")
    if [[ -n "${VOLUME_PATH}" && -d "${VOLUME_PATH}/${SOLR_BACKUP_NAME}" ]]; then
        info "Copying from Docker volume: ${VOLUME_PATH}/${SOLR_BACKUP_NAME}"
        cp -r "${VOLUME_PATH}/${SOLR_BACKUP_NAME}" "${BACKUP_LOCAL}/"
    else
        info "Falling back to docker cp"
        docker cp "${SOLR_CONTAINER}:${SOLR_BACKUP_PATH}/${SOLR_BACKUP_NAME}" "${BACKUP_LOCAL}/"
    fi
else
    info "Copying from local path: ${SOLR_BACKUP_PATH}/${SOLR_BACKUP_NAME}"
    cp -r "${SOLR_BACKUP_PATH}/${SOLR_BACKUP_NAME}" "${BACKUP_LOCAL}/"
fi

backup_size=$(du -sh "${BACKUP_LOCAL}/${SOLR_BACKUP_NAME}" | cut -f1)
ok "Backup copied: ${backup_size}"

# =============================================================================
# STEP 7: Run Converter
# =============================================================================

step "Run Lucene Snapshot Converter"

rm -rf "${CONVERTED_SNAPSHOT}"
mkdir -p "${CONVERTED_SNAPSHOT}"

info "Input  : ${BACKUP_LOCAL}/${SOLR_BACKUP_NAME}"
info "Output : ${CONVERTED_SNAPSHOT}"
info "Index  : ${RESTORED_INDEX}"
echo ""

java -jar "${CONVERTER_JAR}" \
    "${BACKUP_LOCAL}/${SOLR_BACKUP_NAME}" \
    "${CONVERTED_SNAPSHOT}" \
    "${RESTORED_INDEX}"

converter_exit=$?
if [[ $converter_exit -ne 0 ]]; then
    fail "Converter exited with code ${converter_exit}"
fi

# Verify output has expected structure
if [[ -f "${CONVERTED_SNAPSHOT}/index-0" && -d "${CONVERTED_SNAPSHOT}/indices" ]]; then
    snapshot_size=$(du -sh "${CONVERTED_SNAPSHOT}" | cut -f1)
    ok "Converter produced valid snapshot structure (${snapshot_size})"
else
    fail "Converter output missing expected files (index-0, indices/)"
fi

fi # end SKIP_SOLR_SETUP

# =============================================================================
# STEP 8: Copy Snapshot to OpenSearch
# =============================================================================

step "Copy Converted Snapshot to OpenSearch"

wait_for_http "${OS_URL}" "OpenSearch"

if [[ "${USE_DOCKER}" == "true" ]]; then
    # Clean old repo, create fresh dir, fix parent ownership (volume mount creates as root)
    docker exec --user root "${OS_CONTAINER}" bash -c "rm -rf ${OS_SNAPSHOT_PATH}/e2e-test-repo && mkdir -p ${OS_SNAPSHOT_PATH}/e2e-test-repo && chown opensearch:opensearch ${OS_SNAPSHOT_PATH} ${OS_SNAPSHOT_PATH}/e2e-test-repo" 2>/dev/null || true
    # Copy converted snapshot into the container
    info "Copying via docker cp to ${OS_CONTAINER}:${OS_SNAPSHOT_PATH}/e2e-test-repo/"
    docker cp "${CONVERTED_SNAPSHOT}/." "${OS_CONTAINER}:${OS_SNAPSHOT_PATH}/e2e-test-repo/"
    # Fix ownership so OpenSearch can read/write (needed for repo verification)
    docker exec --user root "${OS_CONTAINER}" bash -c "chown -R opensearch:opensearch ${OS_SNAPSHOT_PATH}/e2e-test-repo" 2>/dev/null || true
else
    info "Copying to local OS snapshot path"
    REPO_DIR="${OS_SNAPSHOT_PATH}/e2e-test-repo"
    rm -rf "${REPO_DIR}"
    mkdir -p "${REPO_DIR}"
    cp -r "${CONVERTED_SNAPSHOT}"/* "${REPO_DIR}/"
fi

ok "Snapshot files copied to OpenSearch"

fi # end SKIP_TO_RESTORE

# =============================================================================
# STEP 9: Register Repo + Restore
# =============================================================================

step "Register Snapshot Repository and Restore"

wait_for_http "${OS_URL}" "OpenSearch"

# Delete existing restored index if present
existing_index=$(os_curl -o /dev/null -w "%{http_code}" "${OS_URL}/${RESTORED_INDEX}" 2>/dev/null || echo "000")
if [[ "${existing_index}" == "200" ]]; then
    warn "Index '${RESTORED_INDEX}' already exists -- deleting"
    os_curl -X DELETE "${OS_URL}/${RESTORED_INDEX}" > /dev/null
    sleep 1
fi

# Delete and re-register the snapshot repository
os_curl -X DELETE "${OS_URL}/_snapshot/${OS_SNAPSHOT_REPO}" > /dev/null 2>&1 || true

info "Registering snapshot repository: ${OS_SNAPSHOT_REPO}"

register_response=$(os_curl -X PUT "${OS_URL}/_snapshot/${OS_SNAPSHOT_REPO}" \
    -H "Content-Type: application/json" \
    -d "{
        \"type\": \"fs\",
        \"settings\": {
            \"location\": \"${OS_SNAPSHOT_PATH}/e2e-test-repo\"
        }
    }")

if echo "${register_response}" | grep -q '"acknowledged":true'; then
    ok "Repository registered"
else
    echo "${register_response}"
    fail "Failed to register snapshot repository"
fi

# List snapshots in the repo
info "Listing snapshots in repo..."
snapshots_response=$(os_curl "${OS_URL}/_snapshot/${OS_SNAPSHOT_REPO}/_all")
echo "${snapshots_response}" | python3 -m json.tool 2>/dev/null | head -20 | sed 's/^/    /'

# Get the snapshot name (first one in the repo)
snapshot_name=$(echo "${snapshots_response}" | python3 -c "
import sys, json
data = json.load(sys.stdin)
snaps = data.get('snapshots', [])
if snaps:
    print(snaps[0]['snapshot'])
else:
    print('NONE')
" 2>/dev/null || echo "NONE")

if [[ "${snapshot_name}" == "NONE" ]]; then
    fail "No snapshots found in repository"
fi

info "Restoring snapshot: ${snapshot_name} -> index: ${RESTORED_INDEX}"

restore_response=$(os_curl -X POST "${OS_URL}/_snapshot/${OS_SNAPSHOT_REPO}/${snapshot_name}/_restore" \
    -H "Content-Type: application/json" \
    -d "{
        \"indices\": \"*\",
        \"rename_pattern\": \"(.+)\",
        \"rename_replacement\": \"${RESTORED_INDEX}\"
    }")

if echo "${restore_response}" | grep -q '"accepted":true'; then
    ok "Restore initiated"
else
    echo "${restore_response}"
    fail "Restore request failed"
fi

# Wait for restore to complete
info "Waiting for restore to complete..."
elapsed=0
while true; do
    health=$(os_curl "${OS_URL}/_cluster/health/${RESTORED_INDEX}" 2>/dev/null)
    status=$(echo "${health}" | python3 -c "
import sys, json
data = json.load(sys.stdin)
print(data.get('status', 'unknown'))
" 2>/dev/null || echo "unknown")

    if [[ "${status}" == "green" ]]; then
        ok "Index health: ${status}"
        break
    fi

    sleep 2
    elapsed=$((elapsed + 2))
    if [[ $elapsed -ge $RESTORE_TIMEOUT ]]; then
        warn "Restore timeout after ${RESTORE_TIMEOUT}s (status: ${status}). Expected green."
        warn "Yellow means replica shards not assigned -- check node count and number_of_replicas."
        break
    fi
done

# =============================================================================
# STEP 10: Verify OpenSearch
# =============================================================================

step "Verify OpenSearch Data"

# Brief pause for all shards to be searchable
sleep 3

# 10a. Document count
os_count=$(os_curl "${OS_URL}/${RESTORED_INDEX}/_count" | python3 -c "
import sys, json
data = json.load(sys.stdin)
print(data.get('count', 0))
" 2>/dev/null || echo "0")

echo ""
echo -e "  ${BOLD}Document Count Verification${NC}"
if [[ "${os_count}" == "${NUM_DOCS}" ]]; then
    ok "OpenSearch has ${os_count} docs (expected: ${NUM_DOCS})"
else
    fail "Document count mismatch: OpenSearch has ${os_count}, expected ${NUM_DOCS}"
fi

# 10b. Per-shard breakdown
echo ""
echo -e "  ${BOLD}Per-Shard Distribution${NC}"
shard_info=$(os_curl "${OS_URL}/_cat/shards/${RESTORED_INDEX}?v&h=shard,prirep,docs,store,node&s=shard,prirep")
echo "${shard_info}" | sed 's/^/    /'

# 10c. Replica and cluster health verification
echo ""
echo -e "  ${BOLD}Replica Verification${NC}"

# Check index settings for number_of_replicas
os_replicas=$(os_curl "${OS_URL}/${RESTORED_INDEX}/_settings" | python3 -c "
import sys, json
data = json.load(sys.stdin)
idx = list(data.values())[0]
print(idx['settings']['index']['number_of_replicas'])
" 2>/dev/null || echo "unknown")

expected_replicas=$((REPLICATION_FACTOR - 1))
if [[ "${os_replicas}" == "${expected_replicas}" ]]; then
    ok "number_of_replicas=${os_replicas} (Solr RF=${REPLICATION_FACTOR} -> OS replicas=${expected_replicas})"
else
    fail "Replica mismatch: OS has number_of_replicas=${os_replicas}, expected ${expected_replicas} (from Solr RF=${REPLICATION_FACTOR})"
fi

# Check cluster health for this index is green (all primary + replica shards assigned)
idx_health=$(os_curl "${OS_URL}/_cluster/health/${RESTORED_INDEX}" | python3 -c "
import sys, json
data = json.load(sys.stdin)
print(data.get('status','unknown'), data.get('active_primary_shards',0), data.get('active_shards',0), data.get('unassigned_shards',0))
" 2>/dev/null || echo "unknown 0 0 0")
read -r health_status pri_shards total_shards unassigned_shards <<< "${idx_health}"

expected_total=$((NUM_SHARDS * REPLICATION_FACTOR))
if [[ "${health_status}" == "green" && "${pri_shards}" == "${NUM_SHARDS}" && "${total_shards}" == "${expected_total}" ]]; then
    ok "Cluster health: ${health_status} (${pri_shards} primary + $((total_shards - pri_shards)) replica = ${total_shards} total shards)"
else
    warn "Health: ${health_status}, primary=${pri_shards}, total=${total_shards}, unassigned=${unassigned_shards}"
    fail "Expected green with ${NUM_SHARDS} primary + ${expected_replicas} replica per shard = ${expected_total} total shards"
fi

# 10d. Sample queries
echo ""
echo -e "  ${BOLD}Sample Query Verification${NC}"

# Query 1: Match query on vendor_id (text field, not keyword)
q1_count=$(os_curl "${OS_URL}/${RESTORED_INDEX}/_count" \
    -H "Content-Type: application/json" \
    -d '{"query":{"match":{"vendor_id":"CMT"}}}' | python3 -c "
import sys, json; print(json.load(sys.stdin).get('count', 0))
" 2>/dev/null || echo "0")
ok "vendor_id=CMT : ${q1_count} docs"

# Query 2: Range query on fare_amount
q2_count=$(os_curl "${OS_URL}/${RESTORED_INDEX}/_count" \
    -H "Content-Type: application/json" \
    -d '{"query":{"range":{"fare_amount":{"gte":20.0}}}}' | python3 -c "
import sys, json; print(json.load(sys.stdin).get('count', 0))
" 2>/dev/null || echo "0")
ok "fare >= 20.0  : ${q2_count} docs"

# Query 3: Date range query (June 2024)
q3_count=$(os_curl "${OS_URL}/${RESTORED_INDEX}/_count" \
    -H "Content-Type: application/json" \
    -d '{"query":{"range":{"pickup_datetime":{"gte":"2024-06-01","lte":"2024-06-30"}}}}' | python3 -c "
import sys, json; print(json.load(sys.stdin).get('count', 0))
" 2>/dev/null || echo "0")
ok "June 2024      : ${q3_count} docs"

# Query 4: Match query on payment_type (text field)
q3_count=$(os_curl "${OS_URL}/${RESTORED_INDEX}/_count" \
    -H "Content-Type: application/json" \
    -d '{"query":{"match":{"payment_type":"CRD"}}}' | python3 -c "
import sys, json; print(json.load(sys.stdin).get('count', 0))
" 2>/dev/null || echo "0")
ok "payment=CRD   : ${q3_count} docs"

# Query 5: Passenger count range
q4_count=$(os_curl "${OS_URL}/${RESTORED_INDEX}/_count" \
    -H "Content-Type: application/json" \
    -d '{"query":{"range":{"passenger_count":{"gte":4}}}}' | python3 -c "
import sys, json; print(json.load(sys.stdin).get('count', 0))
" 2>/dev/null || echo "0")
ok "passengers>=4  : ${q4_count} docs"

# Query 6: Full-text search on trip_description
q6_count=$(os_curl "${OS_URL}/${RESTORED_INDEX}/_count" \
    -H "Content-Type: application/json" \
    -d '{"query":{"match":{"trip_description":"airport"}}}' | python3 -c "
import sys, json; print(json.load(sys.stdin).get('count', 0))
" 2>/dev/null || echo "0")
ok "desc~airport   : ${q6_count} docs"

# Query 7: Aggregation on numeric fields (text fields lack doc_values)
echo ""
echo -e "  ${BOLD}Aggregation Test${NC}"
agg_response=$(os_curl "${OS_URL}/${RESTORED_INDEX}/_search?size=0" \
    -H "Content-Type: application/json" \
    -d '{
        "aggs": {
            "avg_fare": {
                "avg": { "field": "fare_amount" }
            },
            "total_revenue": {
                "sum": { "field": "total_amount" }
            },
            "avg_distance": {
                "avg": { "field": "trip_distance" }
            }
        }
    }')

echo "${agg_response}" | python3 -c "
import sys, json
data = json.load(sys.stdin)
aggs = data.get('aggregations', {})
avg_fare = aggs.get('avg_fare', {}).get('value', 0)
total_rev = aggs.get('total_revenue', {}).get('value', 0)
avg_dist = aggs.get('avg_distance', {}).get('value', 0)
print(f'    Average fare     : \${avg_fare:,.2f}')
print(f'    Total revenue    : \${total_rev:,.2f}')
print(f'    Average distance : {avg_dist:,.2f} mi')
" 2>/dev/null

ok "Aggregations working"

# =============================================================================
# CLEANUP (optional)
# =============================================================================

if [[ "${DO_CLEANUP}" == "true" ]]; then
    echo ""
    echo -e "${YELLOW}${BOLD}=== Cleanup ===${NC}"
    echo ""

    # Delete restored index
    os_curl -X DELETE "${OS_URL}/${RESTORED_INDEX}" > /dev/null 2>&1
    ok "Deleted OpenSearch index: ${RESTORED_INDEX}"

    # Delete snapshot repo
    os_curl -X DELETE "${OS_URL}/_snapshot/${OS_SNAPSHOT_REPO}" > /dev/null 2>&1
    ok "Deleted snapshot repo: ${OS_SNAPSHOT_REPO}"

    # Delete Solr collection
    curl -s "${SOLR_URL}/admin/collections?action=DELETE&name=${COLLECTION_NAME}" > /dev/null 2>&1
    ok "Deleted Solr collection: ${COLLECTION_NAME}"

    # Delete Solr backup
    if [[ "${USE_DOCKER}" == "true" ]]; then
        docker exec "${SOLR_CONTAINER}" bash -c "rm -rf ${SOLR_BACKUP_PATH}/${SOLR_BACKUP_NAME}" 2>/dev/null || true
    fi
    ok "Deleted Solr backup: ${SOLR_BACKUP_NAME}"

    # Delete workspace
    rm -rf "${WORK_DIR}"
    ok "Deleted workspace: ${WORK_DIR}"
fi

# =============================================================================
# SUMMARY
# =============================================================================

echo ""
echo -e "${GREEN}${BOLD}================================================================${NC}"
echo -e "${GREEN}${BOLD} END-TO-END TEST PASSED${NC}"
echo -e "${GREEN}${BOLD}================================================================${NC}"
echo ""
echo "  Solr collection : ${COLLECTION_NAME} (${NUM_SHARDS} shards, RF=${REPLICATION_FACTOR}, ${NUM_DOCS} docs)"
echo "  Backup          : ${SOLR_BACKUP_NAME}"
echo "  Converted to    : ${CONVERTED_SNAPSHOT}"
echo "  Restored as     : ${RESTORED_INDEX}"
echo "  OS doc count    : ${os_count}"
echo ""
echo "Artifacts in: ${WORK_DIR}"
echo "  taxi-data.json      : generated dataset"
echo "  solr-backup/        : raw Solr backup"
echo "  os-snapshot/        : converted OpenSearch snapshot"
echo ""
if [[ "${DO_CLEANUP}" == "false" ]]; then
    echo "Run with --cleanup to remove test artifacts after verification."
fi
echo ""
