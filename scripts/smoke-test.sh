#!/usr/bin/env bash
# Verificación posterior al despliegue. Uso: scripts/smoke-test.sh https://valery-pos-api.fly.dev
# Falla (código ≠ 0) si algo esencial no está bien. No usa credenciales ni modifica datos.
set -u
BASE="${1:?Indique la URL base, por ejemplo https://valery-pos-api.fly.dev}"
BASE="${BASE%/}"
fallos=0
ok()   { echo "  OK   $1"; }
fallo(){ echo "  FALLA $1"; fallos=$((fallos+1)); }
codigo(){ curl -s -o /dev/null -w '%{http_code}' --max-time 15 "$@"; }

echo "Verificando $BASE"
for i in $(seq 1 30); do [ "$(codigo "$BASE/actuator/health/readiness")" = "200" ] && break; sleep 5; done
[ "$(codigo "$BASE/actuator/health/readiness")" = "200" ] && ok "readiness (base de datos conectada)" || fallo "readiness no responde 200"
[ "$(codigo "$BASE/actuator/health/liveness")" = "200" ]  && ok "liveness" || fallo "liveness"
[ "$(codigo "$BASE/api/v1/productos")" = "401" ]          && ok "API protegida (401 sin token)" || fallo "la API no exige autenticación"
[ "$(codigo "$BASE/api/v1/admin/usuarios")" = "401" ]     && ok "rutas de administración protegidas" || fallo "rutas /admin sin protección"
[ "$(codigo "$BASE/swagger-ui.html")" = "404" ]           && ok "Swagger deshabilitado en producción" || fallo "Swagger expuesto"
[ "$(codigo "$BASE/v3/api-docs")" = "404" ]               && ok "OpenAPI deshabilitado en producción" || fallo "OpenAPI expuesto"
[ "$(codigo "$BASE/actuator/env")" = "401" ]              && ok "actuator/env no expuesto" || fallo "actuator/env expuesto"
cab="$(curl -s -D - -o /dev/null --max-time 15 "$BASE/actuator/health")"
echo "$cab" | grep -qi '^strict-transport-security' && ok "HSTS presente" || fallo "falta Strict-Transport-Security"
echo "$cab" | grep -qi '^x-content-type-options: nosniff' && ok "X-Content-Type-Options" || fallo "falta X-Content-Type-Options"
echo "$cab" | grep -qi '^x-request-id' && ok "X-Request-Id (trazabilidad)" || fallo "falta X-Request-Id"
http="${BASE/https:/http:}"
if [ "$http" != "$BASE" ]; then
  c="$(codigo "$http/actuator/health")"; { [ "$c" = "301" ] || [ "$c" = "308" ] || [ "$c" = "302" ]; } && ok "HTTP redirige a HTTPS" || fallo "HTTP no redirige a HTTPS ($c)"
fi
login="$(codigo -X POST "$BASE/api/v1/auth/login" -H 'Content-Type: application/json' -d '{"username":"smoke.inexistente","password":"x"}')"
[ "$login" = "401" ] && ok "login rechaza credenciales inválidas" || fallo "login respondió $login (se esperaba 401)"
echo
[ "$fallos" -eq 0 ] && echo "Todo correcto." || { echo "$fallos verificación(es) fallaron."; exit 1; }
