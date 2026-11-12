from datetime import date, timedelta

import numpy as np
import pytest

from app.forecasting import inicio_semana, pronosticar_serie, serie_semanal

LUNES = date(2026, 1, 5)          # lunes
CORTE = date(2026, 11, 12)        # jueves


def diario(valores_por_semana, inicio=LUNES):
    """Una observación por semana (el lunes) con el valor dado."""
    return [(inicio + timedelta(days=7 * i), float(v)) for i, v in enumerate(valores_por_semana)]


def test_inicio_semana_es_lunes():
    assert inicio_semana(date(2026, 11, 12)) == date(2026, 11, 9)
    assert inicio_semana(date(2026, 11, 15)) == date(2026, 11, 9)   # domingo
    assert inicio_semana(date(2026, 11, 16)) == date(2026, 11, 16)  # lunes siguiente


def test_agregacion_excluye_semana_en_curso_y_rellena_ceros():
    obs = [(date(2026, 11, 2), 5.0), (date(2026, 11, 4), 3.0),     # semana 2-nov: 8
           (date(2026, 11, 10), 99.0)]                             # semana en curso: excluida
    primera, y = serie_semanal(obs, CORTE)
    assert primera == date(2026, 11, 2)
    assert list(y) == [8.0]
    obs2 = [(date(2026, 10, 19), 4.0), (date(2026, 11, 4), 6.0)]   # hueco de 1 semana
    _, y2 = serie_semanal(obs2, CORTE)
    assert list(y2) == [4.0, 0.0, 6.0]


def test_sin_historico_devuelve_cero_con_advertencia():
    r = pronosticar_serie([], CORTE, 4)
    assert r["modelo"] == "PROMEDIO_SIMPLE"
    assert all(p["demanda_proyectada"] == 0 for p in r["periodos"])
    assert r["advertencias"]


def test_historico_corto_advierte_y_no_da_intervalos():
    r = pronosticar_serie(diario([10, 10, 10], date(2026, 10, 19)), CORTE, 2)
    assert r["modelo"] == "PROMEDIO_SIMPLE"
    assert r["periodos"][0]["limite_inferior"] is None
    assert r["periodos"][0]["demanda_proyectada"] == pytest.approx(10.0)


def test_serie_constante_proyecta_constante_y_wape_cero():
    obs = diario([20] * 30, date(2026, 4, 20))
    r = pronosticar_serie(obs, CORTE, 4)
    assert r["error_wape"] == 0
    for p in r["periodos"]:
        assert p["demanda_proyectada"] == pytest.approx(20.0, abs=1e-6)
        assert p["limite_inferior"] <= p["demanda_proyectada"] <= p["limite_superior"]


def test_periodos_comienzan_en_la_semana_en_curso_y_son_consecutivos():
    r = pronosticar_serie(diario([5] * 20, date(2026, 6, 15)), CORTE, 3)
    assert r["periodos"][0]["inicio"] == date(2026, 11, 9)
    assert r["periodos"][0]["fin"] == date(2026, 11, 15)
    assert r["periodos"][1]["inicio"] == date(2026, 11, 16)
    assert len(r["periodos"]) == 3


def test_tendencia_creciente_no_queda_por_debajo_del_promedio_historico():
    obs = diario([10 + 2 * i for i in range(30)], date(2026, 4, 20))
    r = pronosticar_serie(obs, CORTE, 4)
    promedio = np.mean([10 + 2 * i for i in range(30)])
    assert r["periodos"][0]["demanda_proyectada"] > promedio


def test_demanda_intermitente_nunca_negativa_y_acotada():
    patron = ([0, 0, 0, 6] * 10)
    r = pronosticar_serie(diario(patron, date(2026, 2, 2)), CORTE, 4)
    for p in r["periodos"]:
        assert p["demanda_proyectada"] >= 0
        assert p["limite_inferior"] >= 0
        assert p["demanda_proyectada"] <= 6
    assert r["modelo"] in {"CROSTON_SBA", "SES(alpha=0.2)", "MEDIA_MOVIL_4", "SES(alpha=0.4)",
                           "HOLT_AMORTIGUADO(0.3,0.1)"}


def test_intervalo_se_ensancha_con_el_horizonte():
    rng = np.random.default_rng(7)
    vals = np.maximum(0, 30 + rng.normal(0, 6, 40)).round()
    r = pronosticar_serie(diario(vals, date(2026, 1, 5)), CORTE, 4)
    anchos = [p["limite_superior"] - p["limite_inferior"] for p in r["periodos"]]
    assert anchos == sorted(anchos)
    assert anchos[-1] > anchos[0]
