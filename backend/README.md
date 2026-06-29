# WheelCharge Backend

FastAPI 기반 WheelCharge MVP 백엔드입니다.

현재 단계에서는 `data/processed/chargers_gunsan_app.json` 파일을 읽어 군산시 전동휠체어 충전소 API를 제공합니다.

## 실행 방법

```bash
cd WheelCharge/backend
pip install -r requirements.txt
uvicorn app.main:app --reload
```

프로젝트 최상위 폴더인 `WheelCharge`에서 실행하는 경우:

```bash
uvicorn backend.app.main:app --reload
```

## 구현 API

- `GET /health`
- `GET /chargers`
- `GET /chargers/{charger_id}`
- `GET /nearest?lat=&lng=&limit=5`

## 향후 예정 API

- `GET /route`
- `GET /route-recommend`
