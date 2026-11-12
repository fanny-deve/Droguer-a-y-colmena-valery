-- 004: Ventas selladas, ítems por lote, sesiones de caja y rechazos de sincronización offline
-- Cubre: RF-01, RF-03, RF-04, RF-05, RF-21, RF-22, RNF-05, RNF-06, RNF-14, Ley 527

create table sesiones_caja (
  id                 uuid primary key default gen_random_uuid(),
  cajero_id          uuid not null references usuarios(id),
  estado             text not null default 'ABIERTA' check (estado in ('ABIERTA', 'CERRADA')),
  abierta_en         timestamptz not null default now(),
  fondo_inicial      numeric(12,2) not null default 0 check (fondo_inicial >= 0),
  cerrada_en         timestamptz,
  efectivo_esperado  numeric(12,2),
  efectivo_contado   numeric(12,2) check (efectivo_contado >= 0),
  diferencia         numeric(12,2),
  observaciones      text,
  check ((estado = 'CERRADA') = (cerrada_en is not null))
);
-- Un cajero solo puede tener una sesión abierta a la vez
create unique index ux_sesion_abierta_por_cajero on sesiones_caja (cajero_id) where estado = 'ABIERTA';
create index ix_sesiones_fecha on sesiones_caja (abierta_en desc);

create table ventas (
  id               uuid primary key default gen_random_uuid(),
  numero           bigint generated always as identity unique,   -- consecutivo del comprobante interno
  cajero_id        uuid not null references usuarios(id),
  sesion_caja_id   uuid references sesiones_caja(id),
  forma_pago       text not null check (forma_pago in ('EFECTIVO', 'TARJETA', 'TRANSFERENCIA', 'BILLETERA_DIGITAL')),
  subtotal         numeric(14,2) not null check (subtotal >= 0),
  descuento        numeric(14,2) not null default 0 check (descuento >= 0),
  total            numeric(14,2) not null check (total >= 0),
  monto_recibido   numeric(14,2) check (monto_recibido >= 0),
  cambio           numeric(14,2) check (cambio >= 0),
  estado           text not null default 'CONFIRMADA' check (estado in ('CONFIRMADA', 'ANULADA')),
  idempotency_key  uuid not null unique,         -- generado en el cliente; evita duplicados al sincronizar (RF-22)
  origen           text not null default 'ONLINE' check (origen in ('ONLINE', 'OFFLINE_SYNC')),
  fecha_cliente    timestamptz,                  -- informativa: nunca se usa para ordenar ni sellar (RNF-14)
  fecha_servidor   timestamptz not null default now(),   -- sello de tiempo UTC del servidor (Ley 527)
  hash_sellado     text not null,
  anulada_en       timestamptz,
  anulada_por      uuid references usuarios(id),
  motivo_anulacion text,
  check (total = subtotal - descuento),
  check (descuento <= subtotal),
  check (forma_pago <> 'EFECTIVO' or (monto_recibido is not null and monto_recibido >= total)),
  check ((estado = 'ANULADA') = (anulada_en is not null)),
  check (estado <> 'ANULADA' or (anulada_por is not null and length(btrim(coalesce(motivo_anulacion, ''))) >= 5))
);
create index ix_ventas_fecha on ventas (fecha_servidor desc);
create index ix_ventas_cajero on ventas (cajero_id, fecha_servidor desc);
create index ix_ventas_sesion on ventas (sesion_caja_id);
create index ix_ventas_estado on ventas (estado, fecha_servidor desc);

-- Venta sellada: solo se permite el paso CONFIRMADA -> ANULADA; nada más puede cambiar (Ley 527)
create or replace function fn_ventas_proteger() returns trigger
language plpgsql set search_path = public as
$$
begin
  if tg_op = 'DELETE' then
    raise exception 'Las ventas no se eliminan: se anulan con motivo (RF-05)' using errcode = 'restrict_violation';
  end if;
  if old.estado = 'ANULADA' then
    raise exception 'Una venta anulada es inmutable' using errcode = 'restrict_violation';
  end if;
  if (new.id, new.numero, new.cajero_id, new.sesion_caja_id, new.forma_pago, new.subtotal, new.descuento,
      new.total, new.monto_recibido, new.cambio, new.idempotency_key, new.origen, new.fecha_cliente,
      new.fecha_servidor, new.hash_sellado)
     is distinct from
     (old.id, old.numero, old.cajero_id, old.sesion_caja_id, old.forma_pago, old.subtotal, old.descuento,
      old.total, old.monto_recibido, old.cambio, old.idempotency_key, old.origen, old.fecha_cliente,
      old.fecha_servidor, old.hash_sellado) then
    raise exception 'Venta sellada: no se pueden alterar sus datos (RNF-14 / Ley 527)' using errcode = 'restrict_violation';
  end if;
  return new;
end
$$;
create trigger trg_ventas_proteger before update or delete on ventas
  for each row execute function fn_ventas_proteger();
create trigger trg_ventas_no_truncate before truncate on ventas
  for each statement execute function fn_bloquear_modificacion();

create table venta_items (
  id               bigint generated always as identity primary key,
  venta_id         uuid not null references ventas(id),
  linea            int not null check (linea > 0),
  producto_id      uuid not null references productos(id),
  lote_id          uuid not null references lotes(id),       -- lote descontado por FEFO (trazabilidad)
  categoria_id     int not null references categorias(id),   -- categoría congelada al momento de la venta (RF-03)
  cantidad         numeric(12,3) not null check (cantidad > 0),
  precio_unitario  numeric(12,2) not null check (precio_unitario >= 0),
  costo_unitario   numeric(12,2) not null check (costo_unitario >= 0),
  subtotal         numeric(14,2) not null check (subtotal >= 0)
);
create index ix_venta_items_venta on venta_items (venta_id);
create index ix_venta_items_producto on venta_items (producto_id);
create index ix_venta_items_lote on venta_items (lote_id);
create trigger trg_venta_items_inmutable before update or delete on venta_items
  for each row execute function fn_bloquear_modificacion();

-- Ventas offline que no pudieron aplicarse (p. ej. stock insuficiente): se conservan y se notifican (RF-22)
create table sync_rechazos (
  id               uuid primary key default gen_random_uuid(),
  idempotency_key  uuid not null unique,
  cajero_id        uuid not null references usuarios(id),
  motivo           text not null,
  detalle          jsonb not null default '{}'::jsonb,
  payload          jsonb not null,
  estado           text not null default 'RECHAZADA' check (estado in ('RECHAZADA', 'RESUELTA')),
  fecha_cliente    timestamptz,
  recibido_en      timestamptz not null default now(),
  resuelta_por     uuid references usuarios(id),
  resuelta_en      timestamptz,
  nota_resolucion  text
);
create index ix_sync_rechazos_estado on sync_rechazos (estado, recibido_en desc);
