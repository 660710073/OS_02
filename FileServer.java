import java.io.*;
import java.net.*;
import java.nio.channels.*;
import java.util.concurrent.*;

public class FileServer {
    private static final int PORT = 8080;
    private static final String FILE_DIR = "./server_files/";

    public static void main(String[] args) throws IOException {
        // ใช้ Virtual Thread ของ Java 21+ ตามที่โจทย์แนะนำ (ทำงานเบากว่า Thread ปกติมาก)
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
        private SocketChannel clientChannel;

        public ClientHandler(SocketChannel clientChannel) {
            this.clientChannel = clientChannel;
        }

        @Override
        public void run() {
            try {
                BufferedReader in = new BufferedReader(new InputStreamReader(Channels.newInputStream(clientChannel)));
                PrintWriter writer = new PrintWriter(Channels.newOutputStream(clientChannel), true);
                
                String request = in.readLine();
                if (request == null) return;
                
                String[] parts = request.split(" ");
                String command = parts[0];

                // 1. เพิ่มคำสั่ง LIST สำหรับส่งรายชื่อไฟล์ให้ Client
                if (command.equals("LIST")) {
                    File dir = new File(FILE_DIR);
                    File[] files = dir.listFiles();
                    if (files != null) {
                        for (File f : files) {
                            if (f.isFile()) {
                                writer.println("FILE " + f.getName() + " " + f.length());
                            }
                        }
                    }
                    writer.println("END_LIST"); // ส่งสัญญาณบอกว่าจบรายชื่อแล้ว
                }
                else if (command.equals("INFO")) {
                    File file = new File(FILE_DIR + parts[1]);
                    if (file.exists()) writer.println("SIZE " + file.length());
                    else writer.println("ERROR 404 File Not Found");
                } 
                else if (command.equals("GET")) {
                    long offset = Long.parseLong(parts[2]);
                    long length = Long.parseLong(parts[3]);
                    File file = new File(FILE_DIR + parts[1]);
                    try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
                        raf.seek(offset);
                        byte[] buffer = new byte[8192];
                        long bytesReadTotal = 0;
                        int bytesRead;
                        OutputStream out = Channels.newOutputStream(clientChannel);
                        while (bytesReadTotal < length) {
                            int bytesToRead = (int) Math.min(buffer.length, length - bytesReadTotal);
                            bytesRead = raf.read(buffer, 0, bytesToRead);
                            if (bytesRead == -1) break;
                            out.write(buffer, 0, bytesRead);
                            bytesReadTotal += bytesRead;
                        }
                        out.flush();
                    }
                }
                else if (command.equals("GETNIO")) {
                    long offset = Long.parseLong(parts[2]);
                    long length = Long.parseLong(parts[3]);
                    File file = new File(FILE_DIR + parts[1]);
                    try (RandomAccessFile raf = new RandomAccessFile(file, "r");
                         FileChannel fileChannel = raf.getChannel()) {
                        fileChannel.transferTo(offset, length, clientChannel);
                    }
                }
            } catch (IOException e) {
            } finally {
                try { clientChannel.close(); } catch (IOException e) {}
            }
        }
    }
}