# Catálogo de endpoints (generado desde el código fuente)

Base: `/api/v1`. Autenticación: `Authorization: Bearer <accessToken>`. Los endpoints `/api/v1/admin/**` son exclusivos de la administradora; el resto requiere sesión (cajera o administradora).

| Método | Ruta | Acceso | Método Java |
|---|---|---|---|
| GET | `/api/v1/admin/alertas` | ADMIN | `AlertaAdminController.listar` |
| POST | `/api/v1/admin/alertas/evaluar` | ADMIN | `AlertaAdminController.evaluar` |
| GET | `/api/v1/admin/alertas/resumen` | ADMIN | `AlertaAdminController.resumen` |
| POST | `/api/v1/admin/alertas/{id}/vista` | ADMIN | `AlertaAdminController.vista` |
| GET | `/api/v1/admin/analitica/abc` | ADMIN | `AnaliticaController.consultar` |
| POST | `/api/v1/admin/analitica/abc/calcular` | ADMIN | `AnaliticaController.calcular` |
| GET | `/api/v1/admin/analitica/abc/resumen` | ADMIN | `AnaliticaController.resumen` |
| GET | `/api/v1/admin/analitica/proyecciones` | ADMIN | `ProyeccionController.listar` |
| GET | `/api/v1/admin/analitica/proyecciones/estado` | ADMIN | `ProyeccionController.estado` |
| POST | `/api/v1/admin/analitica/proyecciones/generar` | ADMIN | `ProyeccionController.generar` |
| GET | `/api/v1/admin/analitica/proyecciones/{productoId}` | ADMIN | `ProyeccionController.producto` |
| GET | `/api/v1/admin/auditoria` | ADMIN | `AuditController.listar` |
| GET | `/api/v1/admin/auditoria/verificar` | ADMIN | `AuditController.verificar` |
| GET | `/api/v1/admin/caja/sesiones` | ADMIN | `CajaAdminController.sesiones` |
| POST | `/api/v1/admin/caja/sesiones/{id}/cerrar-forzado` | ADMIN | `CajaAdminController.cerrarForzado` |
| GET | `/api/v1/admin/categorias` | ADMIN | `CategoriaAdminController.listar` |
| POST | `/api/v1/admin/categorias` | ADMIN | `CategoriaAdminController.crear` |
| PUT | `/api/v1/admin/categorias/{id}` | ADMIN | `CategoriaAdminController.actualizar` |
| GET | `/api/v1/admin/configuracion` | ADMIN | `ConfiguracionController.listar` |
| PUT | `/api/v1/admin/configuracion/{clave}` | ADMIN | `ConfiguracionController.actualizar` |
| GET | `/api/v1/admin/importaciones` | ADMIN | `ImportacionController.listar` |
| POST | `/api/v1/admin/importaciones/catalogo` | ADMIN | `ImportacionController.catalogo` |
| POST | `/api/v1/admin/importaciones/historico-ventas` | ADMIN | `ImportacionController.historico` |
| GET | `/api/v1/admin/importaciones/plantilla/{tipo}` | ADMIN | `ImportacionController.plantilla` |
| GET | `/api/v1/admin/importaciones/{id}/errores` | ADMIN | `ImportacionController.errores` |
| GET | `/api/v1/admin/inventario/ajustes` | ADMIN | `InventarioAdminController.ajustes` |
| POST | `/api/v1/admin/inventario/ajustes` | ADMIN | `InventarioAdminController.ajustar` |
| POST | `/api/v1/admin/inventario/entradas` | ADMIN | `InventarioAdminController.entrada` |
| GET | `/api/v1/admin/inventario/lotes` | ADMIN | `InventarioAdminController.lotes` |
| GET | `/api/v1/admin/inventario/lotes/{id}` | ADMIN | `InventarioAdminController.lote` |
| POST | `/api/v1/admin/inventario/lotes/{id}/bloquear` | ADMIN | `InventarioAdminController.bloquear` |
| POST | `/api/v1/admin/inventario/lotes/{id}/desbloquear` | ADMIN | `InventarioAdminController.desbloquear` |
| GET | `/api/v1/admin/inventario/movimientos` | ADMIN | `InventarioAdminController.kardex` |
| GET | `/api/v1/admin/pedidos` | ADMIN | `PedidoController.listar` |
| POST | `/api/v1/admin/pedidos` | ADMIN | `PedidoController.crear` |
| GET | `/api/v1/admin/pedidos/{id}` | ADMIN | `PedidoController.obtener` |
| PUT | `/api/v1/admin/pedidos/{id}` | ADMIN | `PedidoController.actualizar` |
| POST | `/api/v1/admin/pedidos/{id}/cancelar` | ADMIN | `PedidoController.cancelar` |
| POST | `/api/v1/admin/pedidos/{id}/cerrar` | ADMIN | `PedidoController.cerrar` |
| POST | `/api/v1/admin/pedidos/{id}/enviar` | ADMIN | `PedidoController.enviar` |
| POST | `/api/v1/admin/pedidos/{id}/recepcion` | ADMIN | `PedidoController.recibir` |
| GET | `/api/v1/admin/productos` | ADMIN | `ProductoAdminController.listar` |
| POST | `/api/v1/admin/productos` | ADMIN | `ProductoAdminController.crear` |
| GET | `/api/v1/admin/productos/{id}` | ADMIN | `ProductoAdminController.obtener` |
| PUT | `/api/v1/admin/productos/{id}` | ADMIN | `ProductoAdminController.actualizar` |
| POST | `/api/v1/admin/productos/{id}/baja` | ADMIN | `ProductoAdminController.baja` |
| POST | `/api/v1/admin/productos/{id}/reactivar` | ADMIN | `ProductoAdminController.reactivar` |
| GET | `/api/v1/admin/proveedores` | ADMIN | `ProveedorAdminController.listar` |
| POST | `/api/v1/admin/proveedores` | ADMIN | `ProveedorAdminController.crear` |
| GET | `/api/v1/admin/proveedores/{id}` | ADMIN | `ProveedorAdminController.obtener` |
| PUT | `/api/v1/admin/proveedores/{id}` | ADMIN | `ProveedorAdminController.actualizar` |
| POST | `/api/v1/admin/proveedores/{id}/desactivar` | ADMIN | `ProveedorAdminController.desactivar` |
| GET | `/api/v1/admin/proveedores/{id}/productos` | ADMIN | `ProveedorAdminController.productos` |
| PUT | `/api/v1/admin/proveedores/{id}/productos` | ADMIN | `ProveedorAdminController.reemplazarProductos` |
| POST | `/api/v1/admin/proveedores/{id}/reactivar` | ADMIN | `ProveedorAdminController.reactivar` |
| GET | `/api/v1/admin/reabastecimiento/sugerencias` | ADMIN | `SugerenciaController.sugerencias` |
| GET | `/api/v1/admin/reportes/inventario/movimientos` | ADMIN | `ReporteController.movimientos` |
| GET | `/api/v1/admin/reportes/inventario/perdidas` | ADMIN | `ReporteController.perdidas` |
| GET | `/api/v1/admin/reportes/inventario/valorizado` | ADMIN | `ReporteController.valorizado` |
| GET | `/api/v1/admin/reportes/ventas/por-categoria` | ADMIN | `ReporteController.porCategoria` |
| GET | `/api/v1/admin/reportes/ventas/por-forma-pago` | ADMIN | `ReporteController.porFormaPago` |
| GET | `/api/v1/admin/reportes/ventas/por-periodo` | ADMIN | `ReporteController.porPeriodo` |
| GET | `/api/v1/admin/reportes/ventas/por-producto` | ADMIN | `ReporteController.porProducto` |
| GET | `/api/v1/admin/reportes/ventas/resumen` | ADMIN | `ReporteController.resumen` |
| GET | `/api/v1/admin/sync/rechazos` | ADMIN | `SyncAdminController.listar` |
| POST | `/api/v1/admin/sync/rechazos/{id}/resolver` | ADMIN | `SyncAdminController.resolver` |
| GET | `/api/v1/admin/usuarios` | ADMIN | `UsuarioAdminController.listar` |
| POST | `/api/v1/admin/usuarios` | ADMIN | `UsuarioAdminController.crear` |
| GET | `/api/v1/admin/usuarios/{id}` | ADMIN | `UsuarioAdminController.obtener` |
| PUT | `/api/v1/admin/usuarios/{id}` | ADMIN | `UsuarioAdminController.actualizar` |
| POST | `/api/v1/admin/usuarios/{id}/desactivar` | ADMIN | `UsuarioAdminController.desactivar` |
| POST | `/api/v1/admin/usuarios/{id}/desbloquear` | ADMIN | `UsuarioAdminController.desbloquear` |
| POST | `/api/v1/admin/usuarios/{id}/eliminar` | ADMIN | `UsuarioAdminController.eliminar` |
| POST | `/api/v1/admin/usuarios/{id}/reactivar` | ADMIN | `UsuarioAdminController.reactivar` |
| POST | `/api/v1/admin/usuarios/{id}/reset-password` | ADMIN | `UsuarioAdminController.resetPassword` |
| POST | `/api/v1/admin/ventas/{id}/anular` | ADMIN | `VentaAdminController.anular` |
| GET | `/api/v1/admin/ventas/{id}/verificar-sello` | ADMIN | `VentaAdminController.verificar` |
| POST | `/api/v1/auth/change-password` | CAJERO o ADMIN | `AuthController.cambiarPassword` |
| POST | `/api/v1/auth/login` | público | `AuthController.login` |
| POST | `/api/v1/auth/logout` | CAJERO o ADMIN | `AuthController.logout` |
| GET | `/api/v1/auth/me` | CAJERO o ADMIN | `AuthController.me` |
| POST | `/api/v1/auth/refresh` | público | `AuthController.refresh` |
| POST | `/api/v1/caja/abrir` | CAJERO o ADMIN | `CajaController.abrir` |
| GET | `/api/v1/caja/actual` | CAJERO o ADMIN | `CajaController.actual` |
| POST | `/api/v1/caja/cerrar` | CAJERO o ADMIN | `CajaController.cerrar` |
| GET | `/api/v1/categorias` | CAJERO o ADMIN | `ConsultaController.categorias` |
| GET | `/api/v1/productos` | CAJERO o ADMIN | `ConsultaController.buscar` |
| GET | `/api/v1/productos/codigo/{codigo}` | CAJERO o ADMIN | `ConsultaController.porCodigo` |
| GET | `/api/v1/productos/{id}` | CAJERO o ADMIN | `ConsultaController.obtener` |
| GET | `/api/v1/ventas` | CAJERO o ADMIN | `VentaController.listar` |
| POST | `/api/v1/ventas` | CAJERO o ADMIN | `VentaController.registrar` |
| GET | `/api/v1/ventas/sincronizacion/rechazos` | CAJERO o ADMIN | `VentaController.rechazos` |
| POST | `/api/v1/ventas/sincronizar` | CAJERO o ADMIN | `VentaController.sincronizar` |
| GET | `/api/v1/ventas/{id}` | CAJERO o ADMIN | `VentaController.obtener` |
| GET | `/api/v1/ventas/{id}/comprobante` | CAJERO o ADMIN | `VentaController.comprobante` |
