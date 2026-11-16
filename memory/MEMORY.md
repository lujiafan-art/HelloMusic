# HelloMusic 项目长期笔记

## 构建与测试（沙箱环境约束）

- **Maven 不可用**：离线仓库缺 `maven-resources-plugin` 的传递依赖；在线模式被 Security Center
  以 EPERM 拦截。**一律不要用 `mvn`**。
- **手动构建**：`bash .workbuddy/build-manual.sh`（javac --release 21 + junit-platform-launcher
  TestRunner，精确版本 classpath 已固化在脚本内；TestRunner.java 同目录）。
- **坑**：本机 javac 21.0.13 只认 `--release`（双横线），单横线 `-release` 报「无效的标记」。
- Mockito 依赖 `mock-maker-subclass`（src/test/resources/mockito-extensions/org.mockito.plugins.MockMaker），
  避免 byte-buddy agent 自挂载被内核 EPERM 拦截；运行测试需 `-XX:+EnableDynamicAgentLoading`。

## 架构约定（重构后）

- **控制面唯一实现 = UnixSocketServer**；HTTP `POST /control` 只是它的「外壳」，经 UnixSocketClient
  转发，不直接触碰 CommandDispatcher。传输层依赖方向：
  `HttpGateway(外壳) → UnixSocketClient → UnixSocketServer → CommandDispatcher`。
- **controlToken 鉴权**：仅约束 HTTP 外壳入口（Bearer + 常量时间比较）；数据面与 Unix Socket
  本机通道不鉴权。令牌启动时绑定，运行时 config.set 修改需重启生效。

## 提交历史节点

- 75d2d65 初版 → dda8553 去 Spring 缺陷修复 → cebfaa9 安全加固 → 20c7472 Range 修复
  → 22dd647 currentIndex 约束 → e3190ad executor 生命周期 → 84e7a12 控制面收敛 + 鉴权。
