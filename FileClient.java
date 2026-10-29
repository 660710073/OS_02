import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * วิธีรัน:  java FileClient [GET|GETNIO] [จำนวน workers]
 * ตัวอย่าง: java FileClient GETNIO 10
 *           java FileClient GET 1
 */
public class FileClient {
    private static final String SERVER_IP = "127.0.0.1";
    private static final int PORT = 8080;
    private static final String SAVE_DIR = "./client_files/";
    private static final String SERVER_DIR = "./server_files/"; // ใช้เทียบ hash เมื่อทดสอบบนเครื่องเดียวกัน

    public static void main(String[] args) {
        String command = (args.length > 0) ? args[0].toUpperCase() : "GETNIO";
        int numWorkers = (args.length > 1) ? Integer.parseInt(args[1]) : 10;
        if (!command.equals("GET") && !command.equals("GETNIO")) {
            System.out.println("Usage: java FileClient [GET|GETNIO] [numWorkers]");
            return;
        }

        new File(SAVE_DIR).mkdirs(); // สร้างโฟลเดอร์ปลายทางถ้ายังไม่มี

        System.out.println("--- File name on Server ---");
        List<String> fileList = getServerFiles();
        System.out.println("---------------------------");
        if (fileList.isEmpty()) {
            System.out.println("No File Server");
            return;
        }

        for (String fileName : fileList) {
            String savePath = SAVE_DIR + fileName;
            long totalSize = getFileSize(fileName);
            if (totalSize <= 0) continue;

            System.out.println("\nDownloading: " + fileName + " (" + totalSize + " bytes)"
                    + " mode=" + command + " workers=" + numWorkers);

            // ล้างไฟล์เก่า กันผลเก่าปนกับผลใหม่ (ไม่งั้นดาวน์โหลดพังแต่ไฟล์เก่ายังผ่านการตรวจ)
            try (RandomAccessFile raf = new RandomAccessFile(savePath, "rw")) {
                raf.setLength(0);
            } catch (IOException e) {
                System.out.println("Cannot create " + savePath + ": " + e);
                continue;
            }

            long chunkSize = totalSize / numWorkers;
            Thread[] threads = new Thread[numWorkers];
            DownloadWorker[] workers = new DownloadWorker[numWorkers];
            long startTime = System.nanoTime();

            for (int i = 0; i < numWorkers; i++) {
                long offset = i * chunkSize;
                long length = (i == numWorkers - 1) ? (totalSize - offset) : chunkSize;
                workers[i] = new DownloadWorker(i, SERVER_IP, PORT, fileName, offset, length, savePath, command);
                threads[i] = new Thread(workers[i]);
                threads[i].start();
            }
            for (Thread t : threads) {
                try { t.join(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }

            double seconds = (System.nanoTime() - startTime) / 1_000_000_000.0;
            double mbPerSec = (totalSize / (1024.0 * 1024.0)) / seconds;
            System.out.printf("Download Complete in %.3f seconds (%.2f MB/s)%n", seconds, mbPerSec);

            boolean allOk = true;
            for (DownloadWorker w : workers) allOk &= w.ok;
            System.out.println("Workers: " + (allOk ? "ALL OK" : "SOME FAILED"));

            verifyFile(savePath, totalSize, SERVER_DIR + fileName);
        }
    }

    private static List<String> getServerFiles() {
        List<String> foundFiles = new ArrayList<>();
        try (Socket socket = new Socket(SERVER_IP, PORT);
             PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
             BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {

            out.println("LIST");
            String response;
            while ((response = in.readLine()) != null) {
                if (response.equals("END_LIST")) break;
                System.out.println(response);
                if (response.startsWith("FILE ")) {
                    foundFiles.add(response.split(" ")[1]);
                }
            }
        } catch (IOException e) {
            System.out.println("Cannot connect to server: " + e);
        }
        return foundFiles;
    }

    private static long getFileSize(String filename) {
        try (Socket socket = new Socket(SERVER_IP, PORT);
             PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
             BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {
            out.println("INFO " + filename);
            String response = in.readLine();
            if (response != null && response.startsWith("SIZE ")) {
                return Long.parseLong(response.split(" ")[1]);
            }
            System.out.println("INFO failed for " + filename + ": " + response);
        } catch (IOException | NumberFormatException e) {
            System.out.println("INFO error: " + e);
        }
        return -1;
    }

    private static void verifyFile(String filePath, long expectedSize, String serverCopyPath) {
        File file = new File(filePath);
        System.out.println("--- Validation (File Verification) ---");
        System.out.println("Expected Size: " + expectedSize + " bytes");
        System.out.println("Actual Size:   " + file.length() + " bytes");
        System.out.println(file.length() == expectedSize ? "=> Size Check: PASSED" : "=> Size Check: FAILED");

        try {
            String hash = sha256(file);
            System.out.println("File SHA-256 Hash: " + hash);
            File serverCopy = new File(serverCopyPath);
            if (serverCopy.isFile()) { // ใช้ได้เมื่อรัน Client/Server บนเครื่องเดียวกัน
                String serverHash = sha256(serverCopy);
                System.out.println(hash.equals(serverHash) ? "=> Hash Check: PASSED (match server file)"
                                                           : "=> Hash Check: FAILED (differs from server file)");
            }
        } catch (Exception e) {
            System.out.println("Could not generate hash: " + e);
        }
        System.out.println("------------------------------------------------");
    }

    private static String sha256(File f) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream fis = new FileInputStream(f)) {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = fis.read(buffer)) != -1) digest.update(buffer, 0, n);
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : digest.digest()) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}

class DownloadWorker implements Runnable {
    private final int id;
    private final String ip, filename, savePath, command;
    private final int port;
    private final long offset, length;
    volatile boolean ok = false; // main อ่านค่านี้หลัง join

    public DownloadWorker(int id, String ip, int port, String filename,
                          long offset, long length, String savePath, String command) {
        this.id = id; this.ip = ip; this.port = port; this.filename = filename;
        this.offset = offset; this.length = length; this.savePath = savePath;
        this.command = command;
    }

    // อ่านทีละ byte จนเจอ \n  (ห้ามใช้ BufferedReader เพราะมันจะดูดข้อมูลไฟล์ไปด้วย)
    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') break;
            buf.write(b);
        }
        if (b == -1 && buf.size() == 0) return null;
        return buf.toString(StandardCharsets.UTF_8).trim(); // trim ตัด \r ทิ้ง
    }

    @Override
    public void run() {
        try (Socket socket = new Socket(ip, port);
             PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
             InputStream in = socket.getInputStream();
             RandomAccessFile raf = new RandomAccessFile(savePath, "rw")) {

            out.println(command + " " + filename + " " + offset + " " + length);

            String header = readLine(in);
            if (header == null || !header.startsWith("OK ")) {
                System.out.println("Worker " + id + " got error: " + header);
                return;
            }
            long expected = Long.parseLong(header.substring(3).trim());
            if (expected != length) {
                System.out.println("Worker " + id + " length mismatch: asked " + length + ", server " + expected);
                return;
            }

            raf.seek(offset);
            byte[] buffer = new byte[8192];
            long received = 0;
            while (received < length) {
                int n = in.read(buffer, 0, (int) Math.min(buffer.length, length - received));
                if (n == -1) break;
                raf.write(buffer, 0, n);
                received += n;
            }
            ok = (received == length);
            if (!ok) System.out.println("Worker " + id + " incomplete: " + received + "/" + length);
        } catch (IOException | NumberFormatException e) {
            System.out.println("Worker " + id + " failed: " + e);
        }
    }
}