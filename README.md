# 星穹旅驿 · 阵营选择插件 StarFaction

Purpur/Spigot/Paper **1.21.11**（Java 21）服务端插件。只做“阵营选择”这一件事：
世界观讲解由 StarInn 插件负责，本插件与它互不干扰、零依赖。

**红线承诺**

| 红线 | 实现方式 |
|---|---|
| 零第三方插件依赖 | `plugin.yml` 不声明 depend/softdepend；LuckPerms/PlaceholderAPI/ProtocolLib/Vault/Adventure 一概不用；Geyser 仅运行期 `Class.forName` 探测，不装也不报错 |
| 零网络请求 | 插件运行期不发任何网络请求；JDBC 驱动优先用服务端内置，缺失时从本地 `plugins/StarFaction/drivers/` 目录加载（不联网下载） |
| 无硬编码数字 | 延迟分钟、提醒间隔、阈值、档位、冷却天数、buff时长、昼夜 tick 边界、数据库 host/**port**/库名表名等全部在 `config.yml` |
| 绝不卡住玩家 | 弹窗可点“稍后再说”、可无视；`/faction choose` 随时重开；不选阵营可一直自由游玩 |
| 模块化降级 | 每个模块独立 try-catch；任一模块失败只在自检日志“降级N项”中体现，不影响其余功能，更不影响服务器启动 |

---

## 安装

1. 把 `StarFaction-1.0.0-r2.jar` 放入服务端 `plugins/`；
2. 启动一次生成 `plugins/StarFaction/config.yml`，按需修改；
3. `purpur`/`paper`/`spigot` 1.21.11+，Java 21+；
4. 看启动日志末尾：`[星穹旅驿] 阵营插件自检完成：正常X项 / 降级Y项`。出现降级项时按“常见问题”处理，服务器不受影响。

## 玩家流程（设计）

```
进服 → 自由探索（前 delay-minutes 分钟零打扰）
     → 累计在线满阈值 → 弹选择窗（Java 聊天按钮 / 基岩 Geyser 表单）
     → 点选瞬间同步完成：
         ① 发 starter-kit 基础物资（背包满掉落脚下，不丢失）
         ② 若加入的是在线人数劣势一方且人数差比例超阈值 → 按档位发补偿物资
         ③ 写库（uuid/阵营/时间/补偿档位；在线时长由计时系统持续累加）
         ④ 立即打身份四层（名牌前缀/Tab颜色/聊天前缀/发光轮廓）
         ⑤ 立即按“所在世界时间”施加昼夜 buff
     → 之后身份跨服跨世界持续显示，buff 随世界时间自动切换
```

- 已选过阵营的老玩家**永不弹窗**，join 直接从库重读并恢复身份+buff；
- 点“稍后再说”不标记完成：每隔 `choose.reminder-interval` 分钟再提醒，可以一直不选；
- 玩家正在其他界面（打开了 GUI/箱子，或被 StarInn 等插件标记）时**延迟到结束后再弹**，绝不抢 UI。

## 计时系统

- 库中 `online_seconds` 为累计秒数；join 记会话锚点，quit / 每约60秒周期保存时累加落库；
- 掉线不清零；`choose.count-offline: true`（默认）时，下线时段会按上次离线锚点补齐计入，`false` 时只计真实在线秒数；
- 达到 `choose.delay-minutes` 即触发弹窗。

## 命令与权限

| 命令 | 谁能用 | 作用 |
|---|---|---|
| `/faction choose` | 所有人 | 主动打开选择界面（可随时重开） |
| `/faction choose dawn\|dusk` | 所有人 | 首次选择阵营 |
| `/faction switch dawn\|dusk` | 所有人 | 更换阵营（受 `lock.switch-cooldown-days` 冷却；换阵营立刻清空 `compensate_tier`） |
| `/faction later` | 所有人 | 稍后再说（等于弹窗里的“稍后再说”按钮） |
| `/faction info` | 所有人 | 自己的阵营、累计在线、选择时间、补偿档、冷却剩余、当前 buff 状态、双方在线人数 |
| `/faction reload` | `starfaction.reload`（默认OP） | 重载配置并重建各模块，重新打印自检 |
| `/faction set <玩家> <dawn\|dusk>` | `starfaction.admin`（默认OP） | 强制设置在线玩家阵营（不受冷却；同时发物资、重建身份） |

别名：`/sf`。

## 双端选择界面

- **Java 端**：可点击聊天消息（ClickEvent 执行命令），两个阵营按钮 + 稍后再说；不用书本不用 GUI。
- **基岩端（Geyser）**：`Class.forName` 探测到 `GeyserImpl` 时用 Cumulus **SimpleForm 表单**（按钮式，全反射调用，编译期零引用）；
  探测不到或表单流程连续失败 2 次 → 自动降级为上面同款聊天按钮（按钮消息在 Geyser 下同样可点，玩家也可手动输入
  `/faction choose dawn` / `/faction choose dusk`），全程不报错、只记一条降级日志。
- 所有文案 UTF-8 中文，基岩端表单文本不带 § 色码（基岩聊天不解析 Java 色码处会原样显示乱码，表单刻意只用纯文本）。

## 身份四层（到哪都显示）

| 层 | 实现 | 说明 |
|---|---|---|
| ① 头顶名牌前缀 | `ScoreboardTeam.setPrefix`（§6[黎明] / §d[暮夜]，文字与颜色可配） | 显示在头顶名牌与 Tab |
| ② Tab 列表颜色 | `Team.setColor(GOLD / PURPLE)` | PURPLE 在旧版 ChatColor 中自动映射为 §d 亮紫 |
| ③ 聊天前缀 | 监听 `AsyncPlayerChatEvent` 设 format 为 `[黎明]名字: 消息` | **二选一说明**：没有采用“靠 team prefix 自动附加”方案，因为 Spigot/Paper 的聊天管线以事件 format 为准，队伍前缀并不稳定自动进入聊天文本；自设 format 可保证 Java/基岩双端一致显示 |
| ④ 发光轮廓 | `Player.setGlowing(true)` | Java 端生效；配置 `identity.show-glowing` 开关，异常时自动关闭并在自检日志告警 |

重建时机：**PlayerJoinEvent 从库重读并重建全部四层**；`PlayerChangedWorldEvent` 时刷新；
跨 Velocity 后端服时，**每个后端服都装本插件并连同一个 MySQL 库**，靠各自 join 重建实现跨服显示。

## 昼夜 buff（少而对称，不碰战斗）

- 只给采集/生存向效果，绝不给力量/抗性/速度等战斗效果；
- 黎明（DAWN）：身处 `dawn-world`（默认「炽壤」）且**该世界**为白天 → `HASTE:1 + SATURATION:1`；
- 暮夜（DUSK）：身处 `dusk-world`（默认「渊域」）且**该世界**为黑夜 → `HASTE:1 + NIGHT_VISION:1`；
- 两边都含 HASTE:1 完全对称；黎明多饱和、暮夜多夜视，大致等价；
- 世界名、效果名/等级、检查间隔、持续时长、白天 tick 区间全部可配；
- 白天判定：`world.getTime() % 24000 ∈ [buff.day-start, buff.day-end)`，默认 `[0,12000)` 白天、其余黑夜；
- 两个世界时间天然反相（差 12000 tick），各读各的即可，无需跨世界同步——玩家跑到对方世界或错峰时段，
  buff 自动消失，正好强化“昼夜相反”的世界观；
- `SATURATION` 是瞬时限短效果，本插件靠“剩余时长不足两个检查周期就自动补施”保持持续生效（默认检查 5 秒，
  即约每 10~15 秒补一次短时长，满足并优于“每 20 秒补一次”的要求）；
- 效果只对配置清单内的类型做增删，不会误删其他插件/药水给的效果；换阵营时先清对侧残留再评估。

## 人数平衡补偿（细节+防刷）

- 只统计**在线且已选阵营**的玩家，得到 A(黎明)、B(暮夜)；
- 差值比例 = `|A-B| / max(A,B)`，仅当 `> balance.threshold`（默认 0.2）**且新人加入的是人数少的一方**才触发；
- 分档方式：`threshold → 1.0` 区间按 `max-tier` 线性等分。例：threshold=0.2、max-tier=3 时
  `[0.20,0.467)`→1档、`[0.467,0.733)`→2档、`[0.733,1.00]`→3档；比例越高档越高，**封顶 max-tier，防无限堆**；
- 每档物资在 `balance.tiers`（格式同 starter-kit），没配的档位自动逐级向下取；
- 防刷双锁：①选择后锁定，`/faction switch` 受 `lock.switch-cooldown-days`（默认 7 天，0=永不锁定）冷却；
  ②换阵营立刻把 `compensate_tier` 清零。小号反复“选劣势方领补偿”被冷却+清档双重拦截，
  且补偿只在“加入劣势方”瞬间按当时在线人数结算，不会补发。

## 数据库（单服/多服双模式）

```yaml
database:
  type: sqlite   # 单个后端服 → sqlite；Velocity 后多个后端服 → 必须 mysql
  host: 127.0.0.1
  port: 3306     # MySQL 端口改这里（无硬编码）
  database: starfaction
  username: root
  password: ""
  mysql-driver: "com.mysql.cj.jdbc.Driver"   # 很老的 5.x 驱动包才需要改成 com.mysql.jdbc.Driver
  sqlite-file: "factions.db"   # 相对路径 = plugins/StarFaction/factions.db
  table: faction_data
```

- **单后端服**直接用 sqlite（文件 `plugins/StarFaction/factions.db`，驱动用服务端内置的 sqlite）；
- **Velocity 多后端服必须用 mysql**：所有后端服连同一库，否则切服会丢身份（本插件靠 join 从库重建，
  各服各库/内存库时切服就查不到记录）；
- 表：`faction_data(uuid主键, faction, online_seconds, chosen_at, compensate_tier)`，自动建表；
- 所有 DB 读写走单线程异步执行器（不碰主线程），断线自动重连；
- 数据库连不上 → 自动降级**内存存储**（不持久化）并告警，其余功能照常；
- **MySQL 驱动**：Purpur/Paper 多数版本自带 mysql-connector；若自检提示缺驱动，
  把 `mysql-connector-j-*.jar` 放进 `plugins/StarFaction/drivers/` 目录后 `/faction reload` 即可（纯本地加载，不联网）。
- **驱动类名 `database.mysql-driver`**：默认新版 `com.mysql.cj.jdbc.Driver`。注意一个平台特性：
  Paper/Purpur 1.21.x 在插件**类加载期**会按官方兼容映射把代码里的旧驱动名字面量
  （`com.mysql.jdbc.Driver`）改写为新驱动名——这是服务端行为，不是本插件缺陷；
  因此若你只装了非常老的 5.x 驱动包，把类名写在 `config.yml` 里（配置字符串不经过该改写）是唯一可靠指定方式。

## 与 StarInn 等其他插件的配合

- 本插件零依赖 StarInn；若 StarInn 的引导对话正在占用玩家，可调用静态 API 标记延迟弹窗：
  `com.starinn.faction.StarFaction.deferPrompt(playerId, true/false)`；
  未调用也没关系：玩家打开任何容器/界面时弹窗会自动延后，关闭后立即补弹；
- 阵营数据表 `faction_data` 可供 StarInn 只读查询（建议 StarInn 自连库读，不要调用本插件内部类）。

## 自检日志与降级

启动逐项输出 `[自检] ✓/⚠ ...`，末尾汇总
`[星穹旅驿] 阵营插件自检完成：正常X项 / 降级Y项`；降级明细逐条列出。
失败只关对应功能：数据库连不上→内存存储；配置格式错→内置默认兜底（并打印含行号的解析错误）；
Geyser 表单不可用→基岩聊天命令；glowing 不支持→自动关闭。

**版本记录**

- `1.0.0-r2`：新增 `database.mysql-driver` 配置项；MySQL 驱动候选改为“配置值优先 + 去重”，
  规避 Paper/Purpur 1.21.x 类加载期对旧驱动名字面量的兼容改写（详见数据库章节），错误提示不再重复显示同一类名。
- `1.0.0-r1`：首个交付版；Purpur 1.21.11 实测 sqlite 全功能 8/8 自检通过、mysql 缺驱动降级路径通过。

## 编译构建

```bash
./gradlew build     # JDK 21；产物 build/libs/StarFaction-1.0.0-r2.jar
```
或纯手工：`javac --release 21 -cp <paper-api及其依赖> -d out src/main/java/**/*.java`，
把 `out/` + `src/main/resources/{plugin.yml,config.yml}` 打成 jar 即可。
编译期只依赖 `io.papermc.paper:paper-api`（compileOnly），产物不含第三方库。

## 常见问题（FAQ）

- **Q：只想换阵营颜色/前缀文字？** A：`identity.dawn-prefix / dusk-prefix / dawn-color / dusk-color / chat-prefix / show-glowing`，改完 `/faction reload`。颜色名兼容 GOLD、PURPLE、AQUA 等新旧写法。
- **Q：换正式立绘以外的配置怎么调？** A：全部功能配置都在 `config.yml`，逐项中文注释；物资格式 `材料名:数量`（Bukkit Material 枚举名），buff 格式 `效果名:等级`（HASTE/SATURATION/NIGHT_VISION 等 PotionEffectType 名）。
- **Q：MySQL 端口不是 3306？** A：`database.port` 直接改，插件无硬编码端口。
- **Q：自检里“未找到 JDBC 驱动”只印一个新驱动名（或旧版 jar 印了两遍一样的名字）？** A：正常现象。Paper/Purpur 1.21.x 的插件加载器会在类加载期把旧驱动名字面量改写为新驱动名（官方兼容映射），插件 r2 起已按“配置值优先 + 加载结果去重”处理，不影响功能；确实要用老 5.x 驱动包时把 `database.mysql-driver` 设为 `com.mysql.jdbc.Driver` 即可。
- **Q：多后端服要装几份？** A：每个后端服都装本插件 jar，且 `database.type` 全部改 mysql 指向同一库。
- **Q：基岩端会不会有乱码？** A：表单界面刻意使用纯文本（不含 § 码）；聊天按钮消息中的少量 § 色码 Geyser 会转换，若你的 Geyser 版本过旧显示异常，把 `identity.chat-prefix` 关掉即可回到全纯文本流程。
- **Q：为什么聊天前缀不走 team 自动附加？** A：见“身份四层”③的说明——采用自设 format 方案，双端表现一致。
- **Q：延迟10分钟但我想立刻演示？** A：`choose.delay-minutes: 0`（进服即弹），或直接用 `/faction choose`。
- **Q：卸载会留什么？** A：计分板里两个 `sf_dawn/sf_dusk` 队伍与 factions.db；删掉 jar 后手动执行 `/scoreboard teams remove sf_dawn` / `sf_dusk`（或重载计分板）并删文件即可。
