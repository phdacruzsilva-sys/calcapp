package br.com.driverfinance;

import br.com.driverfinance.Models.*;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Persistência 100% local: um arquivo .tsv legível por entidade dentro da pasta de dados
 * (padrão: ~/.driverfinance). Nada sai do computador do motorista.
 */
public final class Store {
    private final Path dir;

    public Store(Path dir) {
        this.dir = dir;
        try { Files.createDirectories(dir); } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    public Path dir() { return dir; }

    List<String[]> read(String file) {
        Path p = dir.resolve(file);
        if (!Files.exists(p)) return new ArrayList<>();
        try {
            List<String[]> rows = new ArrayList<>();
            for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
                if (line.isBlank()) continue;
                String[] f = line.split("\t", -1);
                for (int i = 0; i < f.length; i++) f[i] = unescape(f[i]);
                rows.add(f);
            }
            return rows;
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    void write(String file, List<String[]> rows) {
        List<String> lines = new ArrayList<>();
        for (String[] r : rows) {
            String[] f = new String[r.length];
            for (int i = 0; i < r.length; i++) f[i] = escape(r[i]);
            lines.add(String.join("\t", f));
        }
        Path target = dir.resolve(file), tmp = dir.resolve(file + ".tmp");
        try {
            Files.write(tmp, lines, StandardCharsets.UTF_8);
            try { Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException e) { Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING); }
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "");
    }

    static String unescape(String s) {
        if (s.indexOf('\\') < 0) return s;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                sb.append(n == 't' ? '\t' : n == 'n' ? '\n' : n);
            } else sb.append(c);
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ Repositório genérico
    public static final class Repo<T extends Entity> {
        private final Store store;
        private final String file;
        private final List<T> items = new ArrayList<>();

        Repo(Store store, String file, Function<String[], T> parser) {
            this.store = store;
            this.file = file;
            for (String[] r : store.read(file)) items.add(parser.apply(r));
        }

        public List<T> all() { return List.copyOf(items); }
        public List<T> where(Predicate<T> p) { return items.stream().filter(p).toList(); }

        public T add(Function<Long, T> factory) {
            long id = items.stream().mapToLong(Entity::id).max().orElse(0) + 1;
            T t = factory.apply(id);
            items.add(t);
            save();
            return t;
        }

        public void replace(T t) {
            for (int i = 0; i < items.size(); i++) if (items.get(i).id() == t.id()) items.set(i, t);
            save();
        }

        public boolean remove(long id) {
            boolean removed = items.removeIf(e -> e.id() == id);
            if (removed) save();
            return removed;
        }

        private void save() { store.write(file, items.stream().map(Entity::row).toList()); }
    }

    // ------------------------------------------------------------------ Configurações (chave/valor)
    public static final class Settings {
        private final Store store;
        private final Map<String, String> map = new LinkedHashMap<>();

        Settings(Store store) {
            this.store = store;
            for (String[] r : store.read("configuracoes.tsv")) if (r.length >= 2) map.put(r[0], r[1]);
        }

        public String str(String key, String def) { return map.getOrDefault(key, def); }
        public double dbl(String key, double def) { try { return Double.parseDouble(map.get(key)); } catch (Exception e) { return def; } }
        public long lng(String key, long def) { try { return Long.parseLong(map.get(key)); } catch (Exception e) { return def; } }

        public void set(String key, Object value) {
            map.put(key, String.valueOf(value));
            store.write("configuracoes.tsv", map.entrySet().stream().map(e -> new String[]{e.getKey(), e.getValue()}).toList());
        }
    }

    // ------------------------------------------------------------------ Agregado com todos os dados
    public static final class Data {
        public final Store store;
        public final Settings settings;
        public Catalog catalog;
        public final Repo<Vehicle> vehicles;
        public final Repo<Financing> financings;
        public final Repo<Expense> expenses;
        public final Repo<FuelEntry> fuels;
        public final Repo<WorkDay> days;

        public Data(Path dir) {
            store = new Store(dir);
            settings = new Settings(store);
            catalog = Catalog.load(dir.resolve("catalogo.csv"));
            vehicles = new Repo<>(store, "veiculos.tsv", Vehicle::of);
            financings = new Repo<>(store, "financiamentos.tsv", Financing::of);
            expenses = new Repo<>(store, "custos.tsv", Expense::of);
            fuels = new Repo<>(store, "abastecimentos.tsv", FuelEntry::of);
            days = new Repo<>(store, "dias_trabalho.tsv", WorkDay::of);
        }

        public void reloadCatalog() { catalog = Catalog.load(store.dir().resolve("catalogo.csv")); }

        public Optional<Vehicle> active() {
            long id = settings.lng("veiculoAtivo", -1);
            return vehicles.all().stream().filter(v -> v.id() == id).findFirst()
                    .or(() -> vehicles.all().stream().findFirst());
        }

        public void setActive(Vehicle v) { settings.set("veiculoAtivo", v.id()); }

        /** Atualiza o odômetro do veículo apenas se o novo valor for maior. */
        public Vehicle touchOdometer(Vehicle v, double odo) {
            if (odo <= v.odometer()) return v;
            Vehicle nv = v.withOdometer(odo);
            vehicles.replace(nv);
            return nv;
        }
    }
}
