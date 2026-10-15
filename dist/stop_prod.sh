#!/usr/bin/env bash
# ============================================================================
# [운영환경 - 폐쇄망 Linux] stop_prod.sh (노드별 개별 중지·백업 및 전체 중지·백업 지원)
# 사용법:
#   ./stop_prod.sh all    (기본값: Node 1~5 전체 서비스 중지 후 각 노드 데이터 백업)
#   ./stop_prod.sh node1  (음성망/DMZ: happycall-node1-telephony-gw.jar 중지 및 백업)
#   ./stop_prod.sh node2  (AI음성망  : happycall-node2-speech-ai.jar 중지 및 백업)
#   ./stop_prod.sh node3  (GPU코어망 : happycall-node3-llm-orchestrator.jar 중지 및 백업)
#   ./stop_prod.sh node4  (기간계망  : happycall-node4-legacy-sync.jar 중지 및 백업)
#   ./stop_prod.sh node5  (업무관제망: happycall-node5-admin-console.jar 중지 및 백업)
# ============================================================================

set -euo pipefail

APP_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "${APP_DIR}"

TARGET_NODE="${1:-all}"
BACKUP_ROOT="${APP_DIR}/backup"
TIMESTAMP="$(date +'%Y%m%d_%H%M%S')"

mkdir -p "${BACKUP_ROOT}"

stop_and_backup_single_node() {
    local NODE_KEY="$1"
    local JAR_NAME="$2"
    local DATA_SUBDIR="$3"

    local PID_FILE="${APP_DIR}/${NODE_KEY}_prod.pid"
    local NODE_DATA_DIR="${APP_DIR}/data/${DATA_SUBDIR}"
    local BACKUP_ARCHIVE="${BACKUP_ROOT}/${NODE_KEY}_data_${TIMESTAMP}.tar.gz"
    local LATEST_ARCHIVE="${BACKUP_ROOT}/${NODE_KEY}_latest_backup.tar.gz"

    mkdir -p "${NODE_DATA_DIR}"

    echo "----------------------------------------------------------------------------"
    echo "[${NODE_KEY}] 1) 현재 기동중인 서비스(${JAR_NAME}) 중지 -> 2) 데이터 백업"
    echo "----------------------------------------------------------------------------"

    local TARGET_PID=""
    if [ -f "${PID_FILE}" ]; then
        TARGET_PID="$(cat "${PID_FILE}" 2>/dev/null || true)"
    fi
    if [ -z "${TARGET_PID}" ] || ! kill -0 "${TARGET_PID}" 2>/dev/null; then
        TARGET_PID="$(pgrep -f "${JAR_NAME}" | head -n 1 || true)"
    fi

    if [ -n "${TARGET_PID}" ] && kill -0 "${TARGET_PID}" 2>/dev/null; then
        echo " -> [${NODE_KEY}] 프로세스(PID: ${TARGET_PID}) 정상 종료 시그널(SIGTERM) 전송..."
        kill -15 "${TARGET_PID}" 2>/dev/null || true
        for i in $(seq 1 8); do
            if ! kill -0 "${TARGET_PID}" 2>/dev/null; then
                break
            fi
            sleep 1
        done
        if kill -0 "${TARGET_PID}" 2>/dev/null; then
            kill -9 "${TARGET_PID}" 2>/dev/null || true
        fi
        echo " -> [${NODE_KEY}] 서비스 중지 완료."
    else
        echo " -> [${NODE_KEY}] 현재 실행중인 프로세스가 없습니다."
    fi
    rm -f "${PID_FILE}"

    tar -czf "${BACKUP_ARCHIVE}" -C "${APP_DIR}/data" "${DATA_SUBDIR}"
    cp -f "${BACKUP_ARCHIVE}" "${LATEST_ARCHIVE}"
    echo " -> [${NODE_KEY}] 런타임 데이터 백업 완료: ${BACKUP_ARCHIVE}"
}

case "${TARGET_NODE}" in
    node1)
        stop_and_backup_single_node "node1" "happycall-node1-telephony-gw.jar" "node1-telephony"
        ;;
    node2)
        stop_and_backup_single_node "node2" "happycall-node2-speech-ai.jar" "node2-speech"
        ;;
    node3)
        stop_and_backup_single_node "node3" "happycall-node3-llm-orchestrator.jar" "node3-llm"
        ;;
    node4)
        stop_and_backup_single_node "node4" "happycall-node4-legacy-sync.jar" "node4-legacy"
        ;;
    node5)
        stop_and_backup_single_node "node5" "happycall-node5-admin-console.jar" "node5-console"
        ;;
    all|*)
        stop_and_backup_single_node "node5" "happycall-node5-admin-console.jar" "node5-console"
        stop_and_backup_single_node "node4" "happycall-node4-legacy-sync.jar" "node4-legacy"
        stop_and_backup_single_node "node3" "happycall-node3-llm-orchestrator.jar" "node3-llm"
        stop_and_backup_single_node "node2" "happycall-node2-speech-ai.jar" "node2-speech"
        stop_and_backup_single_node "node1" "happycall-node1-telephony-gw.jar" "node1-telephony"
        ;;
esac

echo "============================================================================"
echo "[STOP_PROD] 요청 대상(${TARGET_NODE}) 서비스 중지 및 데이터 백업이 완료되었습니다."
echo "============================================================================"
