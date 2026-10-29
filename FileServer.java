import java.io.*;
import java.net.*;
import java.nio.channels.*;
import java.util.concurrent.*;

public class FileServer {
    private static final int PORT = 8080;
    private static final String FILE_DIR = "./server_files/";

    public static void main(String[] args) throws IOException {
        new File(FILE_DIR).mkdirs();
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

        try (ServerSocketChannel serverChannel = ServerSocketChannel.open()) {
            serverChannel.bind(new InetSocketAddress(PORT));
            System.out.println("Server On (Mode Virtual Thread & NIO) Connecting Port: " + PORT);

            while (true) {
                SocketChannel clientChannel = serverChannel.accept();
                executor.submit(new ClientHandler(clientChannel));
            }
        }
    }

    static class ClientHandler implements Runnable {
        private final SocketChannel clientChannel;

        public ClientHandler(SocketChannel clientChannel) {
            this.clientChannel = clientChannel;
        }

        // กัน path traversal: ชื่อไฟล์ต้องไม่มี / หรือ .. และต้องเป็นไฟล์จริง
        private File resolve(String name) {
            File f = new File(FILE_DIR, name);
            return (f.getName().equals(name) && f.isFile()) ? f : null;
        }

        @Override
        public void run() {
            try {
                BufferedReader in = new BufferedReader(
                        new InputStreamReader(Channels.newInputStream(clientChannel)));
                PrintWriter writer = new PrintWriter(Channels.newOutputStream(clientChannel), true);

                String request = in.readLine();
                if (request == null) return;

                String[] parts = request.split(" ");
                String command = parts[0];

                if (command.equals("LIST")) {
                    File[] files = new File(FILE_DIR).listFiles();
                    if (files != null) {
                        for (File f : files) {
                            if (f.isFile()) writer.println("FILE " + f.getName() + " " + f.length());
                        }
                    }
                    writer.println("END_LIST");
                }
                else if (command.equals("INFO")) {
                    File file = (parts.length == 2) ? resolve(parts[1]) : null;
                    if (file != null) writer.println("SIZE " + file.length());
                    else writer.println("ERROR 404 File Not Found");
                }
                else if (command.equals("GET") || command.equals("GETNIO")) {
                    if (parts.length != 4) { writer.println("ERROR 400 Bad Request"); return; }
                    File file = resolve(parts[1]);
                    if (file == null) { writer.println("ERROR 404 File Not Found"); return; }

                    long offset, length;
                    try {
                        offset = Long.parseLong(parts[2]);
                        length = Long.parseLong(parts[3]);
                    } catch (NumberFormatException e) {
                        writer.println("ERROR 400 Bad Number");
                        return;
                    }
                    long size = file.length();
                    if (offset < 0 || length < 0 || offset > size) {
                        writer.println("ERROR 416 Range Not Satisfiable");
                        return;
                    }
                    length = Math.min(length, size - offset);

                    writer.println("OK " + length); // header บอกความยาว payload ที่จะตามมา
                    if (command.equals("GET")) sendTraditional(file, offset, length);
                    else sendNio(file, offset, length);
                }
                else {
                    writer.println("ERROR 400 Unknown Command");
                }
            } catch (Exception e) {
                System.out.println("Handler error: " + e);
            } finally {
                try { clientChannel.close(); } catch (IOException e) {}
            }
        }

        private void sendTraditional(File file, long offset, long length) throws IOException {
            try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
                raf.seek(offset);
                byte[] buffer = new byte[8192];
                OutputStream out = Channels.newOutputStream(clientChannel);
                long remaining = length;
                while (remaining > 0) {
                    int n = raf.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                    if (n == -1) break;
                    out.write(buffer, 0, n);
                    remaining -= n;
                }
                out.flush();
            }
        }

        private void sendNio(File file, long offset, long length) throws IOException {
            try (RandomAccessFile raf = new RandomAccessFile(file, "r");
                 FileChannel fc = raf.getChannel()) {
                long pos = offset, remaining = length;
                while (remaining > 0) { // transferTo อาจส่งไม่ครบในครั้งเดียว ต้องวนซ้ำ
                    long sent = fc.transferTo(pos, remaining, clientChannel);
                    if (sent <= 0) break;
                    pos += sent;
                    remaining -= sent;
                }
            }
        }
    }
}