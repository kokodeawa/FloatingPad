package com.example.floatingpad;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Modelo del mando: controles en coordenadas normalizadas (0..1 del marco útil de pantalla), colisiones,
 * distribuciones predefinidas (asimétrica / simétrica / teclado, horizontal y vertical), agrupación en
 * ventanas táctiles pequeñas y perfiles JSON. No depende de vistas de Android.
 */
public class PadModel {

    public static final int BUTTON = 0, STICK = 1, DPAD = 2, KEY = 3, MSTICK = 4, TRACKPAD = 5, KBD = 6;
    public static final int GLYPH_NONE = 0, GLYPH_CROSS = 1, GLYPH_CIRCLE = 2, GLYPH_SQUARE = 3, GLYPH_TRIANGLE = 4;
    public static final int FAMILY_ASYM = 0, FAMILY_SYM = 1, FAMILY_KEYS = 2;

    // Códigos de tecla Android que entiende VirtualGamepadManager
    public static final int K_UP = 19, K_DOWN = 20, K_LEFT = 21, K_RIGHT = 22;
    public static final int K_A = 96, K_B = 97, K_X = 99, K_Y = 100, K_L1 = 102, K_R1 = 103, K_L2 = 104,
            K_R2 = 105, K_L3 = 106, K_R3 = 107, K_START = 108, K_SELECT = 109, K_MODE = 110;

    public static final float MIN_SCALE = 0.3f, MAX_SCALE = 2.5f;
    public static final float MIN_K = 0.4f, MAX_K = 2.5f;

    public static class Ctl {
        public String id = "", label = "";
        public int type, key, glyph;
        public boolean left, round, pressed;
        public float nx, ny, base, wr = 1f, k = 1f, sens = 1.5f;
        public int[] keys = new int[4];        // MSTICK: arriba, abajo, izquierda, derecha (códigos Linux)
        public float cx, cy, hw, hh;            // geometría en px
        public float sx, sy;                    // posición del stick (-1..1)
        public int mask;                        // direcciones activas: 1 arriba, 2 abajo, 4 izquierda, 8 derecha

        public boolean isCircle() {
            return !((type == BUTTON || type == KEY || type == TRACKPAD || type == KBD) && !round);
        }
    }

    public static class Cluster {
        public final List<Ctl> members = new ArrayList<Ctl>();
        public float fl, ft, fr, fb;
        public int l, t, r, b;

        public String signature() {
            StringBuilder s = new StringBuilder();
            for (Ctl c : members) s.append(c.id).append(',');
            return s.toString();
        }
    }

    private final List<Ctl> ctls = new ArrayList<Ctl>();
    private final float[] g1 = new float[4], g2 = new float[4];
    private int w, h;
    private float density = 1f;
    private float scale = 1f;
    private int family = FAMILY_ASYM;

    // ---------- Marco y acceso ----------

    public void setFrame(int w, int h, float density) {
        this.w = w;
        this.h = h;
        this.density = density;
        relayout();
    }

    public int getWidth() { return w; }
    public int getHeight() { return h; }
    public boolean isPortrait() { return h > w; }
    public List<Ctl> controls() { return ctls; }
    public float getScale() { return scale; }
    public int getFamily() { return family; }
    public void setFamily(int f) { family = f; }

    public String nextId(String prefix) {
        int i = 1;
        while (true) {
            String id = prefix + i;
            boolean used = false;
            for (Ctl c : ctls) if (c.id.equals(id)) { used = true; break; }
            if (!used) return id;
            i++;
        }
    }

    // ---------- Constructores de controles ----------

    private Ctl add(String id, String label, int type, int key, float nx, float ny, float base) {
        Ctl c = new Ctl();
        c.id = id;
        c.label = label;
        c.type = type;
        c.key = key;
        c.nx = nx;
        c.ny = ny;
        c.base = base;
        ctls.add(c);
        return c;
    }

    private void rect(String id, String label, int key, float nx, float ny, float base, float wr) {
        add(id, label, BUTTON, key, nx, ny, base).wr = wr;
    }

    private void round(String id, String label, int key, float nx, float ny, float base) {
        add(id, label, BUTTON, key, nx, ny, base).round = true;
    }

    private void glyph(String id, int glyph, int key, float nx, float ny, float base) {
        Ctl c = add(id, "", BUTTON, key, nx, ny, base);
        c.round = true;
        c.glyph = glyph;
    }

    private void stick(String id, boolean left, float nx, float ny, float base) {
        add(id, id, STICK, 0, nx, ny, base).left = left;
    }

    private void dpad(String id, float nx, float ny, float base) {
        add(id, "D-PAD", DPAD, 0, nx, ny, base);
    }

    private void key(String id, String label, int linux, float nx, float ny, float base, float wr) {
        add(id, label, KEY, linux, nx, ny, base).wr = wr;
    }

    private void keyRound(String id, String label, int linux, float nx, float ny, float base) {
        add(id, label, KEY, linux, nx, ny, base).round = true;
    }

    private void mstick(String id, String label, float nx, float ny, float base, int up, int down, int left, int right) {
        Ctl c = add(id, label, MSTICK, 0, nx, ny, base);
        c.keys = new int[]{up, down, left, right};
    }

    private void kbd(String id, float nx, float ny, float base, float wr) {
        add(id, "Teclado", KBD, 0, nx, ny, base).wr = wr;
    }

    private void trackpad(String id, float nx, float ny, float base, float wr) {
        add(id, "TRACKPAD", TRACKPAD, 0, nx, ny, base).wr = wr;
    }

    /** Posición vertical normalizada para una altura "ub" medida desde abajo en unidades del ancho (vertical). */
    private float vy(float ub) { return 1f - ub * w / (float) h; }

    // ---------- Distribuciones predefinidas ----------

    public void buildPreset(int fam, boolean portrait) {
        ctls.clear();
        family = fam;
        scale = 1f;
        if (w <= 0 || h <= 0) return;
        if (fam == FAMILY_SYM) { if (portrait) symPort(); else symLand(); }
        else if (fam == FAMILY_KEYS) { if (portrait) keysPort(); else keysLand(); }
        else { if (portrait) asymPort(); else asymLand(); }
        relayout();
    }

    /** Sticks asimétricos: stick izquierdo arriba y cruceta abajo; botones de acción arriba y stick derecho abajo. */
    private void asymLand() {
        float m = Math.min(w, h);
        float ax = m / w, ay = m / h;
        rect("L2", "L2", K_L2, 0.065f, 0.10f, 0.05f, 1.7f);
        rect("L1", "L1", K_L1, 0.065f, 0.235f, 0.05f, 1.7f);
        rect("R1", "R1", K_R1, 0.935f, 0.235f, 0.05f, 1.7f);
        rect("R2", "R2", K_R2, 0.935f, 0.10f, 0.05f, 1.7f);
        rect("SELECT", "SELECT", K_SELECT, 0.43f, 0.20f, 0.045f, 1.9f);
        rect("START", "START", K_START, 0.57f, 0.20f, 0.045f, 1.9f);
        stick("LS", true, 0.105f, 0.50f, 0.13f);
        dpad("DPAD", 0.105f, 0.84f, 0.12f);
        float cx = 0.895f, cy = 0.50f, off = 0.115f, r = 0.06f;
        round("Y", "Y", K_Y, cx, cy - off * ay, r);
        round("A", "A", K_A, cx, cy + off * ay, r);
        round("X", "X", K_X, cx - off * ax, cy, r);
        round("B", "B", K_B, cx + off * ax, cy, r);
        stick("RS", false, 0.895f, 0.84f, 0.13f);
    }

    /** Vertical: todo en la mitad inferior de la pantalla (cruceta a la izquierda, botones a la derecha). */
    private void asymPort() {
        float ay = w / (float) h;
        rect("L2", "L2", K_L2, 0.10f, vy(0.96f), 0.045f, 1.7f);
        rect("L1", "L1", K_L1, 0.27f, vy(0.96f), 0.045f, 1.7f);
        rect("R1", "R1", K_R1, 0.73f, vy(0.96f), 0.045f, 1.7f);
        rect("R2", "R2", K_R2, 0.90f, vy(0.96f), 0.045f, 1.7f);
        dpad("DPAD", 0.20f, vy(0.70f), 0.13f);
        float cx = 0.80f, cy = vy(0.70f), off = 0.115f, r = 0.07f;
        round("Y", "Y", K_Y, cx, cy - off * ay, r);
        round("A", "A", K_A, cx, cy + off * ay, r);
        round("X", "X", K_X, cx - off, cy, r);
        round("B", "B", K_B, cx + off, cy, r);
        rect("SELECT", "SELECT", K_SELECT, 0.50f, vy(0.76f), 0.04f, 1.9f);
        rect("START", "START", K_START, 0.50f, vy(0.62f), 0.04f, 1.9f);
        stick("LS", true, 0.22f, vy(0.28f), 0.14f);
        stick("RS", false, 0.78f, vy(0.28f), 0.14f);
    }

    /** Simétrica: cruceta arriba-izquierda, botones de acción arriba-derecha, sticks abajo al centro. */
    private void symLand() {
        float m = Math.min(w, h);
        float ax = m / w, ay = m / h;
        rect("L2", "L2", K_L2, 0.065f, 0.10f, 0.05f, 1.7f);
        rect("L1", "L1", K_L1, 0.065f, 0.235f, 0.05f, 1.7f);
        rect("R1", "R1", K_R1, 0.935f, 0.235f, 0.05f, 1.7f);
        rect("R2", "R2", K_R2, 0.935f, 0.10f, 0.05f, 1.7f);
        rect("SELECT", "SELECT", K_SELECT, 0.40f, 0.12f, 0.04f, 1.9f);
        rect("START", "START", K_START, 0.60f, 0.12f, 0.04f, 1.9f);
        dpad("DPAD", 0.13f, 0.50f, 0.125f);
        float cx = 0.87f, cy = 0.50f, off = 0.115f, r = 0.06f;
        glyph("TRI", PadModel.GLYPH_TRIANGLE, K_Y, cx, cy - off * ay, r);
        glyph("CROSS", PadModel.GLYPH_CROSS, K_A, cx, cy + off * ay, r);
        glyph("SQ", PadModel.GLYPH_SQUARE, K_X, cx - off * ax, cy, r);
        glyph("CIR", PadModel.GLYPH_CIRCLE, K_B, cx + off * ax, cy, r);
        round("HOME", "HOME", K_MODE, 0.50f, 0.50f, 0.05f);
        stick("LS", true, 0.37f, 0.80f, 0.12f);
        stick("RS", false, 0.63f, 0.80f, 0.12f);
        round("L3", "L3", K_L3, 0.37f - 0.185f * ax, 0.80f, 0.035f);
        round("R3", "R3", K_R3, 0.63f + 0.185f * ax, 0.80f, 0.035f);
    }

    private void symPort() {
        float ay = w / (float) h;
        rect("L2", "L2", K_L2, 0.10f, vy(0.96f), 0.045f, 1.7f);
        rect("L1", "L1", K_L1, 0.27f, vy(0.96f), 0.045f, 1.7f);
        rect("R1", "R1", K_R1, 0.73f, vy(0.96f), 0.045f, 1.7f);
        rect("R2", "R2", K_R2, 0.90f, vy(0.96f), 0.045f, 1.7f);
        dpad("DPAD", 0.20f, vy(0.70f), 0.13f);
        float cx = 0.80f, cy = vy(0.70f), off = 0.115f, r = 0.07f;
        glyph("TRI", PadModel.GLYPH_TRIANGLE, K_Y, cx, cy - off * ay, r);
        glyph("CROSS", PadModel.GLYPH_CROSS, K_A, cx, cy + off * ay, r);
        glyph("SQ", PadModel.GLYPH_SQUARE, K_X, cx - off, cy, r);
        glyph("CIR", PadModel.GLYPH_CIRCLE, K_B, cx + off, cy, r);
        rect("SELECT", "SELECT", K_SELECT, 0.50f, vy(0.78f), 0.04f, 1.9f);
        rect("START", "START", K_START, 0.50f, vy(0.64f), 0.04f, 1.9f);
        round("HOME", "HOME", K_MODE, 0.50f, vy(0.46f), 0.045f);
        stick("LS", true, 0.28f, vy(0.26f), 0.125f);
        stick("RS", false, 0.72f, vy(0.26f), 0.125f);
        round("L3", "L3", K_L3, 0.075f, vy(0.26f), 0.035f);
        round("R3", "R3", K_R3, 0.925f, vy(0.26f), 0.035f);
    }

    /** Mando de teclado y ratón: joystick WASD, teclas de acción y trackpad. */
    private void keysLand() {
        mstick("WASD", "WASD", 0.12f, 0.66f, 0.15f, KeyDefs.LK_W, KeyDefs.LK_S, KeyDefs.LK_A, KeyDefs.LK_D);
        key("CTRL", "Ctrl", KeyDefs.LK_LCTRL, 0.07f, 0.14f, 0.055f, 1.5f);
        key("SHIFT", "Shift", KeyDefs.LK_LSHIFT, 0.07f, 0.30f, 0.055f, 1.5f);
        key("TAB", "Tab", KeyDefs.LK_TAB, 0.21f, 0.14f, 0.055f, 1.4f);
        key("SPACE", "Espacio", KeyDefs.LK_SPACE, 0.30f, 0.86f, 0.06f, 2.2f);
        keyRound("N1", "1", KeyDefs.LK_1, 0.340f, 0.14f, 0.055f);
        keyRound("N2", "2", KeyDefs.LK_2, 0.415f, 0.14f, 0.055f);
        keyRound("N3", "3", KeyDefs.LK_3, 0.490f, 0.14f, 0.055f);
        keyRound("N4", "4", KeyDefs.LK_4, 0.565f, 0.14f, 0.055f);
        keyRound("KQ", "Q", KeyDefs.LK_Q, 0.340f, 0.32f, 0.055f);
        keyRound("KE", "E", KeyDefs.LK_E, 0.415f, 0.32f, 0.055f);
        keyRound("KR", "R", KeyDefs.LK_R, 0.490f, 0.32f, 0.055f);
        keyRound("KF", "F", KeyDefs.LK_F, 0.565f, 0.32f, 0.055f);
        key("ESC", "Esc", KeyDefs.LK_ESC, 0.93f, 0.12f, 0.045f, 1.5f);
        key("CLICL", "Clic izq.", KeyDefs.BTN_LEFT, 0.73f, 0.30f, 0.06f, 1.6f);
        key("CLICR", "Clic der.", KeyDefs.BTN_RIGHT, 0.88f, 0.30f, 0.06f, 1.6f);
        trackpad("TRACKPAD", 0.80f, 0.62f, 0.17f, 1.8f);
        kbd("KBD", 0.85f, 0.90f, 0.05f, 2.0f);
    }

    private void keysPort() {
        keyRound("KQ", "Q", KeyDefs.LK_Q, 0.08f, vy(0.97f), 0.045f);
        keyRound("KE", "E", KeyDefs.LK_E, 0.20f, vy(0.97f), 0.045f);
        keyRound("KR", "R", KeyDefs.LK_R, 0.32f, vy(0.97f), 0.045f);
        keyRound("KF", "F", KeyDefs.LK_F, 0.44f, vy(0.97f), 0.045f);
        key("CLICL", "Clic izq.", KeyDefs.BTN_LEFT, 0.66f, vy(0.97f), 0.045f, 1.7f);
        key("CLICR", "Clic der.", KeyDefs.BTN_RIGHT, 0.87f, vy(0.97f), 0.045f, 1.7f);
        keyRound("N1", "1", KeyDefs.LK_1, 0.08f, vy(0.84f), 0.045f);
        keyRound("N2", "2", KeyDefs.LK_2, 0.20f, vy(0.84f), 0.045f);
        keyRound("N3", "3", KeyDefs.LK_3, 0.32f, vy(0.84f), 0.045f);
        keyRound("N4", "4", KeyDefs.LK_4, 0.44f, vy(0.84f), 0.045f);
        trackpad("TRACKPAD", 0.69f, vy(0.56f), 0.21f, 1.3f);
        mstick("WASD", "WASD", 0.19f, vy(0.55f), 0.15f, KeyDefs.LK_W, KeyDefs.LK_S, KeyDefs.LK_A, KeyDefs.LK_D);
        key("SHIFT", "Shift", KeyDefs.LK_LSHIFT, 0.09f, vy(0.30f), 0.05f, 1.5f);
        key("CTRL", "Ctrl", KeyDefs.LK_LCTRL, 0.26f, vy(0.30f), 0.05f, 1.5f);
        key("TAB", "Tab", KeyDefs.LK_TAB, 0.72f, vy(0.22f), 0.05f, 1.4f);
        key("ESC", "Esc", KeyDefs.LK_ESC, 0.90f, vy(0.22f), 0.05f, 1.4f);
        key("SPACE", "Espacio", KeyDefs.LK_SPACE, 0.30f, vy(0.13f), 0.06f, 2.4f);
        kbd("KBD", 0.85f, vy(0.10f), 0.05f, 1.8f);
    }

    // ---------- Geometría y colisiones ----------

    private void dims(Ctl c, float s, float[] o) {
        float m = Math.min(w, h);
        float r = c.base * c.k * s * m;
        if (!c.isCircle()) { o[1] = r; o[0] = r * c.wr; }
        else { o[0] = r; o[1] = r; }
        o[2] = c.nx * w;
        o[3] = c.ny * h;
    }

    public void relayout() {
        if (w <= 0) return;
        for (Ctl c : ctls) {
            dims(c, scale, g1);
            c.hw = g1[0]; c.hh = g1[1]; c.cx = g1[2]; c.cy = g1[3];
        }
    }

    private boolean outOfBounds(float[] g) {
        return g[2] - g[0] < 0 || g[2] + g[0] > w || g[3] - g[1] < 0 || g[3] + g[1] > h;
    }

    private boolean overlap(Ctl a, float[] ga, Ctl b, float[] gb) {
        float gap = 4f * density;
        float dx = ga[2] - gb[2], dy = ga[3] - gb[3];
        boolean ac = a.isCircle(), bc = b.isCircle();
        if (ac && bc) {
            float rr = ga[0] + gb[0] + gap;
            return dx * dx + dy * dy < rr * rr;
        }
        if (!ac && !bc) {
            return Math.abs(dx) < ga[0] + gb[0] + gap && Math.abs(dy) < ga[1] + gb[1] + gap;
        }
        float[] cg = ac ? ga : gb, rg = ac ? gb : ga;
        float px = Math.max(rg[2] - rg[0], Math.min(cg[2], rg[2] + rg[0]));
        float py = Math.max(rg[3] - rg[1], Math.min(cg[3], rg[3] + rg[1]));
        float ex = cg[2] - px, ey = cg[3] - py;
        float rr = cg[0] + gap;
        return ex * ex + ey * ey < rr * rr;
    }

    /** ¿Algún control se sale del marco o choca con otro con la escala s? */
    public boolean collides(float s) {
        int n = ctls.size();
        for (int i = 0; i < n; i++) {
            Ctl a = ctls.get(i);
            dims(a, s, g1);
            if (outOfBounds(g1)) return true;
            for (int j = i + 1; j < n; j++) {
                Ctl b = ctls.get(j);
                dims(b, s, g2);
                if (overlap(a, g1, b, g2)) return true;
            }
        }
        return false;
    }

    /** ¿El control c (con su posición/tamaño actuales) choca con otro o se sale? */
    public boolean blocked(Ctl c) {
        dims(c, scale, g1);
        if (outOfBounds(g1)) return true;
        for (Ctl o : ctls) {
            if (o == c) continue;
            dims(o, scale, g2);
            if (overlap(c, g1, o, g2)) return true;
        }
        return false;
    }

    public float getMaxScale() {
        if (w <= 0) return MAX_SCALE;
        if (!collides(MAX_SCALE)) return MAX_SCALE;
        if (collides(MIN_SCALE)) return MIN_SCALE;
        float lo = MIN_SCALE, hi = MAX_SCALE;
        for (int i = 0; i < 18; i++) {
            float mid = (lo + hi) / 2f;
            if (collides(mid)) hi = mid; else lo = mid;
        }
        return lo;
    }

    /** Aplica el tamaño global pedido, limitado al máximo sin colisiones. Devuelve el aplicado. */
    public float setScale(float s) {
        float want = Math.max(MIN_SCALE, Math.min(MAX_SCALE, s));
        scale = Math.min(want, getMaxScale());
        relayout();
        return scale;
    }

    // ---------- Edición ----------

    /** Cambia el tamaño propio de un control. Devuelve false si no cabe. */
    public boolean adjust(Ctl c, float dk) {
        float old = c.k;
        c.k = Math.max(MIN_K, Math.min(MAX_K, old + dk));
        boolean bad = blocked(c);
        if (bad) c.k = old;
        relayout();
        return !bad;
    }

    public void adjustSens(Ctl c, float d) {
        c.sens = Math.max(0.4f, Math.min(8f, c.sens + d));
    }

    /** Mueve un control a (px,py) con rejilla opcional; intenta diagonal y luego cada eje por separado. */
    public void moveTo(Ctl c, float px, float py, float gridFrac) {
        float m = Math.min(w, h);
        if (gridFrac > 0f) {
            float st = gridFrac * m;
            px = Math.round(px / st) * st;
            py = Math.round(py / st) * st;
        }
        float ox = c.nx, oy = c.ny;
        float tx = px / w, ty = py / h;
        float[][] tries = {{tx, ty}, {tx, oy}, {ox, ty}};
        for (float[] t : tries) {
            c.nx = t[0];
            c.ny = t[1];
            if (!blocked(c)) { relayout(); return; }
        }
        c.nx = ox;
        c.ny = oy;
    }

    /** Añade un control en el hueco libre más cercano al centro. */
    public boolean addControl(Ctl c) {
        if (w <= 0) return false;
        List<float[]> cand = new ArrayList<float[]>();
        for (float y = 0.05f; y <= 0.951f; y += 0.05f) {
            for (float x = 0.05f; x <= 0.951f; x += 0.05f) cand.add(new float[]{x, y});
        }
        Collections.sort(cand, new Comparator<float[]>() {
            @Override public int compare(float[] a, float[] b) {
                float da = (a[0] - 0.5f) * (a[0] - 0.5f) + (a[1] - 0.5f) * (a[1] - 0.5f);
                float db = (b[0] - 0.5f) * (b[0] - 0.5f) + (b[1] - 0.5f) * (b[1] - 0.5f);
                return da < db ? -1 : (da > db ? 1 : 0);
            }
        });
        for (float[] p : cand) {
            c.nx = p[0];
            c.ny = p[1];
            if (!blocked(c)) {
                ctls.add(c);
                relayout();
                return true;
            }
        }
        return false;
    }

    /** ¿Este perfil usa teclado o ratón virtuales? (si no, se destruyen para devolver el teclado al sistema) */
    public boolean needsKbm() {
        for (Ctl c : ctls) {
            if (c.type == KEY || c.type == MSTICK || c.type == TRACKPAD || c.type == KBD) return true;
        }
        return false;
    }

    public void remove(Ctl c) {
        ctls.remove(c);
    }

    // ---------- Ventanas táctiles ----------

    /** Área táctil de un control (px del marco): l, t, r, b. */
    private float[] touchRect(Ctl c) {
        float kx, ky;
        if (!c.isCircle()) { kx = c.hw * 1.15f; ky = c.hh * 1.25f; }
        else { kx = c.hw * 1.3f; ky = c.hw * 1.3f; }
        float pad = 2f * density;
        return new float[]{c.cx - kx - pad, c.cy - ky - pad, c.cx + kx + pad, c.cy + ky + pad};
    }

    private static boolean inter(Cluster a, Cluster b) {
        return a.fl < b.fr && b.fl < a.fr && a.ft < b.fb && b.ft < a.fb;
    }

    private static float area(float l, float t, float r, float b) { return (r - l) * (b - t); }

    private static void absorb(Cluster a, Cluster b) {
        a.members.addAll(b.members);
        a.fl = Math.min(a.fl, b.fl);
        a.ft = Math.min(a.ft, b.ft);
        a.fr = Math.max(a.fr, b.fr);
        a.fb = Math.max(a.fb, b.fb);
    }

    /** Une los bloques cuyas cajas se tocan hasta que todos sean disjuntos. */
    private static void mergeOverlaps(List<Cluster> cl) {
        boolean merged = true;
        while (merged) {
            merged = false;
            outer:
            for (int i = 0; i < cl.size(); i++) {
                for (int j = i + 1; j < cl.size(); j++) {
                    if (inter(cl.get(i), cl.get(j))) {
                        absorb(cl.get(i), cl.remove(j));
                        merged = true;
                        break outer;
                    }
                }
            }
        }
    }

    public List<Cluster> clusters() { return clusters(Integer.MAX_VALUE); }

    /**
     * Agrupa los controles en bloques disjuntos. Cada bloque será una ventana pequeña: lo que queda fuera
     * de ellas no recibe toques del overlay y la pantalla de debajo sigue siendo usable.
     * Si hay más de maxWindows bloques se unen los más próximos (el que menos superficie muerta añade), porque
     * muchas ventanas superpuestas penalizan el rendimiento del móvil.
     */
    public List<Cluster> clusters(int maxWindows) {
        List<Cluster> cl = new ArrayList<Cluster>();
        for (Ctl c : ctls) {
            Cluster k = new Cluster();
            float[] r = touchRect(c);
            k.members.add(c);
            k.fl = r[0]; k.ft = r[1]; k.fr = r[2]; k.fb = r[3];
            cl.add(k);
        }
        mergeOverlaps(cl);
        while (cl.size() > Math.max(1, maxWindows)) {
            int bi = 0, bj = 1;
            float best = Float.MAX_VALUE;
            for (int i = 0; i < cl.size(); i++) {
                for (int j = i + 1; j < cl.size(); j++) {
                    Cluster a = cl.get(i), b = cl.get(j);
                    float u = area(Math.min(a.fl, b.fl), Math.min(a.ft, b.ft), Math.max(a.fr, b.fr), Math.max(a.fb, b.fb));
                    float extra = u - area(a.fl, a.ft, a.fr, a.fb) - area(b.fl, b.ft, b.fr, b.fb);
                    if (extra < best) { best = extra; bi = i; bj = j; }
                }
            }
            absorb(cl.get(bi), cl.remove(bj));
            mergeOverlaps(cl);
        }
        for (Cluster k : cl) {
            k.l = (int) Math.max(0, Math.floor(k.fl));
            k.t = (int) Math.max(0, Math.floor(k.ft));
            k.r = (int) Math.min(w, Math.ceil(k.fr));
            k.b = (int) Math.min(h, Math.ceil(k.fb));
            if (k.r <= k.l) k.r = k.l + 1;
            if (k.b <= k.t) k.b = k.t + 1;
        }
        Collections.sort(cl, new Comparator<Cluster>() {
            @Override public int compare(Cluster a, Cluster b) {
                if (a.t != b.t) return a.t < b.t ? -1 : 1;
                return a.l < b.l ? -1 : (a.l > b.l ? 1 : 0);
            }
        });
        return cl;
    }

    // ---------- Perfiles ----------

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("v", 2);
            o.put("family", family);
            o.put("scale", scale);
            JSONArray a = new JSONArray();
            for (Ctl c : ctls) {
                JSONObject j = new JSONObject();
                j.put("id", c.id);
                j.put("label", c.label);
                j.put("type", c.type);
                j.put("key", c.key);
                j.put("glyph", c.glyph);
                j.put("left", c.left);
                j.put("round", c.round);
                j.put("x", c.nx);
                j.put("y", c.ny);
                j.put("base", c.base);
                j.put("wr", c.wr);
                j.put("k", c.k);
                j.put("sens", c.sens);
                JSONArray ks = new JSONArray();
                for (int i = 0; i < 4; i++) ks.put(c.keys[i]);
                j.put("keys", ks);
                a.put(j);
            }
            o.put("list", a);
        } catch (JSONException ignored) { }
        return o;
    }

    private static float clamp(double v, double lo, double hi) {
        return (float) Math.max(lo, Math.min(hi, v));
    }

    /** Carga un perfil guardado (lista completa de controles). */
    public boolean applyJson(JSONObject o) {
        if (o == null || w <= 0) return false;
        JSONArray a = o.optJSONArray("list");
        if (a == null) return false;
        {
            ctls.clear();
            family = o.optInt("family", FAMILY_ASYM);
            for (int i = 0; i < a.length(); i++) {
                JSONObject j = a.optJSONObject(i);
                if (j == null) continue;
                Ctl c = new Ctl();
                c.id = j.optString("id", "");
                if (c.id.length() == 0) c.id = nextId("C");
                c.label = j.optString("label", "");
                c.type = Math.max(0, Math.min(KBD, j.optInt("type", BUTTON)));
                c.key = j.optInt("key", 0);
                c.glyph = Math.max(0, Math.min(GLYPH_TRIANGLE, j.optInt("glyph", 0)));
                c.left = j.optBoolean("left", false);
                c.round = j.optBoolean("round", false);
                c.nx = clamp(j.optDouble("x", 0.5), 0, 1);
                c.ny = clamp(j.optDouble("y", 0.5), 0, 1);
                c.base = clamp(j.optDouble("base", 0.06), 0.01, 0.6);
                c.wr = clamp(j.optDouble("wr", 1.0), 0.5, 4.0);
                c.k = clamp(j.optDouble("k", 1.0), MIN_K, MAX_K);
                c.sens = clamp(j.optDouble("sens", 1.5), 0.4, 8.0);
                JSONArray ks = j.optJSONArray("keys");
                if (ks != null) for (int n = 0; n < 4; n++) c.keys[n] = ks.optInt(n, 0);
                ctls.add(c);
            }
        }
        scale = MIN_SCALE;
        setScale((float) o.optDouble("scale", 1.0));
        return true;
    }
}
