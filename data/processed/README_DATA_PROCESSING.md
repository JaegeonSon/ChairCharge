# 군산시 전동휠체어 급속충전기 데이터 처리 보고서

## 처리 결과 요약
- CSV 원본: 20건
- 군산시 PDF 원본: 19건
- 통합 master 데이터: 21건
- 앱 지도 마커 표시 가능: 20건
- 좌표 보완 필요: 1건
- 검토 필요 항목: 8건

## 생성 파일
- `chargers_gunsan_master.csv`: CSV + 군산시 PDF 교차검증 통합본
- `chargers_gunsan_app.csv`: 앱/서버에서 바로 읽기 쉬운 좌표 보유 데이터 CSV
- `chargers_gunsan_app.json`: Android 앱 지도 마커/상세화면용 JSON
- `chargers_gunsan.sqlite`: FastAPI 서버 테스트용 SQLite DB
- `chargers_gunsan_issues.csv`: 데이터 검토 필요 항목
- `schema_summary.json`: 필드 목록 및 건수 요약

## 교차검증
- CSV와 PDF 양쪽에 존재: 18건
- CSV에만 존재: 2건 — 근대역사박물관, 군산해누리노인복지관
- PDF에만 존재: 1건 — 대한노인회 군산시지회

## 보완 및 표준화 내용
- 시설명 표준화: `주공4차아파트` → `나운동 주공4차아파트`, `금강노인복지관` → `군산금강노인복지관`
- 주소 표기 정리: `전북 특별자치도` → `전북특별자치도`
- CSV 도로명주소 공란 보완: 군산수송공원, 소룡동 동아아파트 앞 공원, 디오션시티 철길공원
- 연락처 우선순위: PDF 문의처가 있으면 `contact_phone`에 우선 반영, 없으면 CSV 관리기관전화번호 사용

## 개발 사용 권장
- 앱 마커 표시: `chargers_gunsan_app.json`
- 서버 테스트 DB: `chargers_gunsan.sqlite`
- 데이터 검수 및 제안서 근거: `chargers_gunsan_master.csv`, `chargers_gunsan_issues.csv`

## 추가 작업 필요
1. `대한노인회 군산시지회`는 PDF에만 있고 좌표가 없어 지도 표시 전 지오코딩 필요
2. `근대역사박물관`, `군산해누리노인복지관`은 CSV에만 있어 군산시 최신 목록 또는 현장/전화 확인 권장
3. PDF는 공공누리 제4유형으로 표시되어 있으므로 출처 표시, 비상업 이용, 변경금지 조건을 제안서/발표자료에 명시 필요
4. TMAP/Kakao Map API 연동 후 실제 이동 경로 좌표를 받아 접근성 점수 파생변수 생성 필요
