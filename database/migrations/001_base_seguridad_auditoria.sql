-- 001: Base, usuarios (RBAC), tokens, configuración y auditoría inmutable encadenada
-- Cubre: RF-19, RF-20, RNF-07, RNF-08, RNF-09, RNF-14

create type rol_usuario as enum ('ADMIN', 'CAJERO');

-- Fecha "hoy" en zona horaria de Colombia (los vencimientos se evalúan por día calendario local)
create or replace function fn_hoy() returns date
language sql stable set search_path = public as
$$ select (now() at time zone 'America/Bogota')::date $$;

create or replace function fn_set_actualizado_en() returns trigger
language plpgsql set search_path = public as
$$ begin new.actualizado_en := now(); return new; end $$;

create or replace function fn_bloquear_modificacion() returns trigger
language plpgsql set search_path = public as
$$
begin
  raise exception 'Registro inmutable: la tabla % no admite % (RNF-09 / Ley 527)', tg_table_name, tg_op
    using errcode = 'restrict_violation';
end
$$;

create table usuarios (
  id                    uuid primary key default gen_random_uuid(),
  username              text not null,
  nombre_completo       text not null check (length(btrim(nombre_completo)) >= 3),
  email                 text,
  telefono              text,
  password_hash         text not null,
  rol                   rol_usuario not null default 'CAJERO',
  activo                boolean not null default true,
  debe_cambiar_password boolean not null default true,
  intentos_fallidos     int not null default 0 check (intentos_fallidos >= 0),
  bloqueado_hasta       timestamptz,
  token_version         int not null default 0,
  ultimo_login          timestamptz,
  password_cambiada_en  timestamptz,
  creado_por            uuid references usuarios(id),
  creado_en             timestamptz not null default now(),
  actualizado_en        timestamptz not null default now(),
  desactivado_en        timestamptz,
  desactivado_por       uuid references usuarios(id),
  motivo_desactivacion  text,
  eliminado_en          timestamptz,
  eliminado_por         uuid references usuarios(id),
  motivo_eliminacion    text,
  constraint ck_usuarios_username check (username ~ '^[a-z0-9._-]{4,40}$'),
  constraint ck_usuarios_email check (email is null or email ~* '^[^@[:space:]]+@[^@[:space:]]+\.[^@[:space:]]+$'),
  constraint ck_usuarios_eliminado check (eliminado_en is null or activo = false),
  constraint ck_usuarios_motivo_baja check (
    (eliminado_en is null or length(btrim(coalesce(motivo_eliminacion, ''))) >= 5) and
    (desactivado_en is null or length(btrim(coalesce(motivo_desactivacion, ''))) >= 5)
  )
);
create unique index ux_usuarios_username on usuarios (lower(username));
create unique index ux_usuarios_email on usuarios (lower(email)) where email is not null and eliminado_en is null;
create index ix_usuarios_rol_activo on usuarios (rol) where activo and eliminado_en is null;
create trigger trg_usuarios_actualizado before update on usuarios
  for each row execute function fn_set_actualizado_en();

-- Nunca puede quedar el sistema sin una administradora activa, y no se borra físicamente a nadie.
create or replace function fn_usuarios_proteger_admin() returns trigger
language plpgsql set search_path = public as
$$
declare v_otros int;
begin
  if tg_op = 'DELETE' then
    raise exception 'Los usuarios no se eliminan físicamente: use la baja lógica (trazabilidad de auditoría)'
      using errcode = 'restrict_violation';
  end if;
  if old.rol = 'ADMIN' and old.activo and old.eliminado_en is null
     and (new.rol <> 'ADMIN' or not new.activo or new.eliminado_en is not null) then
    select count(*) into v_otros from usuarios
     where rol = 'ADMIN' and activo and eliminado_en is null and id <> old.id;
    if v_otros = 0 then
      raise exception 'Operación rechazada: debe existir al menos una administradora activa'
        using errcode = 'restrict_violation';
    end if;
  end if;
  return new;
end
$$;
create trigger trg_usuarios_proteger_admin before update or delete on usuarios
  for each row execute function fn_usuarios_proteger_admin();

create table refresh_tokens (
  id             uuid primary key default gen_random_uuid(),
  usuario_id     uuid not null references usuarios(id) on delete cascade,
  familia_id     uuid not null,
  token_hash     text not null unique,
  expira_en      timestamptz not null,
  revocado_en    timestamptz,
  reemplazado_por uuid references refresh_tokens(id),
  creado_en      timestamptz not null default now(),
  ip             text,
  user_agent     text
);
create index ix_refresh_usuario on refresh_tokens (usuario_id) where revocado_en is null;
create index ix_refresh_familia on refresh_tokens (familia_id);

create table configuracion (
  clave          text primary key,
  valor          jsonb not null,
  descripcion    text,
  actualizado_por uuid references usuarios(id),
  actualizado_en timestamptz not null default now()
);

-- Bitácora de auditoría: inmutable y encadenada por hash SHA-256 (detecta alteraciones)
create table auditoria (
  id             bigint generated always as identity primary key,
  usuario_id     uuid references usuarios(id),
  username       text,
  rol            text,
  accion         text not null,
  entidad        text not null,
  entidad_id     text,
  detalle        jsonb not null default '{}'::jsonb,
  resultado      text not null default 'OK' check (resultado in ('OK', 'DENEGADO', 'ERROR')),
  ip             text,
  creado_en      timestamptz not null default now(),
  hash_anterior  text not null,
  hash_registro  text not null
);
create index ix_auditoria_usuario on auditoria (usuario_id, creado_en desc);
create index ix_auditoria_entidad on auditoria (entidad, entidad_id);
create index ix_auditoria_fecha on auditoria (creado_en desc);

create or replace function fn_auditoria_calcular_hash(
  p_prev text, p_usuario uuid, p_accion text, p_entidad text, p_entidad_id text, p_detalle jsonb, p_fecha timestamptz
) returns text language sql immutable set search_path = public as
$$
  select encode(sha256(convert_to(
    p_prev || '|' || coalesce(p_usuario::text, '') || '|' || p_accion || '|' || p_entidad || '|' ||
    coalesce(p_entidad_id, '') || '|' || p_detalle::text || '|' || to_char(p_fecha at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US'), 'UTF8')), 'hex')
$$;

create or replace function fn_auditoria_sellar() returns trigger
language plpgsql set search_path = public as
$$
declare v_prev text;
begin
  perform pg_advisory_xact_lock(727001);  -- serializa la cadena
  select hash_registro into v_prev from auditoria order by id desc limit 1;
  new.creado_en := now();
  new.hash_anterior := coalesce(v_prev, 'GENESIS');
  new.hash_registro := fn_auditoria_calcular_hash(new.hash_anterior, new.usuario_id, new.accion,
                         new.entidad, new.entidad_id, new.detalle, new.creado_en);
  return new;
end
$$;
create trigger trg_auditoria_sellar before insert on auditoria
  for each row execute function fn_auditoria_sellar();
create trigger trg_auditoria_inmutable before update or delete on auditoria
  for each row execute function fn_bloquear_modificacion();
create trigger trg_auditoria_no_truncate before truncate on auditoria
  for each statement execute function fn_bloquear_modificacion();

-- Verificación de integridad de la cadena: devuelve los registros cuyo hash no coincide
create or replace function fn_auditoria_verificar()
returns table (id bigint, motivo text) language plpgsql stable set search_path = public as
$$
declare r record; v_prev text := 'GENESIS';
begin
  for r in select * from auditoria a order by a.id loop
    if r.hash_anterior <> v_prev then
      id := r.id; motivo := 'Cadena rota: hash_anterior no coincide'; return next;
    end if;
    if r.hash_registro <> fn_auditoria_calcular_hash(r.hash_anterior, r.usuario_id, r.accion,
         r.entidad, r.entidad_id, r.detalle, r.creado_en) then
      id := r.id; motivo := 'Contenido alterado: hash_registro no coincide'; return next;
    end if;
    v_prev := r.hash_registro;
  end loop;
end
$$;

insert into configuracion (clave, valor, descripcion) values
  ('alertas.dias_vencimiento', '[30,15,7]', 'Umbrales de alerta de vencimiento en días (RF-11)'),
  ('abc.umbral_a', '80', 'Porcentaje acumulado de valor de consumo que define la categoría A (RF-14)'),
  ('abc.umbral_b', '95', 'Porcentaje acumulado de valor de consumo que cierra la categoría B (RF-14)'),
  ('abc.ventana_dias', '90', 'Ventana de ventas en días para el cálculo ABC'),
  ('seguridad.max_intentos', '5', 'Intentos fallidos antes de bloquear la cuenta'),
  ('seguridad.bloqueo_minutos', '15', 'Minutos de bloqueo tras exceder los intentos'),
  ('seguridad.password_min', '10', 'Longitud mínima de contraseña'),
  ('sync.politica_conflicto', '"RECHAZAR"', 'Venta offline sin stock suficiente: RECHAZAR y notificar (RF-22)'),
  ('analitica.horizonte_semanas', '4', 'Horizonte por defecto de la proyección de demanda (RF-18)'),
  ('reabastecimiento.cobertura_semanas', '4', 'Semanas de cobertura objetivo para sugerencias de pedido (RF-16)');
