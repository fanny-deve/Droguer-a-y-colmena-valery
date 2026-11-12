from __future__ import annotations

from datetime import date, datetime
from typing import Literal

from pydantic import BaseModel, Field


class Observacion(BaseModel):
    fecha: date
    unidades: float = Field(ge=0, le=1_000_000)


class Serie(BaseModel):
    producto_id: str = Field(min_length=1, max_length=64)
    observaciones: list[Observacion] = Field(max_length=4000)


class SolicitudProyeccion(BaseModel):
    fecha_corte: date = Field(description="Fecha de referencia (hoy en Colombia). La semana en curso se excluye.")
    horizonte_semanas: int = Field(default=4, ge=1, le=26)
    series: list[Serie] = Field(min_length=1, max_length=1000)


class Periodo(BaseModel):
    inicio: date
    fin: date
    demanda_proyectada: float
    limite_inferior: float | None
    limite_superior: float | None


class ResultadoSerie(BaseModel):
    producto_id: str
    modelo: str
    error_wape: float | None
    observaciones_semanales: int
    periodos: list[Periodo]
    advertencias: list[str]


class RespuestaProyeccion(BaseModel):
    version_modelo: str
    generado_en: datetime
    granularidad: Literal["SEMANAL"] = "SEMANAL"
    aviso: str = "Apoyo cuantitativo a la decisión humana; el sistema no ejecuta compras automáticas."
    resultados: list[ResultadoSerie]
