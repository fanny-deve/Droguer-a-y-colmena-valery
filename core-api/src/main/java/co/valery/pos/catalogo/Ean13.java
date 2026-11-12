package co.valery.pos.catalogo;

/** Validación del dígito verificador de códigos de barras EAN-13 (RF-02). */
public final class Ean13 {
    private Ean13() {}

    public static boolean esEan13(String codigo) {
        return codigo != null && codigo.matches("^\\d{13}$");
    }

    public static boolean valido(String codigo) {
        if (!esEan13(codigo)) return false;
        int suma = 0;
        for (int i = 0; i < 12; i++) {
            int d = codigo.charAt(i) - '0';
            suma += (i % 2 == 0) ? d : d * 3;
        }
        int verificador = (10 - (suma % 10)) % 10;
        return verificador == codigo.charAt(12) - '0';
    }
}
