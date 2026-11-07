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

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 有界线程池工厂：把分散在各处的 {@code newCachedThreadPool} 收敛为「固定大小 + 有界队列」的
 * 有界实现。
 *
 * <p>{@code newCachedThreadPool} 对每个新任务无上限地新建线程，恶意客户端并发建连即可把线程数 /
 * 内存打满（DoS 面）。本工厂统一产出守护线程、固定并发上限、有界等待队列与显式拒绝策略的线程池，
 * 让「有界」这一安全决策集中、可见，而不是散落在三处各自为政。</p>
 */
final class ThreadPools {

    private ThreadPools() {
    }

    /**
     * 创建固定大小 + 有界队列的线程池。
     *
     * @param name 线程名前缀（便于日志 / 排查）
     * @param threads 并发线程数（core=max，固定不变）
     * @param queueCapacity 等待队列上限；满后按 {@code policy} 拒绝
     * @param policy 队列满时的拒绝策略（{@code AbortPolicy}=过载拒绝，{@code CallerRunsPolicy}=反压）
     */
    static ExecutorService bounded(String name, int threads, int queueCapacity,
                                   RejectedExecutionHandler policy) {
        ThreadFactory factory = runnable -> {
            Thread t = new Thread(runnable, name);
            t.setDaemon(true);
            return t;
        };
        return new ThreadPoolExecutor(threads, threads, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity), factory, policy);
    }
}
