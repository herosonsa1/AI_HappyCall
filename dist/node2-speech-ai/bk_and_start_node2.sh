#!/usr/bin/env bash
# ============================================================================
# [Production Linux] bk_and_start_node2.sh
# - Role      : Node 2 - Speech AI STT/TTS & Text Normalizer (APP-03)
# - Target JAR: happycall-node2-speech-ai.jar
# - Prod Port : 8082
# - Pipeline  : 1) Stop running process -> 2) Backup ./data -> 3) Restore & Start
# ============================================================================
set -euo pipefail
APP_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "${APP_DIR}"

JAR_NAME="happycall-node2-speech-ai.jar"
PORT="$(echo "${PROD_PORT:-8082}")"
PID_FILE="${APP_DIR}/node2_prod.pid"
DATA_DIR="${APP_DIR}/data"
BACKUP_DIR="${APP_DIR}/backup"
LOG_DIR="${APP_DIR}/logs"
TIMESTAMP="$(date +'%Y%m%d_%H%M%S')"
BACKUP_ARCHIVE="${BACKUP_DIR}/node2_data_${TIMESTAMP}.tar.gz"
LATEST_ARCHIVE="${BACKUP_DIR}/node2_latest_backup.tar.gz"

mkdir -p "${DATA_DIR}" "${BACKUP_DIR}" "${LOG_DIR}"

echo "[1/3] Stopping running ${JAR_NAME} (Port ${PORT})..."
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

echo "[2/3] Backing up runtime data (${DATA_DIR})..."
if [ -n "$(ls -A "${DATA_DIR}" 2>/dev/null || true)" ]; then
    tar -czf "${BACKUP_ARCHIVE}" -C "${APP_DIR}" "data"
    cp -f "${BACKUP_ARCHIVE}" "${LATEST_ARCHIVE}"
    echo " -> Backup saved: ${BACKUP_ARCHIVE}"
fi

echo "[3/3] Restoring data and starting ${JAR_NAME} on port ${PORT}..."
if [ -f "${LATEST_ARCHIVE}" ]; then
    tar -xzf "${LATEST_ARCHIVE}" -C "${APP_DIR}"
fi

nohup java -server -Xms256m -Xmx1024m -Dfile.encoding=UTF-8 \
    -jar "${APP_DIR}/${JAR_NAME}" \
    --mode=prod \
    --port="${PORT}" \
    --data-dir="${DATA_DIR}" \
    > "${LOG_DIR}/node2_prod.out" 2>&1 &

NEW_PID=$!
echo "${NEW_PID}" > "${PID_FILE}"
echo " -> Started ${JAR_NAME} (PID: ${NEW_PID}, Port: ${PORT})"