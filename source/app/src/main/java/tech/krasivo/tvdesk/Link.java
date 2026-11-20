package tech.krasivo.tvdesk;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.os.Handler;
import android.os.Looper;
import org.json.JSONObject;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.FutureTask;

final class Link implements Runnable {
    static final UUID SERVICE = UUID.fromString("b510816b-29c8-4250-968a-1d598d88be74");
    private final Home home;
    private final BluetoothAdapter adapter;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile boolean closed;
    private volatile boolean allowed;
    private final CountDownLatch decision = new CountDownLatch(1);
    private BluetoothServerSocket listener;
    private BluetoothSocket peer;

    Link(Home home, BluetoothAdapter adapter) { this.home = home; this.adapter = adapter; }
    private static void close(Closeable item) {
        if (item != null) try { item.close(); } catch (IOException ignored) { }
    }
    synchronized void stop() {
        closed = true;
        decision.countDown();
        close(listener);
        close(peer);
    }
    void decide(boolean value) { allowed = value; decision.countDown(); }
    private synchronized void keep(BluetoothServerSocket s) throws IOException {
        if (closed) { close(s); throw new IOException(); }
        listener = s;
    }
    private synchronized void keep(BluetoothSocket s) throws IOException {
        if (closed) { close(s); throw new IOException(); }
        peer = s;
    }
    private static void send(OutputStream out, JSONObject msg) throws IOException {
        out.write((msg.toString()+"\n").getBytes(StandardCharsets.UTF_8)); out.flush();
    }
    // Read a bounded newline-delimited JSON message, including fragmented RFCOMM frames.
    private static String line(BufferedInputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        for (int n; (n = in.read()) != -1;) {
            if (n == 10) return buffer.toString("UTF-8");
            if (buffer.size() >= 131072) throw new IOException("Слишком большое сообщение");
            buffer.write(n);
        }
        return null;
    }
    @Override public void run() {
        String end = "Ноутбук отключён";
        Runnable expire = () -> home.finishLink(this, "Время сеанса истекло");
        try {
            BluetoothServerSocket server = adapter.listenUsingRfcommWithServiceRecord("TV Desk", SERVICE);
            keep(server);
            ui.post(() -> home.linkStatus(this, "Ожидаю ноутбук · 2 минуты"));
            BluetoothSocket socket = server.accept(120000);
            keep(socket);
            close(server);
            String name = socket.getRemoteDevice().getName();
            OutputStream out = socket.getOutputStream();
            send(out, new JSONObject().put("event", "approval").put("version", 1));
            ui.post(() -> home.offer(this, name));
            if (!decision.await(50, TimeUnit.SECONDS) || !allowed || closed) {
                end = "Подключение не подтверждено"; return;
            }
            ui.postDelayed(expire, 15 * 60 * 1000);
            send(out, new JSONObject().put("event", "ready").put("version", 1));
            BufferedInputStream in = new BufferedInputStream(socket.getInputStream());
            String raw;
            while (!closed && (raw = line(in)) != null) {
                JSONObject request = new JSONObject(raw);
                String op = request.optString("op");
                Apps inventory = new Apps(home);
                // Loading labels from vendor packages may require slow storage reads.
                if ("state".equals(op) || "profile".equals(op)) inventory.scan(true);
                if ("details".equals(op) || "uninstall".equals(op)) inventory.inspect(request.optString("app"));
                FutureTask<JSONObject> job = new FutureTask<>(() -> home.command(this, request, inventory));
                ui.post(job);
                JSONObject reply;
                try { reply = new JSONObject().put("ok", true).put("data", job.get(10, TimeUnit.SECONDS)); }
                catch (Exception e) {
                    job.cancel(false);
                    Throwable cause = e.getCause() == null ? e : e.getCause();
                    String msg = cause.getMessage();
                    reply = new JSONObject().put("ok", false).put("error", msg == null ? "Не удалось выполнить действие" : msg);
                }
                send(out, reply.put("id", request.getInt("id")));
            }
        } catch (Exception e) { if (!closed) end = "Связь завершена · " + (e.getMessage() == null ? "проверьте Bluetooth" : e.getMessage()); }
        finally {
            stop(); ui.removeCallbacks(expire);
            final String message = end;
            ui.post(() -> home.finishLink(this, message));
        }
    }
}
