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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code PUT /api/config} 的接线验证：{@code @Valid} 是否真的生效、非法取值是否被转成
 * 结构化的 400、以及控制器是否把「原始 patch」交给 ConfigManager 合并（而不是整体替换）。
 *
 * <p>用 standalone 的 MockMvc，不加载 Spring 上下文，避免测试依赖自动配置的细节。
 */
class MusicControllerConfigEndpointTest {

    private ConfigManager configManager;
    private TaskExecutor taskExecutor;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() throws Exception {
        MusicScannerService scannerService = mock(MusicScannerService.class);
        BroadcastService broadcastService = mock(BroadcastService.class);
        configManager = mock(ConfigManager.class);
        taskExecutor = mock(TaskExecutor.class);

        // standalone 模式下需手工初始化校验器（容器里由 Spring 完成这一步）
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();

        MusicController controller =
                new MusicController(scannerService, broadcastService, configManager, taskExecutor);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiExceptionHandler())
                .setValidator(validator)
                .build();
    }

    @Test
    void rejectsOutOfRangePortAndNamesTheField() throws Exception {
        mockMvc.perform(put("/api/config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"serverPort\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0].field").value("serverPort"));
    }

    @Test
    void rejectsEmptyExtensionList() throws Exception {
        mockMvc.perform(put("/api/config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"supportedExtensions\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0].field").value("supportedExtensions"));
    }

    @Test
    void rejectsMalformedJsonBody() throws Exception {
        mockMvc.perform(put("/api/config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"serverPort\":\"not-a-number\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void acceptsPartialUpdateAndHandsTheRawPatchToConfigManager() throws Exception {
        given(configManager.getConfig()).willReturn(existingConfig());

        mockMvc.perform(put("/api/config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"serverName\":\"NAS\"}"))
                .andExpect(status().isOk());

        ArgumentCaptor<ConfigManager.Config> captor = ArgumentCaptor.forClass(ConfigManager.Config.class);
        then(configManager).should().setConfig(captor.capture());
        assertEquals("NAS", captor.getValue().getServerName(), "提交的字段应原样传给 ConfigManager");
        assertNull(captor.getValue().getServerPort(),
                "未提交的字段应保持 null，由 ConfigManager 合并时保留旧值");

        // 配置变更后应异步触发一次扫描
        then(taskExecutor).should().execute(any(Runnable.class));
    }

    private static ConfigManager.Config existingConfig() {
        ConfigManager.Config config = new ConfigManager.Config();
        config.setMusicLibraryPaths(new ArrayList<>(List.of("/srv/music")));
        config.setServerPort(8080);
        config.setBroadcastPort(9999);
        config.setBroadcastInterval(30000);
        config.setSupportedExtensions(new ArrayList<>(List.of("mp3")));
        config.setServerName("HelloMusic");
        config.setLastPlayMode("FORWARD");
        return config;
    }
}
