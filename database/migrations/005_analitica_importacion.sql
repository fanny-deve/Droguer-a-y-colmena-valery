-- 005: Analítica (ABC, proyecciones, histórico digitalizado) e importaciones
-- Cubre: RF-14, RF-16, RF-17, RF-18, RF-23

-- Histórico digitalizado del cuaderno columnario (antes del sistema); alimenta la proyección sin falsear ventas
create table importaciones (
  id             uuid primary key default gen_random_uuid(),
  tipo           text not null check (tipo in ('CATALOGO_STOCK', 'HISTORICO_VENTAS')),
  archivo_nombre text not null,
  total_filas    int not null check (total_filas >= 0),
  filas_ok       int not null check (filas_ok >= 0),
  filas_error    int not null check (filas_error >= 0),
  usuario_id     uuid not null references usuarios(id),
  creado_en      timestamptz not null default now()
);

create table importacion_errores (
  id             bigint generated always as identity primary key,
  importacion_id uuid not null references importaciones(id) on delete cascade,
  fila           int not null,
  campo          text,
  motivo         text not null
);
create index ix_import_errores on importacion_errores (importacion_id, fila);

create table historico_ventas (
  id             bigint generated always as identity primary key,
  producto_id    uuid not null references productos(id),
  fecha          date not null,
  unidades       numeric(14,3) not null check (unidades >= 0),
  ingresos       numeric(14,2) not null default 0 check (ingresos >= 0),
  fuente         text not null default 'CUADERNO',
  importacion_id uuid references importaciones(id),
  creado_en      timestamptz not null default now(),
  unique (producto_id, fecha, fuente)
);
create index ix_historico_producto_fecha on historico_ventas (producto_id, fecha);

-- Demanda diaria por producto: ventas confirmadas del sistema + histórico digitalizado
create view v_demanda_diaria with (security_invoker = true) as
select vi.producto_id,
       (v.fecha_servidor at time zone 'America/Bogota')::date as fecha,
       sum(vi.cantidad)::numeric(14,3) as unidades,
       sum(vi.subtotal)::numeric(14,2) as ingresos,
       sum(vi.cantidad * vi.costo_unitario)::numeric(14,2) as costo
from venta_items vi join ventas v on v.id = vi.venta_id
where v.estado = 'CONFIRMADA'
group by 1, 2
union all
select h.producto_id, h.fecha, h.unidades, h.ingresos, 0::numeric(14,2) from historico_ventas h;

create table clasificacion_abc_historial (
  id                      bigint generated always as identity primary key,
  corrida_id              uuid not null,
  producto_id             uuid not null references productos(id),
  categoria               char(1) not null check (categoria in ('A', 'B', 'C')),
  valor_consumo           numeric(16,2) not null,
  participacion           numeric(8,4) not null,
  participacion_acumulada numeric(8,4) not null,
  periodo_inicio          date not null,
  periodo_fin             date not null,
  calculado_en            timestamptz not null default now()
);
create index ix_abc_hist_producto on clasificacion_abc_historial (producto_id, calculado_en desc);
create index ix_abc_hist_corrida on clasificacion_abc_historial (corrida_id);

create table proyecciones_demanda (
  id                  uuid primary key default gen_random_uuid(),
  corrida_id          uuid not null,
  producto_id         uuid not null references productos(id),
  granularidad        text not null default 'SEMANAL' check (granularidad in ('DIARIA', 'SEMANAL', 'MENSUAL')),
  periodo_inicio      date not null,
  periodo_fin         date not null check (periodo_fin >= periodo_inicio),
  demanda_proyectada  numeric(14,3) not null check (demanda_proyectada >= 0),
  limite_inferior     numeric(14,3) check (limite_inferior >= 0),
  limite_superior     numeric(14,3),
  modelo              text not null,
  error_mape          numeric(9,4),
  observaciones_usadas int not null default 0,
  parametros          jsonb not null default '{}'::jsonb,
  generado_en         timestamptz not null default now(),
  generado_por        uuid references usuarios(id),
  unique (corrida_id, producto_id, periodo_inicio)
);
create index ix_proy_producto on proyecciones_demanda (producto_id, generado_en desc);
