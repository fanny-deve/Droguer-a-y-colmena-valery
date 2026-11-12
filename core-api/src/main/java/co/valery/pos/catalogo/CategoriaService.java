package co.valery.pos.catalogo;

import co.valery.pos.auditoria.AuditService;
import co.valery.pos.catalogo.CatalogoDtos.CategoriaDto;
import co.valery.pos.catalogo.CatalogoDtos.CategoriaRequest;
import co.valery.pos.common.ApiException;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CategoriaService {
    private final JdbcClient jdbc;
    private final AuditService audit;

    public CategoriaService(JdbcClient jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<CategoriaDto> listar(boolean soloActivas) {
        return jdbc.sql("select id, nombre, descripcion, activa from categorias where (not :soloActivas or activa) order by nombre")
                .param("soloActivas", soloActivas)
                .query((rs, i) -> new CategoriaDto(rs.getInt("id"), rs.getString("nombre"), rs.getString("descripcion"),
                        rs.getBoolean("activa"))).list();
    }

    @Transactional
    public CategoriaDto crear(CategoriaRequest r) {
        if (jdbc.sql("select count(*) from categorias where lower(nombre) = lower(:n)").param("n", r.nombre().trim())
                .query(Long.class).single() > 0) {
            throw ApiException.conflict("CATEGORIA_DUPLICADA", "Ya existe una categoría con ese nombre");
        }
        int id = jdbc.sql("insert into categorias(nombre, descripcion) values (:n, cast(:d as text)) returning id")
                .param("n", r.nombre().trim()).param("d", r.descripcion()).query((rs, i) -> rs.getInt(1)).single();
        audit.registrar("CREAR_CATEGORIA", "categorias", String.valueOf(id), Map.of("nombre", r.nombre().trim()));
        return obtener(id);
    }

    @Transactional
    public CategoriaDto actualizar(int id, CategoriaRequest r, Boolean activa) {
        obtener(id);
        if (jdbc.sql("select count(*) from categorias where lower(nombre) = lower(:n) and id <> :id")
                .param("n", r.nombre().trim()).param("id", id).query(Long.class).single() > 0) {
            throw ApiException.conflict("CATEGORIA_DUPLICADA", "Ya existe una categoría con ese nombre");
        }
        jdbc.sql("update categorias set nombre = :n, descripcion = cast(:d as text), activa = coalesce(cast(:a as boolean), activa) where id = :id")
                .param("n", r.nombre().trim()).param("d", r.descripcion()).param("a", activa).param("id", id).update();
        audit.registrar("ACTUALIZAR_CATEGORIA", "categorias", String.valueOf(id), Map.of("nombre", r.nombre().trim()));
        return obtener(id);
    }

    public CategoriaDto obtener(int id) {
        return jdbc.sql("select id, nombre, descripcion, activa from categorias where id = :id").param("id", id)
                .query((rs, i) -> new CategoriaDto(rs.getInt("id"), rs.getString("nombre"), rs.getString("descripcion"),
                        rs.getBoolean("activa"))).optional()
                .orElseThrow(() -> ApiException.notFound("CATEGORIA_NO_ENCONTRADA", "Categoría no encontrada"));
    }
}
