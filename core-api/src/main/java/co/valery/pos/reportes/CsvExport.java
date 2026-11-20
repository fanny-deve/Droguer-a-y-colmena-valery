package co.valery.pos.reportes;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/** Exportación de reportes a CSV listo para Excel (UTF-8 con BOM, separador ";"). Aplana objetos anidados con claves "a.b". */
public final class CsvExport {
    private CsvExport() {}

    public static boolean pide(String formato) {
        return formato != null && formato.trim().equalsIgnoreCase("csv");
    }

    public static ResponseEntity<byte[]> respuesta(String nombreBase, ObjectMapper om, Object datos) {
        List<Map<String, Object>> filas = new ArrayList<>();
        if (datos instanceof Iterable<?> it) {
            for (Object o : it) filas.add(aplanar(om, o));
        } else {
            filas.add(aplanar(om, datos));
        }
        Set<String> columnas = new LinkedHashSet<>();
        filas.forEach(f -> columnas.addAll(f.keySet()));
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            bos.write(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
            try (CSVPrinter p = new CSVPrinter(new OutputStreamWriter(bos, StandardCharsets.UTF_8),
                    CSVFormat.DEFAULT.builder().setDelimiter(';').setRecordSeparator("\r\n").build())) {
                p.printRecord(columnas);
                for (Map<String, Object> f : filas) {
                    List<Object> fila = new ArrayList<>();
                    for (String c : columnas) fila.add(seguro(f.get(c)));
                    p.printRecord(fila);
                }
            }
            return ResponseEntity.ok().contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                    .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(nombreBase + ".csv").build().toString())
                    .body(bos.toByteArray());
        } catch (IOException e) {
            throw new IllegalStateException("No se pudo generar el CSV", e);
        }
    }

    /** Evita la "inyección de fórmulas" en Excel: un valor de texto que empiece por = + - @ se antepone con comilla. */
    private static Object seguro(Object v) {
        if (v instanceof String s && !s.isEmpty() && "=+-@".indexOf(s.charAt(0)) >= 0) return "'" + s;
        return v;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> aplanar(ObjectMapper om, Object o) {
        Map<String, Object> origen = om.convertValue(o, Map.class);
        Map<String, Object> salida = new LinkedHashMap<>();
        aplanar("", origen, salida);
        return salida;
    }

    @SuppressWarnings("unchecked")
    private static void aplanar(String prefijo, Map<String, Object> m, Map<String, Object> salida) {
        for (Map.Entry<String, Object> e : m.entrySet()) {
            String clave = prefijo.isEmpty() ? e.getKey() : prefijo + "." + e.getKey();
            if (e.getValue() instanceof Map<?, ?> hijo) aplanar(clave, (Map<String, Object>) hijo, salida);
            else if (e.getValue() instanceof List<?> lista) salida.put(clave, lista.toString());
            else salida.put(clave, e.getValue());
        }
    }
}
