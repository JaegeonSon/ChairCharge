import json
from functools import lru_cache
from pathlib import Path

from ..models.charger import Charger, ChargerWithDistance
from ..utils.distance import haversine_distance_m


DATA_RELATIVE_PATH = Path("data") / "processed" / "chargers_gunsan_app.json"


def _find_project_root() -> Path:
    """Find WheelCharge root regardless of whether the server starts in root or backend."""
    current_file = Path(__file__).resolve()
    candidates = [Path.cwd().resolve(), *current_file.parents]

    for candidate in candidates:
        if (candidate / DATA_RELATIVE_PATH).is_file():
            return candidate

    raise FileNotFoundError(f"Cannot find {DATA_RELATIVE_PATH}")


def _charger_data_path() -> Path:
    return _find_project_root() / DATA_RELATIVE_PATH


@lru_cache(maxsize=1)
def get_all_chargers() -> list[Charger]:
    data_path = _charger_data_path()

    try:
        with data_path.open("r", encoding="utf-8") as file:
            raw_chargers = json.load(file)
    except json.JSONDecodeError as exc:
        raise ValueError(f"Invalid charger JSON file: {data_path}") from exc

    if not isinstance(raw_chargers, list):
        raise ValueError("Charger data must be a JSON array")

    return [Charger(**item) for item in raw_chargers]


def get_charger_by_id(charger_id: str) -> Charger | None:
    return next(
        (charger for charger in get_all_chargers() if charger.id == charger_id),
        None,
    )


def _model_to_dict(charger: Charger) -> dict:
    if hasattr(charger, "model_dump"):
        return charger.model_dump()
    return charger.dict()


def get_nearest_chargers(lat: float, lng: float, limit: int = 5) -> list[ChargerWithDistance]:
    nearest = []

    for charger in get_all_chargers():
        distance_m = haversine_distance_m(lat, lng, charger.lat, charger.lng)
        charger_data = _model_to_dict(charger)
        charger_data["distance_m"] = round(distance_m, 2)
        nearest.append(ChargerWithDistance(**charger_data))

    return sorted(nearest, key=lambda charger: charger.distance_m)[:limit]
