package br.com.driverfinance;

import br.com.driverfinance.Analytics.Report;
import br.com.driverfinance.Catalog.Entry;
import br.com.driverfinance.Catalog.ModelRef;
import br.com.driverfinance.Catalog.Verdict;
import br.com.driverfinance.Models.*;
import br.com.driverfinance.Store.Data;
import br.com.driverfinance.Store.Repo;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;

import static br.com.driverfinance.Io.*;

/** Ponto de entrada: menus de console do Controle Financeiro para Motorista de App. */
public final class App {
    private static Data d;

    public static void main(String[] args) {
        System.setOut(new PrintStream(System.out, true, StandardCharsets.UTF_8));
        String home = System.getenv().getOrDefault("DRIVERFINANCE_HOME", System.getProperty("user.home") + "/.driverfinance");
        d = new Data(Path.of(home));
        try { run(); } catch (Quit q) { System.out.println("\nAté logo!"); }
    }

    private static void run() {
        System.out.println("\n##### Controle Financeiro - Motorista de App #####");
        System.out.println("Dados salvos somente neste computador em: " + d.store.dir());
        System.out.println("Catálogo de veículos: " + d.catalog.size() + " regras carregadas.");
        if (d.settings.str("cidade", "").isBlank())
            d.settings.set("cidade", required("Em qual cidade você roda? (as regras da Uber variam por cidade):"));

        while (true) {
            title("MENU  |  Cidade: " + d.settings.str("cidade", "?") + "  |  Carro: " + d.active().map(Vehicle::name).orElse("nenhum"));
            System.out.println(" 1) Veículo (cadastrar / trocar / atualizar)");
            System.out.println(" 2) Financiamento");
            System.out.println(" 3) Custos (manutenção, seguro, IPVA, etc.)");
            System.out.println(" 4) Abastecimento");
            System.out.println(" 5) Registrar dia de trabalho (ganhos)");
            System.out.println(" 6) Relatórios e exportação");
            System.out.println(" 7) Combustível: está compensando?");
            System.out.println(" 8) Estratégias e metas");
            System.out.println(" 9) Lembretes de manutenção");
            System.out.println("10) Configurações");
            System.out.println(" 0) Sair");
            try {
                switch (integer("Opção:", null)) {
                    case 1 -> vehicleMenu();
                    case 2 -> financingMenu();
                    case 3 -> expenseMenu();
                    case 4 -> fuelEntryMenu();
                    case 5 -> workDayMenu();
                    case 6 -> reportMenu();
                    case 7 -> fuelCheck();
                    case 8 -> strategies();
                    case 9 -> reminders();
                    case 10 -> settingsMenu();
                    case 0 -> { System.out.println("Até logo!"); return; }
                    default -> System.out.println("Opção inválida.");
                }
            } catch (Quit q) { throw q; }
            catch (RuntimeException e) { System.out.println("Erro: " + e.getMessage()); }
        }
    }

    private static Vehicle need() {
        Optional<Vehicle> v = d.active();
        if (v.isEmpty()) System.out.println("Cadastre um veículo primeiro (menu 1).");
        return v.orElse(null);
    }

    // =================================================================== 1) Veículo
    private static void vehicleMenu() {
        title("Veículo");
        System.out.println("1) Cadastrar (catálogo Uber)  2) Trocar veículo ativo  3) Atualizar odômetro/valor  4) Remover  0) Voltar");
        switch (integer("Opção:", null)) {
            case 1 -> registerVehicle();
            case 2 -> { Vehicle v = pick("Veículos:", d.vehicles.all(), Vehicle::name); if (v != null) d.setActive(v); }
            case 3 -> {
                Vehicle v = need();
                if (v == null) return;
                v = d.touchOdometer(v, num("Odômetro atual (km):", v.odometer()));
                d.vehicles.replace(v.withValue(num("Valor de mercado atual (R$):", v.currentValue())));
            }
            case 4 -> {
                Vehicle v = pick("Remover qual veículo?", d.vehicles.all(), Vehicle::name);
                if (v != null && yes("Isso apaga também custos, abastecimentos, ganhos e financiamentos dele. Confirmar?", false)) {
                    final long id = v.id();
                    d.financings.where(x -> x.vehicleId() == id).forEach(x -> d.financings.remove(x.id()));
                    d.expenses.where(x -> x.vehicleId() == id).forEach(x -> d.expenses.remove(x.id()));
                    d.fuels.where(x -> x.vehicleId() == id).forEach(x -> d.fuels.remove(x.id()));
                    d.days.where(x -> x.vehicleId() == id).forEach(x -> d.days.remove(x.id()));
                    d.vehicles.remove(id);
                }
            }
            default -> { }
        }
    }

    private static void registerVehicle() {
        String city = d.settings.str("cidade", "*");
        List<ModelRef> found = d.catalog.search(required("Modelo do carro (ex.: onix, hb20, corolla):"));
        if (found.isEmpty()) {
            System.out.println("Modelo não encontrado no catálogo, então não é possível validar a elegibilidade.");
            System.out.println("Confira a lista oficial da sua cidade em uber.com/br/drive/vehicle-requirements e adicione o modelo ao");
            System.out.println("arquivo " + d.store.dir().resolve("catalogo.csv") + " (formato no README) ou importe um catálogo em Configurações.");
            return;
        }
        ModelRef ref = pick("Modelos encontrados:", found, ModelRef::toString);
        if (ref == null) return;
        Entry e = d.catalog.lookup(ref, city).orElse(null);
        if (e == null) { System.out.println("Sem regra para esse modelo em " + city + "."); return; }

        int year = integer("Ano de fabricação:", null);
        String color = required("Cor:");
        boolean ac = yes("O ar-condicionado está funcionando?", e.ac());
        if (yes("Tem placa vermelha, adesivo/plotagem, sinistro ou suspensão/freios alterados?", false)) {
            System.out.println("Esses veículos não são aceitos pela Uber em nenhuma categoria. Cadastro cancelado.");
            return;
        }

        Map<Category, Verdict> verdicts = Catalog.evaluate(year, color, e.doors(), ac, e, LocalDate.now());
        Set<Category> ok = EnumSet.noneOf(Category.class);
        System.out.println("\nElegibilidade de " + ref + " " + year + " em " + city + ":");
        verdicts.forEach((c, v) -> {
            System.out.println("  " + (v.ok() ? "[ACEITO]    " : "[NAO ACEITO]") + " " + c.label + " - " + v.reason());
            if (v.ok()) ok.add(c);
        });
        if (ok.isEmpty()) { System.out.println("\nEste carro não é aceito pela Uber com esses dados. Cadastro cancelado."); return; }
        if (!e.confirmed()) System.out.println("[!] Dados de referência do catálogo: confirme no app/site da Uber antes de comprar ou financiar.");
        if (!e.note().isBlank()) System.out.println("[!] Obs.: " + e.note());
        for (Category c : ok)
            if (Catalog.minTrips(c) > 0)
                System.out.println("[>] " + c.label + " também exige motorista com mais de " + Catalog.minTrips(c)
                        + " viagens e nota mínima " + fmt(Catalog.minRating(c, city)) + ".");

        double kg = 0, ke = 0, kwh = 0;
        if (e.fuel() == FuelType.ELETRICO) kwh = num("Consumo (km por kWh):", e.kmKwh());
        else {
            kg = num("Consumo com gasolina (km/l, use o seu real se souber):", e.kmlGas());
            if (e.fuel() == FuelType.FLEX) ke = num("Consumo com etanol (km/l):", e.kmlEth());
        }
        double purchase = num("Valor pago/compra (R$) (0 se não souber):", 0.0);
        double current = num("Valor de mercado atual (R$, tabela Fipe):", purchase);
        double odo = num("Odômetro atual (km):", 0.0);
        String plate = text("Placa (opcional):", "");
        final double fkg = kg, fke = ke, fkwh = kwh;
        Vehicle v = d.vehicles.add(id -> new Vehicle(id, ref.make(), ref.model(), year, color, e.doors(), ac, e.fuel(),
                fkg, fke, fkwh, purchase, current, odo, plate, ok));
        d.setActive(v);
        System.out.println("\nVeículo cadastrado. Categorias adicionadas automaticamente: "
                + ok.stream().map(c -> c.label).toList());
    }

    // =================================================================== 2) Financiamento
    private static void financingMenu() {
        Vehicle v = need();
        if (v == null) return;
        manage("Financiamento", d.financings, f -> f.vehicleId() == v.id(), () -> {
            String lender = text("Banco/financeira:", "");
            double financed = num("Valor financiado (R$):", null);
            double down = num("Entrada (R$):", 0.0);
            double inst = num("Valor da parcela (R$):", null);
            int n = integer("Número de parcelas:", null);
            LocalDate first = date("Vencimento da 1ª parcela:", LocalDate.now());
            Financing f = d.financings.add(id -> new Financing(id, v.id(), lender, financed, down, inst, n, first));
            System.out.println("Custo total: " + money(f.totalCost()) + " | juros embutidos: ~" + pct(f.impliedMonthlyRate())
                    + " a.m. | já pagas: " + f.paid(LocalDate.now()) + " | saldo: " + money(f.remainingBalance(LocalDate.now())));
        }, f -> f.lender() + " | " + money(f.installment()) + " x " + f.total() + " | 1ª em " + DF.format(f.firstDue())
                + " | faltam " + f.remaining(LocalDate.now()));
    }

    // =================================================================== 3) Custos
    private static void expenseMenu() {
        Vehicle v = need();
        if (v == null) return;
        manage("Custos", d.expenses, e -> e.vehicleId() == v.id(), () -> {
            ExpenseType t = pick("Tipo de custo:", List.of(ExpenseType.values()), Enum::name);
            if (t == null) return;
            String desc = text("Descrição:", t.name());
            double amount = num("Valor (R$):", null);
            LocalDate dt = date("Data (início, se recorrente):", LocalDate.now());
            Recurrence rec = pick("Recorrência (mensal/anual são rateadas por dia nos relatórios):", List.of(Recurrence.values()), Enum::name);
            if (rec == null) return;
            double odo = t.maintenance ? num("Odômetro no serviço (km, 0 se não souber):", 0.0) : 0;
            d.expenses.add(id -> new Expense(id, v.id(), t, desc, amount, dt, rec, odo));
            if (odo > 0) d.touchOdometer(v, odo);
            System.out.println("Custo registrado.");
        }, e -> DF.format(e.date()) + " | " + e.type() + " | " + e.description() + " | " + money(e.amount()) + " | " + e.recurrence());
    }

    // =================================================================== 4) Abastecimento
    private static void fuelEntryMenu() {
        Vehicle v0 = need();
        if (v0 == null) return;
        manage("Abastecimento", d.fuels, f -> f.vehicleId() == v0.id(), () -> {
            Vehicle v = d.active().orElseThrow();
            Fuel fuel = pick("Combustível:", v.allowedFuels(), Enum::name);
            if (fuel == null) return;
            List<FuelEntry> hist = d.fuels.where(x -> x.vehicleId() == v.id());
            double lp = Analytics.lastPrice(hist, fuel);
            double qty = num("Quantidade (" + fuel.unit + "):", null);
            double price = num("Preço por " + fuel.unit + " (R$):", lp > 0 ? lp : null);
            double odo = num("Odômetro (km) (0 se não souber):", 0.0);
            boolean full = yes("Encheu o tanque/bateria?", true);
            LocalDate dt = date("Data:", LocalDate.now());
            d.fuels.add(id -> new FuelEntry(id, v.id(), dt, fuel, qty, price, odo, full));
            d.touchOdometer(v, odo);
            System.out.println("Total: " + money(qty * price));
            Map<Fuel, Double> real = Analytics.realConsumption(d.fuels.where(x -> x.vehicleId() == v.id()));
            if (real.containsKey(fuel)) {
                double decl = v.consumption(fuel);
                System.out.println("Consumo real médio: " + fmt(real.get(fuel)) + " km/" + fuel.unit + " (declarado: " + fmt(decl) + ")");
                if (decl > 0 && real.get(fuel) < decl * 0.85)
                    System.out.println("[!] Consumo real 15%+ pior que o declarado: verifique calibragem, filtro de ar, velas e estilo de condução.");
            }
        }, f -> DF.format(f.date()) + " | " + f.fuel() + " | " + fmt(f.quantity()) + " " + f.fuel().unit + " a " + money(f.unitPrice())
                + " | odo " + Math.round(f.odometer()) + " | " + (f.fullTank() ? "cheio" : "parcial"));
    }

    // =================================================================== 5) Ganhos
    private static void workDayMenu() {
        Vehicle v0 = need();
        if (v0 == null) return;
        manage("Dias de trabalho", d.days, w -> w.vehicleId() == v0.id(), () -> {
            Vehicle v = d.active().orElseThrow();
            LocalDate dt = date("Data:", LocalDate.now());
            String platform = text("Plataforma:", "Uber");
            double hours = num("Horas online:", null);
            double km = num("Km rodados no dia (inclusive vazio):", null);
            int trips = integer("Corridas:", null);
            double earn = num("Ganhos das corridas (R$):", null);
            double tips = num("Gorjetas (R$):", 0.0);
            d.days.add(id -> new WorkDay(id, v.id(), dt, platform, hours, km, trips, earn, tips));
            double gross = earn + tips;
            double fuelEst = km * Analytics.fuelCostPerKm(v, d.fuels.where(x -> x.vehicleId() == v.id()));
            System.out.println("Resumo: " + money(gross) + " brutos | " + money(hours > 0 ? gross / hours : 0) + "/h | "
                    + money(km > 0 ? gross / km : 0) + "/km | combustível estimado " + money(fuelEst)
                    + " | sobra (antes de outros custos) " + money(gross - fuelEst));
        }, w -> DF.format(w.date()) + " | " + w.platform() + " | " + fmt(w.hours()) + "h | " + fmt(w.km()) + " km | "
                + w.trips() + " corridas | " + money(w.gross()));
    }

    // =================================================================== 6) Relatórios
    private static void reportMenu() {
        Vehicle v = need();
        if (v == null) return;
        LocalDate today = LocalDate.now();
        String choice = pick("Período:", List.of("Hoje", "Últimos 7 dias", "Mês atual", "Últimos 30 dias", "Personalizado"), s -> s);
        if (choice == null) return;
        LocalDate from = switch (choice) {
            case "Hoje" -> today;
            case "Últimos 7 dias" -> today.minusDays(6);
            case "Mês atual" -> today.withDayOfMonth(1);
            case "Últimos 30 dias" -> today.minusDays(29);
            default -> date("De:", today.withDayOfMonth(1));
        };
        LocalDate to = choice.equals("Personalizado") ? date("Até:", today) : today;
        Report r = Analytics.report(d, v, from, to);
        printReport(r);
        if (yes("Exportar para CSV (abre no Excel)?", false)) exportCsv(v, r);
    }

    private static void row(String label, double v) { System.out.printf(BR, "  %-40s %s%n", label, money(v)); }

    private static void printReport(Report r) {
        title("Relatório " + DF.format(r.from()) + " a " + DF.format(r.to()));
        System.out.printf(BR, "  Dias trabalhados: %d | horas: %.1f | km: %.0f | corridas: %d%n", r.workDays(), r.hours(), r.km(), r.trips());
        row("Faturamento bruto (ganhos + gorjetas)", r.gross());
        row("(-) Combustível" + (r.fuelEstimated() ? " (estimado)" : ""), r.fuel());
        row("(-) Manutenção", r.maintenance());
        row("(-) Custos avulsos", r.variable());
        row("(-) Custos fixos (seguro, IPVA, etc.)", r.fixed());
        row("(-) Financiamento (proporcional)", r.financing());
        row("= LUCRO LÍQUIDO (caixa)", r.net());
        row("(-) Depreciação estimada do carro", r.depreciation());
        row("= LUCRO REAL (com depreciação)", r.netEconomic());
        System.out.println("  Líquido por hora: " + money(r.netPerHour()) + " | faturamento por km: " + money(r.grossPerKm())
                + " | custo por km: " + money(r.costPerKm()) + " | margem: " + pct(r.margin()));
    }

    private static void exportCsv(Vehicle v, Report r) {
        List<String> lines = new ArrayList<>(List.of("metrica;valor",
                "periodo;" + DF.format(r.from()) + " a " + DF.format(r.to()), "faturamento_bruto;" + fmt(r.gross()),
                "combustivel;" + fmt(r.fuel()), "manutencao;" + fmt(r.maintenance()), "custos_avulsos;" + fmt(r.variable()),
                "custos_fixos;" + fmt(r.fixed()), "financiamento;" + fmt(r.financing()), "lucro_liquido;" + fmt(r.net()),
                "depreciacao;" + fmt(r.depreciation()), "lucro_real;" + fmt(r.netEconomic()), "", "data;plataforma;horas;km;corridas;bruto"));
        for (WorkDay w : d.days.where(x -> x.vehicleId() == v.id() && !x.date().isBefore(r.from()) && !x.date().isAfter(r.to())))
            lines.add(DF.format(w.date()) + ";" + w.platform() + ";" + fmt(w.hours()) + ";" + fmt(w.km()) + ";" + w.trips() + ";" + fmt(w.gross()));
        Path p = d.store.dir().resolve("relatorio_" + r.from() + "_" + r.to() + ".csv");
        try { Files.write(p, lines, StandardCharsets.UTF_8); System.out.println("Exportado: " + p); }
        catch (IOException e) { System.out.println("Falha ao exportar: " + e.getMessage()); }
    }

    // =================================================================== 7) Combustível
    private static void fuelCheck() {
        Vehicle v = need();
        if (v == null) return;
        title("Combustível: está compensando?");
        List<FuelEntry> hist = d.fuels.where(x -> x.vehicleId() == v.id());
        Map<Fuel, Double> real = Analytics.realConsumption(hist);
        for (Fuel f : v.allowedFuels())
            System.out.println("  " + f + ": real " + (real.containsKey(f) ? fmt(real.get(f)) : "sem dados (abasteça com tanque cheio + odômetro)")
                    + " | declarado " + fmt(v.consumption(f)) + " km/" + f.unit);

        if (v.fuelType() == FuelType.FLEX) {
            double pg = priceAsk(hist, Fuel.GASOLINA), pe = priceAsk(hist, Fuel.ETANOL);
            var dec = Analytics.ethanolVsGasoline(v, pg, pe, real);
            System.out.println("\n  Custo por km: gasolina " + money(dec.costKmGas()) + " | etanol " + money(dec.costKmEth()));
            System.out.println("  Regra: etanol compensa se preço_etanol/preço_gasolina < " + fmt(dec.breakEvenRatio())
                    + " (hoje: " + fmt(pe / pg) + ").");
            System.out.println("  >> ABASTEÇA COM " + (dec.ethanolWins() ? "ETANOL" : "GASOLINA") + " (economia ~" + money(dec.savingPer100Km()) + " por 100 km).");
        } else {
            for (Fuel f : v.allowedFuels()) {
                double p = priceAsk(hist, f);
                System.out.println("  Custo por km com " + f + ": " + money(Analytics.costPerKm(v, f, p, real)));
            }
        }
        Report r = Analytics.report(d, v, LocalDate.now().minusDays(29), LocalDate.now());
        if (r.gross() > 0) {
            String verdict = r.fuelShare() >= Advisor.FUEL_SHARE_BAD ? "NÃO COMPENSA no ritmo atual"
                    : r.fuelShare() >= Advisor.FUEL_SHARE_WARN ? "NO LIMITE" : "COMPENSA";
            System.out.println("\n  Últimos 30 dias: combustível " + money(r.fuel()) + (r.fuelEstimated() ? " (estimado)" : "") + " = "
                    + pct(r.fuelShare()) + " do faturamento -> " + verdict + ".");
        } else System.out.println("\n  Registre dias de trabalho (menu 5) para medir o peso do combustível no faturamento.");
    }

    private static double priceAsk(List<FuelEntry> hist, Fuel f) {
        double last = Analytics.lastPrice(hist, f);
        return num("Preço atual de " + f + " (R$/" + f.unit + "):", last > 0 ? last : null);
    }

    // =================================================================== 8) Estratégias
    private static void strategies() {
        Vehicle v = need();
        if (v == null) return;
        title("Estratégias para " + v.name());
        Advisor.advise(d, v, LocalDate.now()).forEach(s -> System.out.println("  " + s));
        if (d.settings.dbl("metaMensal", 0) <= 0) System.out.println("  [>] Defina uma meta mensal em Configurações para receber o plano de dias/horas.");
    }

    // =================================================================== 9) Lembretes
    private static void reminders() {
        Vehicle v = need();
        if (v == null) return;
        title("Lembretes de manutenção (odômetro atual: " + Math.round(v.odometer()) + " km)");
        Analytics.maintenance(d, v).forEach(m -> System.out.println("  [" + m.level() + "] " + m.text()));
        System.out.println("  Intervalos são sugestões genéricas: siga o manual do seu carro.");
    }

    // =================================================================== 10) Configurações
    private static void settingsMenu() {
        title("Configurações");
        System.out.println("1) Cidade (" + d.settings.str("cidade", "?") + ")");
        System.out.println("2) Minha nota na Uber (" + fmt(d.settings.dbl("nota", 0)) + ") e viagens feitas (" + d.settings.lng("viagens", 0) + ")");
        System.out.println("3) Meta mensal líquida (" + money(d.settings.dbl("metaMensal", 0)) + ")");
        System.out.println("4) Meta de líquido por hora (" + money(d.settings.dbl("metaHora", 25)) + ")");
        System.out.println("5) Depreciação anual do carro (" + pct(d.settings.dbl("depreciacaoAnual", 0.10)) + ")");
        System.out.println("6) Importar catálogo de veículos (CSV)");
        System.out.println("0) Voltar");
        switch (integer("Opção:", null)) {
            case 1 -> d.settings.set("cidade", required("Cidade:"));
            case 2 -> { d.settings.set("nota", num("Nota média (ex.: 4,86):", null)); d.settings.set("viagens", integer("Total de viagens:", null)); }
            case 3 -> d.settings.set("metaMensal", num("Meta líquida por mês (R$):", null));
            case 4 -> d.settings.set("metaHora", num("Meta líquida por hora (R$):", null));
            case 5 -> d.settings.set("depreciacaoAnual", num("Depreciação anual em % (ex.: 10):", null) / 100.0);
            case 6 -> importCatalog();
            default -> { }
        }
    }

    private static void importCatalog() {
        Path src = Path.of(required("Caminho do arquivo CSV:"));
        try {
            Catalog.parse(Files.readAllLines(src, StandardCharsets.UTF_8)); // valida antes de copiar
            Files.copy(src, d.store.dir().resolve("catalogo.csv"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            d.reloadCatalog();
            System.out.println("Catálogo importado. Regras carregadas: " + d.catalog.size());
        } catch (IOException | IllegalArgumentException e) { System.out.println("Não foi possível importar: " + e.getMessage()); }
    }

    // =================================================================== utilitário de CRUD
    private static <T extends Entity> void manage(String title, Repo<T> repo, Predicate<T> mine, Runnable add, Function<T, String> label) {
        while (true) {
            title(title);
            System.out.println("1) Adicionar  2) Listar  3) Remover  0) Voltar");
            switch (integer("Opção:", null)) {
                case 1 -> add.run();
                case 2 -> {
                    List<T> l = repo.where(mine);
                    if (l.isEmpty()) System.out.println("  Nenhum registro.");
                    else l.stream().skip(Math.max(0, l.size() - 30)).forEach(e -> System.out.println("  - " + label.apply(e)));
                }
                case 3 -> {
                    T t = pick("Remover qual registro?", repo.where(mine), label);
                    if (t != null && yes("Confirmar remoção?", false)) repo.remove(t.id());
                }
                case 0 -> { return; }
                default -> System.out.println("Opção inválida.");
            }
        }
    }
}
