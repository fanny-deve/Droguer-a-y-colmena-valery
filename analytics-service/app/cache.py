"""Caché opcional en Redis Cloud. Si Redis no está configurado o falla, el servicio sigue funcionando."""
from __future__ import annotations

import hashlib
import os

_cliente = None


def _redis():
    global _cliente
    url = os.getenv("REDIS_URL", "").strip()
    if not url:
        return None
    if _cliente is None:
        import redis
        _cliente = redis.Redis.from_url(url, socket_timeout=1, socket_connect_timeout=1, decode_responses=True)
    return _cliente


def clave(cuerpo: str) -> str:
    return "analytics:proy:" + hashlib.sha256(cuerpo.encode("utf-8")).hexdigest()


def leer(k: str) -> str | None:
    try:
        c = _redis()
        return c.get(k) if c else None
    except Exception:
        return None


def guardar(k: str, valor: str, ttl: int = 300) -> None:
    try:
        c = _redis()
        if c:
            c.set(k, valor, ex=ttl)
    except Exception:
        pass
