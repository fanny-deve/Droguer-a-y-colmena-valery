-- 006: Endurecimiento: RLS en todas las tablas y rol de aplicación de mínimos privilegios
-- Cubre: RNF-07, RNF-09 (defensa en profundidad), Ley 1273 / Ley 1581

-- 1) RLS activado en todo el esquema público: la API REST de Supabase (anon/authenticated) queda sin acceso
do $$
declare t record;
begin
  for t in select tablename from pg_tables where schemaname = 'public' loop
    execute format('alter table public.%I enable row level security', t.tablename);
  end loop;
end $$;

revoke all on all tables in schema public from anon, authenticated;
revoke all on all sequences in schema public from anon, authenticated;
revoke execute on all functions in schema public from public, anon, authenticated;
alter default privileges for role postgres in schema public revoke all on tables from anon, authenticated;
alter default privileges for role postgres in schema public revoke all on sequences from anon, authenticated;
alter default privileges for role postgres in schema public revoke execute on functions from public, anon, authenticated;

-- 2) Rol de la aplicación (Spring Boot). La contraseña se asigna fuera del repositorio.
do $$ begin
  if not exists (select 1 from pg_roles where rolname = 'app_backend') then
    create role app_backend nologin;
  end if;
end $$;

grant usage on schema public to app_backend;
grant select, insert, update on all tables in schema public to app_backend;
grant usage, select on all sequences in schema public to app_backend;
-- DELETE solo donde el negocio lo requiere (nunca sobre tablas de trazabilidad)
revoke update on auditoria, movimientos_inventario, ajustes_inventario, venta_items from app_backend;
grant delete on refresh_tokens, pedido_items, producto_proveedor to app_backend;
grant execute on function fn_hoy(), fn_auditoria_verificar(), fn_auditoria_calcular_hash(text, uuid, text, text, text, jsonb, timestamptz) to app_backend;

-- 3) Política explícita para el rol de la aplicación (RLS sin políticas = nadie más accede)
do $$
declare t record;
begin
  for t in select tablename from pg_tables where schemaname = 'public' loop
    execute format('drop policy if exists app_backend_all on public.%I', t.tablename);
    execute format('create policy app_backend_all on public.%I for all to app_backend using (true) with check (true)', t.tablename);
  end loop;
end $$;
