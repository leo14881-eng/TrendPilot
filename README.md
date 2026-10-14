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

## 自动更新（一次安装，以后不用手动部署）

在 `D:\SOFT\java\TrendPilot` 运行一次 `scripts\install-autoupdate.cmd`（普通用户权限即可），会注册两个计划任务：

- `TrendPilot-Service`：用普通用户权限运行服务（`scripts\run-service.cmd`），登录时自动启动。
- `TrendPilot-Watchdog`：每 2 分钟运行 `scripts\watchdog.ps1`，独立于服务：服务被关或自己退出时立即拉起；`/api/health` 连续约 6 分钟无响应、或 Monitor 超过 20 分钟没跑（卡死）时结束并拉起；停止和恢复都发 Telegram；连续 3 次拉起失败就暂停 1 小时并告警。
- `TrendPilot-AutoUpdate`：每 3 分钟运行 `scripts\auto-update.ps1`：
  - **更新**：GitHub `main` 有新提交时，在 `D:\SOFT\tools\trendpilot-src` 拉取并构建（含全部测试），然后平滑停机、换新版、启动并做健康检查；新版起不来就自动回滚到上一版。结果通过 Telegram 通知。

更新过程不会改动 `config\application-local.yml`、`data`、`logs`。日志在 `logs\auto-update.log`，当前版本记录在 `app\version.txt`。
