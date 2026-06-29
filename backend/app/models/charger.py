from typing import Optional

from pydantic import BaseModel


class Charger(BaseModel):
    id: str
    name: str
    address: str
    lat: float
    lng: float
    install_place: Optional[str] = None
    install_type: Optional[str] = None
    is_indoor: bool = False
    is_outdoor: bool = False
    is_movable: bool = False
    weekday_open: Optional[str] = None
    weekday_close: Optional[str] = None
    saturday_open: Optional[str] = None
    saturday_close: Optional[str] = None
    holiday_open: Optional[str] = None
    holiday_close: Optional[str] = None
    is_24h: bool = False
    simultaneous_count: Optional[int] = None
    air_pump_available: bool = False
    phone_charge_available: bool = False
    contact_phone: Optional[str] = None
    data_date: Optional[str] = None
    review_status: Optional[str] = None
    review_notes: Optional[str] = None


class ChargerWithDistance(Charger):
    distance_m: float
