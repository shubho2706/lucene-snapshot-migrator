#!/usr/bin/env bash
# =============================================================================
# Shared Docker Resource Limits for Test Clusters
# =============================================================================
# Source this file from any test script to get consistent resource limits.
# Override any variable before sourcing, or via environment.
#
# Usage:
#   source "$(dirname "$0")/../docker-defaults.sh"
#
# =============================================================================

# -- Docker memory limits --
# These cap the maximum memory a container can use. Docker kills the container
# if it exceeds the limit (OOMKilled). Set generously enough for the JVM heap
# plus overhead (GC, mmap, native buffers).

DOCKER_MEM_OS_MASTER="${DOCKER_MEM_OS_MASTER:-1g}"
DOCKER_MEM_OS_DATA="${DOCKER_MEM_OS_DATA:-2g}"
DOCKER_MEM_SOLR="${DOCKER_MEM_SOLR:-1g}"
DOCKER_MEM_ZK="${DOCKER_MEM_ZK:-512m}"

# -- JVM heap settings --
# Keep heap well below the Docker memory limit to leave room for off-heap.

JAVA_HEAP_OS_MASTER="${JAVA_HEAP_OS_MASTER:--Xms256m -Xmx256m}"
JAVA_HEAP_OS_DATA="${JAVA_HEAP_OS_DATA:--Xms512m -Xmx512m}"
JAVA_HEAP_SOLR="${JAVA_HEAP_SOLR:--Xms256m -Xmx512m}"

# -- CPU limits --
# Fraction of CPUs each container gets. 1.0 = 1 full core.

DOCKER_CPUS_OS_MASTER="${DOCKER_CPUS_OS_MASTER:-1.0}"
DOCKER_CPUS_OS_DATA="${DOCKER_CPUS_OS_DATA:-2.0}"
DOCKER_CPUS_SOLR="${DOCKER_CPUS_SOLR:-1.0}"
DOCKER_CPUS_ZK="${DOCKER_CPUS_ZK:-0.5}"
