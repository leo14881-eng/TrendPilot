# TrendPilot

币圈影子交易系统（Java 重写版）。**只做影子模拟，不接真实交易**，`capital_authority` 固定为 `NONE_SHADOW_ONLY`。

- 设计：[docs/技术方案.md](docs/技术方案.md)
- 规则与参数：[docs/规则说明.md](docs/规则说明.md)

## 当前进度：完整版（不含 Sentinel）

单服务（Spring Boot + SQLite），只用免费公开接口：

- 每小时 :17 Discovery：Binance 池 + Bybit 独立池 → EARLY 信号 → 系统风险 → 研究 → V2 审核与资金分配 → V1 观察
- 每 5 分钟 Monitor：硬失效、利润保护、论点失效、8% 复核（V1、V2 各自记账）
- WebSocket：15m K 线缓存（Binance、Bybit），持仓最优价供 FastWatch 每 15 秒观察
- 研究：CoinGecko（身份、供给、分类）、DefiLlama（TVL、手续费、收入），解锁记为研究缺口
- 复盘：Blind Replay 每小时 :31，Missed Replay 每天 23:55
- Telegram：每条买卖标明 V1/V2、币、金额、原因；每日 21:00 汇总含 V1/V2 盈利对比
- 本机状态接口：http://localhost:8080/api/status

## 本机运行（Windows）

- `build.cmd`：编译并跑测试，生成 `target\trendpilot.jar`
- `start.cmd`：启动服务（没有 jar 时先自动编译）

工具和缓存都在 D 盘：Maven 由 Maven Wrapper 自动下载到 `D:\SOFT\tools\maven`，依赖缓存在 `D:\SOFT\tools\maven\repository`。JDK 21 优先用 `JAVA_HOME`，否则在 `D:\SOFT\tools\jdk-21*`、`D:\SOFT\java\jdk-21*` 中查找。

## Telegram 配置

复制 `config\application-local.yml.example` 为 `config\application-local.yml`，填入 Bot Token 和 Chat ID。该文件不进代码仓库。未配置时通知只写入数据库的 `notification_outbox` 表。

## 运行产生的目录

- `data\trendpilot.db`：SQLite 数据库（账本、运行记录、通知）
- `logs\`：日志，按天滚动保留 30 天
