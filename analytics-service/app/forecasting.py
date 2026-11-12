"""Proyección de demanda semanal por referencia (RF-18).

Es un apoyo cuantitativo a la decisión de la administradora: nunca ejecuta compras.
Modelos estadísticos simples, acordes con el bajo volumen del micronegocio (el alcance excluye
deep learning). Para cada serie se prueban varios candidatos con validación retrospectiva
(un paso adelante) y se elige el de menor WAPE.
"""
from __future__ import annotations

import math
from dataclasses import dataclass
from datetime import date, timedelta
from typing import Callable

import numpy as np

MIN_SEMANAS_BASICO = 4     # menos que esto: promedio simple, sin intervalos
MIN_SEMANAS_MODELO = 8     # desde aquí se compara entre modelos con backtesting
VENTANA_BACKTEST = 26      # máximo de semanas evaluadas retrospectivamente
Z_80 = 1.2816              # intervalo de predicción del 80 %
VERSION_MODELO = "1.0.0"


def inicio_semana(d: date) -> date:
    """Lunes de la semana de `d`."""
    return d - timedelta(days=d.weekday())


def serie_semanal(observaciones: list[tuple[date, float]], fecha_corte: date) -> tuple[date | None, np.ndarray]:
    """Agrega a semanas completas (lunes-domingo) anteriores a la semana de `fecha_corte`.

    La semana en curso se excluye porque está incompleta y subestimaría la demanda.
    Las semanas sin ventas dentro del rango cuentan como 0.
    """
    corte = inicio_semana(fecha_corte)
    validas = [(f, u) for f, u in observaciones if f < corte]
    if not validas:
        return None, np.zeros(0)
    primera = inicio_semana(min(f for f, _ in validas))
    n = (corte - primera).days // 7
    y = np.zeros(n)
    for f, u in validas:
        y[(inicio_semana(f) - primera).days // 7] += u
    return primera, y


# --- Modelos: cada uno recibe la serie y el horizonte y devuelve h pronósticos ---

def _media_movil(y: np.ndarray, h: int, k: int = 4) -> np.ndarray:
    k = min(k, len(y))
    return np.full(h, float(y[-k:].mean()))


def _ses(y: np.ndarray, h: int, alpha: float) -> np.ndarray:
    nivel = float(y[0])
    for v in y[1:]:
        nivel = alpha * v + (1 - alpha) * nivel
    return np.full(h, nivel)


def _holt_amortiguado(y: np.ndarray, h: int, alpha: float, beta: float, phi: float = 0.9) -> np.ndarray:
    nivel, tend = float(y[0]), 0.0
    for v in y[1:]:
        previo = nivel
        nivel = alpha * v + (1 - alpha) * (previo + phi * tend)
        tend = beta * (nivel - previo) + (1 - beta) * phi * tend
    salida, acum = [], 0.0
    for i in range(1, h + 1):
        acum += phi ** i
        salida.append(nivel + acum * tend)
    return np.array(salida)


def _croston_sba(y: np.ndarray, h: int, alpha: float = 0.1) -> np.ndarray:
    """Croston con corrección Syntetos-Boylan para demanda intermitente (muchas semanas en cero)."""
    nz = np.flatnonzero(y)
    if len(nz) == 0:
        return np.zeros(h)
    z, p, q = float(y[nz[0]]), float(nz[0] + 1), 1
    for i in range(nz[0] + 1, len(y)):
        if y[i] > 0:
            z = alpha * y[i] + (1 - alpha) * z
            p = alpha * q + (1 - alpha) * p
            q = 1
        else:
            q += 1
    return np.full(h, (1 - alpha / 2) * z / p)


@dataclass(frozen=True)
class Candidato:
    nombre: str
    fn: Callable[[np.ndarray, int], np.ndarray]


CANDIDATOS: tuple[Candidato, ...] = (
    Candidato("MEDIA_MOVIL_4", lambda y, h: _media_movil(y, h, 4)),
    Candidato("SES(alpha=0.2)", lambda y, h: _ses(y, h, 0.2)),
    Candidato("SES(alpha=0.4)", lambda y, h: _ses(y, h, 0.4)),
    Candidato("HOLT_AMORTIGUADO(0.3,0.1)", lambda y, h: _holt_amortiguado(y, h, 0.3, 0.1)),
    Candidato("CROSTON_SBA", lambda y, h: _croston_sba(y, h)),
)


def _backtest(c: Candidato, y: np.ndarray) -> tuple[float, np.ndarray]:
    """Error WAPE y residuos de pronosticar un paso adelante sobre las últimas semanas."""
    inicio = max(MIN_SEMANAS_BASICO, len(y) - VENTANA_BACKTEST)
    errores, reales = [], []
    for t in range(inicio, len(y)):
        pred = float(c.fn(y[:t], 1)[0])
        errores.append(y[t] - pred)
        reales.append(y[t])
    e, r = np.array(errores), np.array(reales)
    suma = float(r.sum())
    if suma <= 0:
        return (0.0 if float(np.abs(e).sum()) == 0 else math.inf), e
    return float(np.abs(e).sum() / suma), e


def pronosticar_serie(observaciones: list[tuple[date, float]], fecha_corte: date, horizonte: int) -> dict:
    primera, y = serie_semanal(observaciones, fecha_corte)
    n = len(y)
    advertencias: list[str] = []
    inicio0 = inicio_semana(fecha_corte)
    periodos_base = [(inicio0 + timedelta(days=7 * i), inicio0 + timedelta(days=7 * i + 6)) for i in range(horizonte)]

    if n < MIN_SEMANAS_BASICO:
        media = float(y.mean()) if n else 0.0
        advertencias.append(
            "Histórico insuficiente (menos de 4 semanas completas): estimación de baja confianza."
            if n else "Sin histórico de ventas: la proyección es 0 y no es confiable.")
        return _armar("PROMEDIO_SIMPLE", None, n, periodos_base, np.full(horizonte, media), None, advertencias)

    if n < MIN_SEMANAS_MODELO:
        pred = _media_movil(y, horizonte, 4)
        sigma = float(np.std(y, ddof=1)) if n > 1 else 0.0
        advertencias.append("Histórico corto (menos de 8 semanas): no se compararon modelos; use con cautela.")
        return _armar("MEDIA_MOVIL_4", None, n, periodos_base, pred, sigma, advertencias)

    mejor: tuple[float, Candidato, np.ndarray] | None = None
    for c in CANDIDATOS:
        wape, residuos = _backtest(c, y)
        if mejor is None or wape < mejor[0]:
            mejor = (wape, c, residuos)
    assert mejor is not None
    wape, c, residuos = mejor
    if math.isinf(wape):
        wape_out = None
        advertencias.append("No fue posible estimar el error del modelo.")
    else:
        wape_out = round(wape, 4)
        if wape > 0.6:
            advertencias.append("Error de validación alto (WAPE > 60 %): demanda muy irregular; interprete con cautela.")
    sigma = float(np.std(residuos, ddof=1)) if len(residuos) > 1 else float(np.abs(residuos).mean() if len(residuos) else 0.0)
    return _armar(c.nombre, wape_out, n, periodos_base, c.fn(y, horizonte), sigma, advertencias)


def _armar(modelo: str, wape: float | None, n: int, periodos, pred: np.ndarray,
           sigma: float | None, advertencias: list[str]) -> dict:
    salida = []
    for i, ((ini, fin), valor) in enumerate(zip(periodos, pred), start=1):
        v = max(0.0, float(valor))
        if sigma is None:
            inf = sup = None
        else:
            amplitud = Z_80 * sigma * math.sqrt(i)
            inf, sup = round(max(0.0, v - amplitud), 3), round(v + amplitud, 3)
        salida.append({"inicio": ini, "fin": fin, "demanda_proyectada": round(v, 3),
                       "limite_inferior": inf, "limite_superior": sup})
    return {"modelo": modelo, "error_wape": wape, "observaciones_semanales": n,
            "periodos": salida, "advertencias": advertencias}
