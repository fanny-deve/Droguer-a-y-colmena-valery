# Droguería & Colmena Valery — Sistema POS web y móvil

Sistema POS con arquitectura híbrida modular y microservicio de analítica predictiva para la Droguería & Colmena Valery (Cali).

## Ramas

| Rama | Propósito |
|---|---|
| `main` | Versión estable / entregable |
| `desarrollo` | Integración de frontend y backend |
| `frontend` | PWA: React + TypeScript, Service Worker, IndexedDB |
| `backend` | Base de datos (PostgreSQL/Supabase), API Java + Spring Boot y microservicio Python |

## Contenido de la rama `backend`

```
database/migrations/   Esquema PostgreSQL (001–008): usuarios, catálogo, lotes, ventas selladas, auditoría, analítica, RLS
core-api/              API REST Java 21 + Spring Boot 3.3 (núcleo transaccional)
analytics-service/     Microservicio Python (FastAPI) de proyección de demanda
docs/API_ENDPOINTS.md  Catálogo de endpoints generado desde el código
.github/workflows/     CI: compila y prueba Java (PostgreSQL real) y Python
```

## Puesta en marcha

1. **Base de datos:** las migraciones de `database/migrations` ya están aplicadas en el proyecto Supabase. Para una base nueva, ejecútelas en orden (001 → 008).
2. **Microservicio Python:**
   ```bash
   cd analytics-service && python -m venv .venv && source .venv/bin/activate
   pip install -r requirements-dev.txt
   export ANALYTICS_API_KEY=<clave-larga>
   uvicorn app.main:app --port 8000
   ```
3. **API Java:** copie `core-api/.env.example` a `.env`, complete los valores y ejecute (requiere JDK 21 y Maven):
   ```bash
   cd core-api && export $(grep -v '^#' .env | xargs) && mvn spring-boot:run
   ```
   Documentación interactiva: `http://localhost:8080/swagger-ui.html`.
4. **Todo junto con Docker:** `docker compose up --build` (usa el `.env` de la raíz).

## Pruebas

```bash
cd core-api && mvn verify          # 60 pruebas de integración con PostgreSQL real (requiere Docker)
cd analytics-service && pytest -q  # 19 pruebas
```

## Seguridad (resumen)

- La aplicación se conecta con el rol `app_backend` (mínimos privilegios, sin DELETE en tablas de trazabilidad) y todas las tablas tienen RLS.
- Contraseñas con BCrypt, JWT de corta duración con refresh rotatorio, bloqueo por intentos y revocación inmediata de sesiones.
- Auditoría inmutable encadenada por hash y ventas selladas (Ley 527 de 1999).
- **Ningún secreto se versiona.** Use variables de entorno (ver `core-api/.env.example`).
