package br.com.driverfinance;

import br.com.driverfinance.Models.Category;
import br.com.driverfinance.Models.FuelType;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.time.LocalDate;
import java.util.*;

/**
 * Catálogo de modelos aceitos + motor de elegibilidade por categoria.
 *
 * A Uber NÃO oferece API pública com a lista de veículos aceitos; a lista oficial muda por cidade e por data
 * e fica em uber.com/br/drive/vehicle-requirements. Por isso o catálogo é um CSV editável
 * (recurso embutido + ~/.driverfinance/catalogo.csv, que tem prioridade). Veja o README.
 */
public final class Catalog {

    public record Entry(String city, String make, String model, String body, int doors, boolean ac, FuelType fuel,
                        double kmlGas, double kmlEth, double kmKwh, int minX, int minComfort, int minBlack,
                        Map<Category, LocalDate> excludedFrom, boolean confirmed, String note) {
        public int minYear(Category c) {
            return switch (c) { case UBERX -> minX; case COMFORT -> minComfort; case BLACK -> minBlack; };
        }
    }

    public record ModelRef(String make, String model) {
        @Override public String toString() { return make + " " + model; }
    }

    public record Verdict(boolean ok, String reason) {}

    /** Cores aceitas no Uber Black (comunicado da Uber Brasil). */
    public static final Set<String> BLACK_COLORS =
            Set.of("preto", "cinza", "prata", "branco", "azul-marinho", "marrom", "chumbo");

    /** Cidades com nota mínima de 4,85 também no Comfort (nas demais: 4,80). */
    private static final Set<String> HIGH_RATING_CITIES =
            Set.of("brasilia", "caxias do sul", "curitiba", "joinville", "londrina", "porto alegre");

    private final List<Entry> entries;

    private Catalog(List<Entry> entries) { this.entries = entries; }

    // ------------------------------------------------------------------ Carga
    public static Catalog load(Path userFile) {
        List<Entry> all = new ArrayList<>();
        try {
            if (Files.exists(userFile)) all.addAll(parse(Files.readAllLines(userFile, StandardCharsets.UTF_8)));
            try (InputStream in = Catalog.class.getResourceAsStream("/catalogo_veiculos.csv")) {
                if (in != null) {
                    List<String> lines = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)).lines().toList();
                    all.addAll(parse(lines));
                }
            }
        } catch (IOException e) { throw new UncheckedIOException(e); }
        return new Catalog(all);
    }

    /** Valida e converte linhas CSV (separador ';'). Lança IllegalArgumentException se houver linha inválida. */
    public static List<Entry> parse(List<String> lines) {
        List<Entry> out = new ArrayList<>();
        int n = 0;
        for (String line : lines) {
            n++;
            if (line.isBlank() || line.startsWith("#") || line.startsWith("cidade;")) continue;
            String[] c = line.split(";", -1);
            try {
                if (c.length != 16) throw new IllegalArgumentException("esperadas 16 colunas, encontradas " + c.length);
                Map<Category, LocalDate> ex = new EnumMap<>(Category.class);
                if (!c[13].isBlank())
                    for (String p : c[13].split("\\|")) { String[] kv = p.split(":"); ex.put(Category.valueOf(kv[0]), LocalDate.parse(kv[1])); }
                out.add(new Entry(c[0].trim(), c[1].trim(), c[2].trim(), c[3].trim(), Integer.parseInt(c[4]),
                        c[5].equalsIgnoreCase("SIM"), FuelType.valueOf(c[6].trim()), Double.parseDouble(c[7]),
                        Double.parseDouble(c[8]), Double.parseDouble(c[9]), Integer.parseInt(c[10]),
                        Integer.parseInt(c[11]), Integer.parseInt(c[12]), ex, c[14].equalsIgnoreCase("SIM"), c[15].trim()));
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("Linha " + n + " inválida: " + e.getMessage());
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ Consulta
    static String norm(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT).trim();
    }

    public List<ModelRef> search(String query) {
        String[] tokens = norm(query).split("\\s+");
        Set<ModelRef> found = new TreeSet<>(Comparator.comparing(ModelRef::toString));
        for (Entry e : entries) {
            String hay = norm(e.make() + " " + e.model());
            if (Arrays.stream(tokens).allMatch(hay::contains)) found.add(new ModelRef(e.make(), e.model()));
        }
        return new ArrayList<>(found);
    }

    /** Regra específica da cidade tem prioridade sobre a regra padrão ("*"); arquivo do usuário vem antes do embutido. */
    public Optional<Entry> lookup(ModelRef ref, String city) {
        String nc = norm(city);
        List<Entry> same = entries.stream()
                .filter(e -> e.make().equals(ref.make()) && e.model().equals(ref.model())).toList();
        return same.stream().filter(e -> norm(e.city()).equals(nc)).findFirst()
                .or(() -> same.stream().filter(e -> e.city().equals("*")).findFirst());
    }

    public int size() { return entries.size(); }

    // ------------------------------------------------------------------ Elegibilidade
    public static Map<Category, Verdict> evaluate(int year, String color, int doors, boolean ac, Entry e, LocalDate today) {
        Map<Category, Verdict> out = new EnumMap<>(Category.class);
        for (Category c : Category.values()) {
            int min = e.minYear(c);
            LocalDate cut = e.excludedFrom().get(c);
            Verdict v;
            if (min == 0) v = new Verdict(false, "modelo não consta nesta categoria");
            else if (doors < 4) v = new Verdict(false, "exige 4 portas");
            else if (!ac) v = new Verdict(false, "exige ar-condicionado");
            else if (year < min) v = new Verdict(false, "ano mínimo " + min);
            else if (cut != null && !today.isBefore(cut)) v = new Verdict(false, "modelo excluído desde " + Io.DF.format(cut));
            else if (c == Category.BLACK && !BLACK_COLORS.contains(norm(color)))
                v = new Verdict(false, "cor não aceita (use preto, cinza, prata, branco, azul-marinho, marrom ou chumbo)");
            else v = new Verdict(true, cut != null ? "aceito até " + Io.DF.format(cut) : "ok");
            out.put(c, v);
        }
        return out;
    }

    public static int minTrips(Category c) { return c == Category.UBERX ? 0 : 100; }

    public static double minRating(Category c, String city) {
        return switch (c) {
            case UBERX -> 0;
            case BLACK -> 4.85;
            case COMFORT -> HIGH_RATING_CITIES.contains(norm(city)) ? 4.85 : 4.80;
        };
    }
}
