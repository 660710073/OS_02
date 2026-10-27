import java.io.*;
import java.net.*;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

public class FileClient {
    private static final String SERVER_IP = "127.0.0.1";
    private static final int PORT = 8080;
    
    private static final int NUM_WORKERS = 10; 
    private static final boolean USE_NIO = true; 
    

    public static void main(String[] args) {
        System.out.println("--- File name on Server ---");
        // 1. เรียกดูไฟล์และรับรายชื่อไฟล์ทั้งหมดมาเก็บไว้ในตัวแปร fileList
        List<String> fileList = getServerFiles();
        System.out.println("---------------------------");

        if (fileList.isEmpty()) {
            System.out.println("No File Server");
            return;
        }

        // 2. นำรายชื่อไฟล์ที่ได้มาวนลูปดาวน์โหลดทีละไฟล์
        for (String fileName : fileList) {
            String savePath = "./client_files/" + fileName;
            long totalSize = getFileSize(fileName);
            
            if (totalSize <= 0) continue; // ถ้าขนาดไฟล์ผิดปกติ ให้ข้ามไปไฟล์ถัดไป

            System.out.println("\nDownloading: " + fileName + " (" + totalSize + " bytes)");
            
            long chunkSize = totalSize / NUM_WORKERS;
            Thread[] workers = new Thread[NUM_WORKERS];
            long startTime = System.currentTimeMillis();

            for (int i = 0; i < NUM_WORKERS; i++) {
                long offset = i * chunkSize;
                long length = (i == NUM_WORKERS - 1) ? (totalSize - offset) : chunkSize;
                
                workers[i] = new Thread(new DownloadWorker(SERVER_IP, PORT, fileName, offset, length, savePath, USE_NIO));
                workers[i].start();
            }

            for (Thread worker : workers) {
                try { worker.join(); } catch (InterruptedException e) {}
            }

            long endTime = System.currentTimeMillis();
            System.out.println("Download Complete in " + ((endTime - startTime) / 1000.0) + " seconds");
            
            verifyFile(savePath, totalSize);
        }
    }

    // แก้ไขให้เก็บชื่อไฟล์ใส่ List แล้ว return กลับไป
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
                
                // ถอดเอาเฉพาะ "ชื่อไฟล์" ออกมาจากข้อความ "FILE <name> <size>"
                if (response.startsWith("FILE ")) {
                    String[] parts = response.split(" ");
                    foundFiles.add(parts[1]); 
                }
            }
        } catch (IOException e) {
            System.out.println("Cannot connect to server.");
        }
        return foundFiles;
    }

    private static long getFileSize(String filename) {
        try (Socket socket = new Socket(SERVER_IP, PORT);
             PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
             BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {
            out.println("INFO " + filename);
            String response = in.readLine();
            if (response != null && response.startsWith("SIZE")) return Long.parseLong(response.split(" ")[1]);
        } catch (IOException e) {}
        return -1;
    }

    // เมธอดตรวจสอบ Size และสร้าง Hash (SHA-256)[cite: 2]
    private static void verifyFile(String filePath, long expectedSize) {
        File file = new File(filePath);
        System.out.println("--- Validation (File Verification) ---");
        System.out.println("Expected Size: " + expectedSize + " bytes");
        System.out.println("Actual Size:   " + file.length() + " bytes");
        
        if (file.length() == expectedSize) {
            System.out.println("=> Size Check: PASSED");
        } else {
            System.out.println("=> Size Check: FAILED");
        }

        try (FileInputStream fis = new FileInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = fis.read(buffer)) != -1) {
                digest.update(buffer, 0, bytesRead);
            }
            byte[] hashBytes = digest.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : hashBytes) {
                sb.append(String.format("%02x", b));
            }
            System.out.println("File SHA-256 Hash: " + sb.toString());
        } catch (Exception e) {
            System.out.println("Could not generate hash.");
        }
        System.out.println("------------------------------------------------");
    }
}

class DownloadWorker implements Runnable {
    private String ip, filename, savePath;
    private int port;
    private long offset, length;
    private boolean useNio;

    public DownloadWorker(String ip, int port, String filename, long offset, long length, String savePath, boolean useNio) {
        this.ip = ip; this.port = port; this.filename = filename;
        this.offset = offset; this.length = length; this.savePath = savePath; 
        this.useNio = useNio;
    }

    @Override
    public void run() {
        try (Socket socket = new Socket(ip, port);
             PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
             InputStream in = socket.getInputStream();
             RandomAccessFile raf = new RandomAccessFile(savePath, "rw")) {
             
            String command = useNio ? "GETNIO" : "GET";
            out.println(command + " " + filename + " " + offset + " " + length);
            
            raf.seek(offset); 
            byte[] buffer = new byte[8192];
            long bytesReadTotal = 0;
            int bytesRead;
            
            while (bytesReadTotal < length && (bytesRead = in.read(buffer)) != -1) {
                raf.write(buffer, 0, bytesRead);
                bytesReadTotal += bytesRead;
            }
        } catch (IOException e) {}
    }
}