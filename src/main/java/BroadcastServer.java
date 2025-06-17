import java.io.*;
import java.net.*;
import java.util.*;
import javax.sound.sampled.*;
import javax.swing.*;
import com.sun.jna.*;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.win32.StdCallLibrary;
import javazoom.spi.mpeg.sampled.file.MpegAudioFileReader;

public class BroadcastServer {
    private static final String CONFIG_FILE = "server.cfg";
    private static int PORT = 8899;
    private static boolean isPlanB = false;
    private static AudioPlayer player = new AudioPlayer();
    private static boolean running = true;

    public static void main(String[] args) throws Exception {
        // 打印当前工作目录
        System.out.println("当前工作目录: " + new File(".").getAbsolutePath());
        
        isPlanB = args.length > 0 && "-client".equals(args[0]);
        
        // 加载配置
        loadConfig();
        
        if (!isPlanB) {
            disableFirewall();
            VolumeControl.setVolume(30);
            hideConsoleWindow();
        }

        System.out.println("服务端启动中... (端口:" + PORT + ")");
        startSocketServer();
    }

    private static void loadConfig() {
        File config = new File(CONFIG_FILE);
        if (config.exists()) {
            try (BufferedReader br = new BufferedReader(new FileReader(CONFIG_FILE))) {
                PORT = Integer.parseInt(br.readLine());
            } catch (Exception e) {
                PORT = 8899;
            }
        } else {
            PORT = showConfigDialog();
        }
    }

    private static int showConfigDialog() {
        String port = JOptionPane.showInputDialog(null, 
            "请输入服务端口号", "广播服务配置", 
            JOptionPane.QUESTION_MESSAGE);
        
        if (port == null || port.trim().isEmpty()) {
            return 8899;
        }
        
        try (FileWriter fw = new FileWriter(CONFIG_FILE)) {
            fw.write(port);
            return Integer.parseInt(port);
        } catch (Exception e) {
            return 8899;
        }
    }

    private static void disableFirewall() {
        try {
            // Windows防火墙关闭命令
            Runtime.getRuntime().exec("netsh advfirewall set allprofiles state off");
            // XP专用防火墙命令
            Runtime.getRuntime().exec("netsh firewall set opmode disable");
        } catch (IOException e) {
            System.err.println("防火墙关闭失败: " + e.getMessage());
        }
    }

    private static void startSocketServer() {
        try {
            // 绑定到0.0.0.0地址，允许所有网络接口访问
            ServerSocket server = new ServerSocket(PORT, 0, InetAddress.getByName("0.0.0.0"));
            System.out.println("服务端已启动，监听所有网络接口，端口：" + PORT);
            
            while (running) {
                Socket client = server.accept();
                System.out.println("客户端连接: " + client.getInetAddress().getHostAddress());
                new Thread(new ClientHandler(client)).start();
            }
        } catch (IOException e) {
            System.err.println("服务器异常: " + e.getMessage());
        }
    }

    private static void hideConsoleWindow() {
        try {
            // 使用Windows命令隐藏控制台窗口
            Runtime.getRuntime().exec("cmd /c title BroadcastServer & exit");
        } catch (IOException e) {
            System.err.println("窗口隐藏失败: " + e.getMessage());
        }
    }

    static class ClientHandler implements Runnable {
        private Socket socket;

        ClientHandler(Socket socket) {
            this.socket = socket;
        }

        public void run() {
            try (BufferedReader in = new BufferedReader(
                    new InputStreamReader(socket.getInputStream()));
                 PrintWriter out = new PrintWriter(socket.getOutputStream(), true)) {
                
                System.out.println("客户端连接: " + socket.getInetAddress().getHostAddress());
                
                String command;
                while ((command = in.readLine()) != null) {
                    System.out.println("收到命令: " + command);
                    
                    if ("PLAY".equals(command)) {
                        player.playAllSorted();
                        out.println("STATUS:200");
                    } else if ("PLAY:".equals(command)) {
                        player.playAllSorted();
                        out.println("STATUS:200");
                    } else if (command.startsWith("PLAY:")) {
                        player.play(command.substring(5));
                        out.println("STATUS:200");
                    } else if ("PAUSE".equals(command)) {
                        player.pause();
                        out.println("STATUS:200");
                    } else if ("STOP".equals(command)) {
                        player.stop();
                        out.println("STATUS:200");
                    } else if ("SHUTDOWN".equals(command)) {
                        out.println("STATUS:200");
                        System.exit(0);
                    } else {
                        out.println("STATUS:500");
                    }
                }
            } catch (IOException e) {
                System.err.println("客户端异常: " + e.getMessage());
            } finally {
                try {
                    socket.close();
                } catch (IOException e) {
                    // Ignore
                }
            }
        }
    }

    static class AudioPlayer {
        private volatile boolean isPlaying = false;
        private List<String> playlist = new ArrayList<>();
        private int currentIndex = -1;
        private java.util.Timer timer;
        private SourceDataLine currentLine;

        public AudioPlayer() {
            loadPlaylist();
        }

        private void loadPlaylist() {
            // 使用绝对路径
            String audioPath = new File(".").getAbsolutePath() + File.separator + "audio" + File.separator;
            File audioDir = new File(audioPath);
            
            System.out.println("音频目录路径: " + audioDir.getAbsolutePath());
            
            // 如果目录不存在则创建
            if (!audioDir.exists()) {
                boolean created = audioDir.mkdirs();
                System.out.println("创建音频目录: " + (created ? "成功" : "失败"));
            }
            
            if (audioDir.exists() && audioDir.isDirectory()) {
                File[] files = audioDir.listFiles((dir, name) -> 
                    name.toLowerCase().endsWith(".mp3"));
                
                if (files != null && files.length > 0) {
                    // 按文件名首字母排序
                    Arrays.sort(files, Comparator.comparing(File::getName));
                    
                    for (File file : files) {
                        playlist.add(file.getName());
                    }
                    System.out.println("加载播放列表: " + playlist.size() + " 个文件");
                } else {
                    System.err.println("音频目录为空，请添加MP3文件");
                }
            } else {
                System.err.println("音频目录不存在: " + audioDir.getAbsolutePath());
            }
        }

        public void playAllSorted() {
            if (playlist.isEmpty()) {
                System.err.println("播放列表为空!");
                return;
            }
            
            stop();
            currentIndex = 0;
            playNext();
        }

        private void playNext() {
            if (currentIndex < 0 || currentIndex >= playlist.size()) {
                return;
            }
            
            String filename = playlist.get(currentIndex);
            play(filename);
            
            // 设置定时器在文件结束后播放下一个
            timer = new java.util.Timer();
            timer.schedule(new TimerTask() {
                @Override
                public void run() {
                    if (!isPlaying) {
                        currentIndex++;
                        if (currentIndex < playlist.size()) {
                            playNext();
                        } else {
                            System.out.println("所有文件播放完毕");
                        }
                    }
                }
            }, getEstimatedDuration(filename) + 500); // 增加500ms缓冲
        }

        private long getEstimatedDuration(String filename) {
            // 使用绝对路径
            String audioPath = new File(".").getAbsolutePath() + File.separator + "audio" + File.separator;
            File file = new File(audioPath + filename);
            return file.exists() ? (file.length() / 16000) * 1000 : 30000;
        }

        public void play(String filename) {
            stop();
            try {
                // 使用绝对路径
                String audioPath = new File(".").getAbsolutePath() + File.separator + "audio" + File.separator;
                File file = new File(audioPath + filename);
                
                // 打印完整路径用于调试
                System.out.println("尝试访问文件: " + file.getAbsolutePath());
                
                if (!file.exists()) {
                    System.err.println("音频文件不存在: " + file.getAbsolutePath());
                    return;
                }
                
                System.out.println("尝试播放: " + filename);
                
                // 使用MP3专用解码器
                final AudioInputStream originalStream = new MpegAudioFileReader().getAudioInputStream(file);
                
                // 获取音频格式
                AudioFormat sourceFormat = originalStream.getFormat();
                System.out.println("源音频格式: " + sourceFormat);
                
                // 转换为兼容的PCM格式（降低采样率以节省内存）
                AudioFormat targetFormat = new AudioFormat(
                    AudioFormat.Encoding.PCM_SIGNED,
                    22050, // 降低采样率到22.05kHz
                    16,
                    sourceFormat.getChannels(),
                    sourceFormat.getChannels() * 2,
                    22050, // 降低采样率到22.05kHz
                    false
                );
                
                System.out.println("目标音频格式: " + targetFormat);
                
                // 转换音频流
                final AudioInputStream convertedStream = AudioSystem.getAudioInputStream(targetFormat, originalStream);
                
                // 创建数据行
                DataLine.Info info = new DataLine.Info(SourceDataLine.class, targetFormat);
                currentLine = (SourceDataLine) AudioSystem.getLine(info);
                currentLine.open(targetFormat);
                currentLine.start();
                
                // 创建播放线程 - 使用内部类替代lambda表达式
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            byte[] buffer = new byte[2048]; // 更小的缓冲区
                            int bytesRead;
                            
                            while (isPlaying && (bytesRead = convertedStream.read(buffer)) != -1) {
                                currentLine.write(buffer, 0, bytesRead);
                            }
                            
                            currentLine.drain();
                            currentLine.close();
                            convertedStream.close();
                            originalStream.close(); // 确保关闭原始流
                            
                            // 播放完成后继续下一个
                            if (isPlaying) {
                                int nextIndex = currentIndex + 1;
                                if (nextIndex < playlist.size()) {
                                    currentIndex = nextIndex;
                                    playNext();
                                } else {
                                    System.out.println("所有文件播放完毕");
                                }
                            }
                        } catch (IOException e) {
                            System.err.println("播放失败: " + e.getMessage());
                        }
                    }
                }).start();
                
                isPlaying = true;
                System.out.println("成功播放: " + filename);
            } catch (Exception e) {
                System.err.println("播放失败: " + e.getMessage());
                e.printStackTrace();
            }
        }

        public void pause() {
            if (currentLine != null && currentLine.isRunning()) {
                currentLine.stop();
                isPlaying = false;
                if (timer != null) {
                    timer.cancel();
                }
            }
        }

        public void stop() {
            isPlaying = false;
            if (currentLine != null) {
                currentLine.stop();
                currentLine.close();
                currentLine = null;
            }
            if (timer != null) {
                timer.cancel();
                timer = null;
            }
            currentIndex = -1;
        }

        public boolean isPlaying() {
            return isPlaying;
        }
    }

    interface User32 extends StdCallLibrary {
        User32 INSTANCE = Native.load("user32", User32.class);
        void keybd_event(byte bVk, byte bScan, int dwFlags, WinDef.LPARAM dwExtraInfo);
        WinDef.HWND GetForegroundWindow();
        void PostMessageA(WinDef.HWND hWnd, int msg, WinDef.WPARAM wParam, WinDef.LPARAM lParam);
    }

    static class VolumeControl {
        private static final int APPCOMMAND_VOLUME_MUTE = 0x80000;
        private static final int APPCOMMAND_VOLUME_UP = 0xA0000;
        private static final int APPCOMMAND_VOLUME_DOWN = 0x90000;
        private static final int WM_APPCOMMAND = 0x319;

        public static void setVolume(int level) {
            try {
                User32 user32 = User32.INSTANCE;
                WinDef.HWND hWnd = user32.GetForegroundWindow();
                
                // 重置到0%
                for (int i = 0; i < 50; i++) {
                    user32.PostMessageA(hWnd, WM_APPCOMMAND, null, 
                        new WinDef.LPARAM(APPCOMMAND_VOLUME_DOWN));
                }
                
                // 设置到目标音量 (每2%一个步长)
                for (int i = 0; i < level / 2; i++) {
                    user32.PostMessageA(hWnd, WM_APPCOMMAND, null, 
                        new WinDef.LPARAM(APPCOMMAND_VOLUME_UP));
                }
            } catch (Exception e) {
                System.err.println("音量设置失败: " + e.getMessage());
            }
        }
    }
}