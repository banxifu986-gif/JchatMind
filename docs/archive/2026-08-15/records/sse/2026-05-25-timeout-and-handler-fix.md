# SSE 超时与 DevTools 重启排查记录 - 2026-05-25

## 1. 现象

- 时间：`2026-05-25 17:49`
- 日志表现：
  - `AsyncRequestTimeoutException`
  - 紧接着出现 `HikariPool-1 - Shutdown initiated...`
  - 随后 Spring Boot 重新启动
  - `File Watcher` 日志显示 `Restarting due to 254 class path changes`

## 2. 结论

这次不是新的启动失败，也不是 Ollama 接口再次不可用，而是两个现象叠加：

1. 前端或浏览器存在一个未结束的异步 HTTP 请求
2. 开发期 `spring-boot-devtools` 检测到大量 classpath 变化，触发热重启

热重启开始后，旧请求被容器中断，因此 Spring MVC 记录了：

- `Ignoring exception, response committed already: AsyncRequestTimeoutException`
- `Resolved [org.springframework.web.context.request.async.AsyncRequestTimeoutException]`

这类日志更接近“开发期连接被重启打断”，不是新的核心业务异常。

## 3. 当前代码层面暴露的问题

虽然这次主因是 DevTools 热重启，但现有 `SseServiceImpl` 存在放大问题的行为：

1. SSE 客户端不存在时直接抛 `RuntimeException`
2. SSE 发送失败时直接抛 `RuntimeException`
3. 页面刷新、前端断开连接、后端热重启时，Agent 后续发送 SSE 可能继续报错

这会导致：

- 日志噪音增大
- 异步任务更容易被“前端连接已断开”这种非核心问题污染
- 排障时更难区分真正的业务失败和开发期连接抖动

## 4. 本次修复

对 `backend_v2/src/main/java/com/kama/jchatmind/service/impl/SseServiceImpl.java` 做最小降级：

1. SSE 连接初始化失败时，不再继续抛运行时异常，改为移除连接并记录 `warn`
2. SSE 客户端不存在时，直接跳过发送并记录 `debug`
3. SSE 发送失败时，移除失效连接并记录 `warn`

目标是：

- 不让前端断连反向拖垮后端异步聊天流程
- 将“连接问题”降级为可观测告警，而不是流程级失败

## 5. 验证口径

- 无 SSE 客户端时，`send()` 不应抛异常
- SSE 已断开时，`send()` 不应抛异常，且应移除连接
- 重新从前端发起对话时，即使浏览器刷新或连接重建，也不应因为 `No client found` 之类的异常打断主流程

## 6. 额外说明

- 日志里的 `BeanPostProcessorChecker` 和 `No sampling methods found` 仍属于当前 MCP / Spring AI 启动期提示，不是这次超时问题的根因
- 如果后续仍出现“前端发送后长时间无响应”，下一步应优先检查具体哪个 HTTP 接口超时，而不是把这类 DevTools 重启日志误判为新的启动失败
