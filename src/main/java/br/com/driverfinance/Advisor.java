package br.com.driverfinance;

import br.com.driverfinance.Analytics.Report;
import br.com.driverfinance.Models.*;
import br.com.driverfinance.Store.Data;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.*;

import static br.com.driverfinance.Io.fmt;
import static br.com.driverfinance.Io.money;
import static br.com.driverfinance.Io.pct;

/**
 * Gera estratégias com base nos últimos 30 dias de dados. Regras simples e explicáveis (sem "caixa-preta").
 * Marcadores: [OK] tudo certo · [!] atenção · [>] dica/ação.
 */
public final class Advisor {
    private Advisor() {}

    /** Quanto do faturamento pode ir para combustível antes de acender o alerta. */
    static final double FUEL_SHARE_WARN = 0.30, FUEL_SHARE_BAD = 0.40;
    /** Parcela do financiamento acima disso (% do faturamento mensal) é considerada pesada. */
    static final double FINANCING_SHARE_WARN = 0.25;

    public static List<String> advise(Data d, Vehicle v, LocalDate today) {
        List<String> out = new ArrayList<>();
        Report r = Analytics.report(d, v, today.minusDays(29), today);
        List<FuelEntry> fuels = d.fuels.where(f -> f.vehicleId() == v.id());
        double targetHour = d.settings.dbl("metaHora", 25);
        double goal = d.settings.dbl("metaMensal", 0);

        if (r.workDays() == 0) {
            out.add("[>] Sem dias de trabalho nos últimos 30 dias. Registre seus ganhos (menu 5) para receber estratégias personalizadas.");
        } else {
            performance(out, r, targetHour);
            fuelShare(out, r);
            acceptanceFloor(out, r, targetHour);
            weekdays(out, d, v, r, today);
            goalAndBreakEven(out, d, v, r, goal, today);
        }

        ethanol(out, v, fuels);
        financing(out, d, v, r, today);
        categories(out, d, v);
        for (Analytics.Reminder m : Analytics.maintenance(d, v))
            if (m.level().equals("ATRASADO") || m.level().equals("PROXIMO")) out.add("[!] Manutenção - " + m.text());
        return out;
    }

    private static void performance(List<String> out, Report r, double targetHour) {
        String base = "Últimos 30 dias: líquido " + money(r.net()) + " (" + money(r.netPerHour()) + "/h, margem "
                + pct(r.margin()) + ").";
        if (r.net() <= 0) out.add("[!] " + base + " Você está pagando para trabalhar: revise custos fixos, financiamento e combustível.");
        else if (r.netPerHour() < targetHour)
            out.add("[!] " + base + " Abaixo da sua meta de " + money(targetHour) + "/h.");
        else out.add("[OK] " + base + " Acima da meta de " + money(targetHour) + "/h.");
        out.add("[>] Contando o desgaste do carro (depreciação estimada de " + money(r.depreciation()) + "), o lucro real é "
                + money(r.netEconomic()) + ".");
    }

    private static void fuelShare(List<String> out, Report r) {
        if (r.gross() <= 0) return;
        String src = r.fuelEstimated() ? " (estimado: registre abastecimentos para valores reais)" : "";
        double s = r.fuelShare();
        if (s >= FUEL_SHARE_BAD)
            out.add("[!] Combustível consome " + pct(s) + " do faturamento" + src + ". Não compensa nesse nível: evite deslocamentos vazios longos, calibre os pneus e use o app de comparação de preços.");
        else if (s >= FUEL_SHARE_WARN)
            out.add("[!] Combustível consome " + pct(s) + " do faturamento" + src + ". Está no limite; compare etanol x gasolina (menu 7).");
        else out.add("[OK] Combustível consome " + pct(s) + " do faturamento" + src + ".");
    }

    /** Valor mínimo por km para a corrida valer a pena: custo por km + a meta por hora diluída nos km/h médios. */
    private static void acceptanceFloor(List<String> out, Report r, double targetHour) {
        if (r.km() <= 0 || r.hours() <= 0) return;
        double floor = r.costPerKm() + targetHour / (r.km() / r.hours());
        String cmp = r.grossPerKm() >= floor ? "Você está acima disso (" + money(r.grossPerKm()) + "/km)."
                : "Hoje você fatura só " + money(r.grossPerKm()) + "/km: seja mais seletivo com corridas curtas e de baixo valor.";
        out.add("[>] Piso por km: só vale aceitar corridas a partir de ~" + money(floor) + "/km (custo " + money(r.costPerKm())
                + "/km + sua meta por hora). " + cmp);
    }

    private static void weekdays(List<String> out, Data d, Vehicle v, Report r, LocalDate today) {
        double vcpk = r.km() > 0 ? r.variableCost() / r.km() : 0;
        Map<DayOfWeek, double[]> acc = new EnumMap<>(DayOfWeek.class); // [lucro variável, horas]
        for (WorkDay w : d.days.where(x -> x.vehicleId() == v.id() && !x.date().isBefore(today.minusDays(89)))) {
            double[] a = acc.computeIfAbsent(w.date().getDayOfWeek(), k -> new double[2]);
            a[0] += w.gross() - w.km() * vcpk;
            a[1] += w.hours();
        }
        List<Map.Entry<DayOfWeek, Double>> rank = acc.entrySet().stream().filter(e -> e.getValue()[1] > 0)
                .map(e -> Map.entry(e.getKey(), e.getValue()[0] / e.getValue()[1]))
                .sorted(Map.Entry.<DayOfWeek, Double>comparingByValue().reversed()).toList();
        if (rank.size() < 3) return;
        var best = rank.get(0);
        var worst = rank.get(rank.size() - 1);
        out.add("[>] Melhor dia (últimos 90 dias): " + best.getKey().getDisplayName(TextStyle.FULL, Io.BR) + " a "
                + money(best.getValue()) + "/h. Pior: " + worst.getKey().getDisplayName(TextStyle.FULL, Io.BR) + " a "
                + money(worst.getValue()) + "/h. Concentre horas nos melhores dias e descanse nos piores.");
    }

    private static void goalAndBreakEven(List<String> out, Data d, Vehicle v, Report r, double goal, LocalDate today) {
        double contribDay = (r.gross() - r.variableCost()) / r.workDays();
        double fixedMonth = r.fixedMonthly();
        if (contribDay <= 0) {
            out.add("[!] Cada dia trabalhado não cobre nem o custo variável (combustível/manutenção). Antes de rodar mais, corrija isso.");
            return;
        }
        out.add("[>] Ponto de equilíbrio: ~" + fmt(fixedMonth / contribDay) + " dias/mês só para pagar custos fixos e financiamento ("
                + money(fixedMonth) + "/mês).");
        if (goal > 0) {
            double daysNeeded = (goal + fixedMonth) / contribDay;
            double hoursDay = r.hours() / r.workDays();
            Report month = Analytics.report(d, v, today.withDayOfMonth(1), today);
            out.add("[>] Meta de " + money(goal) + " líquidos/mês: no seu ritmo são ~" + fmt(daysNeeded) + " dias de "
                    + fmt(hoursDay) + "h" + (daysNeeded > 26 ? " (inviável: aumente o ganho por hora ou reduza custos)." : ".")
                    + " Neste mês já acumulou " + money(month.net()) + " (" + pct(Math.max(0, month.net()) / goal) + ").");
        }
    }

    private static void ethanol(List<String> out, Vehicle v, List<FuelEntry> fuels) {
        if (v.fuelType() != FuelType.FLEX) return;
        double pg = Analytics.lastPrice(fuels, Fuel.GASOLINA), pe = Analytics.lastPrice(fuels, Fuel.ETANOL);
        if (pg <= 0 || pe <= 0) return;
        var dec = Analytics.ethanolVsGasoline(v, pg, pe, Analytics.realConsumption(fuels));
        out.add("[>] " + (dec.ethanolWins() ? "Etanol" : "Gasolina") + " compensa mais nos últimos preços registrados (economia de ~"
                + money(dec.savingPer100Km()) + " a cada 100 km).");
    }

    private static void financing(List<String> out, Data d, Vehicle v, Report r, LocalDate today) {
        double monthlyGross = r.gross() / r.periodDays() * Analytics.MONTH_DAYS;
        for (Financing f : d.financings.where(x -> x.vehicleId() == v.id() && x.remaining(today) > 0)) {
            String info = "Financiamento: faltam " + f.remaining(today) + " parcelas (" + money(f.remainingBalance(today))
                    + "), taxa implícita ~" + pct(f.impliedMonthlyRate()) + " a.m.";
            if (monthlyGross > 0 && f.installment() / monthlyGross > FINANCING_SHARE_WARN)
                out.add("[!] " + info + " A parcela pesa " + pct(f.installment() / monthlyGross)
                        + " do faturamento mensal: acima de " + pct(FINANCING_SHARE_WARN) + ", o risco de aperto é alto.");
            else out.add("[OK] " + info);
            if (f.impliedMonthlyRate() > 0.019)
                out.add("[>] Juros acima de ~1,9% a.m. costumam justificar simular portabilidade ou amortização com sobras de caixa.");
        }
    }

    private static void categories(List<String> out, Data d, Vehicle v) {
        String city = d.settings.str("cidade", "*");
        long trips = d.settings.lng("viagens", 0);
        double rating = d.settings.dbl("nota", 0);
        for (Category c : v.categories()) {
            if (c == Category.UBERX) continue;
            long missing = Math.max(0, Catalog.minTrips(c) + 1 - trips);
            double minR = Catalog.minRating(c, city);
            String ratingTxt = rating <= 0 ? "informe sua nota em Configurações" : rating >= minR ? "nota OK" : "nota " + fmt(rating) + " abaixo de " + fmt(minR);
            if (missing > 0 || rating < minR)
                out.add("[>] Seu carro é elegível ao " + c.label + ", mas faltam requisitos de motorista: " + missing
                        + " viagens (mais de " + Catalog.minTrips(c) + ") e " + ratingTxt + ". A categoria costuma pagar mais que o UberX.");
            else out.add("[OK] Você atende aos requisitos de motorista do " + c.label + ": ative a categoria no app.");
        }
    }
}
