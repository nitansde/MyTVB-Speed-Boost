# BTR 调度移植记录

基准：MrTangLuyao/Bilibili-thread-ripper，提交
`bbf4d3dee502a16e424232ae6a51705f52b0e60d`。
源码快照与 MIT 协议在 `tools/btr/`；APK 内的协议在 `assets/licenses/`。
本记录说明已实现和验证的范围，不表示电视实测性能已达标。

## 对照关系

| 原版 | Android |
| --- | --- |
| `range-core.js/splitRange` | `BtrSchedulingPolicy.split` |
| `assignPrimaries`：平滑加权轮询、轮转、1/12 慢节点门槛、末尾探索任务 | `BtrSchedulingPolicy.assign` |
| `recordMeter / adaptiveMinChunk / hedgeDelayMs` | `BtrSchedulingPolicy`：相同采样门槛、平滑系数、量化与上下限 |
| `Semaphore`：关键请求、起播片段、普通片段三级队列；截止时间与排队老化 | `BtrPriorityGate` |
| `hedgeDue / createEarlyHedge / observeFirst` | `BtrSchedulingPolicy.hedgeDue`、`BtrDownloader`；按进度和播放期限补救 |
| `downloadPiece` | `BtrDownloader.piece/race`：三轮、25 秒重试窗口、每轮最多八候选、双请求竞速、32 KiB 续传门槛 |
| `attempt` | `BtrDownloader.attempt/network`：206 和 Content-Range 校验、首字节/停滞/总时限、取消不惩罚节点 |
| `downloadStartupRange` | 索引请求最多三节点，0/120/300 ms 错峰 |
| `downloadStartupMediaRange` | 先交付 64 KiB 探测块，再按已测节点分配；音视频与补救预算一致 |
| `createAutoConcurrency` | `BtrAutoController`：相同档位、起播提升、窗口饱和度、增线程试用、拒绝后的硬退避 |
| `cdn-resolver.js` | `VideoPlayerCdnFailoverState`：速度过期、暖机/探索、失败退避、按已成功的节点/地址判定拒绝 |

## 播放器接入

- 原实现把 ProgressiveMediaSource 的整文件请求当成片段，导致巨型分块。这已移除。
- 有 SegmentBase 的点播使用 DASH，Media3 解析真实索引；一次下载请求最多一个视频片段。
- `BtrDashChunkSourceFactory` 把片段起播时间和字节区间传给下载器，初始化/索引请求单独提级。
- 每个媒体源各有播放时钟；播放速度、暂停和跳转都会更新截止时间，预加载的视频不会套用当前视频的位置。
- 音视频共用下载器和连接预算；缓存放在下载器外层，缓存命中不需要伪造 HTTP 响应。
- 目标预缓冲 45 秒；由 Media3 LoadControl 控制加载与恢复，不在缓冲仅剩十几秒时才补充。
- HTTP Dispatcher 放宽到 128；并发数仍由 BTR 连接预算控制，避免请求又在 HTTP 内部按单节点 5 个排队。
- 起播/重缓冲基础门槛为 6 秒，预缓冲目标为 45 秒。尚未复制原版随起步速度变化的 2.5–10 秒动态门槛。
- 原始地址保留在播放源中，CDN 扩展在实际下载时进行；关闭 BTR 可续接剩余数据到原始线路。
- Debug 区分实际连接、HTTP 排队、网络接收与成功下载速度，并按选中轨道码率和播放倍速显示所需速度。
- 错误显示稳定的中文类别或 HTTP 状态，避免发布版代码混淆后只剩单字母。

## 已验证及边界

执行 `node tools/btr/generate-parity-fixtures.cjs` 会在禁止网络的 JS 沙箱内运行基准源码，
仅为测试暴露闭包函数，生成 240 个分配结果、80 个分块/计量结果、300 个补救判断和
3200 个自动线程事件。`BtrUpstreamParityTest` 逐项对照 Kotlin 输出。

传输测试覆盖：按序交付、失败后只续传尾部、健康请求不在 900 ms 无条件重复、
小块起播探测、取消不封禁、错误 Content-Range 拒绝、优先队列和取消释放。
本地 HTTP 测试覆盖真实 OkHttp → CDN 包装 → Range 下载 → 播放器读取链路。
慢响应服务器测试复现默认 Dispatcher 在 16 个同节点请求中隐藏排队 11 个，
验证修改后 16 个均可在等待响应头阶段发出。
关闭开关的测试验证已消费字节不重复，未消费尾部回到原始地址。
这些测试不代表用户网络实测带宽一定增长。

平台适配并非整个浏览器播放器的复制：

- 浏览器 MSE 的 SourceBuffer、配额恢复由 Media3/Android 的缓冲和加载机制代替；
  目前没有复制原版 MSE 的多个片段预取、动态起播阈值及配额恢复状态机。
- 请求取消使用协程与 OkHttp，超时每 50 ms 检查；浏览器使用 AbortController 和定时器。
- 没有片段边界或单个请求超过 32 MiB 时保留连续读取，不把整文件拆成并发大块。
- 直播加速、播放地址签名刷新尚未在本次移植中完成。节点健康状态当前按音/视频表示分别维护。
- 原版兼容模式和 TV 的设置含义尚非完整一一对应；本次已不再把兼容选项等同于关闭并发。
- 自动测试证明所覆盖的规则相同，不证明任意 4K 视频更快；还需在用户电视/网络验证。
