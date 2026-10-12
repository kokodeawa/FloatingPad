package com.example.floatingpad;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Un comando en ejecución como "shell", sea por Shizuku (Process) o por el ADB propio. */
public abstract class ShellSession {
    public abstract String name();
    public abstract OutputStream out() throws IOException;
    public abstract InputStream in() throws IOException;
    public abstract InputStream err() throws IOException;
    public abstract boolean alive();
    public abstract String exitInfo();
    /** Lo llama el lector cuando la salida se cierra (fin del comando). */
    public abstract void markDead();
    public abstract void close();

    /** Proceso remoto de Shizuku. */
    public static class ProcessSession extends ShellSession {
        private final Process p;

        public ProcessSession(Process p) { this.p = p; }

        @Override public String name() { return "Shizuku"; }
        @Override public OutputStream out() { return p.getOutputStream(); }
        @Override public InputStream in() { return p.getInputStream(); }
        @Override public InputStream err() { return p.getErrorStream(); }

        @Override public boolean alive() {
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                try { return p.isAlive(); } catch (Throwable ignored) { }
            }
            try { p.exitValue(); return false; }
            catch (IllegalArgumentException e) { return true; } // Binder convierte IllegalThreadStateException
            catch (Throwable e) { return false; }
        }

        @Override public String exitInfo() {
            try { return "código de salida " + p.exitValue(); }
            catch (Throwable e) { return "estado desconocido (" + e.getClass().getSimpleName() + ")"; }
        }

        @Override public void markDead() { }

        @Override public void close() {
            try { p.getOutputStream().close(); } catch (Throwable ignored) { }
            try { p.destroy(); } catch (Throwable ignored) { }
        }
    }

    /** Stream "exec:" del ADB propio (depuración inalámbrica). */
    public static class AdbSession extends ShellSession {
        private final InputStream in;
        private final OutputStream out;
        private final Runnable closer;
        private volatile boolean dead;

        public AdbSession(InputStream in, OutputStream out, Runnable closer) {
            this.in = in;
            this.out = out;
            this.closer = closer;
        }

        @Override public String name() { return "ADB propio"; }
        @Override public OutputStream out() { return out; }
        @Override public InputStream in() { return in; }
        @Override public InputStream err() { return new ByteArrayInputStream(new byte[0]); }
        @Override public boolean alive() { return !dead; }
        @Override public String exitInfo() { return "conexión ADB cerrada"; }
        @Override public void markDead() { dead = true; }

        @Override public void close() {
            dead = true;
            try { out.close(); } catch (Throwable ignored) { }
            try { closer.run(); } catch (Throwable ignored) { }
        }
    }
}
