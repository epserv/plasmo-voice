package su.plo.voice.client.socket;

import net.minecraft.client.Minecraft;
import su.plo.voice.client.VoiceClient;
import su.plo.voice.client.gui.VoiceNotAvailableScreen;
import su.plo.voice.client.gui.VoiceSettingsScreen;
import su.plo.voice.protocol.packets.Packet;
import su.plo.voice.protocol.packets.udp.MessageUdp;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;

public class SocketClientUDP extends Thread {
    private static final Minecraft client = Minecraft.getInstance();

    private final InetAddress addr;
    public final int port;
    private final DatagramSocket socket;
    public boolean authorized;
    private final SocketClientAuth auth;
    private final SocketClientUDPListener clientQueue;
    public final SocketClientPing ping;

    public long keepAlive;

    public SocketClientUDP() throws IOException {
        this.addr = InetAddress.getByName(VoiceClient.getServerConfig().getIp());
        this.port = VoiceClient.getServerConfig().getPort();
        this.socket = new DatagramSocket();
        this.socket.setTrafficClass(0x04); // IPTOS_RELIABILITY

        this.keepAlive = System.currentTimeMillis();

        this.auth = new SocketClientAuth(this);
        this.auth.start();

        this.clientQueue = new SocketClientUDPListener(this);
        this.clientQueue.start();

        ping = new SocketClientPing(this);
        ping.start();
    }

    public void checkTimeout() {
        if (System.currentTimeMillis() - this.keepAlive > 7000L) {
            this.ping.timedOut = true;

            if (client.screen instanceof VoiceNotAvailableScreen screen) {
                screen.setConnecting();
            } else if (client.screen instanceof VoiceSettingsScreen) {
                client.execute(() -> {
                    VoiceNotAvailableScreen screen = new VoiceNotAvailableScreen();
                    screen.setConnecting();
                    client.setScreen(screen);
                });
            }
        }

        if (System.currentTimeMillis() - this.keepAlive > 30000L) {
            this.ping.timedOut = false;
            VoiceClient.LOGGER.info("UDP timed out");
            VoiceClient.disconnect();

            if (client.screen instanceof VoiceNotAvailableScreen screen) {
                screen.setCannotConnect();
            } else if (client.screen instanceof VoiceSettingsScreen) {
                client.execute(() -> {
                    VoiceNotAvailableScreen screen = new VoiceNotAvailableScreen();
                    screen.setCannotConnect();
                    client.setScreen(screen);
                });
            }
        }
    }

    public boolean isClosed() {
        return socket.isClosed();
    }

    public void close() {
        if (client.screen instanceof VoiceSettingsScreen) {
            client.execute(() ->
                client.setScreen(new VoiceNotAvailableScreen())
            );
        }

        VoiceClient.LOGGER.info("UDP closed");

        socket.close();
        if(this.auth != null) {
            if(!this.auth.isInterrupted()) {
                this.auth.interrupt();
            }
        }

        if(this.ping != null) {
            if(!this.ping.isInterrupted()) {
                this.ping.interrupt();
            }
        }

        if(this.clientQueue != null) {
            this.clientQueue.interrupt();
        }
    }

    public void send(Packet packet, long sequenceNumber) {
        try {
            byte[] data = MessageUdp.write(packet, null, sequenceNumber);
            socket.send(new DatagramPacket(data, data.length, addr, port));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void run() {
        try {
            while (!this.socket.isClosed()) {
                MessageUdp packet = MessageUdp.read(this.socket);
                if (packet != null) {
                    clientQueue.queue.offer(packet);
                }

                synchronized (clientQueue) {
                    clientQueue.notify();
                }
            }
        } catch (SocketException e) {
            if(!e.getMessage().equals("Socket closed")) {
                e.printStackTrace();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
