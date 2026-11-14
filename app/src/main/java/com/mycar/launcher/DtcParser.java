package com.mycar.launcher;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Разбор ответов ELM327 на запросы кодов неисправностей (режимы 03 и 07). Без зависимостей от Android. */
final class DtcParser {

    private static final Pattern LINE_IDX = Pattern.compile("^([0-9A-F]):\\s*([0-9A-F ]+)$");
    private static final Pattern HEX3 = Pattern.compile("^[0-9A-F]{3}$");
    private static final Pattern HEX_BYTES = Pattern.compile("^([0-9A-F]{2})+$");
    private static final char[] LETTERS = {'P', 'C', 'B', 'U'};

    private DtcParser() {}

    /** Ответ ATDPN: протоколы 6–9 — это CAN (в ответе на режим 03 есть байт количества кодов). */
    static boolean isCan(String response) {
        if (response == null) return true;
        String t = response.replaceAll("[^0-9A-Fa-f]", "").toUpperCase(Locale.ROOT);
        if (t.length() > 1 && t.charAt(0) == 'A') t = t.substring(1);
        if (t.isEmpty()) return true;
        char c = t.charAt(0);
        return c >= '6' && c <= '9';
    }

    /**
     * Возвращает список кодов, пустой массив — «ошибок нет», null — блок не ответил.
     * @param prefix 0x43 для режима 03, 0x47 для режима 07
     */
    static String[] parse(String response, int prefix, boolean can) {
        if (response == null) return null;
        boolean found = false;
        List<String> codes = new ArrayList<>();
        for (int[] m : collectMessages(response)) {
            if (m.length == 0 || m[0] != prefix) continue;
            found = true;
            int start = can ? 2 : 1;
            for (int i = start; i + 1 < m.length; i += 2) {
                int b1 = m[i];
                int b2 = m[i + 1];
                if (b1 == 0 && b2 == 0) continue;
                String code = format(b1, b2);
                if (!codes.contains(code)) codes.add(code);
            }
        }
        if (!found) return null;
        return codes.toArray(new String[0]);
    }

    /** Положительный ответ на команду очистки: «44». */
    static boolean hasPositive(String response, String prefixHex) {
        if (response == null) return false;
        for (String line : response.split("[\\r\\n]+")) {
            String t = line.replace(" ", "").trim().toUpperCase(Locale.ROOT);
            if (HEX_BYTES.matcher(t).matches() && t.startsWith(prefixHex)) return true;
        }
        return false;
    }

    static String format(int b1, int b2) {
        return String.valueOf(LETTERS[(b1 >> 6) & 3])
                + ((b1 >> 4) & 3)
                + Integer.toHexString(b1 & 15).toUpperCase(Locale.ROOT)
                + String.format(Locale.ROOT, "%02X", b2);
    }

    /** Собирает сообщения из строк ответа, включая многокадровые («00A», «0: …», «1: …»). */
    private static List<int[]> collectMessages(String response) {
        List<int[]> result = new ArrayList<>();
        List<Integer> cur = null;
        int expected = -1;

        for (String raw : response.split("[\\r\\n]+")) {
            String line = raw.trim().toUpperCase(Locale.ROOT);
            if (line.isEmpty()) continue;

            Matcher lm = LINE_IDX.matcher(line);
            if (lm.matches()) {
                int idx = Integer.parseInt(lm.group(1), 16);
                if (idx == 0 && cur != null && !cur.isEmpty()) {
                    flush(result, cur, expected);
                    cur = null;
                    expected = -1;
                }
                if (cur == null) cur = new ArrayList<>();
                addBytes(cur, lm.group(2).replace(" ", ""));
                continue;
            }

            String compact = line.replace(" ", "");
            if (HEX3.matcher(compact).matches()) {
                flush(result, cur, expected);
                cur = new ArrayList<>();
                expected = Integer.parseInt(compact, 16);
                continue;
            }
            if (HEX_BYTES.matcher(compact).matches()) {
                flush(result, cur, expected);
                cur = null;
                expected = -1;
                List<Integer> single = new ArrayList<>();
                addBytes(single, compact);
                flush(result, single, -1);
            }
            // остальное («NO DATA», «SEARCHING…») пропускаем
        }
        flush(result, cur, expected);
        return result;
    }

    private static void addBytes(List<Integer> list, String hex) {
        for (int i = 0; i + 1 < hex.length(); i += 2) {
            list.add(Integer.parseInt(hex.substring(i, i + 2), 16));
        }
    }

    private static void flush(List<int[]> result, List<Integer> cur, int expected) {
        if (cur == null || cur.isEmpty()) return;
        int n = expected > 0 ? Math.min(expected, cur.size()) : cur.size();
        int[] bytes = new int[n];
        for (int i = 0; i < n; i++) bytes[i] = cur.get(i);
        result.add(bytes);
    }
}
