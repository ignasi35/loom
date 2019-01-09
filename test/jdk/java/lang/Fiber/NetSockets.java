/*
 * Copyright (c) 2018, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

/**
 * @test
 * @run testng NetSockets
 * @summary Basic tests for Fibers using java.net.Socket
 */

import java.io.*;
import java.net.*;

import org.testng.annotations.Test;
import org.testng.annotations.DataProvider;
import static org.testng.Assert.*;

@Test
public class NetSockets {

    private static final long DELAY = 2000;

    private interface TestCase {
        void run() throws IOException;
    }

    private void test(TestCase test) {
        Fiber.schedule(() -> { test.run(); return null; }).join();
    }

    /**
     * Cancel a fiber in connect
     */
    public void testSocketConnectCancel1() {
        test(() -> {
            try (var listener = new ServerSocket()) {
                listener.bind(new InetSocketAddress( InetAddress.getLocalHost(), 0));
                Fiber.current().map(Fiber::cancel);
                Socket s = new Socket();
                try {
                    s.connect(listener.getLocalSocketAddress());
                    assertTrue(false);
                } catch (IOException expected) {
                } finally {
                    s.close();
                }
            }
        });
    }

    /**
     * Socket read/write, no blocking
     */
    public void testSocketReadWrite1() {
        test(() -> {
            try (var connection = new Connection()) {
                Socket s1 = connection.socket1();
                Socket s2 = connection.socket2();

                // write should not block
                byte[] ba = "XXX".getBytes("UTF-8");
                s1.getOutputStream().write(ba);

                // read should not block
                ba = new byte[10];
                int n = s2.getInputStream().read(ba);
                assertTrue(n > 0);
                assertTrue(ba[0] == 'X');
            }
        });
    }

    /**
     * Fiber blocks in Socket read
     */
    public void testSocketReadWrite2() {
        test(() -> {
            try (var connection = new Connection()) {
                Socket s1 = connection.socket1();
                Socket s2 = connection.socket2();

                // schedule write
                byte[] ba = "XXX".getBytes("UTF-8");
                ScheduledWriter.schedule(s1, ba, DELAY);

                // read should block
                ba = new byte[10];
                int n = s2.getInputStream().read(ba);
                assertTrue(n > 0);
                assertTrue(ba[0] == 'X');
            }
        });
    }

    /**
     * Fiber blocks in Socket write
     */
    public void testSocketReadWrite3() {
        test(() -> {
            try (var connection = new Connection()) {
                Socket s1 = connection.socket1();
                Socket s2 = connection.socket2();

                // schedule thread to read to EOF
                ScheduledReader.schedule(s2, true, DELAY);

                // write should block
                byte[] ba = new byte[100*1024];
                OutputStream out = s1.getOutputStream();
                for (int i=0; i<1000; i++) {
                    out.write(ba);
                }
            }
        });
    }

    /**
     * Socket close while Fiber blocked in read
     */
    public void testSocketReadAsyncClose() {
        test(() -> {
            try (var connection = new Connection()) {
                Socket s = connection.socket1();
                ScheduledCloser.schedule(s, DELAY);
                try {
                    int n = s.getInputStream().read();
                    throw new RuntimeException("read returned " + n);
                } catch (SocketException expected) { }
            }
        });
    }

    /**
     * Socket close while Fiber blocked in write
     */
    public void testSocketWriteAsyncClose() {
        test(() -> {
            try (var connection = new Connection()) {
                Socket s = connection.socket1();
                ScheduledCloser.schedule(s, DELAY);
                try {
                    byte[] ba = new byte[100*10024];
                    OutputStream out = s.getOutputStream();
                    for (;;) {
                        out.write(ba);
                    }
                } catch (SocketException expected) { }
            }
        });
    }

    /**
     * Cancel a fiber blocked in read
     */
    public void testSocketReadCancel() {
        test(() -> {
            try (var connection = new Connection()) {
                var fiber = Fiber.current().orElseThrow();
                ScheduledCanceller.schedule(fiber, DELAY);
                Socket s = connection.socket1();
                try {
                    int n = s.getInputStream().read();
                    throw new RuntimeException("read returned " + n);
                } catch (SocketException expected) { }
            }
        });
    }

    /**
     * Cancel a fiber blocked in write
     */
    public void testSocketWriteCancel() {
        test(() -> {
            try (var connection = new Connection()) {
                var fiber = Fiber.current().orElseThrow();
                ScheduledCanceller.schedule(fiber, DELAY);
                Socket s = connection.socket1();
                try {
                    byte[] ba = new byte[100*10024];
                    OutputStream out = s.getOutputStream();
                    for (;;) {
                        out.write(ba);
                    }
                } catch (SocketException expected) { }
            }
        });
    }

    /**
     * ServerSocket accept, no blocking
     */
    public void testServerSocketCAccept1() {
        test(() -> {
            try (var listener = new ServerSocket(0)) {
                var socket1 = new Socket(listener.getInetAddress(), listener.getLocalPort());
                // accept should not block
                var socket2 = listener.accept();
                socket1.close();
                socket2.close();
            }
        });
    }

    /**
     * Fiber blocks in ServerSocket.accept
     */
    public void testServerSocketAccept2() {
        test(() -> {
            try (var listener = new ServerSocket(0)) {
                var socket1 = new Socket();
                ScheduledConnector.schedule(socket1, listener.getLocalSocketAddress(), DELAY);
                // accept will block
                var socket2 = listener.accept();
                socket1.close();
                socket2.close();
            }
        });
    }

    /**
     * ServerSocket close while Fiber blocked in accept
     */
    public void testServerSocketAcceptAsyncClose() {
        test(() -> {
            try (var listener = new ServerSocket(0)) {
                ScheduledCloser.schedule(listener, DELAY);
                try {
                    listener.accept().close();
                    throw new RuntimeException("connection accepted???");
                } catch (SocketException expected) { }
            }
        });
    }

    /**
     * Fiber cancelled while blocked in ServerSocket.accept
     */
    public void testServerSocketAcceptCancel() {
        test(() -> {
            try (var listener = new ServerSocket(0)) {
                var fiber = Fiber.current().orElseThrow();
                ScheduledCanceller.schedule(fiber, DELAY);
                try {
                    listener.accept().close();
                    throw new RuntimeException("connection accepted???");
                } catch (SocketException expected) { }
            }
        });
    }

    // -- supporting classes --

    /**
     * Creates a loopback connection
     */
    static class Connection implements Closeable {
        private final ServerSocket ss;
        private final Socket s1;
        private final Socket s2;
        Connection() throws IOException {
            ServerSocket ss = new ServerSocket();
            var lh = InetAddress.getLocalHost();
            ss.bind(new InetSocketAddress(lh, 0));
            Socket s = new Socket();
            s.connect(ss.getLocalSocketAddress());

            this.ss = ss;
            this.s1 = s;
            this.s2 = ss.accept();
        }
        Socket socket1() {
            return s1;
        }
        Socket socket2() {
            return s2;
        }
        @Override
        public void close() throws IOException {
            if (ss != null) ss.close();
            if (s1 != null) s1.close();
            if (s2 != null) s2.close();
        }
    }

    /**
     * Closes a socket after a delay
     */
    static class ScheduledCloser implements Runnable {
        private final Closeable c;
        private final long delay;
        ScheduledCloser(Closeable c, long delay) {
            this.c = c;
            this.delay = delay;
        }
        @Override
        public void run() {
            try {
                Thread.sleep(delay);
                c.close();
            } catch (Exception e) { }
        }
        static void schedule(Closeable c, long delay) {
            new Thread(new ScheduledCloser(c, delay)).start();
        }
    }

    /**
     * Reads from a socket, and to EOF, after a delay
     */
    static class ScheduledReader implements Runnable {
        private final Socket s;
        private final boolean readAll;
        private final long delay;

        ScheduledReader(Socket s, boolean readAll, long delay) {
            this.s = s;
            this.readAll = readAll;
            this.delay = delay;
        }

        @Override
        public void run() {
            try {
                Thread.sleep(delay);
                byte[] ba = new byte[8192];
                InputStream in = s.getInputStream();
                for (;;) {
                    int n = in.read(ba);
                    if (n == -1 || !readAll)
                        break;
                }
            } catch (Exception e) { }
        }

        static void schedule(Socket s, boolean readAll, long delay) {
            new Thread(new ScheduledReader(s, readAll, delay)).start();
        }
    }

    /**
     * Writes to a socket after a delay
     */
    static class ScheduledWriter implements Runnable {
        private final Socket s;
        private final byte[] ba;
        private final long delay;

        ScheduledWriter(Socket s, byte[] ba, long delay) {
            this.s = s;
            this.ba = ba.clone();
            this.delay = delay;
        }

        @Override
        public void run() {
            try {
                Thread.sleep(delay);
                s.getOutputStream().write(ba);
            } catch (Exception e) { }
        }

        static void schedule(Socket s, byte[] ba, long delay) {
            new Thread(new ScheduledWriter(s, ba, delay)).start();
        }
    }

    /**
     * Establish a connection to a socket address after a delay
     */
    static class ScheduledConnector implements Runnable {
        private final Socket socket;
        private final SocketAddress address;
        private final long delay;

        ScheduledConnector(Socket socket, SocketAddress address, long delay) {
            this.socket = socket;
            this.address = address;
            this.delay = delay;
        }

        @Override
        public void run() {
            try {
                Thread.sleep(delay);
                socket.connect(address);
            } catch (Exception e) { }
        }

        static void schedule(Socket socket, SocketAddress address, long delay) {
            new Thread(new ScheduledConnector(socket, address, delay)).start();
        }
    }

    /**
     * Cancel a fiber after a delay
     */
    static class ScheduledCanceller implements Runnable {
        private final Fiber fiber;
        private final long delay;

        ScheduledCanceller(Fiber fiber, long delay) {
            this.fiber = fiber;
            this.delay = delay;
        }

        @Override
        public void run() {
            try {
                Thread.sleep(delay);
                fiber.cancel();
            } catch (Exception e) { }
        }

        static void schedule(Fiber fiber, long delay) {
            new Thread(new ScheduledCanceller(fiber, delay)).start();
        }
    }
}
