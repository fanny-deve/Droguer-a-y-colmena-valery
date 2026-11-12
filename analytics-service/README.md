# Microservicio de proyección de demanda (Python / FastAPI)

Servicio aislado del núcleo transaccional (RF-18). **No tiene acceso a la base de datos**: el backend Spring Boot
le envía series de ventas y recibe proyecciones semanales. Es un apoyo a la decisión: no ejecuta compras.

## Ejecutar
```bash
python -m venv .venv && source .venv/bin/activate
pip install -r requirements-dev.txt
export ANALYTICS_API_KEY=una-clave-larga      # obligatoria; sin ella el servicio responde 503
uvicorn app.main:app --port 8000
pytest -q
```

## API
- `GET /health` (público)
- `POST /v1/proyecciones` (cabecera `X-API-Key`)

```json
{ "fecha_corte": "2026-11-12", "horizonte_semanas": 4,
  "series": [ { "producto_id": "uuid", "observaciones": [ {"fecha": "2026-10-05", "unidades": 12} ] } ] }
```

## Metodología
1. Agrega a semanas completas lunes-domingo; excluye la semana en curso y rellena con 0 las semanas sin ventas.
2. Con menos de 4 semanas: promedio simple + advertencia. Entre 4 y 7: media móvil + advertencia.
3. Con 8 o más: compara media móvil, suavizado exponencial (α=0.2 y 0.4), Holt amortiguado y Croston-SBA
   (demanda intermitente) mediante validación retrospectiva de un paso y elige el menor **WAPE**.
4. Intervalo de predicción del 80 % a partir de los residuos; nunca negativo.

## Redis (opcional)
Si `REDIS_URL` está definida (Redis Cloud) cachea respuestas 5 min; si Redis falla, el servicio sigue sin caché.
