-- 008: Índices de apoyo para claves foráneas usadas en uniones y filtros frecuentes
-- (las columnas puramente de autoría —creado_por, desactivado_por, etc.— no se indexan: no se consultan por ellas)

create index if not exists ix_alertas_producto on alertas (producto_id);
create index if not exists ix_alertas_lote on alertas (lote_id) where lote_id is not null;
create index if not exists ix_lotes_proveedor on lotes (proveedor_id) where proveedor_id is not null;
create index if not exists ix_lotes_pedido on lotes (pedido_id) where pedido_id is not null;
create index if not exists ix_pedido_items_producto on pedido_items (producto_id);
create index if not exists ix_ajustes_lote on ajustes_inventario (lote_id);
create index if not exists ix_ajustes_usuario on ajustes_inventario (usuario_id);
create index if not exists ix_productos_proveedor on productos (proveedor_principal_id) where proveedor_principal_id is not null;
create index if not exists ix_venta_items_categoria on venta_items (categoria_id);
create index if not exists ix_sync_rechazos_cajero on sync_rechazos (cajero_id);
create index if not exists ix_importaciones_usuario on importaciones (usuario_id);
create index if not exists ix_historico_importacion on historico_ventas (importacion_id) where importacion_id is not null;
