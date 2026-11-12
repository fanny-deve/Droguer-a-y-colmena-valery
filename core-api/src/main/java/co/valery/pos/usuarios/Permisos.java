package co.valery.pos.usuarios;

import co.valery.pos.common.Rol;
import java.util.List;

/** Matriz de permisos por rol, expuesta al frontend para ocultar lo que cada perfil no puede usar (RF-19). */
public final class Permisos {
    private Permisos() {}

    private static final List<String> CAJERA = List.of(
            "PRODUCTOS_CONSULTAR", "STOCK_CONSULTAR", "VENTAS_REGISTRAR", "VENTAS_CONSULTAR_PROPIAS",
            "COMPROBANTE_VER", "CAJA_GESTIONAR_PROPIA", "VENTAS_SINCRONIZAR", "CAMBIAR_PASSWORD_PROPIA");

    private static final List<String> ADMIN = List.of(
            "PRODUCTOS_CONSULTAR", "STOCK_CONSULTAR", "VENTAS_REGISTRAR", "VENTAS_CONSULTAR_TODAS", "COMPROBANTE_VER",
            "CAJA_GESTIONAR_PROPIA", "VENTAS_SINCRONIZAR", "CAMBIAR_PASSWORD_PROPIA",
            "VENTAS_ANULAR", "CATALOGO_GESTIONAR", "COSTOS_VER", "PROVEEDORES_GESTIONAR", "LOTES_GESTIONAR",
            "AJUSTES_MERMAS", "ALERTAS_VER", "PEDIDOS_GESTIONAR", "REABASTECIMIENTO_SUGERIR", "ABC_VER",
            "PROYECCIONES_VER", "REPORTES_VER", "IMPORTAR_DATOS", "USUARIOS_GESTIONAR", "AUDITORIA_VER",
            "CONFIGURACION_GESTIONAR", "CONFLICTOS_SYNC_RESOLVER");

    public static List<String> de(Rol rol) {
        return rol == Rol.ADMIN ? ADMIN : CAJERA;
    }
}
