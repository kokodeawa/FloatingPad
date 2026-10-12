package com.example.floatingpad;

import org.json.JSONObject;
import java.util.List;

public class LayoutCheck {
    static int fails = 0;
    static void check(boolean ok, String msg) { if (!ok) { fails++; System.out.println("  FALLO: " + msg); } }

    public static void main(String[] a) throws Exception {
        int[][] land = {{2260, 1000}, {2250, 1020}, {1920, 1000}, {2400, 1080}, {1600, 720}, {2100, 940}};
        int[][] port = {{1080, 2100}, {1080, 1950}, {720, 1400}, {1200, 2200}, {1080, 1700}, {1080, 2300}};
        String[] fam = {"Asimetrico", "Simetrico", "Teclado"};
        for (int p = 0; p < 2; p++) {
            int[][] frames = p == 0 ? land : port;
            for (int[] f : frames) {
                for (int fi = 0; fi < 3; fi++) {
                    PadModel m = new PadModel();
                    m.setFrame(f[0], f[1], 2.75f);
                    m.buildPreset(fi, p == 1);
                    String tag = fam[fi] + (p == 1 ? " vertical " : " horizontal ") + f[0] + "x" + f[1];
                    boolean col = m.collides(1f);
                    float mx = m.getMaxScale();
                    List<PadModel.Cluster> cl = m.clusters(8);
                    System.out.println(tag + ": controles=" + m.controls().size() + " colision@1=" + col
                            + " maxScale=" + String.format("%.2f", mx) + " ventanas=" + cl.size());
                    check(!col, tag + " colisiona a escala 1");
                    // clusters disjuntos y cubren a sus miembros
                    long area = 0;
                    for (int i = 0; i < cl.size(); i++) {
                        PadModel.Cluster c = cl.get(i);
                        area += (long) (c.r - c.l) * (c.b - c.t);
                        for (PadModel.Ctl k : c.members) {
                            check(k.cx - k.hw >= c.l - 1 && k.cx + k.hw <= c.r + 1 && k.cy - k.hh >= c.t - 1 && k.cy + k.hh <= c.b + 1,
                                    tag + " control " + k.id + " fuera de su ventana");
                        }
                        for (int j = i + 1; j < cl.size(); j++) {
                            PadModel.Cluster d = cl.get(j);
                            boolean ov = c.l < d.r - 2 && d.l < c.r - 2 && c.t < d.b - 2 && d.t < c.b - 2;
                            check(!ov, tag + " ventanas solapadas " + i + "," + j);
                        }
                    }
                    double pct = 100.0 * area / ((long) f[0] * f[1]);
                    System.out.println("   area tactil ocupada: " + String.format("%.1f", pct) + "%");
                    check(pct < 65.0, tag + " ocupa demasiada pantalla");

                    // ida y vuelta JSON
                    String s = m.toJson().toString();
                    PadModel m2 = new PadModel();
                    m2.setFrame(f[0], f[1], 2.75f);
                    check(m2.applyJson(new JSONObject(s)), tag + " applyJson");
                    check(m2.controls().size() == m.controls().size(), tag + " JSON cuenta distinta");
                    for (int i = 0; i < m.controls().size(); i++) {
                        PadModel.Ctl x = m.controls().get(i), y = m2.controls().get(i);
                        check(x.id.equals(y.id) && x.type == y.type && x.key == y.key && Math.abs(x.nx - y.nx) < 1e-4
                                && Math.abs(x.ny - y.ny) < 1e-4 && x.glyph == y.glyph && x.round == y.round
                                && java.util.Arrays.equals(x.keys, y.keys), tag + " JSON difiere en " + x.id);
                    }

                    // añadir controles
                    PadModel.Ctl kk = new PadModel.Ctl();
                    kk.id = m.nextId("K"); kk.type = PadModel.KEY; kk.label = "E"; kk.key = 18; kk.base = 0.055f;
                    boolean okk = m.addControl(kk);
                    PadModel.Ctl ms = new PadModel.Ctl();
                    ms.id = m.nextId("J"); ms.type = PadModel.MSTICK; ms.base = 0.13f; ms.keys = new int[]{103, 108, 105, 106};
                    boolean okm = m.addControl(ms);
                    System.out.println("   añadir tecla=" + okk + " joystick=" + okm + " colision@1=" + m.collides(1f));
                    check(okk && okm, tag + " no pudo añadir controles");
                    check(!m.collides(1f), tag + " colisiona tras añadir");
                }
            }
        }
        // el teclado virtual solo hace falta en el perfil de teclado
        for (int p = 0; p < 2; p++) {
            for (int fi = 0; fi < 3; fi++) {
                PadModel m = new PadModel();
                m.setFrame(p == 0 ? 2260 : 1080, p == 0 ? 1000 : 2100, 2.75f);
                m.buildPreset(fi, p == 1);
                boolean hasKbd = false;
                for (PadModel.Ctl c : m.controls()) if (c.type == PadModel.KBD) hasKbd = true;
                check(m.needsKbm() == (fi == 2), "needsKbm familia " + fi);
                check(hasKbd == (fi == 2), "boton de teclado solo en perfil de teclado, familia " + fi);
            }
        }
        System.out.println(fails == 0 ? "TODO OK" : ("FALLOS: " + fails));
        System.exit(fails == 0 ? 0 : 1);
    }
}
