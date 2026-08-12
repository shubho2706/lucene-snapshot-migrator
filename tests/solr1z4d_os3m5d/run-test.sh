#!/usr/bin/env bash
# =============================================================================
# Large Cluster E2E Test
# =============================================================================
#
# Topology:
#   Solr:       1 ZooKeeper + 4 SolrCloud nodes
#   OpenSearch: 3 dedicated masters + 5 data/ingest nodes
#
# Test parameters:
#   10,000 docs | 4 shards | RF=3 (-> OS replicas=2)
#   12 total shard copies across 5 data nodes
#
# This script:
#   1. Starts both clusters via docker run
#   2. Waits for all nodes to join
#   3. Runs the standard e2e-test.sh with overridden config
#   4. Prints full cluster diagnostics
#
# Usage:
#   ./run-test.sh                    # start clusters + run test + diagnostics
#   ./run-test.sh --skip-cluster-up  # reuse running clusters
#   ./run-test.sh --down             # tear down clusters after test
#   ./run-test.sh --down-only        # just tear down (no test)
#
# =============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
E2E_DIR="${SCRIPT_DIR}/../e2e"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"

# -- Cluster config --
SOLR_PORT=8990
OS_PORT=9210
SOLR_CONTAINER=solr-large-1
OS_CONTAINER=os-large-data-1
OS_URL="http://localhost:${OS_PORT}"
SOLR_URL="http://localhost:${SOLR_PORT}/solr"

SOLR_IMAGE="solr:9.4"
ZK_IMAGE="zookeeper:3.9"
OS_IMAGE="opensearchproject/opensearch:2.11.1"

SOLR_NETWORK="solr-large-net"
OS_NETWORK="os-large-net"
SOLR_BACKUP_VOL="solr-large-backup"
OS_SNAPSHOT_VOL="os-large-snapshot"

EXPECTED_SOLR_NODES=4
EXPECTED_OS_NODES=8

# -- Test config --
NUM_SHARDS=4
REPLICATION_FACTOR=3
NUM_DOCS=10000
COLLECTION_NAME=nyc_taxi_large
RESTORED_INDEX=restored_nyc_taxi_large

# =============================================================================
# CLI
# =============================================================================

SKIP_CLUSTER_UP=false
DO_DOWN=false
DOWN_ONLY=false

while [[ $# -gt 0 ]]; do
    case $1 in
        --skip-cluster-up) SKIP_CLUSTER_UP=true; shift ;;
        --down)            DO_DOWN=true; shift ;;
        --down-only)       DOWN_ONLY=true; shift ;;
        --docs)            NUM_DOCS="$2"; shift 2 ;;
        --shards)          NUM_SHARDS="$2"; shift 2 ;;
        --rf)              REPLICATION_FACTOR="$2"; shift 2 ;;
        --help|-h)
            head -30 "$0" | tail -25
            exit 0
            ;;
        *) echo "Unknown: $1"; exit 1 ;;
    esac
done

# =============================================================================
# Helpers
# =============================================================================

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'
BLUE='\033[0;34m'; NC='\033[0m'; BOLD='\033[1m'

info() { echo -e "  ${BLUE}[INFO]${NC} $1"; }
ok()   { echo -e "  ${GREEN}[OK]${NC} $1"; }
fail() { echo -e "  ${RED}[FAIL]${NC} $1"; exit 1; }
warn() { echo -e "  ${YELLOW}[WARN]${NC} $1"; }

os_curl() { curl -s "$@"; }

# Source shared Docker resource limits
source "$(dirname "$0")/../docker-defaults.sh"

# =============================================================================
# Tear down function
# =============================================================================

teardown() {
    echo -e "${YELLOW}${BOLD}=== Tearing Down Large Clusters ===${NC}"

    # OS data nodes
    for i in 1 2 3 4 5; do
        docker stop "os-large-data-${i}" 2>/dev/null && docker rm "os-large-data-${i}" 2>/dev/null && ok "Removed os-large-data-${i}" || true
    done
    # OS master nodes
    for i in 1 2 3; do
        docker stop "os-large-master-${i}" 2>/dev/null && docker rm "os-large-master-${i}" 2>/dev/null && ok "Removed os-large-master-${i}" || true
    done
    # Solr nodes
    for i in 1 2 3 4; do
        docker stop "solr-large-${i}" 2>/dev/null && docker rm "solr-large-${i}" 2>/dev/null && ok "Removed solr-large-${i}" || true
    done
    # ZooKeeper
    docker stop zk-large 2>/dev/null && docker rm zk-large 2>/dev/null && ok "Removed zk-large" || true

    # Networks
    docker network rm "${SOLR_NETWORK}" 2>/dev/null && ok "Removed ${SOLR_NETWORK}" || true
    docker network rm "${OS_NETWORK}" 2>/dev/null && ok "Removed ${OS_NETWORK}" || true

    # Volumes
    docker volume rm "${SOLR_BACKUP_VOL}" 2>/dev/null && ok "Removed ${SOLR_BACKUP_VOL}" || true
    docker volume rm "${OS_SNAPSHOT_VOL}" 2>/dev/null && ok "Removed ${OS_SNAPSHOT_VOL}" || true

    ok "Teardown complete"
}

if [[ "${DOWN_ONLY}" == "true" ]]; then
    teardown
    exit 0
fi

# =============================================================================
# Pre-flight
# =============================================================================

echo -e "${BOLD}"
echo "================================================================"
echo " Large Cluster E2E Test"
echo "================================================================"
echo -e "${NC}"
echo "Topology:"
echo "  Solr       : 1 ZK + ${EXPECTED_SOLR_NODES} nodes (port ${SOLR_PORT})"
echo "  OpenSearch : 3 masters + 5 data (port ${OS_PORT})"
echo "Test:"
echo "  Docs=${NUM_DOCS}  Shards=${NUM_SHARDS}  RF=${REPLICATION_FACTOR}"
echo "  Collection : ${COLLECTION_NAME}"
echo "  OS index   : ${RESTORED_INDEX}"
echo ""

# Check converter JAR
CONVERTER_JAR="${PROJECT_ROOT}/target/lucene-snapshot-converter-1.0.0-SNAPSHOT.jar"
if [[ ! -f "${CONVERTER_JAR}" ]]; then
    fail "Converter JAR not found. Build: cd ${PROJECT_ROOT} && mvn clean package -DskipTests"
fi

# Check vm.max_map_count
if [[ "$(cat /proc/sys/vm/max_map_count 2>/dev/null)" -lt 262144 ]]; then
    warn "vm.max_map_count too low, setting to 262144"
    sudo sysctl -w vm.max_map_count=262144 || fail "Failed to set vm.max_map_count"
fi

# =============================================================================
# Step 0: Start clusters
# =============================================================================

if [[ "${SKIP_CLUSTER_UP}" == "false" ]]; then

    # ---- Solr Cluster ----
    echo -e "${BLUE}${BOLD}=== Starting Solr Cluster (1 ZK + ${EXPECTED_SOLR_NODES} nodes) ===${NC}"
    echo ""

    docker network create "${SOLR_NETWORK}" 2>/dev/null || true
    docker volume create "${SOLR_BACKUP_VOL}" 2>/dev/null || true

    # ZooKeeper
    docker run -d --name zk-large \
        --hostname zk-large \
        --network "${SOLR_NETWORK}" \
        --memory "${DOCKER_MEM_ZK}" \
        --cpus "${DOCKER_CPUS_ZK}" \
        -p 2183:2181 \
        -e ZOO_MY_ID=1 \
        -e "ZOO_4LW_COMMANDS_WHITELIST=*" \
        "${ZK_IMAGE}"
    ok "Started zk-large (port 2183)"

    sleep 5

    # 4 Solr nodes
    for i in 1 2 3 4; do
        port=$((8989 + i))
        docker run -d --name "solr-large-${i}" \
            --hostname "solr-large-${i}" \
            --network "${SOLR_NETWORK}" \
            --memory "${DOCKER_MEM_SOLR}" \
            --cpus "${DOCKER_CPUS_SOLR}" \
            -p "${port}:8983" \
            -e "ZK_HOST=zk-large:2181" \
            -e "SOLR_JAVA_MEM=${JAVA_HEAP_SOLR}" \
            -v "${SOLR_BACKUP_VOL}:/var/solr/data/backup" \
            "${SOLR_IMAGE}"
        ok "Started solr-large-${i} (port ${port})"
    done

    # Fix /var/solr/data ownership (backup volume mount can reset parent to root)
    for i in 1 2 3 4; do
        docker exec --user root "solr-large-${i}" chown -R solr:solr /var/solr/data 2>/dev/null || true
    done
    ok "Fixed Solr data directory permissions"

    info "Waiting for ${EXPECTED_SOLR_NODES} Solr nodes..."
    elapsed=0
    while true; do
        live=$(curl -s "${SOLR_URL}/admin/collections?action=CLUSTERSTATUS" 2>/dev/null | \
            python3 -c "
import sys, json
data = json.load(sys.stdin)
print(len(data.get('cluster', {}).get('live_nodes', [])))
" 2>/dev/null || echo "0")

        if [[ "${live}" -ge ${EXPECTED_SOLR_NODES} ]]; then
            ok "Solr cluster ready: ${live} live nodes"
            break
        fi

        sleep 3
        elapsed=$((elapsed + 3))
        if [[ $elapsed -ge 120 ]]; then
            fail "Solr cluster not ready after 120s (${live}/${EXPECTED_SOLR_NODES} nodes)"
        fi
        [[ $((elapsed % 9)) -eq 0 ]] && info "${live}/${EXPECTED_SOLR_NODES} nodes..."
    done

    # ---- OpenSearch Cluster ----
    echo ""
    echo -e "${BLUE}${BOLD}=== Starting OpenSearch Cluster (3 masters + 5 data) ===${NC}"
    echo ""

    docker network create "${OS_NETWORK}" 2>/dev/null || true
    docker volume create "${OS_SNAPSHOT_VOL}" 2>/dev/null || true

    OS_COMMON_ENV=(
        -e "cluster.name=os-large-cluster"
        -e "discovery.seed_hosts=os-large-master-1,os-large-master-2,os-large-master-3"
        -e "cluster.initial_cluster_manager_nodes=os-large-master-1,os-large-master-2,os-large-master-3"
        -e "bootstrap.memory_lock=true"
        -e "DISABLE_INSTALL_DEMO_CONFIG=true"
        -e "DISABLE_SECURITY_PLUGIN=true"
        -e "path.repo=/usr/share/opensearch/data/snapshots"
    )

    OS_COMMON_OPTS=(
        --network "${OS_NETWORK}"
        --ulimit memlock=-1:-1
        -v "${OS_SNAPSHOT_VOL}:/usr/share/opensearch/data/snapshots"
    )

    # 3 master nodes
    for i in 1 2 3; do
        docker run -d --name "os-large-master-${i}" \
            --hostname "os-large-master-${i}" \
            "${OS_COMMON_OPTS[@]}" \
            --memory "${DOCKER_MEM_OS_MASTER}" \
            --cpus "${DOCKER_CPUS_OS_MASTER}" \
            "${OS_COMMON_ENV[@]}" \
            -e "node.name=os-large-master-${i}" \
            -e "node.roles=cluster_manager" \
            -e "OPENSEARCH_JAVA_OPTS=${JAVA_HEAP_OS_MASTER}" \
            "${OS_IMAGE}"
        ok "Started os-large-master-${i}"
    done

    # 5 data nodes (only data-1 gets external port)
    for i in 1 2 3 4 5; do
        port_args=""
        if [[ $i -eq 1 ]]; then
            port_args="-p ${OS_PORT}:9200"
        fi
        docker run -d --name "os-large-data-${i}" \
            --hostname "os-large-data-${i}" \
            "${OS_COMMON_OPTS[@]}" \
            ${port_args} \
            --memory "${DOCKER_MEM_OS_DATA}" \
            --cpus "${DOCKER_CPUS_OS_DATA}" \
            "${OS_COMMON_ENV[@]}" \
            -e "node.name=os-large-data-${i}" \
            -e "node.roles=data,ingest" \
            -e "OPENSEARCH_JAVA_OPTS=${JAVA_HEAP_OS_DATA}" \
            "${OS_IMAGE}"
        ok "Started os-large-data-${i}$([ $i -eq 1 ] && echo " (port ${OS_PORT})" || true)"
    done

    info "Waiting for ${EXPECTED_OS_NODES} OpenSearch nodes (green)..."
    elapsed=0
    while true; do
        health=$(curl -s "${OS_URL}/_cluster/health" 2>/dev/null || echo "{}")
        nodes=$(echo "${health}" | python3 -c "import sys,json; print(json.load(sys.stdin).get('number_of_nodes',0))" 2>/dev/null || echo "0")
        status=$(echo "${health}" | python3 -c "import sys,json; print(json.load(sys.stdin).get('status','red'))" 2>/dev/null || echo "red")

        if [[ "${nodes}" -ge ${EXPECTED_OS_NODES} && "${status}" == "green" ]]; then
            ok "OpenSearch cluster ready: ${nodes} nodes, status=${status}"
            break
        fi

        sleep 5
        elapsed=$((elapsed + 5))
        if [[ $elapsed -ge 180 ]]; then
            fail "OS cluster not ready after 180s (nodes=${nodes}, status=${status})"
        fi
        [[ $((elapsed % 15)) -eq 0 ]] && info "${nodes}/${EXPECTED_OS_NODES} nodes, status=${status}"
    done

    echo ""
    echo -e "${BLUE}${BOLD}--- Solr Live Nodes ---${NC}"
    curl -s "${SOLR_URL}/admin/collections?action=CLUSTERSTATUS" | python3 -c "
import sys, json
data = json.load(sys.stdin)
for node in sorted(data.get('cluster', {}).get('live_nodes', [])):
    print(f'  {node}')
" 2>/dev/null

    echo ""
    echo -e "${BLUE}${BOLD}--- OpenSearch Nodes ---${NC}"
    os_curl "${OS_URL}/_cat/nodes?v&h=name,node.role,heap.percent,ram.percent,disk.used_percent&s=name"
    echo ""

fi # end SKIP_CLUSTER_UP

# =============================================================================
# Step 1: Run E2E test (reuse existing e2e-test.sh with overrides)
# =============================================================================

echo ""
echo -e "${BLUE}${BOLD}=== Running E2E Pipeline ===${NC}"
echo ""

# The e2e-test.sh uses -u admin:admin and --insecure in os_curl.
# With security disabled, the server ignores auth headers — no issue.
set +e
SOLR_PORT="${SOLR_PORT}" \
SOLR_CONTAINER="${SOLR_CONTAINER}" \
SOLR_BACKUP_VOLUME="${SOLR_BACKUP_VOL}" \
OS_PORT="${OS_PORT}" \
OS_CONTAINER="${OS_CONTAINER}" \
OS_SNAPSHOT_VOLUME="${OS_SNAPSHOT_VOL}" \
NUM_SHARDS="${NUM_SHARDS}" \
REPLICATION_FACTOR="${REPLICATION_FACTOR}" \
NUM_DOCS="${NUM_DOCS}" \
COLLECTION_NAME="${COLLECTION_NAME}" \
RESTORED_INDEX="${RESTORED_INDEX}" \
WORK_DIR="${SCRIPT_DIR}/workspace" \
"${E2E_DIR}/e2e-test.sh"

e2e_exit=$?
set -e

# =============================================================================
# Step 2: Cluster diagnostics (runs even if e2e failed — useful for debugging)
# =============================================================================

echo ""
echo -e "${BLUE}${BOLD}================================================================${NC}"
echo -e "${BLUE}${BOLD} Cluster Diagnostics${NC}"
echo -e "${BLUE}${BOLD}================================================================${NC}"

echo ""
echo -e "${BOLD}--- OpenSearch Cluster Health ---${NC}"
os_curl "${OS_URL}/_cluster/health?pretty"

echo ""
echo -e "${BOLD}--- OpenSearch Nodes ---${NC}"
os_curl "${OS_URL}/_cat/nodes?v&h=name,node.role,heap.percent,ram.percent,cpu,load_1m,disk.used_percent&s=name"

echo ""
echo -e "${BOLD}--- Index Settings ---${NC}"
os_curl "${OS_URL}/${RESTORED_INDEX}/_settings?pretty" 2>/dev/null || warn "Index not found"

echo ""
echo -e "${BOLD}--- Index Mappings ---${NC}"
os_curl "${OS_URL}/${RESTORED_INDEX}/_mappings?pretty" 2>/dev/null || true

echo ""
echo -e "${BOLD}--- Index Stats (summary) ---${NC}"
os_curl "${OS_URL}/${RESTORED_INDEX}/_stats?pretty" 2>/dev/null | head -80 || true

echo ""
echo -e "${BOLD}--- Shard Allocation ---${NC}"
os_curl "${OS_URL}/_cat/shards/${RESTORED_INDEX}?v&h=shard,prirep,state,docs,store,ip,node&s=shard,prirep" 2>/dev/null || true

echo ""
echo -e "${BOLD}--- Segments (first 80 lines) ---${NC}"
os_curl "${OS_URL}/${RESTORED_INDEX}/_segments?pretty" 2>/dev/null | head -80 || true

echo ""
echo -e "${BOLD}--- Recovery ---${NC}"
os_curl "${OS_URL}/${RESTORED_INDEX}/_recovery?pretty" 2>/dev/null | head -60 || true

echo ""
echo -e "${BOLD}--- Solr Cluster Status ---${NC}"
curl -s "${SOLR_URL}/admin/collections?action=CLUSTERSTATUS" 2>/dev/null | python3 -c "
import sys, json
data = json.load(sys.stdin)
cluster = data.get('cluster', {})
print(f'  Live nodes: {len(cluster.get(\"live_nodes\", []))}')
colls = data.get('cluster', {}).get('collections', {})
for name, coll in colls.items():
    shards = coll.get('shards', {})
    rf = coll.get('replicationFactor', '?')
    print(f'  Collection: {name} (shards={len(shards)}, RF={rf})')
    for sname, shard in sorted(shards.items()):
        replicas = shard.get('replicas', {})
        leader = [r for r in replicas.values() if r.get('leader') == 'true']
        leader_node = leader[0].get('node_name', '?') if leader else '?'
        print(f'    {sname}: {len(replicas)} replicas, leader={leader_node}')
" 2>/dev/null || true

# =============================================================================
# Result
# =============================================================================

echo ""
if [[ $e2e_exit -eq 0 ]]; then
    echo -e "${GREEN}${BOLD}================================================================${NC}"
    echo -e "${GREEN}${BOLD} LARGE CLUSTER TEST PASSED${NC}"
    echo -e "${GREEN}${BOLD}================================================================${NC}"
else
    echo -e "${RED}${BOLD}================================================================${NC}"
    echo -e "${RED}${BOLD} LARGE CLUSTER TEST FAILED (exit code: ${e2e_exit})${NC}"
    echo -e "${RED}${BOLD}================================================================${NC}"
fi

echo ""
echo "  Solr       : ${EXPECTED_SOLR_NODES} nodes, collection=${COLLECTION_NAME} (${NUM_SHARDS} shards, RF=${REPLICATION_FACTOR})"
echo "  OpenSearch : ${EXPECTED_OS_NODES} nodes (3m+5d), index=${RESTORED_INDEX}"
echo "  Documents  : ${NUM_DOCS}"
echo ""
echo "Clusters are still running. To query:"
echo "  Solr : curl '${SOLR_URL}/${COLLECTION_NAME}/select?q=*:*&rows=0'"
echo "  OS   : curl '${OS_URL}/${RESTORED_INDEX}/_count'"
echo ""
echo "To tear down: $0 --down-only"
echo ""

# =============================================================================
# Optional teardown
# =============================================================================

if [[ "${DO_DOWN}" == "true" ]]; then
    teardown
fi

exit $e2e_exit
