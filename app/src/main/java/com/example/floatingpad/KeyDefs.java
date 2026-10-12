package com.example.floatingpad;

import java.util.ArrayList;
import java.util.List;

/** Tablas de teclas para el selector: teclado/ratón (códigos Linux) y botones de mando (códigos Android). */
public final class KeyDefs {

    public static final int KIND_KEY = 0, KIND_MOUSE = 1, KIND_PAD = 2;

    public static final class Def {
        public final String label;
        public final int code;
        public final int kind;
        Def(String label, int code, int kind) { this.label = label; this.code = code; this.kind = kind; }
    }

    public static final class Group {
        public final String title;
        public final Def[] defs;
        Group(String title, Def[] defs) { this.title = title; this.defs = defs; }
    }

    // Códigos Linux (input-event-codes.h)
    public static final int LK_ESC = 1, LK_TAB = 15, LK_ENTER = 28, LK_LCTRL = 29, LK_LSHIFT = 42, LK_LALT = 56,
            LK_SPACE = 57, LK_W = 17, LK_A = 30, LK_S = 31, LK_D = 32, LK_Q = 16, LK_E = 18, LK_R = 19, LK_F = 33,
            LK_I = 23, LK_J = 36, LK_K = 37, LK_L = 38, LK_UP = 103, LK_LEFT = 105, LK_RIGHT = 106, LK_DOWN = 108,
            LK_1 = 2, LK_2 = 3, LK_3 = 4, LK_4 = 5, LK_C = 46, LK_V = 47, LK_X = 45, LK_Z = 44, LK_G = 34,
            BTN_LEFT = 272, BTN_RIGHT = 273, BTN_MIDDLE = 274;

    private static final String LETTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final int[] LETTER_CODES = {30, 48, 46, 32, 18, 33, 34, 35, 23, 36, 37, 38, 50, 49, 24, 25, 16, 19,
            31, 20, 22, 47, 17, 45, 21, 44};

    private static Def k(String l, int c) { return new Def(l, c, KIND_KEY); }

    public static Group[] keyboardGroups() {
        List<Def> letters = new ArrayList<Def>();
        for (int i = 0; i < LETTERS.length(); i++) letters.add(k(String.valueOf(LETTERS.charAt(i)), LETTER_CODES[i]));

        List<Def> digits = new ArrayList<Def>();
        for (int i = 1; i <= 9; i++) digits.add(k(String.valueOf(i), 1 + i));
        digits.add(k("0", 11));

        List<Def> fn = new ArrayList<Def>();
        for (int i = 1; i <= 10; i++) fn.add(k("F" + i, 58 + i));
        fn.add(k("F11", 87));
        fn.add(k("F12", 88));

        Def[] ctrl = {k("Esc", 1), k("Tab", 15), k("Espacio", 57), k("Enter", 28), k("Retroc.", 14),
                k("Shift", 42), k("Ctrl", 29), k("Alt", 56), k("Mayús", 58), k("Win", 125), k("Supr", 111), k("Insert", 110)};
        Def[] nav = {k("↑", 103), k("↓", 108), k("←", 105), k("→", 106), k("Inicio", 102), k("Fin", 107),
                k("RePág", 104), k("AvPág", 109)};
        Def[] sym = {k("-", 12), k("=", 13), k("[", 26), k("]", 27), k(";", 39), k("'", 40), k("`", 41),
                k("\\", 43), k(",", 51), k(".", 52), k("/", 53)};
        Def[] pad = {k("Num0", 82), k("Num1", 79), k("Num2", 80), k("Num3", 81), k("Num4", 75), k("Num5", 76),
                k("Num6", 77), k("Num7", 71), k("Num8", 72), k("Num9", 73), k("Num.", 83), k("Num+", 78),
                k("Num-", 74), k("Num*", 55), k("Num/", 98), k("NumEnt", 96)};
        Def[] mouse = {new Def("Clic izq.", 272, KIND_MOUSE), new Def("Clic der.", 273, KIND_MOUSE),
                new Def("Clic central", 274, KIND_MOUSE)};

        return new Group[]{
                new Group("Letras", letters.toArray(new Def[0])),
                new Group("Números", digits.toArray(new Def[0])),
                new Group("Control", ctrl),
                new Group("Flechas y navegación", nav),
                new Group("Función", fn.toArray(new Def[0])),
                new Group("Símbolos", sym),
                new Group("Teclado numérico", pad),
                new Group("Ratón", mouse)
        };
    }

    public static Group[] padGroups() {
        Def[] face = {new Def("A", 96, KIND_PAD), new Def("B", 97, KIND_PAD), new Def("X", 99, KIND_PAD),
                new Def("Y", 100, KIND_PAD)};
        Def[] sh = {new Def("L1", 102, KIND_PAD), new Def("R1", 103, KIND_PAD), new Def("L2", 104, KIND_PAD),
                new Def("R2", 105, KIND_PAD)};
        Def[] mid = {new Def("L3", 106, KIND_PAD), new Def("R3", 107, KIND_PAD), new Def("SELECT", 109, KIND_PAD),
                new Def("START", 108, KIND_PAD), new Def("MODE", 110, KIND_PAD)};
        Def[] dp = {new Def("↑", 19, KIND_PAD), new Def("↓", 20, KIND_PAD), new Def("←", 21, KIND_PAD),
                new Def("→", 22, KIND_PAD)};
        return new Group[]{new Group("Botones", face), new Group("Gatillos y hombros", sh),
                new Group("Sticks y menú", mid), new Group("Cruceta", dp)};
    }

    private static java.util.HashMap<Integer, String> labelCache;

    /** Etiqueta corta de una tecla Linux (para dibujar las direcciones de un joystick mapeado). */
    public static synchronized String labelFor(int linuxCode) {
        if (labelCache == null) {
            labelCache = new java.util.HashMap<Integer, String>();
            for (Group g : keyboardGroups()) {
                for (Def d : g.defs) {
                    if (!labelCache.containsKey(d.code)) {
                        labelCache.put(d.code, d.label.length() > 4 ? d.label.substring(0, 4) : d.label);
                    }
                }
            }
        }
        String l = labelCache.get(linuxCode);
        return l == null ? "?" : l;
    }

    /** Teclas que declara el teclado virtual: solo las que el mando puede enviar (sin encendido, volumen, etc.). */
    public static int[] keyboardCodes() {
        java.util.TreeSet<Integer> set = new java.util.TreeSet<Integer>();
        for (Group g : keyboardGroups()) {
            for (Def d : g.defs) if (d.kind == KIND_KEY) set.add(d.code);
        }
        set.add(54);   // Shift derecho
        set.add(97);   // Ctrl derecho
        set.add(100);  // Alt derecho
        set.add(126);  // Win derecho
        int[] out = new int[set.size()];
        int i = 0;
        for (Integer c : set) out[i++] = c;
        return out;
    }

    private KeyDefs() { }
}
