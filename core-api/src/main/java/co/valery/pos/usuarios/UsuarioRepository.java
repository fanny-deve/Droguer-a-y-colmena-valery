package co.valery.pos.usuarios;

import co.valery.pos.common.Rol;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class UsuarioRepository {
    private static final String COLUMNAS = """
            id, username, nombre_completo, email, telefono, rol::text as rol, activo, debe_cambiar_password,
            intentos_fallidos, bloqueado_hasta, token_version, ultimo_login, creado_en, desactivado_en,
            motivo_desactivacion, eliminado_en, password_hash
            """;

    private static final RowMapper<Usuario> MAPPER = (rs, i) -> new Usuario(
            rs.getObject("id", UUID.class), rs.getString("username"), rs.getString("nombre_completo"),
            rs.getString("email"), rs.getString("telefono"), Rol.valueOf(rs.getString("rol")),
            rs.getBoolean("activo"), rs.getBoolean("debe_cambiar_password"), rs.getInt("intentos_fallidos"),
            rs.getObject("bloqueado_hasta", OffsetDateTime.class), rs.getInt("token_version"),
            rs.getObject("ultimo_login", OffsetDateTime.class), rs.getObject("creado_en", OffsetDateTime.class),
            rs.getObject("desactivado_en", OffsetDateTime.class), rs.getString("motivo_desactivacion"),
            rs.getObject("eliminado_en", OffsetDateTime.class), rs.getString("password_hash"));

    private final JdbcClient jdbc;

    public UsuarioRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Usuario> findById(UUID id) {
        return jdbc.sql("select " + COLUMNAS + " from usuarios where id = :id").param("id", id).query(MAPPER).optional();
    }

    /** Bloquea la fila hasta el fin de la transacción (NO KEY UPDATE: no choca con las FK de auditoría). */
    public Optional<Usuario> findByIdForUpdate(UUID id) {
        return jdbc.sql("select " + COLUMNAS + " from usuarios where id = :id for no key update").param("id", id).query(MAPPER).optional();
    }

    public Optional<Usuario> findByUsername(String username) {
        return jdbc.sql("select " + COLUMNAS + " from usuarios where lower(username) = lower(:u)").param("u", username)
                .query(MAPPER).optional();
    }

    public long contarAdminsActivos() {
        return jdbc.sql("select count(*) from usuarios where rol = 'ADMIN' and activo and eliminado_en is null")
                .query(Long.class).single();
    }

    public UUID insertar(String username, String nombre, String email, String telefono, String passwordHash, Rol rol,
                         boolean debeCambiar, UUID creadoPor) {
        return jdbc.sql("""
                insert into usuarios(username, nombre_completo, email, telefono, password_hash, rol,
                                     debe_cambiar_password, creado_por, password_cambiada_en)
                values (:username, :nombre, cast(:email as text), cast(:tel as text), :hash, cast(:rol as rol_usuario),
                        :debe, cast(:creadoPor as uuid), case when :debe then null else now() end)
                returning id
                """)
                .param("username", username).param("nombre", nombre).param("email", email).param("tel", telefono)
                .param("hash", passwordHash).param("rol", rol.name()).param("debe", debeCambiar)
                .param("creadoPor", creadoPor)
                .query((rs, i) -> rs.getObject(1, UUID.class)).single();
    }

    public record Filtro(String estado, String q, Rol rol) {}

    private static final String FILTROS = """
            where (cast(:rol as text) is null or rol::text = cast(:rol as text))
              and (cast(:q as text) is null or username ilike '%' || cast(:q as text) || '%'
                   or nombre_completo ilike '%' || cast(:q as text) || '%')
              and (cast(:estado as text) is null
                   or (cast(:estado as text) = 'ELIMINADO' and eliminado_en is not null)
                   or (cast(:estado as text) = 'INACTIVO' and eliminado_en is null and not activo)
                   or (cast(:estado as text) = 'ACTIVO' and eliminado_en is null and activo))
            """;

    public List<Usuario> listar(Filtro f, int limit, int offset) {
        return jdbc.sql("select " + COLUMNAS + " from usuarios " + FILTROS + " order by creado_en desc limit :l offset :o")
                .param("rol", f.rol() == null ? null : f.rol().name()).param("q", f.q()).param("estado", f.estado())
                .param("l", limit).param("o", offset).query(MAPPER).list();
    }

    public long contar(Filtro f) {
        return jdbc.sql("select count(*) from usuarios " + FILTROS)
                .param("rol", f.rol() == null ? null : f.rol().name()).param("q", f.q()).param("estado", f.estado())
                .query(Long.class).single();
    }

    /** Suma un intento fallido y bloquea la cuenta al alcanzar el máximo. Devuelve true si quedó bloqueada. */
    public boolean registrarFalloLogin(UUID id, int maxIntentos, int bloqueoMinutos) {
        return jdbc.sql("""
                update usuarios set
                  intentos_fallidos = case when intentos_fallidos + 1 >= :max then 0 else intentos_fallidos + 1 end,
                  bloqueado_hasta = case when intentos_fallidos + 1 >= :max
                                         then now() + make_interval(mins => :min) else bloqueado_hasta end
                where id = :id
                returning coalesce(bloqueado_hasta > now(), false)
                """).param("id", id).param("max", maxIntentos).param("min", bloqueoMinutos)
                .query((rs, i) -> rs.getBoolean(1)).single();
    }

    public void loginExitoso(UUID id) {
        jdbc.sql("update usuarios set intentos_fallidos = 0, bloqueado_hasta = null, ultimo_login = now() where id = :id")
                .param("id", id).update();
    }

    /** Cambia la contraseña e invalida todas las sesiones previas (token_version + 1). */
    public void actualizarPassword(UUID id, String hash, boolean debeCambiar) {
        jdbc.sql("""
                update usuarios set password_hash = :h, debe_cambiar_password = :d, password_cambiada_en = now(),
                       token_version = token_version + 1, intentos_fallidos = 0, bloqueado_hasta = null
                where id = :id
                """).param("id", id).param("h", hash).param("d", debeCambiar).update();
    }

    public void actualizarDatos(UUID id, String nombre, String email, String telefono) {
        jdbc.sql("update usuarios set nombre_completo = :n, email = cast(:e as text), telefono = cast(:t as text) where id = :id")
                .param("id", id).param("n", nombre).param("e", email).param("t", telefono).update();
    }

    public void desactivar(UUID id, UUID adminId, String motivo) {
        jdbc.sql("""
                update usuarios set activo = false, desactivado_en = now(), desactivado_por = :a,
                       motivo_desactivacion = :m, token_version = token_version + 1
                where id = :id
                """).param("id", id).param("a", adminId).param("m", motivo).update();
    }

    public void reactivar(UUID id) {
        jdbc.sql("""
                update usuarios set activo = true, desactivado_en = null, desactivado_por = null,
                       motivo_desactivacion = null, token_version = token_version + 1,
                       intentos_fallidos = 0, bloqueado_hasta = null
                where id = :id
                """).param("id", id).update();
    }

    public void desbloquear(UUID id) {
        jdbc.sql("update usuarios set intentos_fallidos = 0, bloqueado_hasta = null where id = :id").param("id", id).update();
    }

    /** Baja lógica definitiva: conserva la identidad para la trazabilidad pero elimina datos de contacto. */
    public void eliminar(UUID id, UUID adminId, String motivo) {
        jdbc.sql("""
                update usuarios set activo = false, eliminado_en = now(), eliminado_por = :a, motivo_eliminacion = :m,
                       email = null, telefono = null, token_version = token_version + 1
                where id = :id
                """).param("id", id).param("a", adminId).param("m", motivo).update();
    }

    public long sesionesCajaAbiertas(UUID cajeroId) {
        return jdbc.sql("select count(*) from sesiones_caja where cajero_id = :id and estado = 'ABIERTA'")
                .param("id", cajeroId).query(Long.class).single();
    }
}
