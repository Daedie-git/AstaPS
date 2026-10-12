package emu.grasscutter.server.game;

import emu.grasscutter.Grasscutter;
import emu.grasscutter.utils.Utils;
import io.netty.buffer.*;
import io.netty.channel.DefaultEventLoop;
import java.net.InetSocketAddress;
import java.util.concurrent.ConcurrentHashMap;
import kcp.highway.*;
import lombok.Getter;

public class GameSessionManager {
    @Getter private static final DefaultEventLoop logicThread = new DefaultEventLoop();
    private static final ConcurrentHashMap<Ukcp, GameSession> sessions = new ConcurrentHashMap<>();
    private static volatile boolean stopping;

    public static void closeAdmission() {
        synchronized (sessions) { stopping = true; }
    }

    public static void awaitLogout(java.util.Collection<GameSession> playerSessions) {
        var snapshot = new java.util.HashSet<>(playerSessions);
        snapshot.addAll(sessions.values());
        try {
            logoutSessions(logicThread, snapshot).get(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while awaiting session logout", interrupted);
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException failure) {
            throw new IllegalStateException("Session logout did not complete", failure);
        }
    }

    static java.util.concurrent.CompletableFuture<Void> logoutSessions(
            java.util.concurrent.Executor executor, java.util.Collection<GameSession> snapshot) {
        return java.util.concurrent.CompletableFuture.runAsync(() -> {
            var stages = new java.util.ArrayList<Runnable>();
            snapshot.forEach(session -> {
                stages.add(session::close);
                stages.add(session::handleClose);
            });
            new emu.grasscutter.server.ShutdownSequence().run(stages.toArray(Runnable[]::new));
        }, executor);
    }
    private static final KcpListener listener =
            new KcpListener() {
            @Override
            public void onConnected(Ukcp ukcp) {
                int times = 0;
                GameServer server = Grasscutter.getGameServer();
                while (server == null) {
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                        ukcp.close();
                        return;
                    }
                    if (times++ > 5) {
                        Grasscutter.getLogger().error("Service is not available!");
                        ukcp.close();
                        return;
                    }
                    server = Grasscutter.getGameServer();
                }

                GameSession conversation = new GameSession(server);

                synchronized (sessions) {
                    if (stopping) { ukcp.close(); return; }
                    sessions.put(ukcp, conversation);
                    conversation.onConnected(
                        new KcpTunnel() {
                            @Override
                            public InetSocketAddress getAddress() {
                                return ukcp.user().getRemoteAddress();
                            }

                            @Override
                            public void writeData(byte[] bytes) {
                                ByteBuf buf = Unpooled.wrappedBuffer(bytes);
                                ukcp.write(buf);
                                buf.release();
                            }

                            @Override
                            public void close() {
                                ukcp.close();
                            }

                            @Override
                            public int getSrtt() {
                                return ukcp.srtt();
                            }
                        });
                }
            }

                @Override
                public void handleReceive(ByteBuf buf, Ukcp kcp) {
                    if (stopping) return;
                    var byteData = Utils.byteBufToArray(buf);
                    logicThread.execute(
                            () -> {
                                try {
                                    var conversation = sessions.get(kcp);
                                    if (conversation != null && !stopping) {
                                        conversation.handleReceive(byteData);
                                    }
                                } catch (Exception e) {
                                    e.printStackTrace();
                                }
                            });
                }

                @Override
                public void handleException(Throwable ex, Ukcp ukcp) {}

                @Override
                public void handleClose(Ukcp ukcp) {
                    GameSession conversation = sessions.get(ukcp);
                    if (conversation != null) {
                        logicThread.execute(() -> {
                            try {
                                conversation.handleClose();
                            } catch (Exception e) {
                                Grasscutter.getLogger().error("Error during session cleanup", e);
                            } finally {
                                sessions.remove(ukcp, conversation);
                            }
                        });
                    }
                }
            };

    public static KcpListener getListener() {
        return listener;
    }

    public interface KcpTunnel {
        InetSocketAddress getAddress();

        void writeData(byte[] bytes);

        void close();

        int getSrtt();
    }

    interface KcpChannel {
        void onConnected(KcpTunnel tunnel);

        void handleClose();

        void handleReceive(byte[] bytes);
    }
}
