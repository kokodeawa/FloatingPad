package com.example.floatingpad;

import android.view.InputDevice;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Teclado y ratón virtuales (segundo proceso `uinput`, aislado del mando).
 *
 * Reglas de diseño:
 *  - Una sola hebra (`ex`) toca la sesión y el estado: el orden de los eventos es estricto.
 *  - El dispositivo se crea ANTES de usarlo (start() al entrar en un perfil que lo necesita), nunca a mitad de un gesto.
 *  - Se destruye con retraso al salir del perfil, para no crear/destruir dispositivos continuamente (cada cambio hace
 *    que Android avise a las apps de un cambio de teclado, y algunos juegos se reinician o se descontrolan).
 *  - Se recuerdan las teclas pulsadas para no repetir pulsaciones ni dejar teclas atascadas.
 *  - Los movimientos del ratón se acumulan y se envían en bloques (~160/s como máximo) para no saturar el canal.
 */
public class KbmDevices {

    public interface Listener {
        /** Se llama desde una hebra de fondo. */
        void onState(boolean ready, String message);
    }

    private static final int EV_REL = 2, REL_X = 0, REL_Y = 1, REL_WHEEL = 8;
    private static final String KB_NAME = "Overlay Keyboard", MOUSE_NAME = "Overlay Mouse";

    private final VirtualGamepadManager owner;
    private final ScheduledExecutorService ex = Executors.newSingleThreadScheduledExecutor();
    private final Object lock = new Object();
    private final StringBuilder output = new StringBuilder();
    private final Set<Integer> down = new HashSet<Integer>();

    // solo desde `ex`
    private ShellSession sess;
    private OutputStream out;
    private boolean ready;
    private long failUntil;

    // protegido por `lock`
    private float accX, accY, remX, remY;
    private boolean flushPending;
    private ScheduledFuture<?> stopTask;

    private volatile Listener listener;

    public KbmDevices(VirtualGamepadManager owner) { this.owner = owner; }

    public void setListener(Listener l) { listener = l; }

    // ---------- API ----------

    /** Crea el teclado y el ratón (si no existen). Cancela una destrucción pendiente. */
    public void start() {
        cancelStop();
        ex.execute(new Runnable() {
            @Override public void run() {
                try { ensure(); } catch (Throwable e) { fail("crear", e); }
            }
        });
    }

    /** Destruye los dispositivos pasado un tiempo, salvo que se vuelvan a necesitar antes. */
    public void stopLater(long delayMs) {
        synchronized (lock) {
            if (stopTask != null) stopTask.cancel(false);
            stopTask = ex.schedule(new Runnable() {
                @Override public void run() { closeInternal(); }
            }, delayMs, TimeUnit.MILLISECONDS);
        }
    }

    public void stopNow() {
        cancelStop();
        ex.execute(new Runnable() {
            @Override public void run() { closeInternal(); }
        });
    }

    public void key(final int code, final boolean isDown) {
        ex.execute(new Runnable() {
            @Override public void run() {
                try {
                    if (isDown ? down.contains(code) : !down.contains(code)) return;   // ya está en ese estado
                    if (!ensure()) return;
                    if (isDown) down.add(code); else down.remove(code);
                    boolean mouse = code >= 0x110 && code <= 0x117;
                    write("{\"id\":" + (mouse ? 2 : 1) + ",\"command\":\"inject\",\"events\":[1," + code + ","
                            + (isDown ? 1 : 0) + ",0,0,0]}");
                } catch (Throwable e) { fail("tecla", e); }
            }
        });
    }

    public void move(float dx, float dy) {
        synchronized (lock) {
            accX += dx;
            accY += dy;
            if (flushPending) return;
            flushPending = true;
        }
        ex.schedule(flushTask, 6, TimeUnit.MILLISECONDS);
    }

    public void wheel(final int ticks) {
        ex.execute(new Runnable() {
            @Override public void run() {
                try {
                    if (!ensure()) return;
                    write("{\"id\":2,\"command\":\"inject\",\"events\":[" + EV_REL + "," + REL_WHEEL + "," + ticks + ",0,0,0]}");
                } catch (Throwable e) { fail("rueda", e); }
            }
        });
    }

    /** Suelta todas las teclas y botones que sigan pulsados. */
    public void releaseAllKeys() {
        ex.execute(new Runnable() {
            @Override public void run() {
                try {
                    if (ready) {
                        for (Integer code : new ArrayList<Integer>(down)) {
                            boolean mouse = code >= 0x110 && code <= 0x117;
                            write("{\"id\":" + (mouse ? 2 : 1) + ",\"command\":\"inject\",\"events\":[1," + code + ",0,0,0,0]}");
                        }
                    }
                } catch (Throwable e) { fail("soltar", e); }
                down.clear();
            }
        });
    }

    // ---------- Internos (hebra `ex`) ----------

    private final Runnable flushTask = new Runnable() {
        @Override public void run() {
            int ix, iy;
            synchronized (lock) {
                flushPending = false;
                remX += accX;
                remY += accY;
                accX = 0;
                accY = 0;
                ix = (int) remX;
                iy = (int) remY;
                remX -= ix;
                remY -= iy;
            }
            if (ix == 0 && iy == 0) return;
            try {
                if (!ensure()) return;
                write("{\"id\":2,\"command\":\"inject\",\"events\":[" + EV_REL + "," + REL_X + "," + ix + "," + EV_REL + ","
                        + REL_Y + "," + iy + ",0,0,0]}");
            } catch (Throwable e) { fail("ratón", e); }
        }
    };

    private void cancelStop() {
        synchronized (lock) {
            if (stopTask != null) { stopTask.cancel(false); stopTask = null; }
        }
    }

    private boolean ensure() {
        if (ready && sess != null && sess.alive()) return true;
        if (ready) closeInternal();                     // la sesión cayó: limpiar antes de recrear
        if (!owner.isReady()) return false;             // el mando principal aún no está conectado
        if (System.currentTimeMillis() < failUntil) return false;

        int first = owner.goodBus() == 3 ? 3 : 1;
        int[] buses = {first, first == 1 ? 3 : 1};
        String lastOut = "";
        for (int bus : buses) {
            try {
                synchronized (output) { output.setLength(0); }
                ShellSession s = owner.openAuxUinput();
                sess = s;
                out = s.out();
                startReader(s, false);
                startReader(s, true);
                write(kbRegisterJson(bus));
                write(mouseRegisterJson(bus));
                boolean bad = false;
                for (int i = 0; i < 10; i++) {
                    try { Thread.sleep(150); } catch (InterruptedException ignored) { }
                    String o = snapshot();
                    if (!s.alive() || o.contains("Error") || o.contains("Exception")) { bad = true; break; }
                    if (i >= 1 && visible()) break;
                }
                if (!bad) {
                    ready = true;
                    owner.log("Teclado y ratón virtuales creados (bus " + bus + ")");
                    notifyState(true, "Teclado y ratón virtuales listos");
                    return true;
                }
                lastOut = snapshot();
            } catch (Throwable e) {
                lastOut = e.getClass().getSimpleName() + ": " + e.getMessage();
            }
            closeInternal();
        }
        failUntil = System.currentTimeMillis() + 15000;     // no reintentar en bucle
        String msg = lastOut.trim();
        if (msg.length() > 160) msg = msg.substring(0, 160);
        owner.log("No se pudo crear el teclado/ratón virtual: " + msg);
        notifyState(false, "no se pudieron crear. " + msg);
        return false;
    }

    private void fail(String where, Throwable e) {
        owner.log("Fallo en teclado/ratón (" + where + "): " + e);
        closeInternal();
        failUntil = System.currentTimeMillis() + 3000;
    }

    private void closeInternal() {
        ready = false;
        down.clear();
        synchronized (lock) { accX = accY = remX = remY = 0; }
        ShellSession s = sess;
        sess = null;
        out = null;
        if (s != null) s.close();
    }

    private void notifyState(boolean ok, String msg) {
        Listener l = listener;
        if (l != null) {
            try { l.onState(ok, msg); } catch (Throwable ignored) { }
        }
    }

    private String snapshot() {
        synchronized (output) { return output.toString(); }
    }

    private boolean visible() {
        boolean kb = false, ms = false;
        try {
            for (int id : InputDevice.getDeviceIds()) {
                InputDevice d = InputDevice.getDevice(id);
                if (d == null || d.getName() == null) continue;
                if (d.getName().contains(KB_NAME)) kb = true;
                if (d.getName().contains(MOUSE_NAME)) ms = true;
            }
        } catch (Throwable ignored) { }
        return kb && ms;
    }

    private void startReader(final ShellSession s, final boolean err) {
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    InputStream is = err ? s.err() : s.in();
                    BufferedReader br = new BufferedReader(new InputStreamReader(is));
                    String line;
                    while ((line = br.readLine()) != null) {
                        synchronized (output) { if (output.length() < 1500) output.append(line).append('\n'); }
                    }
                } catch (Throwable ignored) { }
                if (!err) s.markDead();
            }
        });
        t.setDaemon(true);
        t.start();
    }

    private void write(String json) throws IOException {
        OutputStream o = out;
        if (o == null) throw new IOException("uinput (teclado/ratón) no iniciado");
        o.write((json + "\n").getBytes("UTF-8"));
        o.flush();
    }

    private static String busJson(int bus) {
        return bus == 3 ? "\"bus\":\"bluetooth\"," : "\"bus\":\"usb\",";
    }

    private String kbRegisterJson(int bus) {
        StringBuilder s = new StringBuilder();
        s.append("{\"id\":1,\"command\":\"register\",\"name\":\"").append(KB_NAME).append("\",\"vid\":4617,\"pid\":1,")
                .append(busJson(bus)).append("\"configuration\":[{\"type\":100,\"data\":[1]},{\"type\":101,\"data\":[");
        int[] codes = KeyDefs.keyboardCodes();
        for (int i = 0; i < codes.length; i++) { if (i > 0) s.append(','); s.append(codes[i]); }
        s.append("]}]}");
        return s.toString();
    }

    private String mouseRegisterJson(int bus) {
        return "{\"id\":2,\"command\":\"register\",\"name\":\"" + MOUSE_NAME + "\",\"vid\":4617,\"pid\":2," + busJson(bus)
                + "\"configuration\":[{\"type\":100,\"data\":[1,2]},{\"type\":101,\"data\":[272,273,274]},"
                + "{\"type\":102,\"data\":[0,1,8]}]}";
    }
}
