package br.com.driverfinance;

import br.com.driverfinance.Models.*;
import br.com.driverfinance.Store.Data;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Cálculos financeiros, de combustível e de manutenção. Sem I/O: só recebe dados e devolve números. */
public final class Analytics {
    private Analytics() {}

    public static final double MONTH_DAYS = 30.4375;

    public record Report(LocalDate from, LocalDate to, int workDays, double hours, double km, int trips,
                         double gross, double fuel, boolean fuelEstimated, double maintenance, double variable,
                         double fixed, double financing, double depreciation) {
        public int periodDays() { return (int) ChronoUnit.DAYS.between(from, to) + 1; }
        public double cashCost() { return fuel + maintenance + variable + fixed + financing; }
        public double net() { return gross - cashCost(); }
        public double netEconomic() { return net() - depreciation; }
        public double netPerHour() { return hours > 0 ? net() / hours : 0; }
        public double grossPerKm() { return km > 0 ? gross / km : 0; }
        public double costPerKm() { return km > 0 ? cashCost() / km : 0; }
        public double margin() { return gross > 0 ? net() / gross : 0; }
        public double fuelShare() { return gross > 0 ? fuel / gross : 0; }
        /** Custo que varia com o uso (combustível, manutenção, avulsos). */
        public double variableCost() { return fuel + maintenance + variable; }
        public double fixedMonthly() { return (fixed + financing) / periodDays() * MONTH_DAYS; }
    }

    private static boolean in(LocalDate d, LocalDate from, LocalDate to) { return !d.isBefore(from) && !d.isAfter(to); }

    private static long overlap(LocalDate start, LocalDate end, LocalDate from, LocalDate to) {
        LocalDate s = start.isAfter(from) ? start : from, e = end.isBefore(to) ? end : to;
        return e.isBefore(s) ? 0 : ChronoUnit.DAYS.between(s, e) + 1;
    }

    // ------------------------------------------------------------------ Relatório de período
    public static Report report(Data d, Vehicle v, LocalDate from, LocalDate to) {
        List<WorkDay> days = d.days.where(w -> w.vehicleId() == v.id() && in(w.date(), from, to));
        double hours = days.stream().mapToDouble(WorkDay::hours).sum();
        double km = days.stream().mapToDouble(WorkDay::km).sum();
        double gross = days.stream().mapToDouble(WorkDay::gross).sum();
        int trips = days.stream().mapToInt(WorkDay::trips).sum();

        List<FuelEntry> allFuel = d.fuels.where(f -> f.vehicleId() == v.id());
        List<FuelEntry> fuelIn = allFuel.stream().filter(f -> in(f.date(), from, to)).toList();
        boolean estimated = fuelIn.isEmpty() && km > 0;
        double fuel = estimated ? km * fuelCostPerKm(v, allFuel)
                : fuelIn.stream().mapToDouble(FuelEntry::total).sum();

        double maint = 0, variable = 0, fixed = 0;
        for (Expense e : d.expenses.where(x -> x.vehicleId() == v.id())) {
            double cost = switch (e.recurrence()) {
                case UNICA -> in(e.date(), from, to) ? e.amount() : 0;
                case MENSAL -> e.amount() * overlap(e.date(), LocalDate.MAX, from, to) / MONTH_DAYS;
                case ANUAL -> e.amount() * overlap(e.date(), LocalDate.MAX, from, to) / 365.0;
            };
            if (e.type().maintenance) maint += cost;
            else if (e.recurrence() != Recurrence.UNICA) fixed += cost;
            else variable += cost;
        }

        double financing = 0;
        for (Financing f : d.financings.where(x -> x.vehicleId() == v.id()))
            financing += f.installment() * overlap(f.firstDue(), f.lastDue().plusMonths(1).minusDays(1), from, to) / MONTH_DAYS;

        double annualDepr = d.settings.dbl("depreciacaoAnual", 0.10);
        double depr = v.currentValue() * annualDepr * (ChronoUnit.DAYS.between(from, to) + 1) / 365.0;

        return new Report(from, to, days.size(), hours, km, trips, gross, fuel, estimated, maint, variable, fixed,
                financing, depr);
    }

    // ------------------------------------------------------------------ Combustível
    public static double lastPrice(List<FuelEntry> entries, Fuel f) {
        return entries.stream().filter(e -> e.fuel() == f).max(Comparator.comparing(FuelEntry::date))
                .map(FuelEntry::unitPrice).orElse(0.0);
    }

    /**
     * Consumo real (km/L ou km/kWh) pelo método do tanque cheio: km rodados entre dois abastecimentos completos
     * dividido pelo volume abastecido desde o anterior. O trecho é atribuído ao combustível do abastecimento que o fecha.
     */
    public static Map<Fuel, Double> realConsumption(List<FuelEntry> entries) {
        List<FuelEntry> sorted = entries.stream().filter(e -> e.odometer() > 0)
                .sorted(Comparator.comparingDouble(FuelEntry::odometer)).toList();
        Map<Fuel, double[]> acc = new EnumMap<>(Fuel.class);
        double lastFullOdo = -1, pending = 0;
        for (FuelEntry e : sorted) {
            pending += e.quantity();
            if (!e.fullTank()) continue;
            if (lastFullOdo >= 0 && e.odometer() > lastFullOdo) {
                double[] a = acc.computeIfAbsent(e.fuel(), k -> new double[2]);
                a[0] += e.odometer() - lastFullOdo;
                a[1] += pending;
            }
            lastFullOdo = e.odometer();
            pending = 0;
        }
        Map<Fuel, Double> out = new EnumMap<>(Fuel.class);
        acc.forEach((k, a) -> { if (a[1] > 0) out.put(k, a[0] / a[1]); });
        return out;
    }

    public static double consumption(Vehicle v, Fuel f, Map<Fuel, Double> real) {
        return real.getOrDefault(f, v.consumption(f));
    }

    public static Fuel defaultFuel(Vehicle v, List<FuelEntry> entries) {
        return entries.stream().max(Comparator.comparing(FuelEntry::date)).map(FuelEntry::fuel)
                .orElse(v.fuelType() == FuelType.ELETRICO ? Fuel.ELETRICO : Fuel.GASOLINA);
    }

    /** Custo de energia por km rodado com o combustível e preço informados. */
    public static double costPerKm(Vehicle v, Fuel f, double price, Map<Fuel, Double> real) {
        double c = consumption(v, f, real);
        return c > 0 ? price / c : 0;
    }

    public static double fuelCostPerKm(Vehicle v, List<FuelEntry> entries) {
        Fuel f = defaultFuel(v, entries);
        return costPerKm(v, f, lastPrice(entries, f), realConsumption(entries));
    }

    public record EthanolDecision(double kmlGas, double kmlEth, double costKmGas, double costKmEth,
                                  double breakEvenRatio, boolean ethanolWins, double savingPer100Km) {}

    /** Etanol compensa quando preço_etanol / preço_gasolina < km/L_etanol / km/L_gasolina. */
    public static EthanolDecision ethanolVsGasoline(Vehicle v, double pGas, double pEth, Map<Fuel, Double> real) {
        double kg = consumption(v, Fuel.GASOLINA, real), ke = consumption(v, Fuel.ETANOL, real);
        if (ke <= 0 && kg > 0) ke = kg * 0.70; // regra prática quando não há dado do etanol
        double cg = kg > 0 ? pGas / kg : 0, ce = ke > 0 ? pEth / ke : 0;
        boolean eth = ce > 0 && cg > 0 && ce < cg;
        return new EthanolDecision(kg, ke, cg, ce, kg > 0 ? ke / kg : 0, eth, Math.abs(cg - ce) * 100);
    }

    // ------------------------------------------------------------------ Manutenção preventiva
    public record Reminder(ExpenseType type, String level, String text) {}

    public static List<Reminder> maintenance(Data d, Vehicle v) {
        List<Reminder> out = new ArrayList<>();
        for (ExpenseType t : ExpenseType.values()) {
            if (t.intervalKm <= 0) continue;
            Optional<Expense> last = d.expenses.where(e -> e.vehicleId() == v.id() && e.type() == t && e.odometer() > 0)
                    .stream().max(Comparator.comparingDouble(Expense::odometer));
            if (last.isEmpty()) {
                out.add(new Reminder(t, "INFO", t + ": sem registro com odômetro (intervalo sugerido: "
                        + t.intervalKm + " km). Registre em Custos para ativar o lembrete."));
                continue;
            }
            double left = last.get().odometer() + t.intervalKm - v.odometer();
            if (left <= 0) out.add(new Reminder(t, "ATRASADO", t + ": vencido há " + Math.round(-left) + " km."));
            else if (left <= t.intervalKm * 0.10) out.add(new Reminder(t, "PROXIMO", t + ": faltam ~" + Math.round(left) + " km."));
            else out.add(new Reminder(t, "OK", t + ": em dia (faltam ~" + Math.round(left) + " km)."));
        }
        return out;
    }
}
