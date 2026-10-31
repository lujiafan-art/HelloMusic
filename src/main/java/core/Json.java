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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * JSON 基础设施：统一的 {@link ObjectMapper} 工厂。
 *
 * <p>脱离 Spring 后不再有「自动配置的 ObjectMapper」，这里提供一个注册了
 * {@code JavaTimeModule}（用于 {@code LocalDateTime} 序列化）且关闭「日期写成时间戳数组」的
 * 标准实例。{@link ConfigManager} / {@link PlaylistService} / {@link BroadcastService} /
 * 命令分发器与测试共用它，保证序列化行为全局一致。</p>
 */
final class Json {

    private Json() {
    }

    /**
     * 构建一个标准的 {@link ObjectMapper}。
     *
     * <p>必须注册 {@code JavaTimeModule}：{@link MusicFile#getLastModified()} 与
     * {@link Playlist} 的 {@code createdAt}/{@code updatedAt} 都是 {@code LocalDateTime}，
     * 裸 ObjectMapper 会抛 {@code InvalidDefinitionException}。</p>
     */
    static ObjectMapper newMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }
}
