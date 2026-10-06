# 51: 多源弹性下载引擎与 HTTP Range 断点续传调度器

Type: implementation  
Status: ready-for-review  
Labels: m4-spec, tracer-bullet, network, resilient-download  

## User Story

作为身处移动弱网或跨国网络环境下的用户，我在下载数十至数百兆的 Linux 镜像与内核资产时，如果网络中断，希望能够自动从断点处继续下载而不是从零重头开始；如果首选镜像源访问超时，希望引擎能自动切换到备用镜像通道，确保镜像下载一次成功（US-24, US-25）。

## What to build

在 `:runtime:network` 或 `:app` 中实现多源抗阻弹性下载引擎，彻底根除下载失败与重复耗时痛点，遵循 ADR-0006 与 spec-m4.md：

1. **抽象下载引擎接缝（`ResilientDownloadEngine`）**：
   - 定义高可用下载服务接口：
     ```java
     public interface DownloadEngine {
         void download(List<String> mirrorUrls, Path destination, String expectedSha256, DownloadProgressCallback callback) throws IOException;
     }
     ```
2. **HTTP `Range` 断点续传协议实现**：
   - 检查目标临时文件（`<destination>.part`）是否存在；
   - 若存在且字节数 $> 0$，在 HTTP 请求头中添加 `Range: bytes={existingLength}-`；
   - 准确处理服务端响应码：
     * `HTTP 206 Partial Content`：以追加模式（`StandardOpenOption.APPEND`）继续写入；
     * `HTTP 200 OK`：服务端不支持断点续传，优雅回退为截断并全量重写；
     * `HTTP 416 Range Not Satisfiable`：本地游标异常，重置文件并全量重下；
3. **多镜像故障转移与指数退避（Failover & Exponential Backoff）**：
   - 维护镜像通道优先级列表（如 GitHub Releases 官方源、全球 CDN 加速源、国内镜像代理源）；
   - 设置严格的超时门槛（连接超时 $10\text{s}$，读超时 $15\text{s}$）；
   - 在当前镜像失败后，执行最多 3 次指数退避重试（间隔 1s、2s、4s）；
   - 重试耗尽则自动平滑切换至下一镜像节点，保持断点偏移量继续下载；
4. **流式 SHA-256 强校验与进度回传**：
   - 数据写入磁盘的同时，流式计算 `MessageDigest("SHA-256")`；
   - 若支持断点续传，初始化时先行计算已下载前缀的 SHA-256（或在全量写入完成后统一校验完整文件）；
   - 严格比对目标哈希，一旦不匹配立即清理损坏文件并抛出 `ChecksumMismatchException`；
   - 实时计算瞬时下载速率（MB/s）、当前百分比与预估剩余时间（ETA），回调通知 UI。

**Blocked by:** None

## Seam Design

- **纯 Java 核心**：下载引擎采用标准 Java 原生网络库（`HttpURLConnection` 或 `HttpClient`），无外部第三方重型网络依赖，便于脱机单测；
- **状态回调解耦**：UI 仅通过 `DownloadProgressCallback` 接口感知下载进度，业务逻辑与 Android 视图完全解耦。

## Acceptance Criteria

- [x] HTTP `Range` 断点续传正常工作：模拟下载 50% 时断开连接，再次触发时发送包含对应 offset 的 `Range` 请求头，并在收到 `HTTP 206` 时成功拼接完整文件；
- [x] 故障转移（Failover）确定性生效：当第一个镜像源抛出连接超时或 404/500 错误时，引擎在预设重试次数耗尽后平滑转向第二个镜像源并完成下载；
- [x] 文件完整性哈希防御：当下载内容被人为篡改导致 SHA-256 不匹配时，确定性抛出异常并自动物理删除被污染的临时文件；
- [x] 下载进度监听器以平滑节拍回调当前字节数、总字节数、瞬时速率（MB/s）与节点信息；
- [x] 纯 JVM 测试（基于轻量 MockHttpServer）100% 绿灯通过。

## Device Verification Steps

在真机上验证多源下载与断网恢复：
```bash
# 1. 触发镜像下载（如 Alpine minirootfs）
# 在 Android UI 上点击创建实例，观察 Logcat 输出的下载进度与镜像源切换
adb logcat -d -s DroidLayerDownload:D

# 2. 模拟断网：下载中途关闭手机 Wi-Fi / 蜂窝网络
adb shell svc wifi disable && adb shell svc data disable
# 观察日志打印 SocketTimeoutException 并进入指数退避

# 3. 恢复网络：观察是否收到 HTTP 206 并从原进度百分比平滑恢复
adb shell svc wifi enable
adb logcat -d -s DroidLayerDownload:D | grep "HTTP 206 Partial Content"
```

## Test Strategy

- JVM 单元测试：
  - `RangeResumeTest`：构建本地 Mock HTTP Server，模拟响应 206 与 200，断言数据拼接无损；
  - `MirrorFailoverTest`：首个端口拒绝连接，断言顺利切换到备用端口并完成下载；
  - `Sha256StreamingValidationTest`：测试篡改字节导致校验失败与临时文件清除逻辑；
- 运行 `./gradlew :runtime:network:test`。

## Prototype/research dependency

依赖 ADR-0006 发行版资产分发规范与 `docs/research/2026-10-06-virtualization-ux-and-competitors.md` 中 PojavLauncher 断点下载分析。
