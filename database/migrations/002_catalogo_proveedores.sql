-- 002: Catálogo de productos, categorías y proveedores
-- Cubre: RF-02, RF-06, RF-15

create extension if not exists pg_trgm with schema extensions;

create table categorias (
  id             int generated always as identity primary key,
  nombre         text not null,
  descripcion    text,
  activa         boolean not null default true,
  creado_en      timestamptz not null default now(),
  actualizado_en timestamptz not null default now()
);
create unique index ux_categorias_nombre on categorias (lower(nombre));
create trigger trg_categorias_actualizado before update on categorias
  for each row execute function fn_set_actualizado_en();

create table proveedores (
  id                  uuid primary key default gen_random_uuid(),
  nombre              text not null check (length(btrim(nombre)) >= 2),
  nit                 text,
  contacto_nombre     text,
  telefono            text,
  email               text,
  direccion           text,
  tiempo_entrega_dias int not null default 3 check (tiempo_entrega_dias between 0 and 90),
  notas               text,
  activo              boolean not null default true,
  eliminado_en        timestamptz,
  creado_por          uuid references usuarios(id),
  creado_en           timestamptz not null default now(),
  actualizado_en      timestamptz not null default now()
);
create unique index ux_proveedores_nombre on proveedores (lower(nombre)) where eliminado_en is null;
create unique index ux_proveedores_nit on proveedores (nit) where nit is not null and eliminado_en is null;
create trigger trg_proveedores_actualizado before update on proveedores
  for each row execute function fn_set_actualizado_en();

create table productos (
  id                     uuid primary key default gen_random_uuid(),
  codigo                 text not null,               -- EAN-13 o código interno (colmena / granel)
  nombre                 text not null check (length(btrim(nombre)) >= 2),
  descripcion            text,
  categoria_id           int not null references categorias(id),
  unidad                 text not null default 'UNIDAD'
                         check (unidad in ('UNIDAD', 'CAJA', 'BLISTER', 'FRASCO', 'GRAMO', 'KILOGRAMO', 'MILILITRO', 'LITRO')),
  precio_venta           numeric(12,2) not null check (precio_venta >= 0),
  costo_referencia       numeric(12,2) not null default 0 check (costo_referencia >= 0),
  maneja_vencimiento     boolean not null default true,
  stock_minimo           numeric(12,3) not null default 0 check (stock_minimo >= 0),
  punto_reorden          numeric(12,3) not null default 0 check (punto_reorden >= 0),
  proveedor_principal_id uuid references proveedores(id),
  clasificacion_abc      char(1) check (clasificacion_abc in ('A', 'B', 'C')),
  abc_actualizado_en     timestamptz,
  activo                 boolean not null default true,
  eliminado_en           timestamptz,
  creado_por             uuid references usuarios(id),
  creado_en              timestamptz not null default now(),
  actualizado_en         timestamptz not null default now(),
  constraint ck_productos_codigo check (codigo ~ '^[A-Za-z0-9._-]{3,30}$'),
  constraint ck_productos_baja check (eliminado_en is null or activo = false)
);
-- Unicidad global del código (incluye dados de baja: la baja es lógica, RF-06)
create unique index ux_productos_codigo on productos (upper(codigo));
create index ix_productos_nombre_trgm on productos using gin (nombre extensions.gin_trgm_ops);
create index ix_productos_categoria on productos (categoria_id) where activo;
create index ix_productos_abc on productos (clasificacion_abc) where activo;
create trigger trg_productos_actualizado before update on productos
  for each row execute function fn_set_actualizado_en();

-- Productos suministrados por cada proveedor (RF-15)
create table producto_proveedor (
  producto_id  uuid not null references productos(id),
  proveedor_id uuid not null references proveedores(id),
  costo_ultimo numeric(12,2) check (costo_ultimo >= 0),
  creado_en    timestamptz not null default now(),
  primary key (producto_id, proveedor_id)
);
create index ix_producto_proveedor_prov on producto_proveedor (proveedor_id);

insert into categorias (nombre, descripcion) values
  ('Farmacia - Medicamentos de venta libre', 'Medicamentos OTC del portafolio de droguería'),
  ('Farmacia - Higiene y cuidado personal', 'Productos de higiene, aseo y cuidado personal'),
  ('Cuidado del hogar', 'Artículos de cuidado del hogar'),
  ('Colmena - Insumos', 'Insumos y productos de colmena (apicultura)');
