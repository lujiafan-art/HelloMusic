/*
 * HelloMusic - Local music server with Unix socket control plane and HTTP media stream
 * Copyright (C) 2026 Lu Jiafan
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 控制面 Unix Socket 服务器的生命周期测试：socket 文件的创建与清理。
 *
 * <p>重点回归「stop() 与 start() 的 cleanupSocketPath() 删除竞争」：删除必须由
 * {@link UnixSocketServer#stop()} 同步完成，而不是放在 {@code start()} 的 {@code finally}
 * 里异步执行。若仍走旧路径，{@code stop()} 返回后 socket 文件尚未被删除（删除发生在 accept
 * 线程上），后续新实例 {@code bind} 同一路径时会被旧实例的迟到的删除误伤。</p>
 */
class UnixSocketServerTest {

    @TempDir
    Path tempDir;

    /** stop() 返回即应同步删除 socket 文件，不依赖 start 线程异步清理。 */
    @Test
    void stopRemovesSocketFileSynchronously() throws Exception {
        Path sock = tempDir.resolve("a.sock");
        UnixSocketServer server = new UnixSocketServer(sock, null, Json.newMapper());
        Thread t = new Thread(server::start, "sock-test-1");
        t.start();
        try {
            awaitBound(server, sock, 5000);
            server.stop();
            // 关键断言：stop() 一返回，文件就应已删除（不 join start 线程）
            assertFalse(Files.exists(sock),
                    "stop() 返回后 socket 文件应被同步删除，不得依赖 start 线程异步清理");
            t.join(5000);
            assertFalse(t.isAlive(), "start 线程应已退出");
        } finally {
            server.stop(); // 幂等兜底
            t.join(2000);
        }
    }

    /** 停止后新实例应能干净地重新 bind 同一路径（依赖 stop() 已清掉旧文件）。 */
    @Test
    void restartAfterStopRebindsCleanly() throws Exception {
        Path sock = tempDir.resolve("b.sock");

        UnixSocketServer first = new UnixSocketServer(sock, null, Json.newMapper());
        Thread t1 = new Thread(first::start, "sock-test-2a");
        t1.start();
        awaitBound(first, sock, 5000);
        first.stop();
        t1.join(5000);

        UnixSocketServer second = new UnixSocketServer(sock, null, Json.newMapper());
        Thread t2 = new Thread(second::start, "sock-test-2b");
        t2.start();
        try {
            awaitBound(second, sock, 5000);
            assertTrue(Files.exists(sock), "重启后 socket 文件应重新创建");
        } finally {
            second.stop();
            t2.join(2000);
        }
        assertFalse(Files.exists(sock), "最终停止后 socket 文件应被清理");
    }

    /** 等待服务完成 bind（socket 文件已创建且 serverChannel 已赋值）。 */
    private static void awaitBound(UnixSocketServer server, Path sock, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (isBound(server) && Files.exists(sock)) {
                return;
            }
            Thread.sleep(10);
        }
        fail("服务未在超时内完成绑定: " + sock);
    }

    private static boolean isBound(UnixSocketServer server) throws Exception {
        Field f = UnixSocketServer.class.getDeclaredField("serverChannel");
        f.setAccessible(true);
        return f.get(server) != null;
    }
}
