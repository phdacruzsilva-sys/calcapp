package br.com.driverfinance;

import java.nio.charset.StandardCharsets;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Scanner;
import java.util.function.Function;

/** Entrada e saída de console. Aceita vírgula ou ponto decimal e datas dd/MM/aaaa. */
public final class Io {
    private Io() {}

    /** Lançada quando a entrada termina (Ctrl+D / pipe vazio). */
    public static final class Quit extends RuntimeException {}

    public static final Locale BR = Locale.forLanguageTag("pt-BR");
    public static final DateTimeFormatter DF = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final Scanner IN = new Scanner(System.in, StandardCharsets.UTF_8);

    public static String ask(String prompt) {
        System.out.print(prompt + " ");
        System.out.flush();
        if (!IN.hasNextLine()) throw new Quit();
        return IN.nextLine().trim();
    }

    public static String text(String prompt, String def) {
        String s = ask(def == null || def.isBlank() ? prompt : prompt + " [" + def + "]");
        return s.isBlank() && def != null ? def : s;
    }

    public static String required(String prompt) {
        while (true) { String s = ask(prompt); if (!s.isBlank()) return s; }
    }

    public static double num(String prompt, Double def) {
        while (true) {
            String s = ask(def == null ? prompt : prompt + " [" + fmt(def) + "]");
            if (s.isBlank() && def != null) return def;
            try { return Double.parseDouble(s.replace("R$", "").replace(" ", "").replace(',', '.')); }
            catch (NumberFormatException e) { System.out.println("  Valor inválido. Use números, ex.: 12,5"); }
        }
    }

    public static int integer(String prompt, Integer def) {
        while (true) {
            String s = ask(def == null ? prompt : prompt + " [" + def + "]");
            if (s.isBlank() && def != null) return def;
            try { return Integer.parseInt(s); } catch (NumberFormatException e) { System.out.println("  Informe um número inteiro."); }
        }
    }

    public static LocalDate date(String prompt, LocalDate def) {
        while (true) {
            String s = ask(prompt + " [" + DF.format(def) + "]");
            if (s.isBlank()) return def;
            try { return LocalDate.parse(s, DF); } catch (DateTimeParseException e) {
                try { return LocalDate.parse(s); } catch (DateTimeParseException e2) { System.out.println("  Use o formato dd/mm/aaaa."); }
            }
        }
    }

    public static boolean yes(String prompt, boolean def) {
        String s = ask(prompt + (def ? " (S/n)" : " (s/N)")).toLowerCase(Locale.ROOT);
        return s.isBlank() ? def : s.startsWith("s") || s.startsWith("y");
    }

    /** Mostra lista numerada; devolve a escolha ou null se o usuário digitar 0. */
    public static <T> T pick(String title, List<T> options, Function<T, String> label) {
        if (options.isEmpty()) return null;
        System.out.println("\n" + title);
        for (int i = 0; i < options.size(); i++) System.out.printf("  %d) %s%n", i + 1, label.apply(options.get(i)));
        System.out.println("  0) Cancelar");
        while (true) {
            int n = integer("Escolha:", null);
            if (n == 0) return null;
            if (n >= 1 && n <= options.size()) return options.get(n - 1);
            System.out.println("  Opção inexistente.");
        }
    }

    public static String money(double v) { return NumberFormat.getCurrencyInstance(BR).format(v).replace('\u00a0', ' '); }
    public static String fmt(double v) { return String.format(BR, "%.2f", v); }
    public static String pct(double v) { return String.format(BR, "%.1f%%", v * 100); }

    public static void title(String t) { System.out.println("\n=== " + t + " ==="); }
}
