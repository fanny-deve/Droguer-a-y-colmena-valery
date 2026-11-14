package co.valery.pos.importacion;

import co.valery.pos.common.ApiException;
import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

/** Lector de CSV tolerante a lo que produce Excel en Colombia: separador ; o ,, UTF-8 con BOM o Windows-1252, encabezados con tildes. */
public final class CsvLector {
    private CsvLector() {}

    public record Fila(int numero, Map<String, String> valores) {
        public String get(String columna) {
            String v = valores.get(columna);
            return v == null || v.isBlank() ? null : v.trim();
        }
    }

    public record Archivo(List<String> encabezados, List<Fila> filas) {}

    public static Archivo leer(byte[] bytes, int maxFilas) {
        if (bytes == null || bytes.length == 0) {
            throw ApiException.badRequest("ARCHIVO_VACIO", "El archivo está vacío");
        }
        String texto = decodificar(bytes);
        if (texto.startsWith("\uFEFF")) texto = texto.substring(1);
        String primera = texto.lines().filter(l -> !l.isBlank()).findFirst().orElse("");
        char delimitador = primera.chars().filter(c -> c == ';').count() > primera.chars().filter(c -> c == ',').count() ? ';' : ',';
        CSVFormat formato = CSVFormat.DEFAULT.builder().setDelimiter(delimitador).setHeader().setSkipHeaderRecord(true)
                .setIgnoreEmptyLines(true).setIgnoreSurroundingSpaces(true).setTrim(true).build();
        try (CSVParser parser = formato.parse(new StringReader(texto))) {
            List<String> originales = parser.getHeaderNames();
            List<String> encabezados = new ArrayList<>();
            for (String h : originales) encabezados.add(normalizar(h));
            List<Fila> filas = new ArrayList<>();
            for (CSVRecord r : parser) {
                if (filas.size() >= maxFilas) {
                    throw ApiException.badRequest("DEMASIADAS_FILAS", "El archivo supera el máximo de " + maxFilas + " filas por importación");
                }
                Map<String, String> m = new LinkedHashMap<>();
                for (int i = 0; i < encabezados.size() && i < r.size(); i++) m.put(encabezados.get(i), r.get(i));
                filas.add(new Fila((int) r.getRecordNumber() + 1, m));   // la fila 1 es el encabezado, como en Excel
            }
            return new Archivo(encabezados, filas);
        } catch (IOException | IllegalArgumentException e) {
            throw ApiException.badRequest("ARCHIVO_INVALIDO", "No se pudo leer el archivo como CSV: " + e.getMessage());
        }
    }

    private static String decodificar(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            return new String(bytes, java.nio.charset.Charset.forName("windows-1252"));
        }
    }

    public static String normalizar(String h) {
        if (h == null) return "";
        String s = Normalizer.normalize(h.trim().toLowerCase(Locale.ROOT), Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return s.replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
    }
}
