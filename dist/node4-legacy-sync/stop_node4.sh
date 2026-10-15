#!/usr/bin/env bash
# ============================================================================
# [Production Linux] stop_node4.sh
# - Role      : Node 4 - PII Masking Archiver & Legacy DB Sync (APP-05/06)
# - Target JAR: happycall-node4-legacy-sync.jar
# - Pipeline  : 1) Stop running process -> 2) Backup ./data
# ============================================================================
set -euo pipefail
APP_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "${APP_DIR}"

JAR_NAME="happycall-node4-legacy-sync.jar"
PID_FILE="${APP_DIR}/node4_prod.pid"
DATA_DIR="${APP_DIR}/data"
BACKUP_DIR="${APP_DIR}/backup"
TIMESTAMP="$(date +'%Y%m%d_%H%M%S')"
BACKUP_ARCHIVE="${BACKUP_DIR}/node4_data_${TIMESTAMP}.tar.gz"
LATEST_ARCHIVE="${BACKUP_DIR}/node4_latest_backup.tar.gz"

mkdir -p "${DATA_DIR}" "${BACKUP_DIR}"

echo "[1/2] Stopping running ${JAR_NAME}..."
TARGET_PID=""
[ -f "${PID_FILE}" ] && TARGET_PID="$(cat "${PID_FILE}" 2>/dev/null || true)"
if [ -z "${TARGET_PID}" ] || ! kill -0 "${TARGET_PID}" 2>/dev/null; then
    TARGET_PID="$(pgrep -f "${JAR_NAME}" | head -n 1 || true)"
fi
if [ -n "${TARGET_PID}" ] && kill -0 "${TARGET_PID}" 2>/dev/null; then
    kill -15 "${TARGET_PID}" 2>/dev/null || true
    sleep 2
    kill -0 "${TARGET_PID}" 2>/dev/null && kill -9 "${TARGET_PID}" 2>/dev/null || true
fi
rm -f "${PID_FILE}"

echo "[2/2] Backing up runtime data (${DATA_DIR})..."
tar -czf "${BACKUP_ARCHIVE}" -C "${APP_DIR}" "data"
cp -f "${BACKUP_ARCHIVE}" "${LATEST_ARCHIVE}"
echo " -> Stopped and backed up to ${BACKUP_ARCHIVE}"