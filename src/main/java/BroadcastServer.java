import java.io.*;
import java.net.*;
import java.util.*;
import javax.swing.*;
import com.sun.jna.*;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.win32.StdCallLibrary;

public class BroadcastServer {
    private static final String CONFIG_FILE = "server.cfg";
    private static int PORT = 8899;
    private static boolean isPlanB = false;
    private static AudioPlayer player = new AudioPlayer();
    private static boolean running = true;
    
    private static final int BROADCAST_PORT = 8888; // 广播端口
    private static boolean broadcasting = true;
    
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
            startBroadcasting(); // 启动广播线程
        }

        System.out.println("服务端启动中... (端口:" + PORT + ")");
        startSocketServer();
    }

    // 广播服务端地址
    private static void startBroadcasting() {
        new Thread(() -> {
            try (DatagramSocket socket = new DatagramSocket()) {
                socket.setBroadcast(true);
                
                // 获取本机局域网IP地址
                String localIp = getLocalIP();
                if (localIp == null) {
                    System.err.println("无法获取本机局域网IP，广播功能禁用");
                    return;
                }
                
                String message = "AudioServerDiscovery|" + localIp + "|" + PORT;
                byte[] buffer = message.getBytes();
                
                InetAddress broadcastAddress = InetAddress.getByName("255.255.255.255");
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length, 
                                                          broadcastAddress, BROADCAST_PORT);
                
                System.out.println("开始广播服务端地址: " + message);
                
                // 每5秒广播一次
                while (broadcasting) {
                    try {
                        socket.send(packet);
                        Thread.sleep(5000);
                    } catch (Exception e) {
                        System.err.println("广播发送失败: " + e.getMessage());
                    }
                }
            } catch (Exception e) {
                System.err.println("广播初始化失败: " + e.getMessage());
            }
        }).start();
    }
    
    // 获取本机局域网IP（优先选择192.168.137.x网段）
    private static String getLocalIP() {
        try {
            // 优先选择192.168.137.x网段的地址（Windows热点默认网段）
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement();
                if (iface.isLoopback() || !iface.isUp()) 
                    continue;
                
                // 检查是否是无线网络接口（热点通常使用无线接口）
                String ifaceName = iface.getName().toLowerCase();
                boolean isWireless = ifaceName.contains("wireless") || 
                                    ifaceName.contains("wifi") || 
                                    ifaceName.contains("wlan");
                
                Enumeration<InetAddress> addresses = iface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    if (addr instanceof Inet4Address) {
                        String ip = addr.getHostAddress();
                        
                        // 优先选择192.168.137.x地址
                        if (ip.startsWith("192.168.137.")) {
                            return ip;
                        }
                    }
                }
            }
            
            // 如果没有找到热点IP，则返回第一个可用的IPv4地址
            interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement();
                if (iface.isLoopback() || !iface.isUp()) 
                    continue;
                
                Enumeration<InetAddress> addresses = iface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    if (addr instanceof Inet4Address) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (SocketException e) {
            System.err.println("获取网络接口失败: " + e.getMessage());
        }
        return null;
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
                        broadcasting = false; // 停止广播
                        try {
                            Thread.sleep(1000);   // 给广播线程时间退出
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
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
        private volatile boolean isPaused = false;
        private List<String> playlist = new ArrayList<>();
        private int currentIndex = -1;
        private java.util.Timer timer;
        
        // 用于ffplay播放控制
        private Process ffplayProcess;
        private String currentPlayingFile = null;
        private Thread playThread;
        private long pausePosition = 0; // 暂停位置（毫秒）
        private String ffplayPath;
        private long playbackStartTime; // 记录播放开始时间

        public AudioPlayer() {
            // 尝试在同目录下查找ffplay或ffmpeg
            ffplayPath = findFFplay();
            loadPlaylist();
        }

        private String findFFplay() {
            // 检查当前目录下的ffplay.exe或ffmpeg.exe
            File currentDir = new File(".");
            File[] files = currentDir.listFiles();
            
            if (files != null) {
                for (File file : files) {
                    String name = file.getName().toLowerCase();
                    if (name.equals("ffplay.exe") || name.equals("ffmpeg.exe")) {
                        return file.getAbsolutePath();
                    }
                }
            }
            
            // 如果在当前目录没找到，尝试在PATH中查找
            String path = System.getenv("PATH");
            if (path != null) {
                String[] paths = path.split(File.pathSeparator);
                for (String p : paths) {
                    File ffplay = new File(p, "ffplay.exe");
                    if (ffplay.exists()) return ffplay.getAbsolutePath();
                    
                    File ffmpeg = new File(p, "ffmpeg.exe");
                    if (ffmpeg.exists()) return ffmpeg.getAbsolutePath();
                }
            }
            
            return null;
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
                    name.toLowerCase().endsWith(".mp3") || 
                    name.toLowerCase().endsWith(".wav") || 
                    name.toLowerCase().endsWith(".ogg"));
                
                if (files != null && files.length > 0) {
                    // 按文件名首字母排序
                    Arrays.sort(files, Comparator.comparing(File::getName));
                    
                    for (File file : files) {
                        playlist.add(file.getName());
                    }
                    System.out.println("加载播放列表: " + playlist.size() + " 个文件");
                } else {
                    System.err.println("音频目录为空，请添加音频文件");
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
            
            // 如果当前处于暂停状态，则继续播放
            if (isPaused && currentPlayingFile != null) {
                resume();
                return;
            }
            
            // 如果停止后再次播放，从头开始
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
            // 如果请求播放的文件与当前暂停的文件相同，则继续播放
            if (isPaused && filename.equals(currentPlayingFile)) {
                resume();
                return;
            }
            
            // 否则开始新的播放
            stop();
            
            if (ffplayPath == null) {
                System.err.println("未找到ffplay或ffmpeg，无法播放音频");
                return;
            }
            
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
                
                System.out.println("尝试播放: " + filename + " 使用: " + ffplayPath);
                
                // 构建ffplay命令
                List<String> command = new ArrayList<>();
                command.add(ffplayPath);
                command.add("-nodisp");   // 不显示窗口
                command.add("-autoexit"); // 播放完成后自动退出
                command.add("-loglevel"); // 减少日志输出
                command.add("quiet");
                command.add("\"" + file.getAbsolutePath() + "\""); // 添加引号处理路径空格
                
                // 启动ffplay进程
                ProcessBuilder pb = new ProcessBuilder(command);
                ffplayProcess = pb.start();
                
                // 保存当前播放的文件
                currentPlayingFile = filename;
                isPlaying = true;
                isPaused = false;
                pausePosition = 0;
                playbackStartTime = System.currentTimeMillis(); // 记录开始时间
                
                // 创建监控线程
                playThread = new Thread(() -> {
                    try {
                        // 等待进程结束
                        int exitCode = ffplayProcess.waitFor();
                        
                        if (exitCode != 0) {
                            System.err.println("ffplay播放失败，退出码: " + exitCode);
                            
                            // 读取错误流
                            try (BufferedReader reader = new BufferedReader(
                                new InputStreamReader(ffplayProcess.getErrorStream()))) {
                                String line;
                                while ((line = reader.readLine()) != null) {
                                    System.err.println("FFPLAY ERROR: " + line);
                                }
                            } catch (IOException e) {
                                // 忽略
                            }
                        }
                        
                        // 如果播放完成（不是暂停或停止）
                        if (isPlaying && !isPaused) {
                            System.out.println("文件播放完成: " + currentPlayingFile);
                            
                            // 播放完成后继续下一个
                            if (isPlaying) {
                                int nextIndex = currentIndex + 1;
                                if (nextIndex < playlist.size()) {
                                    currentIndex = nextIndex;
                                    playNext();
                                } else {
                                    System.out.println("所有文件播放完毕");
                                    // 重置播放状态
                                    resetPlayState();
                                }
                            }
                        }
                    } catch (InterruptedException e) {
                        System.err.println("播放线程被中断: " + e.getMessage());
                    }
                });
                
                playThread.start();
                System.out.println("成功播放: " + filename);
            } catch (Exception e) {
                System.err.println("播放失败: " + e.getMessage());
                e.printStackTrace();
                resetPlayState();
            }
        }
        
        // 继续播放（从暂停位置）
        private void resume() {
            if (!isPaused || currentPlayingFile == null) {
                System.err.println("没有暂停的播放可以恢复");
                return;
            }
            
            if (ffplayPath == null) {
                System.err.println("未找到ffplay或ffmpeg，无法播放音频");
                return;
            }
            
            try {
                System.out.println("恢复播放: " + currentPlayingFile + " 从位置: " + pausePosition + "ms");
                
                // 使用绝对路径
                String audioPath = new File(".").getAbsolutePath() + File.separator + "audio" + File.separator;
                File file = new File(audioPath + currentPlayingFile);
                
                if (!file.exists()) {
                    System.err.println("音频文件不存在: " + file.getAbsolutePath());
                    return;
                }
                
                // 构建ffplay命令（带起始位置）
                List<String> command = new ArrayList<>();
                command.add(ffplayPath);
                command.add("-nodisp");
                command.add("-autoexit");
                command.add("-ss");
                command.add(String.valueOf(pausePosition / 1000.0)); // 转换为秒
                command.add("\"" + file.getAbsolutePath() + "\""); // 添加引号处理路径空格
                
                // 启动ffplay进程
                ProcessBuilder pb = new ProcessBuilder(command);
                ffplayProcess = pb.start();
                
                isPlaying = true;
                isPaused = false;
                playbackStartTime = System.currentTimeMillis(); // 记录开始时间
                
                // 创建监控线程
                playThread = new Thread(() -> {
                    try {
                        // 等待进程结束
                        int exitCode = ffplayProcess.waitFor();
                        
                        if (exitCode != 0) {
                            System.err.println("ffplay播放失败，退出码: " + exitCode);
                            
                            // 读取错误流
                            try (BufferedReader reader = new BufferedReader(
                                new InputStreamReader(ffplayProcess.getErrorStream()))) {
                                String line;
                                while ((line = reader.readLine()) != null) {
                                    System.err.println("FFPLAY ERROR: " + line);
                                }
                            } catch (IOException e) {
                                // 忽略
                            }
                        }
                        
                        // 如果播放完成（不是暂停或停止）
                        if (isPlaying && !isPaused) {
                            System.out.println("文件播放完成: " + currentPlayingFile);
                            
                            // 播放完成后继续下一个
                            if (isPlaying) {
                                int nextIndex = currentIndex + 1;
                                if (nextIndex < playlist.size()) {
                                    currentIndex = nextIndex;
                                    playNext();
                                } else {
                                    System.out.println("所有文件播放完毕");
                                    // 重置播放状态
                                    resetPlayState();
                                }
                            }
                        }
                    } catch (InterruptedException e) {
                        System.err.println("播放线程被中断: " + e.getMessage());
                    }
                });
                
                playThread.start();
                System.out.println("成功恢复播放: " + currentPlayingFile);
            } catch (Exception e) {
                System.err.println("恢复播放失败: " + e.getMessage());
                e.printStackTrace();
                resetPlayState();
            }
        }

        public void pause() {
            if (isPlaying && !isPaused) {
                // 计算实际播放时长 = 当前时间 - 开始时间
                long elapsed = System.currentTimeMillis() - playbackStartTime;
                pausePosition += elapsed; // 累加实际播放时长
                
                isPlaying = false;
                isPaused = true;
                
                System.out.println("暂停播放: " + currentPlayingFile + " 位置: " + pausePosition + "ms");
                
                // 停止ffplay进程
                if (ffplayProcess != null && ffplayProcess.isAlive()) {
                    ffplayProcess.destroy();
                }
                
                // 取消定时器
                if (timer != null) {
                    timer.cancel();
                    timer = null;
                }
            }
        }

        public void stop() {
            isPlaying = false;
            isPaused = false;
            pausePosition = 0;
            
            System.out.println("停止播放");
            
            // 停止ffplay进程
            if (ffplayProcess != null && ffplayProcess.isAlive()) {
                ffplayProcess.destroy();
            }
            
            // 取消定时器
            if (timer != null) {
                timer.cancel();
                timer = null;
            }
            
            // 重置播放索引
            currentIndex = -1;
            currentPlayingFile = null;
        }
        
        // 重置播放状态
        private void resetPlayState() {
            currentPlayingFile = null;
            pausePosition = 0;
            isPlaying = false;
            isPaused = false;
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