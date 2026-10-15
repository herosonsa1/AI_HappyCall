#!/usr/bin/env bash
# ============================================================================
# [운영환경 - 폐쇄망/온프레미스 리눅스 물리 서버 #1] install_freepbx17_linux.sh
# - 역할: FreePBX 17 + Asterisk 20 LTS 설정 자동 배포 및 AudioSocket/ARI/PJSIP 활성화
# - 대상 서버: 물리 서버 #1 (Node 1 통합 코어 서버와 동거 가능, 또는 전용 VM)
# - 실행 방법: sudo ./install_freepbx17_linux.sh [NODE1_IP:PORT]
#   예시: sudo ./install_freepbx17_linux.sh 127.0.0.1:9092
# ============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
NODE1_AS_ADDR="${1:-127.0.0.1:9092}"
ASTERISK_ETC="/etc/asterisk"

echo "============================================================================"
echo "[HERO AI HAPPYCALL] FreePBX 17 (Asterisk 20 LTS) 설정 자동 구성 스크립트"
echo " - 연동 대상 Node 1 AudioSocket 주소: ${NODE1_AS_ADDR}"
echo "============================================================================"

if [ ! -d "${ASTERISK_ETC}" ]; then
    echo "[INFO] /etc/asterisk 디렉토리가 없어 생성합니다 (또는 Docker 컨테이너 모드로 기동 가능)."
    mkdir -p "${ASTERISK_ETC}"
fi

echo "[STEP 1/4] 기존 Asterisk 커스텀 설정 백업..."
BACKUP_TS="$(date +'%Y%m%d_%H%M%S')"
mkdir -p "${ASTERISK_ETC}/backup_${BACKUP_TS}"
for f in pjsip_custom.conf extensions_custom.conf ari_additional_custom.conf http_custom.conf manager_custom.conf; do
    if [ -f "${ASTERISK_ETC}/${f}" ]; then
        cp -f "${ASTERISK_ETC}/${f}" "${ASTERISK_ETC}/backup_${BACKUP_TS}/${f}"
    fi
done

echo "[STEP 2/4] AI 해피콜 전용 PJSIP / AudioSocket Dialplan / ARI / AMI 설정 배포..."
cp -f "${SCRIPT_DIR}/asterisk-etc/pjsip_custom.conf" "${ASTERISK_ETC}/pjsip_custom.conf"
cp -f "${SCRIPT_DIR}/asterisk-etc/extensions_custom.conf" "${ASTERISK_ETC}/extensions_custom.conf"
cp -f "${SCRIPT_DIR}/asterisk-etc/ari_additional_custom.conf" "${ASTERISK_ETC}/ari_additional_custom.conf"
cp -f "${SCRIPT_DIR}/asterisk-etc/http_custom.conf" "${ASTERISK_ETC}/http_custom.conf"
cp -f "${SCRIPT_DIR}/asterisk-etc/manager_custom.conf" "${ASTERISK_ETC}/manager_custom.conf"

# 운영 서버 Node 1 AudioSocket 주소 치환
sed -i "s|NODE1_AUDIOSOCKET_ADDR=.*|NODE1_AUDIOSOCKET_ADDR=${NODE1_AS_ADDR}|g" "${ASTERISK_ETC}/extensions_custom.conf"

echo "[STEP 3/4] OS 방화벽(firewalld/ufw) SIP/RTP/ARI/AudioSocket 포트 허용 점검..."
if command -v firewall-cmd >/dev/null 2>&1; then
    firewall-cmd --permanent --add-port=5060/udp || true
    firewall-cmd --permanent --add-port=5060/tcp || true
    firewall-cmd --permanent --add-port=10000-10100/udp || true
    firewall-cmd --permanent --add-port=8088/tcp || true
    firewall-cmd --permanent --add-port=5038/tcp || true
    firewall-cmd --permanent --add-port=9092/tcp || true
    firewall-cmd --reload || true
fi

echo "[STEP 4/4] FreePBX (fwconsole) 및 Asterisk 20 모듈 리로드..."
if command -v fwconsole >/dev/null 2>&1; then
    fwconsole chown
    fwconsole reload
fi

if command -v asterisk >/dev/null 2>&1; then
    asterisk -rx "module load res_audiosocket.so" || true
    asterisk -rx "module load app_audiosocket.so" || true
    asterisk -rx "module load res_ari.so" || true
    asterisk -rx "module load res_ari_channels.so" || true
    asterisk -rx "core reload"
    echo "----------------------------------------------------------------------------"
    echo "[검증] Asterisk AudioSocket & ARI 로드 상태:"
    asterisk -rx "module show like audiosocket" || true
    asterisk -rx "ari show status" || true
    asterisk -rx "pjsip show endpoints" || true
else
    echo "[WARN] 현재 호스트에 네이티브 asterisk 바이너리가 없습니다."
    echo "       Docker 환경인 경우: docker compose -f ${SCRIPT_DIR}/docker-compose.freepbx.yml up -d"
fi

echo "============================================================================"
echo "[완료] FreePBX 17 + Asterisk 20 AI 해피콜 연동 구성이 완료되었습니다."
echo "============================================================================"
