#!/usr/bin/env bash
# 필드 선택 알고리즘 한 번에: 1단계(field_select.py, AI 없음) → 2·3단계(FieldSearchRunner, AI 확인 · 되돌려 빼기)
# 결과: results/fieldsearch_<시각>/ summary.json · steps.csv · selected_template.yaml (보안팀 검토 뒤 templates/ 로)
#
# 기본값은 OTel 데모. 다른 시스템은 같은 폴더 형식(raw.jsonl · injected_pii.yaml · truth.yaml)으로 만들고 환경변수로 바꾼다.
#   DIR=data/rcaeval/incidents NORMAL=normal SELECT=a,b,c VALIDATE=d,e HAND= scripts/fieldsearch.sh
# 주의: 2·3단계는 후보 칸을 실제로 AI 에 보낸다. 가짜 민감값 데이터로만 돌린다 (10/27 결정).
#   YG_AI_MOCK=true 면 AI 를 부르지 않고 흐름만 확인한다.
set -euo pipefail
cd "$(dirname "$0")/.."
DIR=${DIR:-scenarios}
NORMAL=${NORMAL:-S0_normal}
SELECT=${SELECT:-}
VALIDATE=${VALIDATE:-E2a_adFailure,E2b_cartFailure}
CONTEXT=${CONTEXT:-resource.service.name,logRecord.timeUnixNano,logRecord.severityText}
HAND=${HAND-otel}
CAND=${CAND:-data/fieldsearch_candidates.json}

python3 scripts/field_select.py --dir "$DIR" --normal "$NORMAL" --select "$SELECT" --validate "$VALIDATE" \
  --context "$CONTEXT" --hand "$HAND" --json "$CAND"
./gradlew -q bootRun --args="--spring.profiles.active=fieldsearch --yeogwasigan.fieldsearch.candidates-file=$CAND"
