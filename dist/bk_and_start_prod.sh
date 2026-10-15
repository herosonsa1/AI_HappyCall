#!/usr/bin/env bash
# ============================================================================
# [운영환경 - 폐쇄망 Linux] bk_and_start_prod.sh (3-Node 통합 아키텍처 지원)
# 사용법:
#   ./bk_and_start_prod.sh all    (기본값: Node 1~3 전체 중지 -> 노드별 백업 -> 복원 -> 개별 포트 기동)
#   ./bk_and_start_prod.sh node1  (통합코어서버: happycall-node1-core-platform.jar / HTTP 8080, AudioSocket 9092)
#   ./bk_and_start_prod.sh node2  (GPU음성서버 : happycall-node2-speech-ai.jar / HTTP 8082)
#   ./bk_and_start_prod.sh node3  (GPULLM서버  : happycall-node3-llm-orchestrator.jar / HTTP 8083)
# ============================================================================

set -euo pipefail

APP_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "${APP_DIR}"

TARGET_NODE="${1:-all}"
BACKUP_ROOT="${APP_DIR}/backup"
LOG_ROOT="${APP_DIR}/logs"
TIMESTAMP="$(date +'%Y%m%d_%H%M%S')"

mkdir -p "${BACKUP_ROOT}" "${LOG_ROOT}"

bk_and_start_single_node() {
    local NODE_KEY="$1"
    local JAR_NAME="$2"
    local NODE_PORT="$3"
    local DATA_SUBDIR="$4"

    local PID_FILE="${APP_DIR}/${NODE_KEY}_prod.pid"
    local NODE_DATA_DIR="${APP_DIR}/data/${DATA_SUBDIR}"
    local BACKUP_ARCHIVE="${BACKUP_ROOT}/${NODE_KEY}_data_${TIMESTAMP}.tar.gz"
    local LATEST_ARCHIVE="${BACKUP_ROOT}/${NODE_KEY}_latest_backup.tar.gz"
    local JAR_PATH="${APP_DIR}/${JAR_NAME}"

    if [ ! -f "${JAR_PATH}" ] && [ -f "${APP_DIR}/${DATA_SUBDIR}/${JAR_NAME}" ]; then
        JAR_PATH="${APP_DIR}/${DATA_SUBDIR}/${JAR_NAME}"
    fi

    mkdir -p "${NODE_DATA_DIR}"

    echo "----------------------------------------------------------------------------"
    echo "[${NODE_KEY}] 1) 기존 서비스 중지 -> 2) 데이터 백업 -> 3) 데이터 복원 및 서비스 기동"
    echo " - 대상 JAR  : ${JAR_NAME}"
    echo " - 전용 포트 : ${NODE_PORT}"
    echo " - 데이터경로: ${NODE_DATA_DIR}"
    echo "----------------------------------------------------------------------------"

    # 1. 현재 기동중인 해당 노드 서비스 중지
    local TARGET_PID=""
    if [ -f "${PID_FILE}" ]; then
        TARGET_PID="$(cat "${PID_FILE}" 2>/dev/null || true)"
    fi
    if [ -z "${TARGET_PID}" ] || ! kill -0 "${TARGET_PID}" 2>/dev/null; then
        TARGET_PID="$(pgrep -f "${JAR_NAME}" | head -n 1 || true)"
    fi

    if [ -n "${TARGET_PID}" ] && kill -0 "${TARGET_PID}" 2>/dev/null; then
        echo " -> [${NODE_KEY}] 기동중인 프로세스(PID: ${TARGET_PID}) 종료 중..."
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
        echo " -> [${NODE_KEY}] 기존 프로세스 중지 완료."
    else
        echo " -> [${NODE_KEY}] 기동중인 기존 프로세스 없음."
    fi
    rm -f "${PID_FILE}"

    # 2. 현재 기동중이던 노드 데이터 백업
    if [ -n "$(ls -A "${NODE_DATA_DIR}" 2>/dev/null || true)" ]; then
        tar -czf "${BACKUP_ARCHIVE}" -C "${APP_DIR}/data" "${DATA_SUBDIR}"
        cp -f "${BACKUP_ARCHIVE}" "${LATEST_ARCHIVE}"
        echo " -> [${NODE_KEY}] 데이터 백업 완료: ${BACKUP_ARCHIVE}"
    fi

    # 3. 백업 데이터 복원 및 서비스 시작
    if [ -f "${LATEST_ARCHIVE}" ]; then
        tar -xzf "${LATEST_ARCHIVE}" -C "${APP_DIR}/data"
        echo " -> [${NODE_KEY}] 백업 스냅샷(${LATEST_ARCHIVE}) 복원 완료."
    fi

    nohup java -server -Xms256m -Xmx1024m -Dfile.encoding=UTF-8 \
        -jar "${JAR_PATH}" \
        --mode=prod \
        --port="${NODE_PORT}" \
        --data-dir="${NODE_DATA_DIR}" \
        > "${LOG_ROOT}/${NODE_KEY}_prod.out" 2>&1 &

    local NEW_PID=$!
    echo "${NEW_PID}" > "${PID_FILE}"
    sleep 1
    echo " -> [${NODE_KEY}] 서비스 기동 완료 (PID: ${NEW_PID}, Port: ${NODE_PORT})"
}

case "${TARGET_NODE}" in
    node1)
        bk_and_start_single_node "node1" "happycall-node1-core-platform.jar" "${NODE1_PORT:-8080}" "node1-core"
        ;;
    node2)
        bk_and_start_single_node "node2" "happycall-node2-speech-ai.jar" "${NODE2_PORT:-8082}" "node2-speech"
        ;;
    node3)
        bk_and_start_single_node "node3" "happycall-node3-llm-orchestrator.jar" "${NODE3_PORT:-8083}" "node3-llm"
        ;;
    all|*)
        bk_and_start_single_node "node1" "happycall-node1-core-platform.jar" "${NODE1_PORT:-8080}" "node1-core"
        bk_and_start_single_node "node2" "happycall-node2-speech-ai.jar" "${NODE2_PORT:-8082}" "node2-speech"
        bk_and_start_single_node "node3" "happycall-node3-llm-orchestrator.jar" "${NODE3_PORT:-8083}" "node3-llm"
        ;;
esac

echo "============================================================================"
echo "[BK_AND_START_PROD] 요청 대상(${TARGET_NODE}) 백업·복원 및 기동이 완료되었습니다."
echo "============================================================================"
