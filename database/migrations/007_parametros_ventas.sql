-- 007: Parámetros de ventas y caja
-- Cubre: RF-01, RF-21 (cierre de caja)

insert into configuracion (clave, valor, descripcion) values
  ('ventas.exigir_caja_abierta', 'true',
   'La cajera debe abrir su caja antes de vender (apertura/cierre con arqueo). La administradora no está sujeta a esta regla.')
on conflict (clave) do nothing;
