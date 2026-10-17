/*
 * HelloMusic - Local music server with UDP broadcast and REST API
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

import com.fasterxml.jackson.databind.ObjectWriter;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * JSON 落盘工具：先把内容写入同目录的临时文件，再原子地替换目标文件。
 *
 * <p><b>为什么不能直接写目标文件？</b> 直接 {@code writeValue(target, ...)} 会先截断目标文件，
 * 若写到一半进程被 kill 或机器断电，磁盘上留下的就是半截 JSON。下次启动反序列化失败，
 * 而调用方普遍用 {@code catch (IOException)} 吞掉异常并打印一行错误，结果是用户的配置文件或
 * <b>整个歌单文件被静默丢弃</b>。先写临时文件再原子替换，可以让「目标文件」在任何时刻要么是
 * 旧内容、要么是新内容，不存在中间状态。</p>
 *
 * <p>临时文件刻意建在<b>目标文件所在目录</b>（而不是系统临时目录）：原子替换要求源与目标位于
 * 同一文件系统，跨文件系统的移动无法保证原子性。</p>
 */
final class JsonFiles {

    private JsonFiles() {
    }

    /**
     * 把 {@code value} 序列化后原子写入 {@code target}。
     *
     * <p>临时文件用普通的 CREATE/TRUNCATE 打开（而非 {@link Files#createTempFile}），
     * 这样最终文件的权限与直接创建时一致，不会因为临时文件默认的 600 权限而改变配置文件的权限位。</p>
     *
     * @throws IOException 序列化失败或文件操作失败；此时目标文件保持原样（若原本存在）
     */
    static void writeAtomic(ObjectWriter writer, File target, Object value) throws IOException {
        Path targetPath = target.toPath().toAbsolutePath();
        Path directory = targetPath.getParent();
        Path temporary = directory.resolve(target.getName() + ".tmp." + Long.toHexString(System.nanoTime()));

        try {
            try (OutputStream out = Files.newOutputStream(temporary,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE)) {
                writer.writeValue(out, value);
            }

            try {
                // ATOMIC_MOVE 会忽略其余选项；在 POSIX 上底层 rename(2) 本身就是原子替换
                Files.move(temporary, targetPath, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                // 少数文件系统不支持原子移动，退化为普通替换：
                // 安全性不如前者，但仍优于「直接截断目标文件后再写」
                Files.move(temporary, targetPath, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            // 成功时临时文件已被移走（此处为无副作用的 false）；失败时清掉残留，不留半截文件
            Files.deleteIfExists(temporary);
        }
    }
}
