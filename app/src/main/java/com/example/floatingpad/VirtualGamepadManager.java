package com.example.floatingpad;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.view.InputDevice;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import rikka.shizuku.Shizuku;

/**
 * Crea el mando virtual ejecutando "/system/bin/uinput -" como shell. Usa Shizuku si está listo;
 * si no, el ADB propio (depuración inalámbrica) de la app. Los comandos van en JSON por stdin.
 */
public class VirtualGamepadManager {
    public interface Callback { void onResult(boolean ok, String message); }

    private static final String UINPUT_BIN = "/system/bin/uinput";
    private static final String PAD_NAME = "Overlay Gamepad";
    private static final int EV_SYN = 0, EV_KEY = 1, EV_ABS = 3;
    private static final int ABS_X = 0, ABS_Y = 1, ABS_Z = 2, ABS_RX = 3, ABS_RY = 4, ABS_RZ = 5,
            ABS_HAT0X = 16, ABS_HAT0Y = 17;
    private static final int BTN_A = 304, BTN_B = 305, BTN_X = 307, BTN_Y = 308, BTN_TL = 310,
            BTN_TR = 311, BTN_SELECT = 314, BTN_START = 315, BTN_MODE = 316, BTN_THUMBL = 317, BTN_THUMBR = 318;

    // {bus: 1="usb" 3="bluetooth" (obligatorio en Android 13), abs_info: 1=code + info anidado}
    private static final int[][] VARIANTS = {{1, 1}, {3, 1}};
    private static final int R_VISIBLE = 0, R_ALIVE = 1, R_ERROR = 2;

    private static final String LOGCAT_CMD =
            "logcat -d -t 800 2>&1 | grep -iE 'uinput|uhid|libuinput|app_process' | tail -40";

    private static VirtualGamepadManager instance;

    private final Context ctx;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private volatile ShellSession sess;
    private volatile OutputStream out;
    private volatile boolean created;
    private volatile boolean usingShizuku;
    private volatile int goodBus = 1;
    private volatile String lastDiagnostic = "Aún no se ha intentado conectar.";
    private final StringBuilder procOutput = new StringBuilder();
    private boolean up, down, left, right;

    private VirtualGamepadManager(Context c) { ctx = c.getApplicationContext(); }

    public static synchronized VirtualGamepadManager get(Context c) {
        if (instance == null) instance = new VirtualGamepadManager(c);
        return instance;
    }

    public String getLastDiagnostic() { return lastDiagnostic; }
    private void diag(String s) { lastDiagnostic = System.currentTimeMillis() + " | " + s; }
    public boolean hasBinder() { ShellSession s = sess; return created && s != null && s.alive(); }
    public String getBackendName() { ShellSession s = sess; return s == null ? "ninguno" : s.name(); }

    // ---------- API pública (todo se ejecuta en un único hilo, en orden) ----------

    public void connect(final Callback cb) {
        io.execute(new Runnable() { @Override public void run() { doConnect(cb); } });
    }

    public void sendKey(final int k, final boolean d) {
        io.execute(new Runnable() { @Override public void run() { try { doKey(k, d); } catch (Throwable e) { fail(e); } } });
    }

    public void sendAxis(final boolean l, final float x, final float y) {
        io.execute(new Runnable() { @Override public void run() { try { doAxis(l, x, y); } catch (Throwable e) { fail(e); } } });
    }

    public void stop() {
        io.execute(new Runnable() { @Override public void run() { closeSession(); kbm.stopNow(); diag("Mando detenido"); } });
    }

    public void reset() {
        io.execute(new Runnable() { @Override public void run() { closeSession(); kbm.stopNow(); diag("Conexión reiniciada"); } });
    }

    // ---------- Apertura de shells (Shizuku o ADB propio) ----------

    private boolean shizukuReady() {
        try {
            return Shizuku.pingBinder() && !Shizuku.isPreV11()
                    && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable e) {
            return false;
        }
    }

    private ShellSession openShizuku(String[] argv) throws Exception {
        try {
            Method m = Shizuku.class.getDeclaredMethod("newProcess", String[].class, String[].class, String.class);
            m.setAccessible(true);
            return new ShellSession.ProcessSession((Process) m.invoke(null, new Object[]{argv, null, null}));
        } catch (InvocationTargetException e) {
            Throwable c = e.getCause() != null ? e.getCause() : e;
            throw (c instanceof Exception) ? (Exception) c : new Exception(c);
        }
    }

    /** Abre un comando de shell con el método disponible. */
    private ShellSession openShell(String shellCommand, String[] argvForShizuku, boolean shizuku) throws Exception {
        if (shizuku) return openShizuku(argvForShizuku);
        return AdbConnector.get(ctx).openExec(shellCommand);
    }

    // ---------- Conexión ----------

    private void doConnect(Callback cb) {
        diag("connect() iniciado");
        if (hasBinder()) { report(cb, true, "MANDO_VIRTUAL_CREADO: ya existía"); return; }
        if (!new File(UINPUT_BIN).exists()) {
            report(cb, false, "UINPUT_BLOQUEADO: no existe " + UINPUT_BIN + " (hace falta Android 12 o superior).");
            return;
        }
        boolean shizuku = shizukuReady();
        if (!shizuku) {
            if (Build.VERSION.SDK_INT < 30) {
                report(cb, false, "SIN_METODO: la depuración inalámbrica propia necesita Android 11 o superior. Instala y activa Shizuku.");
                return;
            }
            if (!AdbConnector.get(ctx).isPaired()) {
                report(cb, false, "NECESITA_EMPAREJAR: pulsa «Emparejar (guiado)» una vez; después se conecta sola.");
                return;
            }
        }
        usingShizuku = shizuku;
        closeSession();
        StringBuilder tried = new StringBuilder();
        for (int vi = 0; vi < VARIANTS.length; vi++) {
            int res;
            try {
                res = tryVariant(vi, shizuku);
            } catch (Throwable e) {
                closeSession();
                report(cb, false, "No se pudo ejecutar uinput con " + (shizuku ? "Shizuku" : "el ADB propio") + ": "
                        + e.getClass().getSimpleName() + ": " + e.getMessage()
                        + (shizuku ? "" : "\nComprueba que «Depuración inalámbrica» esté activada y el móvil en Wi-Fi."));
                return;
            }
            String o;
            synchronized (procOutput) { o = procOutput.toString().trim(); }
            if (res == R_ERROR) {
                ShellSession s = sess;
                tried.append("\nVariante ").append(vi + 1).append(" rechazada [").append(s == null ? "?" : s.exitInfo())
                        .append("]: ").append(o.length() == 0 ? "(proceso terminó sin salida)" : o);
                closeSession();
                continue;
            }
            goodBus = VARIANTS[vi][0];
            created = true;
            report(cb, true, "MANDO_VIRTUAL_CREADO: " + PAD_NAME + " [" + (shizuku ? "Shizuku" : "ADB propio") + ", variante " + (vi + 1) + "]"
                    + (res == R_VISIBLE ? " (Android lo detecta como gamepad)" : " (proceso activo, pero Android aún no lo lista)")
                    + (o.length() == 0 ? "" : "\nSalida de uinput: " + o) + tried);
            return;
        }
        tried.append("\n--- logcat (uinput/uhid):\n").append(shellOutput(LOGCAT_CMD));
        report(cb, false, "UINPUT_BLOQUEADO: ninguna variante de registro fue aceptada." + tried);
    }

    private int tryVariant(int vi, boolean shizuku) throws Exception {
        synchronized (procOutput) { procOutput.setLength(0); }
        diag("Probando variante " + (vi + 1) + " de registro…");
        ShellSession s = openShell(UINPUT_BIN + " -", new String[]{UINPUT_BIN, "-"}, shizuku);
        sess = s;
        out = s.out();
        startReader(s, false);
        startReader(s, true);
        writeLine(registerJson(VARIANTS[vi][0], VARIANTS[vi][1]));
        for (int i = 0; i < 15; i++) {
            try { Thread.sleep(200); } catch (InterruptedException ignored) { }
            if (!s.alive()) { try { Thread.sleep(300); } catch (InterruptedException ignored) { } return R_ERROR; }
            String o;
            synchronized (procOutput) { o = procOutput.toString(); }
            if (o.contains("Error") || o.contains("Exception")) return R_ERROR;
            if (i >= 2 && padVisible()) return R_VISIBLE;
        }
        return R_ALIVE;
    }

    private boolean padVisible() {
        try {
            for (int id : InputDevice.getDeviceIds()) {
                InputDevice d = InputDevice.getDevice(id);
                if (d != null && d.getName() != null && d.getName().contains(PAD_NAME)) return true;
            }
        } catch (Throwable ignored) { }
        return false;
    }

    private void startReader(final ShellSession s, final boolean err) {
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    InputStream is = err ? s.err() : s.in();
                    BufferedReader br = new BufferedReader(new InputStreamReader(is));
                    String line;
                    while ((line = br.readLine()) != null) {
                        synchronized (procOutput) { if (procOutput.length() < 1500) procOutput.append(line).append('\n'); }
                    }
                } catch (Throwable ignored) { }
                if (!err) s.markDead();
            }
        });
        t.setDaemon(true);
        t.start();
    }

    // ---------- Diagnóstico ----------

    /** Ejecuta un comando de shell y devuelve su salida (máx. ~12 s). */
    private String shellOutput(String cmd) {
        final StringBuilder r = new StringBuilder();
        ShellSession s = null;
        try {
            s = openShell(cmd, new String[]{"sh", "-c", cmd}, usingShizuku || shizukuReady());
            final ShellSession fs = s;
            Thread t1 = new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        BufferedReader b = new BufferedReader(new InputStreamReader(fs.in()));
                        String l;
                        while ((l = b.readLine()) != null) synchronized (r) { r.append(l).append('\n'); }
                    } catch (Throwable ignored) { }
                }
            });
            Thread t2 = new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        BufferedReader b = new BufferedReader(new InputStreamReader(fs.err()));
                        String l;
                        while ((l = b.readLine()) != null) synchronized (r) { r.append("[err] ").append(l).append('\n'); }
                    } catch (Throwable ignored) { }
                }
            });
            t1.setDaemon(true); t2.setDaemon(true);
            t1.start(); t2.start();
            t1.join(12000);
            t2.join(500);
        } catch (Throwable e) {
            synchronized (r) { r.append("ERROR: ").append(e.getClass().getSimpleName()).append(": ").append(e.getMessage()).append('\n'); }
        } finally {
            if (s != null) s.close();
        }
        synchronized (r) { return r.toString(); }
    }

    public void diagnose(final Callback cb) {
        new Thread(new Runnable() {
            @Override public void run() {
                StringBuilder r = new StringBuilder();
                boolean shizuku = shizukuReady();
                r.append("Método: ").append(shizuku ? "Shizuku" : "ADB propio")
                        .append(" | emparejado=").append(AdbConnector.get(ctx).isPaired())
                        .append(" | Android ").append(Build.VERSION.SDK_INT).append('\n');
                if (shizuku || AdbConnector.get(ctx).isPaired()) {
                    String cmd = "echo SDK=$(getprop ro.build.version.sdk) $(getprop ro.product.manufacturer) $(getprop ro.product.model); id; "
                            + "echo '--- /dev'; ls -l /dev/uinput /dev/uhid /system/bin/hid /system/bin/uinput 2>&1; "
                            + "echo '--- getevent (mando virtual)'; getevent -p 2>&1 | grep -i -B1 -A14 'overlay gamepad'; "
                            + "echo '--- logcat uinput/uhid'; " + LOGCAT_CMD + "; echo '--- fin'";
                    r.append(shellOutput(cmd));
                }
                synchronized (procOutput) {
                    r.append("--- salida del proceso uinput de la app:\n").append(procOutput.length() == 0 ? "(vacía)" : procOutput.toString()).append('\n');
                }
                r.append("sesión viva=").append(hasBinder()).append(" creado=").append(created)
                        .append(" visibleEnAndroid=").append(padVisible()).append('\n');
                if (cb != null) cb.onResult(true, r.toString());
            }
        }).start();
    }

    // ---------- Eventos ----------

    private void doKey(int k, boolean pressed) throws IOException {
        if (!created) return;
        switch (k) {
            case 19: up = pressed; hat(); return;
            case 20: down = pressed; hat(); return;
            case 21: left = pressed; hat(); return;
            case 22: right = pressed; hat(); return;
            case 104: inject(EV_ABS, ABS_Z, pressed ? 255 : 0); return;
            case 105: inject(EV_ABS, ABS_RZ, pressed ? 255 : 0); return;
            default:
                int c = map(k);
                if (c != 0) inject(EV_KEY, c, pressed ? 1 : 0);
        }
    }

    private int map(int k) {
        switch (k) {
            case 96: return BTN_A;
            case 97: return BTN_B;
            case 99: return BTN_X;
            case 100: return BTN_Y;
            case 102: return BTN_TL;
            case 103: return BTN_TR;
            case 108: return BTN_START;
            case 109: return BTN_SELECT;
            case 106: return BTN_THUMBL;
            case 107: return BTN_THUMBR;
            case 110: return BTN_MODE;
            default: return 0;
        }
    }

    private void hat() throws IOException {
        int x = (right ? 1 : 0) - (left ? 1 : 0);
        int y = (down ? 1 : 0) - (up ? 1 : 0);
        writeLine("{\"id\":1,\"command\":\"inject\",\"events\":[" + EV_ABS + "," + ABS_HAT0X + "," + x + ","
                + EV_ABS + "," + ABS_HAT0Y + "," + y + "," + EV_SYN + ",0,0]}");
    }

    private void inject(int type, int code, int value) throws IOException {
        writeLine("{\"id\":1,\"command\":\"inject\",\"events\":[" + type + "," + code + "," + value + "," + EV_SYN + ",0,0]}");
    }

    private void doAxis(boolean leftStick, float x, float y) throws IOException {
        if (!created) return;
        int xv = (int) (Math.max(-1f, Math.min(1f, x)) * 32767f);
        int yv = (int) (Math.max(-1f, Math.min(1f, y)) * 32767f);
        writeLine("{\"id\":1,\"command\":\"inject\",\"events\":[" + EV_ABS + "," + (leftStick ? ABS_X : ABS_RX) + "," + xv + ","
                + EV_ABS + "," + (leftStick ? ABS_Y : ABS_RY) + "," + yv + "," + EV_SYN + ",0,0]}");
    }

    private void writeLine(String json) throws IOException {
        OutputStream o = out;
        if (o == null) throw new IOException("uinput no iniciado");
        o.write((json + "\n").getBytes("UTF-8"));
        o.flush();
    }

    private void fail(Throwable e) {
        diag("Fallo al enviar: " + e);
        closeSession();
    }

    private void closeSession() {
        created = false;
        up = down = left = right = false;
        ShellSession s = sess;
        sess = null;
        out = null;
        if (s != null) s.close();
    }

    private String registerJson(int busMode, int absMode) {
        StringBuilder s = new StringBuilder();
        s.append("{\"id\":1,\"command\":\"register\",\"name\":\"").append(PAD_NAME).append("\",");
        // VID/PID numéricos: Android los usa para elegir el mapa de ejes (gatillos y stick derecho).
        // Si se cambian, esos ejes pueden dejar de funcionar correctamente en algunas apps.
        s.append("\"vid\":1118,\"pid\":654,");
        if (busMode == 1) s.append("\"bus\":\"usb\",");
        else if (busMode == 3) s.append("\"bus\":\"bluetooth\",");
        s.append("\"configuration\":[");
        s.append("{\"type\":100,\"data\":[1,3]},");
        s.append("{\"type\":101,\"data\":[").append(BTN_A).append(',').append(BTN_B).append(',').append(BTN_X).append(',')
                .append(BTN_Y).append(',').append(BTN_TL).append(',').append(BTN_TR).append(',').append(BTN_SELECT)
                .append(',').append(BTN_START).append(',').append(BTN_MODE).append(',').append(BTN_THUMBL)
                .append(',').append(BTN_THUMBR).append("]},");
        s.append("{\"type\":103,\"data\":[0,1,2,3,4,5,16,17]}]");
        if (absMode > 0) {
            s.append(",\"abs_info\":[");
            s.append(abs(ABS_X, -32768, 32767, 16, 128)).append(',');
            s.append(abs(ABS_Y, -32768, 32767, 16, 128)).append(',');
            s.append(abs(ABS_Z, 0, 255, 0, 0)).append(',');
            s.append(abs(ABS_RX, -32768, 32767, 16, 128)).append(',');
            s.append(abs(ABS_RY, -32768, 32767, 16, 128)).append(',');
            s.append(abs(ABS_RZ, 0, 255, 0, 0)).append(',');
            s.append(abs(ABS_HAT0X, -1, 1, 0, 0)).append(',');
            s.append(abs(ABS_HAT0Y, -1, 1, 0, 0));
            s.append("]");
        }
        s.append("}");
        return s.toString();
    }

    private static String abs(int code, int min, int max, int fuzz, int flat) {
        return "{\"code\":" + code + ",\"info\":{\"value\":0,\"minimum\":" + min + ",\"maximum\":" + max
                + ",\"fuzz\":" + fuzz + ",\"flat\":" + flat + ",\"resolution\":0}}";
    }


    // ---------- Teclado y ratón virtuales (ver KbmDevices) ----------

    private final KbmDevices kbm = new KbmDevices(this);

    public KbmDevices kbm() { return kbm; }
    public void sendRaw(int code, boolean down) { kbm.key(code, down); }
    public void sendMouseMove(float dx, float dy) { kbm.move(dx, dy); }
    public void sendWheel(int ticks) { kbm.wheel(ticks); }
    public void prepareKbm() { kbm.start(); }
    public void releaseKbm() { kbm.stopNow(); }
    public void releaseKbmLater(long ms) { kbm.stopLater(ms); }
    public void releaseAllKeys() { kbm.releaseAllKeys(); }

    boolean isReady() { return created; }
    int goodBus() { return goodBus; }
    void log(String s) { diag(s); }

    /** Sesión uinput auxiliar para KbmDevices (no reinicia la conexión del mando si falla). */
    ShellSession openAuxUinput() throws Exception {
        if (usingShizuku) return openShizuku(new String[]{UINPUT_BIN, "-"});
        return AdbConnector.get(ctx).openExecKeep(UINPUT_BIN + " -");
    }

    private void report(Callback cb, boolean ok, String m) {
        diag(m);
        if (cb != null) cb.onResult(ok, m);
    }
}
