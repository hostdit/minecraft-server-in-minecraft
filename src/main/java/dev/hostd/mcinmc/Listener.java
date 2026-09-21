package dev.hostd.mcinmc;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class Listener {

    private static final Map<Door, ServerSocket> SOCKETS = new ConcurrentHashMap<>();
    private static volatile String lanAddress = "this machine";

    private Listener() {
    }

    public static synchronized void open(Door door) {
        close(door);
        ServerSocket socket;
        try {
            socket = new ServerSocket(door.port());
        } catch (IOException e) {
            System.out.println("[mcinmc] " + door.label() + " door could not bind " + door.port() + ": " + e.getMessage());
            return;
        }
        SOCKETS.put(door, socket);
        System.out.println("[mcinmc] " + door.label() + " door open on " + door.port() + " in pid " + ProcessHandle.current().pid());
        Thread accept = new Thread(() -> acceptLoop(door, socket), "mcinmc-accept-" + door.label());
        accept.setDaemon(true);
        accept.start();
        if (lanAddress.equals("this machine")) {
            Thread resolve = new Thread(Listener::findLanAddress, "mcinmc-lan");
            resolve.setDaemon(true);
            resolve.start();
        }
    }

    public static synchronized void close(Door door) {
        ServerSocket socket = SOCKETS.remove(door);
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    public static boolean bound(Door door) {
        return SOCKETS.containsKey(door);
    }

    public static String lanAddress() {
        return lanAddress;
    }

    private static void acceptLoop(Door door, ServerSocket socket) {
        while (SOCKETS.get(door) == socket) {
            try {
                Socket client = socket.accept();
                client.setTcpNoDelay(true);
                Thread worker = new Thread(new Session(client, door), "mcinmc-session");
                worker.setDaemon(true);
                worker.start();
            } catch (IOException e) {
                return;
            }
        }
    }

    private static void findLanAddress() {
        try {
            for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nic.isUp() || nic.isLoopback() || nic.isVirtual()) {
                    continue;
                }
                for (InetAddress address : Collections.list(nic.getInetAddresses())) {
                    if (address instanceof Inet4Address && address.isSiteLocalAddress()) {
                        lanAddress = address.getHostAddress();
                        return;
                    }
                }
            }
        } catch (IOException e) {
            System.out.println("[mcinmc] could not read network interfaces: " + e.getMessage());
        }
    }
}
