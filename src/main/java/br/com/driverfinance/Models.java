package br.com.driverfinance;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/** Entidades do domínio. Cada entidade sabe se converter de/para uma linha de texto (ver {@link Store}). */
public final class Models {
    private Models() {}

    public enum Category {
        UBERX("UberX"), COMFORT("Uber Comfort"), BLACK("Uber Black");
        public final String label;
        Category(String label) { this.label = label; }
    }

    public enum FuelType { FLEX, GASOLINA, HIBRIDO, ELETRICO }

    /** O que foi efetivamente abastecido. */
    public enum Fuel {
        GASOLINA("L"), ETANOL("L"), ELETRICO("kWh");
        public final String unit;
        Fuel(String unit) { this.unit = unit; }
    }

    public enum Recurrence { UNICA, MENSAL, ANUAL }

    /** Tipos de custo. {@code intervalKm} > 0 gera lembrete de manutenção preventiva. */
    public enum ExpenseType {
        MANUTENCAO(true, 0), OLEO(true, 10_000), PNEUS(true, 40_000), FREIOS(true, 30_000), REVISAO(true, 10_000),
        SEGURO(false, 0), IPVA(false, 0), LICENCIAMENTO(false, 0), LAVAGEM(false, 0), MULTA(false, 0),
        PEDAGIO(false, 0), ESTACIONAMENTO(false, 0), OUTROS(false, 0);

        public final boolean maintenance;
        public final int intervalKm;
        ExpenseType(boolean maintenance, int intervalKm) { this.maintenance = maintenance; this.intervalKm = intervalKm; }
    }

    public interface Entity {
        long id();
        String[] row();
    }

    private static String[] cols(Object... v) {
        String[] r = new String[v.length];
        for (int i = 0; i < v.length; i++) r[i] = String.valueOf(v[i]);
        return r;
    }

    // ------------------------------------------------------------------ Veículo
    public record Vehicle(long id, String make, String model, int year, String color, int doors, boolean ac,
                          FuelType fuelType, double kmlGas, double kmlEth, double kmKwh,
                          double purchaseValue, double currentValue, double odometer, String plate,
                          Set<Category> categories) implements Entity {

        public String[] row() {
            String cats = categories.stream().map(Enum::name).collect(Collectors.joining(","));
            return cols(id, make, model, year, color, doors, ac, fuelType, kmlGas, kmlEth, kmKwh,
                    purchaseValue, currentValue, odometer, plate, cats);
        }

        public static Vehicle of(String[] r) {
            Set<Category> cats = EnumSet.noneOf(Category.class);
            if (!r[15].isBlank()) for (String c : r[15].split(",")) cats.add(Category.valueOf(c));
            return new Vehicle(Long.parseLong(r[0]), r[1], r[2], Integer.parseInt(r[3]), r[4], Integer.parseInt(r[5]),
                    Boolean.parseBoolean(r[6]), FuelType.valueOf(r[7]), Double.parseDouble(r[8]), Double.parseDouble(r[9]),
                    Double.parseDouble(r[10]), Double.parseDouble(r[11]), Double.parseDouble(r[12]),
                    Double.parseDouble(r[13]), r[14], cats);
        }

        public String name() { return make + " " + model + " " + year; }

        /** Consumo declarado (km por litro, ou km por kWh). 0 = não se aplica. */
        public double consumption(Fuel f) {
            return switch (f) { case GASOLINA -> kmlGas; case ETANOL -> kmlEth; case ELETRICO -> kmKwh; };
        }

        public List<Fuel> allowedFuels() {
            return switch (fuelType) {
                case FLEX -> List.of(Fuel.GASOLINA, Fuel.ETANOL);
                case GASOLINA -> List.of(Fuel.GASOLINA);
                case HIBRIDO -> List.of(Fuel.GASOLINA, Fuel.ELETRICO);
                case ELETRICO -> List.of(Fuel.ELETRICO);
            };
        }

        public Vehicle withOdometer(double km) {
            return new Vehicle(id, make, model, year, color, doors, ac, fuelType, kmlGas, kmlEth, kmKwh,
                    purchaseValue, currentValue, km, plate, categories);
        }

        public Vehicle withValue(double value) {
            return new Vehicle(id, make, model, year, color, doors, ac, fuelType, kmlGas, kmlEth, kmKwh,
                    purchaseValue, value, odometer, plate, categories);
        }
    }

    // ------------------------------------------------------------------ Financiamento
    public record Financing(long id, long vehicleId, String lender, double financed, double downPayment,
                            double installment, int total, LocalDate firstDue) implements Entity {

        public String[] row() { return cols(id, vehicleId, lender, financed, downPayment, installment, total, firstDue); }

        public static Financing of(String[] r) {
            return new Financing(Long.parseLong(r[0]), Long.parseLong(r[1]), r[2], Double.parseDouble(r[3]),
                    Double.parseDouble(r[4]), Double.parseDouble(r[5]), Integer.parseInt(r[6]), LocalDate.parse(r[7]));
        }

        /** Parcelas com vencimento já passado (a 1ª vence em firstDue). */
        public int paid(LocalDate today) {
            if (today.isBefore(firstDue)) return 0;
            return (int) Math.min(total, ChronoUnit.MONTHS.between(firstDue, today) + 1);
        }

        public int remaining(LocalDate today) { return total - paid(today); }
        public double remainingBalance(LocalDate today) { return remaining(today) * installment; }
        public double totalCost() { return downPayment + installment * total; }
        public LocalDate lastDue() { return firstDue.plusMonths(total - 1L); }

        /** Taxa de juros mensal implícita (PV = PMT * (1 - (1+i)^-n) / i), resolvida por bisseção. */
        public double impliedMonthlyRate() {
            if (installment * total <= financed || financed <= 0) return 0;
            double lo = 1e-9, hi = 0.5;
            for (int k = 0; k < 200; k++) {
                double mid = (lo + hi) / 2;
                double pv = installment * (1 - Math.pow(1 + mid, -total)) / mid;
                if (pv > financed) lo = mid; else hi = mid;
            }
            return (lo + hi) / 2;
        }
    }

    // ------------------------------------------------------------------ Custos
    public record Expense(long id, long vehicleId, ExpenseType type, String description, double amount,
                          LocalDate date, Recurrence recurrence, double odometer) implements Entity {

        public String[] row() { return cols(id, vehicleId, type, description, amount, date, recurrence, odometer); }

        public static Expense of(String[] r) {
            return new Expense(Long.parseLong(r[0]), Long.parseLong(r[1]), ExpenseType.valueOf(r[2]), r[3],
                    Double.parseDouble(r[4]), LocalDate.parse(r[5]), Recurrence.valueOf(r[6]), Double.parseDouble(r[7]));
        }
    }

    // ------------------------------------------------------------------ Abastecimento
    public record FuelEntry(long id, long vehicleId, LocalDate date, Fuel fuel, double quantity, double unitPrice,
                            double odometer, boolean fullTank) implements Entity {

        public String[] row() { return cols(id, vehicleId, date, fuel, quantity, unitPrice, odometer, fullTank); }

        public static FuelEntry of(String[] r) {
            return new FuelEntry(Long.parseLong(r[0]), Long.parseLong(r[1]), LocalDate.parse(r[2]), Fuel.valueOf(r[3]),
                    Double.parseDouble(r[4]), Double.parseDouble(r[5]), Double.parseDouble(r[6]), Boolean.parseBoolean(r[7]));
        }

        public double total() { return quantity * unitPrice; }
    }

    // ------------------------------------------------------------------ Dia de trabalho (ganhos)
    public record WorkDay(long id, long vehicleId, LocalDate date, String platform, double hours, double km,
                          int trips, double earnings, double tips) implements Entity {

        public String[] row() { return cols(id, vehicleId, date, platform, hours, km, trips, earnings, tips); }

        public static WorkDay of(String[] r) {
            return new WorkDay(Long.parseLong(r[0]), Long.parseLong(r[1]), LocalDate.parse(r[2]), r[3],
                    Double.parseDouble(r[4]), Double.parseDouble(r[5]), Integer.parseInt(r[6]),
                    Double.parseDouble(r[7]), Double.parseDouble(r[8]));
        }

        public double gross() { return earnings + tips; }
    }
}
