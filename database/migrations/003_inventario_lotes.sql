-- 003: Pedidos a proveedor, lotes (FEFO), kardex inmutable, mermas/ajustes y alertas
-- Cubre: RF-07, RF-08, RF-09, RF-10, RF-11, RF-12, RF-13, RF-15, RF-16, RNF-10

create table pedidos_proveedor (
  id             uuid primary key default gen_random_uuid(),
  numero         bigint generated always as identity unique,
  proveedor_id   uuid not null references proveedores(id),
  estado         text not null default 'BORRADOR'
                 check (estado in ('BORRADOR', 'ENVIADO', 'RECIBIDO_PARCIAL', 'RECIBIDO', 'CANCELADO')),
  fecha_esperada date,
  notas          text,
  creado_por     uuid not null references usuarios(id),
  creado_en      timestamptz not null default now(),
  actualizado_en timestamptz not null default now(),
  enviado_en     timestamptz,
  cerrado_en     timestamptz
);
create index ix_pedidos_proveedor on pedidos_proveedor (proveedor_id, estado);
create trigger trg_pedidos_actualizado before update on pedidos_proveedor
  for each row execute function fn_set_actualizado_en();

create table pedido_items (
  id                       uuid primary key default gen_random_uuid(),
  pedido_id                uuid not null references pedidos_proveedor(id) on delete cascade,
  producto_id              uuid not null references productos(id),
  cantidad_solicitada      numeric(12,3) not null check (cantidad_solicitada > 0),
  cantidad_recibida        numeric(12,3) not null default 0 check (cantidad_recibida >= 0),
  costo_unitario_estimado  numeric(12,2) check (costo_unitario_estimado >= 0),
  sugerido                 boolean not null default false,
  unique (pedido_id, producto_id)
);

create table lotes (
  id                   uuid primary key default gen_random_uuid(),
  producto_id          uuid not null references productos(id),
  proveedor_id         uuid references proveedores(id),
  pedido_id            uuid references pedidos_proveedor(id),
  codigo_lote          text not null check (length(btrim(codigo_lote)) >= 1),
  fecha_vencimiento    date,
  cantidad_inicial     numeric(12,3) not null check (cantidad_inicial > 0),
  cantidad_disponible  numeric(12,3) not null check (cantidad_disponible >= 0),
  costo_unitario       numeric(12,2) not null check (costo_unitario >= 0),
  fecha_ingreso        timestamptz not null default now(),
  estado               text not null default 'ACTIVO' check (estado in ('ACTIVO', 'AGOTADO', 'BLOQUEADO')),
  notas                text,
  creado_por           uuid references usuarios(id),
  creado_en            timestamptz not null default now(),
  actualizado_en       timestamptz not null default now(),
  unique (producto_id, codigo_lote)
);
-- Índice FEFO: lotes con existencias ordenados por vencimiento más próximo (Resolución 1403)
create index ix_lotes_fefo on lotes (producto_id, fecha_vencimiento nulls last, fecha_ingreso)
  where cantidad_disponible > 0 and estado = 'ACTIVO';
create index ix_lotes_vencimiento on lotes (fecha_vencimiento) where cantidad_disponible > 0;

-- Reglas de integridad del lote: vencimiento obligatorio si el producto lo maneja y estado coherente con el stock
create or replace function fn_lotes_validar() returns trigger
language plpgsql set search_path = public as
$$
declare v_maneja boolean;
begin
  select maneja_vencimiento into v_maneja from productos where id = new.producto_id;
  if v_maneja and new.fecha_vencimiento is null then
    raise exception 'El producto exige fecha de vencimiento en cada lote (Decreto 780 / Res. 1403)'
      using errcode = 'check_violation';
  end if;
  if new.estado = 'ACTIVO' and new.cantidad_disponible = 0 then new.estado := 'AGOTADO';
  elsif new.estado = 'AGOTADO' and new.cantidad_disponible > 0 then new.estado := 'ACTIVO';
  end if;
  new.actualizado_en := now();
  return new;
end
$$;
create trigger trg_lotes_validar before insert or update on lotes
  for each row execute function fn_lotes_validar();

-- Kardex: cada cambio de existencias deja un movimiento inmutable (trazabilidad punta a punta, RNF-10)
create table movimientos_inventario (
  id                  bigint generated always as identity primary key,
  lote_id             uuid not null references lotes(id),
  producto_id         uuid not null references productos(id),
  tipo                text not null check (tipo in ('ENTRADA', 'CARGA_INICIAL', 'VENTA', 'ANULACION_VENTA',
                                                    'MERMA', 'AJUSTE_POSITIVO', 'AJUSTE_NEGATIVO')),
  cantidad            numeric(12,3) not null check (cantidad <> 0),   -- con signo: + entra, - sale
  stock_lote_antes    numeric(12,3) not null,
  stock_lote_despues  numeric(12,3) not null check (stock_lote_despues >= 0),
  costo_unitario      numeric(12,2) not null,
  referencia_tipo     text,
  referencia_id       text,
  motivo              text,
  usuario_id          uuid references usuarios(id),
  creado_en           timestamptz not null default now(),
  check (stock_lote_despues = stock_lote_antes + cantidad)
);
create index ix_mov_producto on movimientos_inventario (producto_id, creado_en desc);
create index ix_mov_lote on movimientos_inventario (lote_id, creado_en);
create index ix_mov_referencia on movimientos_inventario (referencia_tipo, referencia_id);
create trigger trg_mov_inmutable before update or delete on movimientos_inventario
  for each row execute function fn_bloquear_modificacion();
create trigger trg_mov_no_truncate before truncate on movimientos_inventario
  for each statement execute function fn_bloquear_modificacion();

-- Mermas y ajustes (RF-13): motivo obligatorio, usuario, fecha y valoración a costo
create table ajustes_inventario (
  id              uuid primary key default gen_random_uuid(),
  lote_id         uuid not null references lotes(id),
  producto_id     uuid not null references productos(id),
  tipo            text not null check (tipo in ('VENCIMIENTO', 'DANO', 'DIFERENCIA_CONTEO', 'OTRO')),
  sentido         text not null check (sentido in ('NEGATIVO', 'POSITIVO')),
  cantidad        numeric(12,3) not null check (cantidad > 0),
  motivo          text not null check (length(btrim(motivo)) >= 5),
  costo_unitario  numeric(12,2) not null check (costo_unitario >= 0),
  valor_total     numeric(14,2) generated always as (round(cantidad * costo_unitario, 2)) stored,
  movimiento_id   bigint references movimientos_inventario(id),
  usuario_id      uuid not null references usuarios(id),
  creado_en       timestamptz not null default now(),
  check (tipo <> 'VENCIMIENTO' or sentido = 'NEGATIVO')
);
create index ix_ajustes_fecha on ajustes_inventario (creado_en desc);
create index ix_ajustes_producto on ajustes_inventario (producto_id);
create trigger trg_ajustes_inmutable before update or delete on ajustes_inventario
  for each row execute function fn_bloquear_modificacion();

-- Alertas de vencimiento (30/15/7 días, vencido) y de reabastecimiento (RF-11, RF-12)
create table alertas (
  id           uuid primary key default gen_random_uuid(),
  tipo         text not null check (tipo in ('VENCIMIENTO_30', 'VENCIMIENTO_15', 'VENCIMIENTO_7', 'VENCIDO',
                                             'STOCK_MINIMO', 'PUNTO_REORDEN')),
  producto_id  uuid not null references productos(id),
  lote_id      uuid references lotes(id),
  severidad    text not null check (severidad in ('INFO', 'ADVERTENCIA', 'CRITICA')),
  mensaje      text not null,
  estado       text not null default 'ACTIVA' check (estado in ('ACTIVA', 'VISTA', 'RESUELTA')),
  datos        jsonb not null default '{}'::jsonb,
  generada_en  timestamptz not null default now(),
  vista_por    uuid references usuarios(id),
  vista_en     timestamptz,
  resuelta_en  timestamptz
);
-- Una sola alerta abierta por (tipo, producto, lote): evita duplicados al re-evaluar
create unique index ux_alertas_abierta on alertas
  (tipo, producto_id, coalesce(lote_id, '00000000-0000-0000-0000-000000000000'::uuid))
  where estado <> 'RESUELTA';
create index ix_alertas_estado on alertas (estado, severidad, generada_en desc);

-- Existencias consolidadas por producto (vendible = lotes activos y no vencidos)
create view v_stock_productos with (security_invoker = true) as
select p.id as producto_id,
       coalesce(sum(l.cantidad_disponible) filter (where l.estado <> 'BLOQUEADO'), 0)::numeric(14,3) as stock_total,
       coalesce(sum(l.cantidad_disponible) filter (
         where l.estado = 'ACTIVO' and (l.fecha_vencimiento is null or l.fecha_vencimiento > fn_hoy())), 0)::numeric(14,3) as stock_vendible,
       coalesce(sum(l.cantidad_disponible) filter (
         where l.fecha_vencimiento is not null and l.fecha_vencimiento <= fn_hoy()), 0)::numeric(14,3) as stock_vencido
from productos p
left join lotes l on l.producto_id = p.id and l.cantidad_disponible > 0
group by p.id;
