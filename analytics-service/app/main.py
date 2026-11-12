from __future__ import annotations

import os
import secrets
from datetime import datetime, timezone

from fastapi import Depends, FastAPI, Header, HTTPException, Response, status

from . import cache
from .forecasting import VERSION_MODELO, pronosticar_serie
from .schemas import RespuestaProyeccion, ResultadoSerie, SolicitudProyeccion

app = FastAPI(
    title="Microservicio de Proyección de Demanda — Droguería & Colmena Valery",
    version=VERSION_MODELO,
    description="Servicio analítico aislado del núcleo transaccional (RF-18). Sin acceso a la base de datos: "
                "recibe series de ventas por API y devuelve proyecciones semanales.",
)


def verificar_api_key(x_api_key: str | None = Header(default=None)) -> None:
    """Falla cerrado: sin clave configurada en el servidor, nadie accede (RNF-07)."""
    esperada = os.getenv("ANALYTICS_API_KEY", "")
    if not esperada:
        raise HTTPException(status.HTTP_503_SERVICE_UNAVAILABLE, "Servicio sin ANALYTICS_API_KEY configurada")
    if x_api_key is None or not secrets.compare_digest(x_api_key.encode(), esperada.encode()):
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "API key inválida")


@app.get("/health", tags=["Estado"])
def health() -> dict:
    return {"status": "ok", "version": VERSION_MODELO}


@app.post("/v1/proyecciones", response_model=RespuestaProyeccion, tags=["Proyección"],
          dependencies=[Depends(verificar_api_key)])
def proyectar(solicitud: SolicitudProyeccion) -> Response:
    cuerpo = solicitud.model_dump_json()
    k = cache.clave(cuerpo)
    previo = cache.leer(k)
    if previo is not None:
        return Response(content=previo, media_type="application/json", headers={"X-Cache": "HIT"})

    resultados = []
    for s in solicitud.series:
        r = pronosticar_serie([(o.fecha, o.unidades) for o in s.observaciones],
                              solicitud.fecha_corte, solicitud.horizonte_semanas)
        resultados.append(ResultadoSerie(producto_id=s.producto_id, **r))
    respuesta = RespuestaProyeccion(version_modelo=VERSION_MODELO, generado_en=datetime.now(timezone.utc),
                                    resultados=resultados)
    salida = respuesta.model_dump_json()
    cache.guardar(k, salida)
    return Response(content=salida, media_type="application/json", headers={"X-Cache": "MISS"})
