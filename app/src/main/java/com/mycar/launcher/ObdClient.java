package com.mycar.launcher;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Связь с адаптером ELM327 по Bluetooth. Работает в фоновом потоке, сам переподключается.
 * Читает: скорость (010D), температуру охлаждающей жидкости (0105),
 * уровень топлива (012F) и напряжение (ATRV).
 */
final class ObdClient {

    static final int NO_DEVICE = 0;
    static final int BT_OFF = 1;
    static final int CONNECTING = 2;
    static final int CONNECTED = 3;
    static final int RETRY = 4;

    /** Значение «нет данных». */
    static final int UNKNOWN = -999;

    private static final UUID SPP = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private static final Pattern VOLT = Pattern.compile("(?<![\\w.])(\\d{1,2}(?:\\.\\d)?)\\s*V\\b", Pattern.CASE_INSENSITIVE);
    private static final ObdClient INSTANCE = new ObdClient();

    static ObdClient get() {
        return INSTANCE;
    }

    volatile int state = NO_DEVICE;
    volatile int speed = UNKNOWN;
    volatile int coolant = UNKNOWN;
    volatile int fuel = UNKNOWN;
    volatile float voltage = -1f;

    // Задачи диагностики (их ставит экран, выполняет рабочий поток между опросами)
    static final int JOB_NONE = 0;
    static final int JOB_READ = 1;
    static final int JOB_CLEAR = 2;
    volatile int job = JOB_NONE;
    volatile boolean busy = false;
    volatile int dtcCount = -1;                 // -1: ещё неизвестно
    volatile String[] confirmed = new String[0];
    volatile String[] pending = new String[0];
    volatile int clearResult = 0;               // 0 — не запрашивали, 1 — стёрто, 2 — не удалось

    private volatile int generation = 0;
    private String address;
    private Thread worker;
    private BluetoothSocket current;
    private int timeouts;

    private ObdClient() {}

    /** Запустить (или перезапустить, если адрес сменился) подключение к адаптеру. */
    synchronized void start(final String mac) {
        if (mac == null) return;
        if (mac.equals(address) && worker != null && worker.isAlive()) return;

        generation++;
        close(current);
        current = null;
        resetValues();

        address = mac;
        state = CONNECTING;
        final int gen = generation;
        worker = new Thread(new Runnable() {
            @Override
            public void run() {
                loop(gen, mac);
            }
        }, "obd-client");
        worker.setDaemon(true);
        worker.start();
    }

    // ---------- основной цикл ----------

    private void loop(int gen, String mac) {
        while (gen == generation) {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null || !adapter.isEnabled()) {
                setState(gen, BT_OFF);
                if (!pause(4000)) return;
                continue;
            }

            BluetoothSocket socket = null;
            try {
                setState(gen, CONNECTING);
                adapter.cancelDiscovery();
                BluetoothDevice device = adapter.getRemoteDevice(mac);
                socket = open(device);
                synchronized (this) {
                    if (gen != generation) {
                        close(socket);
                        return;
                    }
                    current = socket;
                }
                InputStream in = socket.getInputStream();
                OutputStream out = socket.getOutputStream();
                timeouts = 0;
                initElm(in, out);
                setState(gen, CONNECTED);
                job = JOB_READ;
                poll(in, out, gen);
            } catch (Exception ignored) {
                // связь потеряна или адаптер недоступен — попробуем снова
            } finally {
                close(socket);
                if (gen == generation) resetValues();
            }

            if (gen != generation) return;
            setState(gen, RETRY);
            if (!pause(4000)) return;
        }
    }

    private BluetoothSocket open(BluetoothDevice device) throws IOException {
        BluetoothSocket s = device.createRfcommSocketToServiceRecord(SPP);
        try {
            s.connect();
            return s;
        } catch (IOException first) {
            close(s);
            try {
                // запасной способ для некоторых дешёвых адаптеров
                BluetoothSocket fallback = (BluetoothSocket) device.getClass()
                        .getMethod("createRfcommSocket", int.class)
                        .invoke(device, 1);
                fallback.connect();
                return fallback;
            } catch (Exception second) {
                throw new IOException("connect failed", first);
            }
        }
    }

    private void initElm(InputStream in, OutputStream out) throws IOException {
        String first = command(in, out, "ATZ", 3500);
        if (first == null) throw new IOException("no answer from adapter");
        command(in, out, "ATE0", 1500);
        command(in, out, "ATL0", 1500);
        command(in, out, "ATS0", 1500);
        command(in, out, "ATH0", 1500);
        command(in, out, "ATSP0", 1500);
        command(in, out, "0100", 9000); // поиск протокола; без зажигания может не ответить — это нормально
    }

    private void poll(InputStream in, OutputStream out, int gen) throws IOException {
        int cycle = 0;
        int[] misses = new int[3];

        while (gen == generation) {
            cycle++;

            int requested = job;
            if (requested != JOB_NONE) {
                job = JOB_NONE;
                runJob(in, out, requested);
            }

            if (misses[0] < 3 || cycle % 20 == 0) {
                int[] b = pid(in, out, "010D");
                if (b != null && b.length >= 1) {
                    speed = b[0];
                    misses[0] = 0;
                } else if (++misses[0] >= 3) {
                    speed = UNKNOWN;
                }
            }

            if (misses[1] < 3 || cycle % 20 == 0) {
                int[] b = pid(in, out, "0105");
                if (b != null && b.length >= 1) {
                    coolant = b[0] - 40;
                    misses[1] = 0;
                } else if (++misses[1] >= 3) {
                    coolant = UNKNOWN;
                }
            }

            if (misses[2] < 3 || cycle % 20 == 0) {
                int[] b = pid(in, out, "012F");
                if (b != null && b.length >= 1) {
                    fuel = Math.round(b[0] * 100f / 255f);
                    misses[2] = 0;
                } else if (++misses[2] >= 3) {
                    fuel = UNKNOWN;
                }
            }

            String v = command(in, out, "ATRV", 1500);
            if (v == null) {
                noteTimeout();
            } else {
                timeouts = 0;
                Matcher m = VOLT.matcher(v);
                if (m.find()) {
                    try {
                        voltage = Float.parseFloat(m.group(1));
                    } catch (NumberFormatException ignored) {
                        // пропускаем
                    }
                }
            }

            if (!pause(150)) return;
        }
    }

    // ---------- диагностика ----------

    void requestRead() {
        job = JOB_READ;
    }

    void requestClear() {
        clearResult = 0;
        job = JOB_CLEAR;
    }

    private void runJob(InputStream in, OutputStream out, int what) throws IOException {
        busy = true;
        try {
            if (what == JOB_CLEAR) {
                String r = command(in, out, "04", 6000);
                clearResult = DtcParser.hasPositive(r, "44") ? 1 : 2;
                pause(600);
            }
            readDtcs(in, out);
        } finally {
            busy = false;
        }
    }

    private void readDtcs(InputStream in, OutputStream out) throws IOException {
        boolean can = DtcParser.isCan(command(in, out, "ATDPN", 1500));
        String[] c = DtcParser.parse(command(in, out, "03", 6000), 0x43, can);
        String[] p = DtcParser.parse(command(in, out, "07", 6000), 0x47, can);
        confirmed = c == null ? new String[0] : c;
        pending = p == null ? new String[0] : p;
        dtcCount = c == null ? -1 : c.length;
    }

    // ---------- низкоуровневое ----------

    /** Запрос показателя; возвращает байты данных или null. */
    private int[] pid(InputStream in, OutputStream out, String pid) throws IOException {
        String response = command(in, out, pid, 1500);
        if (response == null) {
            noteTimeout();
            return null;
        }
        timeouts = 0;
        return parseBytes(response, "41" + pid.substring(2));
    }

    private void noteTimeout() throws IOException {
        if (++timeouts >= 4) throw new IOException("adapter stopped answering");
    }

    /** Отправляет команду и ждёт символ приглашения «>». При таймауте возвращает null. */
    private String command(InputStream in, OutputStream out, String cmd, int timeoutMs) throws IOException {
        while (in.available() > 0) {
            if (in.read() < 0) throw new IOException("closed");
        }
        out.write((cmd + "\r").getBytes("US-ASCII"));
        out.flush();

        StringBuilder sb = new StringBuilder();
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (in.available() > 0) {
                int c = in.read();
                if (c < 0) throw new IOException("closed");
                if (c == '>') return sb.toString();
                sb.append((char) c);
            } else {
                try {
                    Thread.sleep(15);
                } catch (InterruptedException e) {
                    throw new IOException("interrupted");
                }
            }
        }
        return null;
    }

    private static int[] parseBytes(String response, String prefix) {
        for (String line : response.split("[\\r\\n]+")) {
            String t = line.replace(" ", "").trim().toUpperCase(Locale.ROOT);
            if (t.startsWith(prefix) && t.matches("[0-9A-F]+") && t.length() >= prefix.length() + 2) {
                String data = t.substring(prefix.length());
                int[] result = new int[data.length() / 2];
                for (int i = 0; i < result.length; i++) {
                    result[i] = Integer.parseInt(data.substring(2 * i, 2 * i + 2), 16);
                }
                return result;
            }
        }
        return null;
    }

    private void setState(int gen, int newState) {
        if (gen == generation) state = newState;
    }

    private void resetValues() {
        speed = UNKNOWN;
        coolant = UNKNOWN;
        fuel = UNKNOWN;
        voltage = -1f;
        dtcCount = -1;
        confirmed = new String[0];
        pending = new String[0];
        busy = false;
        job = JOB_NONE;
    }

    private static void close(BluetoothSocket s) {
        if (s == null) return;
        try {
            s.close();
        } catch (IOException ignored) {
            // уже закрыт
        }
    }

    private static boolean pause(long ms) {
        try {
            Thread.sleep(ms);
            return true;
        } catch (InterruptedException e) {
            return false;
        }
    }
}
