# Graph Triangle Stats (Java)

本地图三角计数与聚集系数统计服务。通过 REST 接口接收无向图边的新增/删除事件流，
增量维护三角计数，并提供按节点、按边、按全图三种粒度的查询，以及节点级与全图级聚集系数。

## 构建与测试

```bash
mvn compile
mvn test
mvn spring-boot:run
```

## 事件与顺序/去重语义

- 事件有两种操作：`ADD_EDGE`、`REMOVE_EDGE`，载荷为 `{operation, source, target}`。
- 图是**无向图**：`(u, v)` 与 `(v, u)` 被规范化为同一条边（端点按字典序排序）。
- **提交顺序定义状态**：事件按其所在批次的提交顺序依次应用。对同一条边先加后删与先删后加
  会得到不同结果，这是有意定义的语义——状态由提交序列决定，而不是事件的到达先后。
- **乱序安全**：“乱序”指客户端可以任意顺序发送批次，服务端按实际提交成功的顺序构建状态；
  由于边是无向且操作针对规范化边，端点颠倒不会产生重复边。
- **幂等去重**：重复应用同一事件不改变统计结果。重复添加已存在的边、重复删除不存在的边
  都是空操作（no-op）。事件的幂等性来自集合语义（边集合的 add/remove），不依赖客户端 ID。
- 不支持自环（`source == target`），端点不能为空，超长端点 ID 会被拒绝。

## 批量提交与原子性

- `POST /api/graph/events` 接收一个事件批次 `{"events":[...]}`，整批原子提交：
  服务先在状态副本上模拟整批，任一事件校验失败或超出容量，整批拒绝且不产生任何部分效果。
- 只有当批次完整写入预写日志（WAL）并 fsync 后，才发布新的统计快照。
- 同一事件序列无论如何分批（一批提交或逐条提交），最终统计结果完全一致；
  区别只在于产生的版本号数量。

## 版本号与一致性快照

- 每个**成功提交的批次**生成一个单调递增的版本号，从 1 开始；被拒绝的批次不占用版本号。
- 每次提交都会构造一个全新的不可变 `GraphSnapshot`，并通过单次 volatile 写发布。
  查询从不加锁，因此读写并发时，任一查询返回的都必然是某个已提交版本的完整快照，
  绝不会读到“部分事件已应用、部分未应用”的中间状态。
- 查询可带可选参数 `?version=N` 读取历史版本；最近 `graph.stats.retained-versions`
  个版本会被保留。请求过旧或不存在的版本返回 `404 UNKNOWN_VERSION`。
- 所有查询响应都带有所基于的 `version`，可用于定位与恢复核对。

## 崩溃恢复

- 每个已提交批次在发布前先追加到 `graph.stats.wal-dir` 下的 `wal.log`，
  帧格式为 `[int 长度][int CRC32][payload]`，每次追加后 `fsync`。
- 重启时：先扫描日志，尾部残缺或 CRC 不匹配的帧视为“崩溃时未写完的未提交批次”，
  立即截断丢弃；然后顺序重放所有完整帧重建内存统计。
- 已提交事件不会重复应用（重放只会重建到最近一次已提交位置），未提交事件不会生效，
  恢复后的统计与崩溃前一致，版本号继续递增。

## 容量上限（有界内存）

内存占用受显式上限约束，超限行为明确可区分，绝不静默丢弃事件：

| 配置键 | 默认值 | 含义 |
| --- | --- | --- |
| `graph.stats.max-vertices` | `100000` | 活跃（度数>0）顶点数上限 |
| `graph.stats.max-edges` | `500000` | 边数上限 |
| `graph.stats.max-vertex-id-length` | `256` | 单个顶点 ID 最大长度 |
| `graph.stats.retained-versions` | `16` | 保留的历史快照版本数 |
| `graph.stats.wal-dir` | `data/wal` | 预写日志目录 |

- 批次在提交前于副本上模拟，若结果会超过 `max-vertices` 或 `max-edges`，
  整批被拒绝，返回 **HTTP 507** 与错误码 `CAPACITY_EXCEEDED`，错误体中含当前/上限数值，
  线上状态不变。
- 顶点 ID 超长或自环、空端点返回 **HTTP 400** `INVALID_EVENT`。
- 容量策略当前为“拒绝”（REJECT），不做静默降级或丢弃。

## HTTP 接口

| 方法 & 路径 | 说明 |
| --- | --- |
| `POST /api/graph/events` | 原子提交一批事件，返回 `{version, vertices, edges, triangles}` |
| `GET /api/graph/triangles?version=N` | 全图三角数、楔数、全局/平均聚集系数 |
| `GET /api/graph/vertices/{v}/triangles?version=N` | 节点三角数、度、局部聚集系数 |
| `GET /api/graph/edges/triangles?source=u&target=v&version=N` | 边所在三角数及该边是否存在 |

全局聚集系数采用传递率定义 `3T / W`（`W` 为长度为 2 的路径总数，无楔时为 0）；
局部聚集系数为 `2t(v) / (d(v)(d(v)-1))`，度小于 2 时为 0。响应中同时给出
平均局部聚集系数（孤立点按 0 计入）。

## 代码结构

- `domain`：规范化边 `Edge`、事件 `EdgeEvent`、不可变快照 `GraphSnapshot`。
- `engine`：`TriangleStatsEngine`，增量维护邻接表、每边/每节点三角计数与全图总数。
- `wal`：`WriteAheadLog`，CRC 校验的持久提交日志与崩溃重放/截断。
- `service`：`GraphStatsService`，校验、副本模拟、原子提交、版本快照、容量与恢复编排。
- `web`：REST 控制器、DTO 与可区分错误码的全局异常处理。
