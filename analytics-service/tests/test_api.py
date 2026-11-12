import os
from datetime import date, timedelta

import pytest
from fastapi.testclient import TestClient

os.environ["ANALYTICS_API_KEY"] = "clave-de-prueba-123"
os.environ.pop("REDIS_URL", None)

from app.main import app  # noqa: E402

cliente = TestClient(app)
H = {"X-API-Key": "clave-de-prueba-123"}


def cuerpo(n=20):
    ini = date(2026, 6, 1)
    obs = [{"fecha": str(ini + timedelta(days=7 * i)), "unidades": 10 + (i % 3)} for i in range(n)]
    return {"fecha_corte": "2026-11-12", "horizonte_semanas": 4,
            "series": [{"producto_id": "p-1", "observaciones": obs}]}


def test_health_publico():
    assert cliente.get("/health").json()["status"] == "ok"


def test_sin_api_key_401():
    assert cliente.post("/v1/proyecciones", json=cuerpo()).status_code == 401


def test_api_key_incorrecta_401():
    assert cliente.post("/v1/proyecciones", json=cuerpo(), headers={"X-API-Key": "mala"}).status_code == 401


def test_servidor_sin_clave_configurada_falla_cerrado(monkeypatch):
    monkeypatch.delenv("ANALYTICS_API_KEY")
    assert cliente.post("/v1/proyecciones", json=cuerpo(), headers=H).status_code == 503


def test_proyeccion_ok():
    r = cliente.post("/v1/proyecciones", json=cuerpo(), headers=H)
    assert r.status_code == 200
    d = r.json()
    assert d["granularidad"] == "SEMANAL" and len(d["resultados"]) == 1
    res = d["resultados"][0]
    assert res["producto_id"] == "p-1" and len(res["periodos"]) == 4
    assert all(p["demanda_proyectada"] >= 0 for p in res["periodos"])


@pytest.mark.parametrize("mod", [
    lambda b: b.update(horizonte_semanas=0),
    lambda b: b.update(horizonte_semanas=99),
    lambda b: b.update(series=[]),
    lambda b: b["series"][0]["observaciones"][0].update(unidades=-5),
    lambda b: b.pop("fecha_corte"),
])
def test_validaciones_422(mod):
    b = cuerpo()
    mod(b)
    assert cliente.post("/v1/proyecciones", json=b, headers=H).status_code == 422
