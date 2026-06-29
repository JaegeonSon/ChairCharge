from fastapi import FastAPI, HTTPException, Query
from fastapi.middleware.cors import CORSMiddleware

from .models.charger import Charger, ChargerWithDistance
from .services.charger_service import (
    get_all_chargers,
    get_charger_by_id,
    get_nearest_chargers,
)


app = FastAPI(
    title="WheelCharge API",
    description="WheelCharge MVP backend for Gunsan wheelchair charger data.",
    version="0.1.0",
)

# MVP 단계에서는 Android 앱 개발/테스트 편의를 위해 모든 Origin을 허용한다.
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


@app.get("/health")
def health_check() -> dict[str, str]:
    return {
        "status": "ok",
        "service": "WheelCharge API",
    }


@app.get("/chargers", response_model=list[Charger])
def read_chargers() -> list[Charger]:
    try:
        return get_all_chargers()
    except (FileNotFoundError, ValueError) as exc:
        raise HTTPException(status_code=500, detail=str(exc)) from exc


@app.get("/chargers/{charger_id}", response_model=Charger)
def read_charger(charger_id: str) -> Charger:
    try:
        charger = get_charger_by_id(charger_id)
    except (FileNotFoundError, ValueError) as exc:
        raise HTTPException(status_code=500, detail=str(exc)) from exc

    if charger is None:
        raise HTTPException(status_code=404, detail="Charger not found")

    return charger


@app.get("/nearest", response_model=list[ChargerWithDistance])
def read_nearest_chargers(
    lat: float = Query(..., ge=-90, le=90),
    lng: float = Query(..., ge=-180, le=180),
    limit: int = Query(5, ge=1, le=50),
) -> list[ChargerWithDistance]:
    try:
        return get_nearest_chargers(lat=lat, lng=lng, limit=limit)
    except (FileNotFoundError, ValueError) as exc:
        raise HTTPException(status_code=500, detail=str(exc)) from exc
