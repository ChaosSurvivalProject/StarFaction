package com.starinn.faction;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;

/**
 * 星穹旅驿 · 阵营选择插件（StarFaction）
 *
 * 定位：只做阵营选择与阵营身份，世界观讲解由 StarInn 插件负责，互不干扰。
 * 红线：零第三方插件依赖（LuckPerms/PlaceholderAPI/ProtocolLib/Vault/Adventure 均不用，
 * Geyser API 仅做 Class.forName 可选检测）；零网络请求；端口等数字全部可配；
 * 弹窗可关可稍后可命令重开；任一模块失败只降级该功能，绝不导致插件启动失败。
 *
 * 结构：单主类 + 嵌套类（Cfg / Database / Chooser / Identity / BuffManager /
 * BalanceManager / ReminderTask / PlayerListener）。
 */
public final class StarFaction extends JavaPlugin implements CommandExecutor, TabCompleter {

    // ===================== 阵营枚举 =====================

    /** 阵营：黎明（DAWN）/ 暮夜（DUSK）。 */
    public enum Faction {
        DAWN, DUSK;

        /** 由配置字符串（dawn/dusk，忽略大小写）解析阵营，非法返回 null。 */
        public static Faction byName(String s) {
            if (s == null) return null;
            String t = s.trim();
            if (t.isEmpty()) return null;
            if (t.equalsIgnoreCase(DAWN.name())) return DAWN;
            if (t.equalsIgnoreCase(DUSK.name())) return DUSK;
            return null;
        }
    }

    // ===================== 全局句柄 =====================

    private static StarFaction instance;

    Cfg cfg;
    volatile Database db;
    volatile Identity identity;
    volatile BuffManager buffs;
    volatile BalanceManager balance;
    volatile Chooser chooser;
    volatile ReminderTask reminder;
    private PlayerListener playerListener;

    private NamespacedKey keyOnlineRef;   // 上次在线时间锚点（用于 count-offline 计算下线时段）
    private NamespacedKey keyLastPrompt;  // 上次弹窗时间锚点（跨重启仍然生效，避免上线瞬间轰炸）

    /** join 时间锚点（毫秒）。周期保存会滑动锚点，剩余时长 = row.onlineSeconds + (now - 锚点)。 */
    private final Map<UUID, Long> sessionStart = new ConcurrentHashMap<>();
    /** 上次弹窗时间（毫秒，内存为主，PDC 兜底跨重启）。 */
    private final Map<UUID, Long> lastPromptAt = new ConcurrentHashMap<>();
    /** 供其他插件（如 StarInn 引导对话）标记“该玩家正在其他 UI 中”，期间不弹阵营窗。 */
    private final java.util.Set<UUID> externalDeferred = new CopyOnWriteArraySet<>();

    // 自检计数
    private int okCount;
    private int degradedCount;
    private final List<String> degradeNotes = new ArrayList<>();

    /** 外部插件可调：标记/解除某玩家阵营弹窗延迟（其 UI 结束后自动恢复提醒）。 */
    public static void deferPrompt(UUID playerId, boolean deferred) {
        StarFaction p = instance;
        if (p == null || playerId == null) return;
        if (deferred) p.externalDeferred.add(playerId);
        else p.externalDeferred.remove(playerId);
    }

    // ===================== 生命周期 =====================

    @Override
    public void onEnable() {
        instance = this;
        keyOnlineRef = new NamespacedKey(this, "online_ref");
        keyLastPrompt = new NamespacedKey(this, "last_prompt");

        // 1) 配置（任何错误都不抛出，内置默认值兜底）
        cfg = Cfg.load(this);

        // 2) 数据库（连不上则降级内存存储并告警）
        db = Database.create(this);

        // 3) 身份四层（队伍/颜色/发光）
        identity = new Identity(this);
        try {
            identity.setup();
            checkOk("身份标识(名牌/Tab/发光)", "黎明/暮夜队伍已就绪，前缀=" + cfg.dawnPrefix + "/" + cfg.duskPrefix);
        } catch (Throwable t) {
            checkDegraded("身份标识(名牌/Tab/发光)", "队伍初始化失败，仅保留聊天前缀：" + t.getMessage());
        }

        // 4) 人数平衡 + 选择流程 + 基岩端检测
        balance = new BalanceManager(this);
        chooser = new Chooser(this);
        chooser.detectBedrock();

        // 5) 昼夜 buff 任务
        buffs = new BuffManager(this);
        try {
            buffs.start();
            checkOk("昼夜buff任务", "检查间隔=" + cfg.buffCheckIntervalS + "秒，黎明世界=" + cfg.dawnWorld + "，暮夜世界=" + cfg.duskWorld);
        } catch (Throwable t) {
            checkDegraded("昼夜buff任务", "任务启动失败，buff功能暂停：" + t.getMessage());
        }

        // 6) 计时与提醒任务
        reminder = new ReminderTask(this);
        try {
            reminder.start();
            checkOk("计时与提醒", "延迟" + (cfg.delaySeconds / 60) + "分钟弹窗，下线" + (cfg.countOffline ? "计入" : "不计入") + "，提醒间隔" + (cfg.reminderMs / 60000) + "分钟");
        } catch (Throwable t) {
            checkDegraded("计时与提醒", "任务启动失败，稍后可用 /faction choose 手动打开：" + t.getMessage());
        }

        // 7) 事件监听（join/quit/换世界/聊天前缀）
        try {
            playerListener = new PlayerListener(this);
            Bukkit.getPluginManager().registerEvents(playerListener, this);
            checkOk("聊天前缀/事件监听", cfg.chatPrefixEnabled ? "聊天格式 [阵营] 玩家: 消息" : "chat-prefix 已关闭");
        } catch (Throwable t) {
            checkDegraded("事件监听", "监听注册失败：" + t.getMessage());
        }

        // 8) 命令注册（executor 注册为主类，reload 换 chooser 时无需重注册）
        try {
            PluginCommand cmd = getCommand("faction");
            if (cmd == null) {
                checkDegraded("命令注册", "plugin.yml 缺少 faction 命令，命令不可用");
            } else {
                cmd.setExecutor(this);
                cmd.setTabCompleter(this);
                checkOk("命令注册", "/faction choose|switch|info|reload|set");
            }
        } catch (Throwable t) {
            checkDegraded("命令注册", t.getMessage());
        }

        // 9) 在线玩家数据预载（热重载场景）
        reloadOnlineState();

        printSummary();
        getLogger().info("[星穹旅驿] StarFaction 已启用 v" + getDescription().getVersion());
    }

    @Override
    public void onDisable() {
        try {
            Bukkit.getScheduler().cancelTasks(this);
        } catch (Throwable ignore) {
        }
        try {
            saveOnlineProgress(System.currentTimeMillis());
        } catch (Throwable t) {
            getLogger().warning("[星穹旅驿] 停服保存在线时长失败：" + t.getMessage());
        }
        try {
            if (db != null) db.flushAndClose();
        } catch (Throwable t) {
            getLogger().warning("[星穹旅驿] 关闭数据库失败：" + t.getMessage());
        }
        instance = null;
        getLogger().info("[星穹旅驿] StarFaction 已停用");
    }

    /** /faction reload：整体重建各模块，任何一步失败只降级对应功能。 */
    boolean reloadAll() {
        try {
            saveOnlineProgress(System.currentTimeMillis()); // 先把本会话在线增量落进缓存行
        } catch (Throwable ignore) {
        }
        Bukkit.getScheduler().cancelTasks(this);
        HandlerList.unregisterAll(this);
        try {
            if (db != null) db.flushAndClose();
        } catch (Throwable ignore) {
        }
        okCount = 0;
        degradedCount = 0;
        degradeNotes.clear();
        // 重新落一次默认配置（若玩家删了文件），再重载
        try {
            saveDefaultConfig();
        } catch (Throwable ignore) {
        }
        cfg = Cfg.load(this);
        // BUGFIX: 内存降级模式下重建 Database 会得到全新空 cache，导致 reload 后所有玩家
        // 阵营与在线时长被清空。此处保留原内存库实例（数据仍在其中），仅在非内存模式才重建。
        boolean keepMemoryDb = db != null && db.memoryOnly();
        if (!keepMemoryDb) db = Database.create(this);
        identity = new Identity(this);
        try {
            identity.setup();
            checkOk("身份标识(名牌/Tab/发光)", "重建完成");
        } catch (Throwable t) {
            checkDegraded("身份标识(名牌/Tab/发光)", t.getMessage());
        }
        balance = new BalanceManager(this);
        Chooser old = chooser;
        chooser = new Chooser(this);
        if (old != null) chooser.copyBedrockState(old);
        chooser.detectBedrock();
        buffs = new BuffManager(this);
        try {
            buffs.start();
            checkOk("昼夜buff任务", "重启完成");
        } catch (Throwable t) {
            checkDegraded("昼夜buff任务", t.getMessage());
        }
        reminder = new ReminderTask(this);
        try {
            reminder.start();
            checkOk("计时与提醒", "重启完成");
        } catch (Throwable t) {
            checkDegraded("计时与提醒", t.getMessage());
        }
        try {
            playerListener = new PlayerListener(this);
            Bukkit.getPluginManager().registerEvents(playerListener, this);
            checkOk("聊天前缀/事件监听", "重启完成");
        } catch (Throwable t) {
            checkDegraded("事件监听", t.getMessage());
        }
        PluginCommand cmd = getCommand("faction");
        if (cmd == null) {
            checkDegraded("命令注册", "plugin.yml 缺少 faction 命令");
        } else {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
            checkOk("命令注册", "重启完成");
        }
        reloadOnlineState();
        printSummary();
        return true;
    }

    // ===================== 命令入口（委派给当前 Chooser，热替换安全） =====================

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        Chooser c = chooser;
        if (c == null) {
            sender.sendMessage("§c[星穹旅驿] 阵营模块未就绪，请稍后再试。");
            return true;
        }
        return c.handle(sender, label, args);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        Chooser c = chooser;
        return c == null ? new ArrayList<>() : c.tab(sender, args);
    }

    // ===================== 在线时长与会话工具 =====================

    /** 当前会话已在线秒数（无锚点返回 0）。 */
    long activeSessionSec(UUID id, long now) {
        Long st = sessionStart.get(id);
        if (st == null || now <= st) return 0L;
        return (now - st) / 1000L;
    }

    /**
     * 累计“全部在线秒数”（含本会话未落库部分）。
     * row 尚未从库中加载时返回 -1，调用方据此跳过（避免误在加载完成前弹窗）。
     */
    long totalOnlineSec(UUID id) {
        Row r = db == null ? null : db.cachedRow(id);
        if (r == null) return -1L;
        return r.onlineSeconds + activeSessionSec(id, System.currentTimeMillis());
    }

    /** 周期性（约60秒）/退服/停服调用：把本会话增量落库并滑动锚点与 PDC 参考点。 */
    void saveOnlineProgress(long now) {
        Database d = db;
        if (d == null) return;
        for (Player pl : Bukkit.getOnlinePlayers()) {
            try {
                UUID id = pl.getUniqueId();
                Long st = sessionStart.get(id);
                if (st == null) continue;
                long delta = (now - st) / 1000L;
                if (delta > 0) {
                    Row r = d.cachedRow(id);
                    if (r != null) {
                        r.onlineSeconds += delta;
                        d.saveAsync(id.toString(), r);
                        // BUGFIX: 仅当成功累加到缓存行时才滑动锚点；否则保留旧锚点待下次补算，
                        // 避免缓存行尚未加载时该段在线时长被永久丢弃。
                        sessionStart.put(id, now);
                    }
                }
                setPdcLong(pl, keyOnlineRef, now);
            } catch (Throwable t) {
                getLogger().warning("[星穹旅驿] 保存在线进度异常(已忽略)：" + t);
            }
        }
    }

    /** 全员重新挂会话锚 + 从库重载（reload 后与首次启用时用；join 事件覆盖正常上线流程）。 */
    void reloadOnlineState() {
        final long now = System.currentTimeMillis();
        Database d = db;
        if (d == null) return;
        for (Player pl : Bukkit.getOnlinePlayers()) {
            sessionStart.put(pl.getUniqueId(), now);
            d.loadAsync(pl.getUniqueId().toString(), row -> {
                Player cur = Bukkit.getPlayer(pl.getUniqueId());
                if (cur == null) return;
                if (row.faction != null) {
                    Identity i = identity;
                    if (i != null) i.apply(cur, row.faction);
                    BuffManager b = buffs;
                    if (b != null) b.checkPlayer(cur);
                }
            });
        }
    }

    /** 是否应延迟弹窗：外部标记 / 打开了非自身合成栏的容器（如引导对话、商店界面）。 */
    boolean isPromptDeferred(Player pl) {
        if (externalDeferred.contains(pl.getUniqueId())) return true;
        try {
            InventoryType type = pl.getOpenInventory().getType();
            if (type != null && type != InventoryType.CRAFTING) return true;
        } catch (Throwable ignore) {
        }
        return false;
    }

    long lastPromptMs(Player pl) {
        Long mem = lastPromptAt.get(pl.getUniqueId());
        if (mem != null) return mem;
        Long pdc = getPdcLong(pl, keyLastPrompt);
        return pdc == null ? 0L : pdc;
    }

    void recordPrompt(Player pl) {
        long now = System.currentTimeMillis();
        lastPromptAt.put(pl.getUniqueId(), now);
        setPdcLong(pl, keyLastPrompt, now);
    }

    void setPdcLong(Player pl, NamespacedKey key, long v) {
        try {
            pl.getPersistentDataContainer().set(key, PersistentDataType.LONG, v);
        } catch (Throwable ignore) {
        }
    }

    Long getPdcLong(Player pl, NamespacedKey key) {
        try {
            return pl.getPersistentDataContainer().get(key, PersistentDataType.LONG);
        } catch (Throwable t) {
            return null;
        }
    }

    // ===================== 自检与降级记录 =====================

    void checkOk(String name, String note) {
        okCount++;
        getLogger().info("[自检] ✓ " + name + "：" + note);
    }

    void checkDegraded(String name, String note) {
        degradedCount++;
        degradeNotes.add(name + "：" + note);
        getLogger().warning("[自检] ⚠ " + name + " 已降级：" + note);
    }

    void printSummary() {
        getLogger().info("[星穹旅驿] 阵营插件自检完成：正常" + okCount + "项 / 降级" + degradedCount + "项");
        for (String s : degradeNotes) {
            getLogger().warning("[星穹旅驿] 降级明细 → " + s);
        }
    }

    /** 发物品：背包满则自然掉落脚下，不丢失。 */
    void giveKit(Player pl, List<ItemStack> kit, String label) {
        if (kit == null || kit.isEmpty()) return;
        try {
            List<ItemStack> clones = new ArrayList<>(kit.size());
            for (ItemStack s : kit) clones.add(s.clone());
            HashMap<Integer, ItemStack> left = pl.getInventory().addItem(clones.toArray(new ItemStack[0]));
            if (left != null && !left.isEmpty()) {
                for (ItemStack s : left.values()) {
                    pl.getWorld().dropItemNaturally(pl.getLocation(), s);
                }
                pl.sendMessage("§e[星穹旅驿] 背包已满，" + label + "已掉落在你脚下。");
            }
            StringBuilder names = new StringBuilder();
            for (ItemStack s : clones) {
                if (names.length() > 0) names.append("、");
                names.append(itemLabel(s)).append("x").append(s.getAmount());
            }
            pl.sendMessage("§a[星穹旅驿] 已发放【" + label + "】：" + names);
        } catch (Throwable t) {
            getLogger().warning("[星穹旅驿] 发放物资异常(已忽略)：" + t);
        }
    }

    /** 物品中文名兜底（Material 没有官方中文名，取枚举名美化即可，玩家可辨识）。 */
    static String itemLabel(ItemStack s) {
        String n = s.getType().name().toLowerCase(Locale.ROOT).replace('_', ' ');
        return n;
    }

    // =====================================================================
    // 数据行
    // =====================================================================

    /** 一名玩家的阵营记录（内存缓存行，字段全部 volatile，供异步落库时安全读取）。 */
    static final class Row {
        volatile Faction faction;          // null = 尚未选择
        volatile long onlineSeconds;       // 累计在线秒数
        volatile long chosenAt;            // 最近一次选择/切换阵营的毫秒时间戳
        volatile int compensateTier;       // 最近一次选择的补偿档位（0=无；换阵营清 0）
    }

    // =====================================================================
    // 配置模块（Cfg）
    // =====================================================================

    /**
     * 配置模块：任何一项读取失败都回退内置默认值并打印警告（含配置文件与行号信息），
     * 绝不抛异常导致启动失败。所有数字（延迟分钟、阈值、端口等）均可在 config.yml 配置。
     */
    static final class Cfg {
        // choose
        long delaySeconds = 600;          // choose.delay-minutes * 60
        boolean countOffline = true;      // 下线时段是否计入
        long reminderMs = 300_000;        // 稍后再说后重新提醒间隔
        // 物资
        List<ItemStack> starterKit = new ArrayList<>();
        Map<Integer, List<ItemStack>> tierKits = new HashMap<>();
        // 世界
        String dawnWorld = "炽壤";
        String duskWorld = "渊域";
        // buff
        List<EffectSpec> dawnEffects = new ArrayList<>();
        List<EffectSpec> duskEffects = new ArrayList<>();
        long buffCheckIntervalS = 5;
        long effectDurationS = 15;        // 每次施加效果时长（大于检查间隔即可持续）
        long dayStartTicks = 0;
        long dayEndTicks = 12000;
        // balance
        boolean balanceEnabled = true;
        double threshold = 0.2;
        int maxTier = 3;
        // lock
        long switchCooldownMs = 7L * 24 * 3600_000L; // switch-cooldown-days * 天，0=永不锁定
        // identity
        String dawnPrefix = "黎明";
        String duskPrefix = "暮夜";
        ChatColor dawnColor = ChatColor.GOLD;
        ChatColor duskColor = ChatColor.LIGHT_PURPLE;
        boolean showGlowing = true;
        boolean chatPrefixEnabled = true;
        // database
        String dbType = "sqlite";
        String sqliteFilePath = "factions.db";
        String dbTable = "faction_data";
        String mysqlHost = "127.0.0.1";
        int mysqlPort = 3306;
        String mysqlDatabase = "starfaction";
        String mysqlUser = "root";
        String mysqlPass = "";
        String mysqlDriver = "com.mysql.cj.jdbc.Driver";

        /** 一条 buff 配置（类型 + 等级，等级 1 = amplifier 0）。 */
        static final class EffectSpec {
            final PotionEffectType type;
            final int level;

            EffectSpec(PotionEffectType t, int l) {
                type = t;
                level = l;
            }

            int amp() {
                return Math.max(0, level - 1);
            }

            String display() {
                return type.getName() + " " + level;
            }
        }

        /** 配置根文件对象（解析失败时为 jar 内置默认）。 */
        private YamlConfiguration root;
        private boolean usedFallback;

        static Cfg load(StarFaction p) {
            Cfg c = new Cfg();
            try {
                p.saveDefaultConfig();
            } catch (Throwable ignore) {
            }

            // ---- 读取 YAML；语法错误时打印“文件+行号”信息并整体回退内置默认值 ----
            YamlConfiguration y = new YamlConfiguration();
            File f = new File(p.getDataFolder(), "config.yml");
            boolean parseFail = false;
            if (f.exists()) {
                try (InputStreamReader r = new InputStreamReader(new FileInputStream(f), java.nio.charset.StandardCharsets.UTF_8)) {
                    y.load(r);
                } catch (InvalidConfigurationException e) {
                    parseFail = true;
                    p.getLogger().severe("[星穹旅驿] 配置解析失败（config.yml " + e.getMessage() + "），本次全部使用内置默认值兜底");
                } catch (Throwable e) {
                    parseFail = true;
                    p.getLogger().severe("[星穹旅驿] 配置读取失败（config.yml）：" + e + "，本次全部使用内置默认值兜底");
                }
            }
            if (!parseFail && f.exists()) {
                c.root = y;
            } else {
                YamlConfiguration def = new YamlConfiguration();
                try (InputStream in = p.getResource("config.yml")) {
                    if (in != null) def.load(new InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
                } catch (Throwable ignore) {
                }
                c.root = def;
                c.usedFallback = def.getKeys(true).isEmpty(); // 连内置都空才算彻底失败
            }
            p.checkOk("配置加载", parseFail ? "格式错误→内置默认值兜底" : "config.yml 读取正常");

            YamlConfiguration r = c.root;

            // ---- choose ----
            long delayMin = Math.max(0, r.getLong("choose.delay-minutes", 10));
            c.delaySeconds = delayMin * 60L;
            c.countOffline = r.getBoolean("choose.count-offline", true);
            long remMin = Math.max(0, r.getLong("choose.reminder-interval", 5));
            c.reminderMs = remMin * 60_000L;

            // ---- 物资 ----
            c.starterKit = parseKit(p, r.getStringList("starter-kit"), "starter-kit");
            if (c.starterKit.isEmpty()) {
                c.starterKit = parseKit(p, List.of("WOODEN_SWORD:1", "BREAD:8"), "starter-kit(内置默认)");
            }
            c.tierKits.clear();
            try {
                org.bukkit.configuration.ConfigurationSection tiersSec = r.getConfigurationSection("balance.tiers");
                if (tiersSec == null) {
                    p.getLogger().warning("[星穹旅驿] 配置缺少 balance.tiers 段，人数补偿物资已关闭");
                } else {
                for (String k : tiersSec.getKeys(false)) {
                    int tier;
                    try {
                        tier = Integer.parseInt(k.trim());
                    } catch (NumberFormatException e) {
                        p.getLogger().warning("[星穹旅驿] balance.tiers 存在非法档位名 \"" + k + "\"，已跳过该行");
                        continue;
                    }
                    if (tier < 1) continue;
                    List<ItemStack> kit = parseKit(p, r.getStringList("balance.tiers." + k), "balance.tiers." + k);
                    if (!kit.isEmpty()) c.tierKits.put(tier, kit);
                }
                }
            } catch (Throwable ignore) {
            }

            // ---- 世界 ----
            c.dawnWorld = r.getString("dawn-world", c.dawnWorld);
            c.duskWorld = r.getString("dusk-world", c.duskWorld);

            // ---- buff ----
            c.dawnEffects = parseEffects(p, r.getStringList("buff.dawn.effects"), "buff.dawn.effects");
            if (c.dawnEffects.isEmpty()) c.dawnEffects = parseEffects(p, List.of("HASTE:1", "SATURATION:1"), "buff.dawn.effects(内置默认)");
            c.duskEffects = parseEffects(p, r.getStringList("buff.dusk.effects"), "buff.dusk.effects");
            if (c.duskEffects.isEmpty()) c.duskEffects = parseEffects(p, List.of("HASTE:1", "NIGHT_VISION:1"), "buff.dusk.effects(内置默认)");
            c.buffCheckIntervalS = Math.max(1, r.getLong("buff.check-interval", 5));
            c.effectDurationS = Math.max(c.buffCheckIntervalS, r.getLong("buff.effect-duration-seconds", 15));
            c.dayStartTicks = clampTick(r.getLong("buff.day-start", 0));
            c.dayEndTicks = clampTick(r.getLong("buff.day-end", 12000));

            // ---- balance ----
            c.balanceEnabled = r.getBoolean("balance.enabled", true);
            double th = r.getDouble("balance.threshold", 0.2);
            if (th <= 0 || th >= 1) {
                p.getLogger().warning("[星穹旅驿] balance.threshold=" + th + " 不在 (0,1) 内，使用默认 0.2");
                th = 0.2;
            }
            c.threshold = th;
            c.maxTier = Math.max(1, Math.min(64, (int) r.getLong("balance.max-tier", 3)));

            // ---- lock ----
            long days = Math.max(0, r.getLong("lock.switch-cooldown-days", 7));
            c.switchCooldownMs = days * 24L * 3600_000L;

            // ---- identity ----
            c.dawnPrefix = r.getString("identity.dawn-prefix", c.dawnPrefix);
            c.duskPrefix = r.getString("identity.dusk-prefix", c.duskPrefix);
            c.dawnColor = parseColor(p, r.getString("identity.dawn-color", "GOLD"), ChatColor.GOLD, "identity.dawn-color");
            c.duskColor = parseColor(p, r.getString("identity.dusk-color", "PURPLE"), ChatColor.LIGHT_PURPLE, "identity.dusk-color");
            c.showGlowing = r.getBoolean("identity.show-glowing", true);
            c.chatPrefixEnabled = r.getBoolean("identity.chat-prefix", true);

            // ---- database ----
            String t = r.getString("database.type", "sqlite");
            t = t == null ? "sqlite" : t.trim().toLowerCase(Locale.ROOT);
            if (!t.equals("mysql") && !t.equals("sqlite")) {
                p.getLogger().warning("[星穹旅驿] database.type=\"" + t + "\" 无法识别（仅支持 sqlite/mysql），使用 sqlite");
                t = "sqlite";
            }
            c.dbType = t;
            String sf = r.getString("database.sqlite-file", "factions.db");
            File sfFile = new File(sf);
            if (!sfFile.isAbsolute()) {
                // 相对路径按插件数据目录解析（默认即 plugins/StarFaction/factions.db）
                sfFile = new File(p.getDataFolder(), sf);
            }
            c.sqliteFilePath = sfFile.getAbsolutePath();
            c.dbTable = r.getString("database.table", "faction_data").trim();
            if (!c.dbTable.matches("[A-Za-z0-9_]+")) {
                p.getLogger().warning("[星穹旅驿] database.table=\"" + c.dbTable + "\" 含非法字符，回退 faction_data");
                c.dbTable = "faction_data";
            }
            c.mysqlHost = r.getString("database.host", c.mysqlHost);
            long port = r.getLong("database.port", 3306);
            if (port < 1 || port > 65535) {
                p.getLogger().warning("[星穹旅驿] database.port=" + port + " 越界，回退 3306（端口只读取此配置，无硬编码目标）");
                port = 3306;
            }
            c.mysqlPort = (int) port;
            c.mysqlDatabase = r.getString("database.database", c.mysqlDatabase);
            c.mysqlUser = r.getString("database.username", c.mysqlUser);
            c.mysqlPass = r.getString("database.password", "");
            c.mysqlDriver = r.getString("database.mysql-driver", c.mysqlDriver).trim();
            if (c.mysqlDriver.isEmpty()) {
                c.mysqlDriver = "com.mysql.cj.jdbc.Driver";
            }

            // 配置里的单项格式问题已经逐条 warn，这里汇总
            p.getLogger().info("[星穹旅驿] 配置：基础物资" + c.starterKit.size() + "种；档位物资" + c.tierKits.size() + "档；"
                    + "黎明buff" + displayEffects(c.dawnEffects) + " / 暮夜buff" + displayEffects(c.duskEffects));
            return c;
        }

        static String displayEffects(List<EffectSpec> list) {
            StringBuilder sb = new StringBuilder("[");
            for (EffectSpec s : list) {
                if (sb.length() > 1) sb.append(",");
                sb.append(s.display());
            }
            return sb.append("]").toString();
        }

        static long clampTick(long v) {
            return Math.max(0, Math.min(24000L, v));
        }

        /** 解析 "MATERIAL:数量" 列表为 ItemStack；非法条目逐条警告并跳过（对应配置行）。 */
        static List<ItemStack> parseKit(StarFaction p, List<String> list, String path) {
            List<ItemStack> out = new ArrayList<>();
            if (list == null) return out;
            for (int i = 0; i < list.size(); i++) {
                String raw = list.get(i);
                if (raw == null || raw.trim().isEmpty()) continue;
                String[] parts = raw.split(":");
                Material m = Material.matchMaterial(parts[0].trim().toUpperCase(Locale.ROOT));
                if (m == null || !m.isItem()) {
                    p.getLogger().warning("[星穹旅驿] " + path + " 第" + (i + 1) + "条 \"" + raw + "\" 无法识别为物品，已跳过");
                    continue;
                }
                int amount = 1;
                if (parts.length > 1) {
                    try {
                        amount = Integer.parseInt(parts[1].trim());
                    } catch (NumberFormatException e) {
                        p.getLogger().warning("[星穹旅驿] " + path + " 第" + (i + 1) + "条数量非法 \"" + parts[1] + "\"，按1处理");
                    }
                }
                if (amount < 1) amount = 1;
                if (amount > 3600) amount = 3600; // 防御性上限，避免数量爆炸
                int max = Math.max(1, m.getMaxStackSize());
                while (amount > 0) {
                    int n = Math.min(amount, max);
                    out.add(new ItemStack(m, n));
                    amount -= n;
                }
            }
            return out;
        }

        /** 解析 "TYPE:level" 列表为效果规格；非法条目逐条警告并跳过。 */
        static List<EffectSpec> parseEffects(StarFaction p, List<String> list, String path) {
            List<EffectSpec> out = new ArrayList<>();
            if (list == null) return out;
            for (int i = 0; i < list.size(); i++) {
                String raw = list.get(i);
                if (raw == null || raw.trim().isEmpty()) continue;
                String[] parts = raw.split(":");
                PotionEffectType type = PotionEffectType.getByName(parts[0].trim().toUpperCase(Locale.ROOT));
                if (type == null) {
                    p.getLogger().warning("[星穹旅驿] " + path + " 第" + (i + 1) + "条 \"" + raw + "\" 无法识别为效果，已跳过");
                    continue;
                }
                int level = 1;
                if (parts.length > 1) {
                    try {
                        level = Integer.parseInt(parts[1].trim());
                    } catch (NumberFormatException e) {
                        p.getLogger().warning("[星穹旅驿] " + path + " 第" + (i + 1) + "条等级非法 \"" + parts[1] + "\"，按1处理");
                    }
                }
                if (level < 1) level = 1;
                if (level > 255) level = 255;
                out.add(new EffectSpec(type, level));
            }
            return out;
        }

        /** 常用颜色别名：配置里写 PURPLE 等“新式颜色名”时映射到旧版 ChatColor 常量。 */
        static final Map<String, ChatColor> COLOR_ALIASES = Map.of(
                "PURPLE", ChatColor.LIGHT_PURPLE,   // §d 亮紫（暮夜默认色）
                "MAGENTA", ChatColor.LIGHT_PURPLE,
                "ORANGE", ChatColor.GOLD,
                "CYAN", ChatColor.AQUA);

        static ChatColor parseColor(StarFaction p, String name, ChatColor def, String path) {
            String up = name.trim().toUpperCase(Locale.ROOT);
            try {
                return ChatColor.valueOf(up);
            } catch (Throwable t) {
                ChatColor alias = COLOR_ALIASES.get(up);
                if (alias != null) return alias;
                p.getLogger().warning("[星穹旅驿] " + path + "=\"" + name + "\" 不是合法颜色名（如 GOLD/PURPLE），使用默认 " + def.name());
                return def;
            }
        }
    }

    // =====================================================================
    // 数据库模块（Database）
    // =====================================================================

    /**
     * 数据访问层：
     * - 支持 sqlite（单后端服）/ mysql（Velocity 多后端服共用）双模式；
     * - 连接失败自动降级为纯内存存储（不持久化）并告警，绝不影响插件其余功能；
     * - 全部读写走单线程执行器，不阻塞主线程；
     * - JDBC 驱动优先用服务端自带；缺失时从 plugins/StarFaction/drivers/*.jar 本地加载
     *   （自实现类加载，零网络请求、零第三方插件依赖），MySQL 采用 DriverManager/Driver
     *   轻量单连接（所有 SQL 串行化在单线程执行器内，等价于最小连接池）。
     */
    static abstract class Database {
        final StarFaction p;
        final Map<String, Row> cache = new ConcurrentHashMap<>();

        Database(StarFaction p) {
            this.p = p;
        }

        static Database create(StarFaction p) {
            String type = p.cfg.dbType;
            Database db = null;
            if ("mysql".equals(type)) {
                try {
                    db = new MysqlDb(p);
                    p.checkOk("数据库(MySQL)", "已连接 " + p.cfg.mysqlHost + ":" + p.cfg.mysqlPort + "/" + p.cfg.mysqlDatabase + " 表 " + p.cfg.dbTable);
                } catch (Throwable t) {
                    p.getLogger().severe("[星穹旅驿] MySQL 连接失败：" + t);
                    p.getLogger().severe("[星穹旅驿] 提示：若缺驱动，请把 mysql-connector-j 的 jar 放入 plugins/StarFaction/drivers/ 后 /faction reload");
                    db = null;
                }
            } else {
                try {
                    db = new SqliteDb(p);
                    p.checkOk("数据库(SQLite)", "文件 " + p.cfg.sqliteFilePath);
                } catch (Throwable t) {
                    p.getLogger().severe("[星穹旅驿] SQLite 打开失败：" + t);
                    db = null;
                }
            }
            if (db == null) {
                db = new MemoryDb(p);
                p.checkDegraded("数据库", "已降级为内存存储：阵营/时长仅在当前运行期有效，重启后丢失，请检查 database 配置与驱动是否可用");
            }
            return db;
        }

        Row cachedRow(UUID id) {
            return cache.get(id.toString());
        }

        Row ensureRow(String uuid) {
            return cache.computeIfAbsent(uuid, k -> new Row());
        }

        /**
         * 从库里读出并合并进缓存（窄竞态保护：加载期间玩家已抢先在内存里选了阵营时，
         * 保留内存中的选择结果，在线秒数取较大值）。
         */
        Row mergeLoaded(String uuid, Row loaded) {
            if (loaded == null) loaded = new Row();
            final Row fr = loaded;
            cache.merge(uuid, fr, (existing, fresh) -> {
                if (existing.faction == null && fresh.faction != null) {
                    existing.faction = fresh.faction;
                    existing.chosenAt = fresh.chosenAt;
                    existing.compensateTier = fresh.compensateTier;
                }
                existing.onlineSeconds = Math.max(existing.onlineSeconds, fresh.onlineSeconds);
                return existing;
            });
            return cache.get(uuid);
        }

        /** 异步加载；完成后回调已在主线程，参数为缓存行（不存在则新空行，faction==null 表示未选）。 */
        abstract void loadAsync(String uuid, Consumer<Row> onMain);

        abstract void saveAsync(String uuid, Row row);

        /** 停服/重载：把缓存内在线玩家的行刷回库，再关闭资源。同步等待短暂时间。 */
        void flushAndClose() {
            for (Map.Entry<String, Row> en : cache.entrySet()) {
                UUID id = parseUuid(en.getKey());
                if (id != null && Bukkit.getPlayer(id) != null) {
                    saveAsync(en.getKey(), en.getValue());
                }
            }
        }

        static UUID parseUuid(String s) {
            try {
                return UUID.fromString(s);
            } catch (Throwable t) {
                return null;
            }
        }

        boolean memoryOnly() {
            return false;
        }

        /**
         * 打开通用数据库连接：先试服务端自带的 JDBC 驱动（Paper/Purpur 通常内置 sqlite，
         * 多数也内置 mysql 驱动）；不行再加载 plugins/StarFaction/drivers/ 目录下的 jar
         * （URLClassLoader + Driver 直连，绕开 DriverManager 的类加载器可见性限制）。
         */
        static Connection open(StarFaction p, String url, String[] driverClasses, String user, String pass) throws Exception {
            for (String dc : driverClasses) {
                try {
                    Class.forName(dc);
                    if (user == null) {
                        return DriverManager.getConnection(url);
                    }
                    return DriverManager.getConnection(url, user, pass == null ? "" : pass);
                } catch (Throwable ignore) {
                    // 驱动未内置，继续尝试下一个 / 目录加载
                }
            }
            File dir = new File(p.getDataFolder(), "drivers");
            File[] jars = dir.listFiles((d, n) -> n.toLowerCase(Locale.ROOT).endsWith(".jar"));
            if (jars != null && jars.length > 0) {
                URL[] us = new URL[jars.length];
                for (int i = 0; i < jars.length; i++) {
                    us[i] = jars[i].toURI().toURL();
                }
                try (URLClassLoader ucl = new URLClassLoader(us, Database.class.getClassLoader())) {
                    for (String dc : driverClasses) {
                        try {
                            Class<?> c = Class.forName(dc, true, ucl);
                            Driver d = (Driver) c.getDeclaredConstructor().newInstance();
                            java.util.Properties props = new java.util.Properties();
                            if (user != null) props.setProperty("user", user);
                            if (pass != null) props.setProperty("password", pass);
                            Connection conn = d.connect(url, props);
                            if (conn != null) {
                                p.getLogger().info("[星穹旅驿] 已从 drivers/ 目录加载 JDBC 驱动：" + dc);
                                return conn;
                            }
                        } catch (Throwable ignore) {
                        }
                    }
                }
            }
            throw new SQLException("未找到 JDBC 驱动 " + String.join("/", driverClasses)
                    + "；请把驱动 jar 放入 " + dir.getAbsolutePath() + " 后 /faction reload");
        }

        /** 单线程执行器（守护线程），串行化全部 SQL，避免 sqlite/单连接并发问题。 */
        static ExecutorService newDbExecutor(String name) {
            return Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, name);
                t.setDaemon(true);
                return t;
            });
        }
    }

    // ---------------- SQLite ----------------

    static final class SqliteDb extends Database {
        private Connection conn;
        private ExecutorService ex;
        private volatile boolean dead;

        SqliteDb(StarFaction p) throws Exception {
            super(p);
            File f = new File(p.cfg.sqliteFilePath);
            File parent = f.getAbsoluteFile().getParentFile();
            if (parent != null && !parent.exists()) {
                //noinspection ResultOfMethodCallIgnored
                parent.mkdirs();
            }
            conn = Database.open(p, "jdbc:sqlite:" + f.getAbsolutePath(),
                    new String[]{"org.sqlite.JDBC"}, null, null);
            try (Statement st = conn.createStatement()) {
                st.execute("PRAGMA busy_timeout=5000");
                st.execute("CREATE TABLE IF NOT EXISTS " + p.cfg.dbTable + " ("
                        + "uuid TEXT PRIMARY KEY,"
                        + "faction TEXT,"
                        + "online_seconds INTEGER NOT NULL DEFAULT 0,"
                        + "chosen_at INTEGER NOT NULL DEFAULT 0,"
                        + "compensate_tier INTEGER NOT NULL DEFAULT 0)");
            }
            ex = newDbExecutor("StarFaction-SQLite");
        }

        private void submit(Runnable task) {
            if (dead) return;
            ExecutorService e = ex;
            if (e == null || e.isShutdown()) return;
            try {
                e.submit(() -> {
                    try {
                        task.run();
                    } catch (Throwable t) {
                        p.getLogger().warning("[星穹旅驿] SQLite 异步操作失败(该功能本次操作跳过)：" + t);
                    }
                });
            } catch (Throwable rejected) {
                // 执行器已关闭（停服窗口），忽略
            }
        }

        @Override
        void loadAsync(String uuid, Consumer<Row> onMain) {
            submit(() -> {
                Row row = null;
                try (PreparedStatement ps = conn.prepareStatement("SELECT faction, online_seconds, chosen_at, compensate_tier FROM "
                        + p.cfg.dbTable + " WHERE uuid=?")) {
                    ps.setString(1, uuid);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            row = new Row();
                            row.faction = Faction.byName(rs.getString("faction"));
                            row.onlineSeconds = rs.getLong("online_seconds");
                            row.chosenAt = rs.getLong("chosen_at");
                            row.compensateTier = rs.getInt("compensate_tier");
                        }
                    }
                } catch (Throwable t) {
                    p.getLogger().warning("[星穹旅驿] SQLite 读取失败(" + uuid + ")：" + t);
                }
                final Row fr = mergeLoaded(uuid, row);
                Bukkit.getScheduler().runTask(p, () -> onMain.accept(fr));
            });
        }

        @Override
        void saveAsync(String uuid, Row row) {
            submit(() -> {
                try (PreparedStatement ps = conn.prepareStatement("INSERT INTO " + p.cfg.dbTable
                        + " (uuid, faction, online_seconds, chosen_at, compensate_tier) VALUES (?,?,?,?,?)"
                        + " ON CONFLICT(uuid) DO UPDATE SET faction=excluded.faction, online_seconds=excluded.online_seconds,"
                        + " chosen_at=excluded.chosen_at, compensate_tier=excluded.compensate_tier")) {
                    ps.setString(1, uuid);
                    ps.setString(2, row.faction == null ? null : row.faction.name());
                    ps.setLong(3, row.onlineSeconds);
                    ps.setLong(4, row.chosenAt);
                    ps.setInt(5, row.compensateTier);
                    ps.executeUpdate();
                } catch (Throwable t) {
                    p.getLogger().warning("[星穹旅驿] SQLite 写入失败(" + uuid + ")：" + t);
                }
            });
        }

        @Override
        void flushAndClose() {
            super.flushAndClose();
            ExecutorService e = ex;
            if (e != null) {
                e.shutdown();
                try {
                    if (!e.awaitTermination(6, TimeUnit.SECONDS)) {
                        p.getLogger().warning("[星穹旅驿] SQLite 刷写超时（6秒），放弃剩余任务");
                    }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
            dead = true;
            try {
                if (conn != null) conn.close();
            } catch (Throwable ignore) {
            }
        }
    }

    // ---------------- MySQL ----------------

    static final class MysqlDb extends Database {
        private Connection conn;
        private ExecutorService ex;
        private volatile boolean dead;
        private static final String[] DRIVERS = {"com.mysql.cj.jdbc.Driver", "com.mysql.jdbc.Driver"};

        /**
         * 组装尝试的驱动类名列表：配置项 database.mysql-driver 优先（字符串来自 config.yml，
         * 不经过服务端插件类加载期的常量重映射，极端旧驱动环境可手动指定生效）；
         * 再补内置候选并用 LinkedHashSet 去重——Paper/Purpur 1.21.x 的 mapgroup 会把类内
         * 字面量 "com.mysql.jdbc.Driver" 在加载期改写为新驱动名，去重后错误提示也不会重复显示同一个名字。
         */
        static String[] driverList(StarFaction p) {
            java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<>();
            if (p != null && p.cfg != null && p.cfg.mysqlDriver != null) {
                set.add(p.cfg.mysqlDriver);
            }
            set.addAll(java.util.Arrays.asList(DRIVERS));
            return set.toArray(new String[0]);
        }

        MysqlDb(StarFaction p) throws Exception {
            super(p);
            conn = openUrl(p);
            try (Statement st = conn.createStatement()) {
                st.execute("CREATE TABLE IF NOT EXISTS " + p.cfg.dbTable + " ("
                        + "uuid VARCHAR(36) PRIMARY KEY,"
                        + "faction VARCHAR(8) NULL,"
                        + "online_seconds BIGINT NOT NULL DEFAULT 0,"
                        + "chosen_at BIGINT NOT NULL DEFAULT 0,"
                        + "compensate_tier INT NOT NULL DEFAULT 0) DEFAULT CHARSET=utf8mb4");
            }
            ex = newDbExecutor("StarFaction-MySQL");
        }

        private static Connection openUrl(StarFaction p) throws Exception {
            String url = buildUrl(p.cfg);
            return Database.open(p, url, driverList(p), p.cfg.mysqlUser, p.cfg.mysqlPass);
        }

        /** 轻量重连：所有 SQL 已串行化在单线程执行器内，这里只保一条活连接。 */
        private void ensureConn() {
            try {
                if (conn != null && !conn.isClosed()) return;
            } catch (Throwable ignore) {
            }
            try {
                if (conn != null) conn.close();
            } catch (Throwable ignore) {
            }
            try {
                conn = openUrl(p);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        private static String buildUrl(Cfg c) {
            return "jdbc:mysql://" + c.mysqlHost + ":" + c.mysqlPort + "/" + c.mysqlDatabase
                    + "?useSSL=false&characterEncoding=UTF-8&serverTimezone=UTC&connectTimeout=5000&socketTimeout=15000";
        }

        private void submit(Runnable task) {
            if (dead) return;
            ExecutorService e = ex;
            if (e == null || e.isShutdown()) return;
            try {
                e.submit(() -> {
                    try {
                        ensureConn();
                        task.run();
                    } catch (Throwable t) {
                        p.getLogger().warning("[星穹旅驿] MySQL 操作失败(本次操作跳过，稍后自动重连)：" + t);
                    }
                });
            } catch (Throwable rejected) {
            }
        }

        @Override
        void loadAsync(String uuid, Consumer<Row> onMain) {
            submit(() -> {
                Row row = null;
                try (PreparedStatement ps = conn.prepareStatement("SELECT faction, online_seconds, chosen_at, compensate_tier FROM "
                        + p.cfg.dbTable + " WHERE uuid=?")) {
                    ps.setString(1, uuid);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            row = new Row();
                            row.faction = Faction.byName(rs.getString("faction"));
                            row.onlineSeconds = rs.getLong("online_seconds");
                            row.chosenAt = rs.getLong("chosen_at");
                            row.compensateTier = rs.getInt("compensate_tier");
                        }
                    }
                } catch (Throwable t) {
                    p.getLogger().warning("[星穹旅驿] MySQL 读取失败(" + uuid + ")：" + t);
                }
                final Row fr = mergeLoaded(uuid, row);
                Bukkit.getScheduler().runTask(p, () -> onMain.accept(fr));
            });
        }

        @Override
        void saveAsync(String uuid, Row row) {
            submit(() -> {
                try (PreparedStatement ps = conn.prepareStatement("INSERT INTO " + p.cfg.dbTable
                        + " (uuid, faction, online_seconds, chosen_at, compensate_tier) VALUES (?,?,?,?,?)"
                        + " ON DUPLICATE KEY UPDATE faction=VALUES(faction), online_seconds=VALUES(online_seconds),"
                        + " chosen_at=VALUES(chosen_at), compensate_tier=VALUES(compensate_tier)")) {
                    ps.setString(1, uuid);
                    if (row.faction == null) ps.setNull(2, java.sql.Types.VARCHAR);
                    else ps.setString(2, row.faction.name());
                    ps.setLong(3, row.onlineSeconds);
                    ps.setLong(4, row.chosenAt);
                    ps.setInt(5, row.compensateTier);
                    ps.executeUpdate();
                } catch (Throwable t) {
                    p.getLogger().warning("[星穹旅驿] MySQL 写入失败(" + uuid + ")：" + t);
                }
            });
        }

        @Override
        void flushAndClose() {
            super.flushAndClose();
            ExecutorService e = ex;
            if (e != null) {
                e.shutdown();
                try {
                    if (!e.awaitTermination(6, TimeUnit.SECONDS)) {
                        p.getLogger().warning("[星穹旅驿] MySQL 刷写超时（6秒）");
                    }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
            dead = true;
            try {
                if (conn != null) conn.close();
            } catch (Throwable ignore) {
            }
        }
    }

    // ---------------- 内存降级 ----------------

    static final class MemoryDb extends Database {
        MemoryDb(StarFaction p) {
            super(p);
        }

        @Override
        boolean memoryOnly() {
            return true;
        }

        @Override
        void loadAsync(String uuid, Consumer<Row> onMain) {
            Row r = cache.computeIfAbsent(uuid, k -> new Row());
            if (Bukkit.isPrimaryThread()) onMain.accept(r);
            else Bukkit.getScheduler().runTask(p, () -> onMain.accept(r));
        }

        @Override
        void saveAsync(String uuid, Row row) {
            cache.put(uuid, row); // 缓存即存储本体，无 IO
        }
    }

    // =====================================================================
    // 身份四层（Identity）
    // =====================================================================

    /**
     * 身份标识：
     * ① 头顶名牌前缀：ScoreboardTeam.setPrefix（显示在头顶名牌）
     * ② Tab 列表颜色：Team.setColor（GOLD / PURPLE，可在配置改名/改色）
     * ③ 聊天前缀：见 PlayerListener（setFormat 方案，README 有取舍说明）
     * ④ 发光轮廓：Player.setGlowing（try-catch + show-glowing 开关，基岩端不支持自动关）
     * 重建时机：join 从库重读重建 / 换世界刷新 / 跨后端服靠每服 join 重建。
     */
    static final class Identity {
        static final String TEAM_DAWN = "sf_dawn";
        static final String TEAM_DUSK = "sf_dusk";
        private final StarFaction p;
        private volatile boolean glowingBroken;

        Identity(StarFaction p) {
            this.p = p;
        }

        private Scoreboard scoreboard() {
            return Bukkit.getScoreboardManager().getMainScoreboard();
        }

        void setup() {
            Scoreboard sb = scoreboard();
            for (String name : new String[]{TEAM_DAWN, TEAM_DUSK}) {
                Team old = sb.getTeam(name);
                if (old != null) old.unregister(); // 重建，保证重载后前缀/颜色跟配置一致
            }
            buildTeam(sb, TEAM_DAWN, "黎明", p.cfg.dawnPrefix, p.cfg.dawnColor);
            buildTeam(sb, TEAM_DUSK, "暮夜", p.cfg.duskPrefix, p.cfg.duskColor);
        }

        private void buildTeam(Scoreboard sb, String internal, String disp, String prefix, ChatColor color) {
            Team t = sb.registerNewTeam(internal);
            t.setDisplayName(disp);
            // 头顶/Tab 前缀：颜色 + 文字 + 复位（Team 自带 prefix 会同时显示在头顶名牌与 Tab）
            t.setPrefix(color.toString() + "[" + prefix + "]" + ChatColor.RESET + " ");
            t.setSuffix("");
            try {
                t.setColor(color);
            } catch (Throwable ignore) {
                // 某些端（如基岩经 Geyser 转换）不支持队伍颜色时，前缀里已带颜色，不影响显示
            }
        }

        void apply(Player pl, Faction f) {
            try {
                Scoreboard sb = scoreboard();
                Team old = sb.getEntryTeam(pl.getName());
                if (old != null && !old.getName().equals(f == Faction.DAWN ? TEAM_DAWN : TEAM_DUSK)) {
                    old.removeEntry(pl.getName());
                }
                Team t = sb.getTeam(f == Faction.DAWN ? TEAM_DAWN : TEAM_DUSK);
                if (t != null) t.addEntry(pl.getName());
            } catch (Throwable t) {
                p.getLogger().warning("[星穹旅驿] 加入队伍失败(" + pl.getName() + ")：" + t);
            }
            if (p.cfg.showGlowing && !glowingBroken) {
                try {
                    pl.setGlowing(true);
                } catch (Throwable e) {
                    glowingBroken = true;
                    p.checkDegraded("发光轮廓", "该端不支持 setGlowing，已自动关闭（可在配置 identity.show-glowing 保持关闭）");
                }
            }
        }

        /** 退服时摘掉队伍，避免跨服计分板残留。 */
        void clear(Player pl) {
            try {
                Scoreboard sb = scoreboard();
                Team old = sb.getEntryTeam(pl.getName());
                if (old != null && (old.getName().equals(TEAM_DAWN) || old.getName().equals(TEAM_DUSK))) {
                    old.removeEntry(pl.getName());
                }
            } catch (Throwable ignore) {
            }
            if (!glowingBroken) {
                try {
                    pl.setGlowing(false);
                } catch (Throwable ignore) {
                }
            }
        }
    }

    // =====================================================================
    // 人数平衡（BalanceManager）
    // =====================================================================

    /**
     * 阵营人数平衡补偿：
     * 只统计“在线且已选阵营”的玩家（不含正在选择的本人）；
     * 差值比例 = |A-B| / max(A,B)；只有新人加入的是人数少的一方且比例>threshold 才触发；
     * 分档采用“threshold→1.0 之间线性等分 maxTier 段”：
     * 段宽 = (1-threshold)/maxTier，段数封顶 maxTier，防无限堆。
     * 例：threshold=0.2、max-tier=3 时：[0.2,0.4667)=档1，[0.4667,0.7333)=档2，[0.7333,1]=档3。
     */
    static final class BalanceManager {
        private final StarFaction p;

        BalanceManager(StarFaction p) {
            this.p = p;
        }

        /** 当前在线已选人数（不含 exclude，可为 null）。 */
        int[] countOnline(Faction joinSide, UUID exclude) {
            int a = 0, b = 0;
            Database d = p.db;
            if (d == null) return new int[]{0, 0};
            for (Player pl : Bukkit.getOnlinePlayers()) {
                if (exclude != null && exclude.equals(pl.getUniqueId())) continue;
                Row r = d.cachedRow(pl.getUniqueId());
                if (r == null || r.faction == null) continue;
                if (r.faction == Faction.DAWN) a++;
                else b++;
            }
            return new int[]{a, b};
        }

        /**
         * 计算本次加入 joinSide 应得的补偿档（0=无）。
         * 只在“加入人数少的一方”时触发。
         */
        int tierForJoin(Faction joinSide, UUID self) {
            Cfg c = p.cfg;
            if (!c.balanceEnabled) return 0;
            int[] cnt = countOnline(joinSide, self);
            int mine = joinSide == Faction.DAWN ? cnt[0] : cnt[1];
            int other = joinSide == Faction.DAWN ? cnt[1] : cnt[0];
            if (other <= mine) return 0;            // 加入的不是劣势方
            double ratio = (double) (other - mine) / (double) other;
            if (ratio <= c.threshold) return 0;
            double band = (1.0 - c.threshold) / c.maxTier;
            int tier = (int) Math.floor((ratio - c.threshold) / band) + 1;
            if (tier < 1) tier = 1;
            if (tier > c.maxTier) tier = c.maxTier;
            return tier;
        }

        /** 取某档补偿物资（配置缺档则逐级降档）。 */
        List<ItemStack> kitForTier(int tier) {
            Map<Integer, List<ItemStack>> kits = p.cfg.tierKits;
            for (int t = tier; t >= 1; t--) {
                List<ItemStack> kit = kits.get(t);
                if (kit != null && !kit.isEmpty()) return kit;
            }
            return List.of();
        }
    }

    // =====================================================================
    // 昼夜 buff（BuffManager）
    // =====================================================================

    /**
     * 昼夜 buff：
     * - 黎明玩家在黎明世界且该世界为白天 → dawn.effects；
     * - 暮夜玩家在暮夜世界且该世界为黑夜 → dusk.effects；
     * - 白天判定 world.getTime()%24000 ∈ [day-start, day-end)，黑夜为其余时段；
     * - 两个世界时间天然反相，各读各的即可，玩家跑错世界/错峰时段 buff 自动消失；
     * - SATURATION 为瞬时限时长效果，靠“检查间隔内剩余时长不足则续施”实现持续生效。
     */
    static final class BuffManager {
        private final StarFaction p;
        private volatile BukkitRunnable task;
        private final StringBuilder lastActive = new StringBuilder(); // info 命令展示当前 buff 状态用

        BuffManager(StarFaction p) {
            this.p = p;
        }

        void start() {
            stop();
            long period = p.cfg.buffCheckIntervalS * 20L;
            task = new BukkitRunnable() {
                @Override
                public void run() {
                    try {
                        for (Player pl : Bukkit.getOnlinePlayers()) {
                            checkPlayer(pl);
                        }
                    } catch (Throwable t) {
                        p.getLogger().warning("[星穹旅驿] buff 检查异常(已忽略，下一轮继续)：" + t);
                    }
                }
            };
            task.runTaskTimer(p, period, period);
        }

        void stop() {
            BukkitRunnable t = task;
            if (t != null) {
                try {
                    t.cancel();
                } catch (Throwable ignore) {
                }
                task = null;
            }
        }

        static boolean isDay(World w, Cfg c) {
            long t = w.getTime() % 24000L;
            if (c.dayStartTicks <= c.dayEndTicks) {
                return t >= c.dayStartTicks && t < c.dayEndTicks;
            }
            // 配置成跨 0 点区间时（如 day-start>day-end）按环绕处理
            return t >= c.dayStartTicks || t < c.dayEndTicks;
        }

        /** 玩家当前该不该有 buff 的判定，供 checkPlayer 与 info 命令复用。 */
        boolean conditionMet(Player pl, Faction f) {
            Cfg c = p.cfg;
            World w = pl.getWorld();
            if (f == Faction.DAWN) {
                return w.getName().equals(c.dawnWorld) && isDay(w, c);
            }
            return w.getName().equals(c.duskWorld) && !isDay(w, c);
        }

        void checkPlayer(Player pl) {
            Database d = p.db;
            if (d == null) return;
            Row r = d.cachedRow(pl.getUniqueId());
            List<Cfg.EffectSpec> union = new ArrayList<>();
            union.addAll(p.cfg.dawnEffects);
            union.addAll(p.cfg.duskEffects);
            if (r == null || r.faction == null) {
                // 未选阵营：确保身上没有本插件的 buff
                for (Cfg.EffectSpec s : union) {
                    removeIfPresent(pl, s);
                }
                return;
            }
            List<Cfg.EffectSpec> list = r.faction == Faction.DAWN ? p.cfg.dawnEffects : p.cfg.duskEffects;
            boolean active = conditionMet(pl, r.faction);
            for (Cfg.EffectSpec s : list) {
                if (active) {
                    apply(pl, s);
                } else {
                    removeIfPresent(pl, s);
                }
            }
        }

        private void apply(Player pl, Cfg.EffectSpec s) {
            try {
                int durTicks = (int) Math.min(Integer.MAX_VALUE - 8L, p.cfg.effectDurationS * 20L);
                PotionEffect cur = pl.getPotionEffect(s.type);
                // 剩余时长不足 2 个检查周期 → 续施（SATURATION 这类短时效果因此持续生效）
                if (cur == null || cur.getAmplifier() != s.amp() || cur.getDuration() <= p.cfg.buffCheckIntervalS * 40L) {
                    // ambient+无粒子：不遮挡、不刷屏
                    pl.addPotionEffect(new PotionEffect(s.type, durTicks, s.amp(), true, false));
                }
            } catch (Throwable t) {
                p.getLogger().warning("[星穹旅驿] 施加效果失败(" + s.type.getName() + ")：" + t);
            }
        }

        private void removeIfPresent(Player pl, Cfg.EffectSpec s) {
            try {
                if (pl.hasPotionEffect(s.type)) pl.removePotionEffect(s.type);
            } catch (Throwable ignore) {
            }
        }

        /** 阵营切换/退服时清掉本插件可能施加过的全部效果（不影响其他插件/药水的效果）。 */
        void clearAll(Player pl) {
            try {
                for (Cfg.EffectSpec s : p.cfg.dawnEffects) removeIfPresent(pl, s);
                for (Cfg.EffectSpec s : p.cfg.duskEffects) removeIfPresent(pl, s);
            } catch (Throwable ignore) {
            }
        }

        /** info 命令用：描述当前生效情况。 */
        String describeFor(Player pl) {
            Database d = p.db;
            if (d == null) return "数据未就绪";
            Row r = d.cachedRow(pl.getUniqueId());
            if (r == null) return "数据加载中";
            if (r.faction == null) return "未选阵营（无buff）";
            boolean active = conditionMet(pl, r.faction);
            List<Cfg.EffectSpec> list = r.faction == Faction.DAWN ? p.cfg.dawnEffects : p.cfg.duskEffects;
            if (!active) {
                return "当前条件不满足（需在" + (r.faction == Faction.DAWN ? p.cfg.dawnWorld + "的白天/" : p.cfg.duskWorld + "的黑夜") + "）";
            }
            StringBuilder sb = new StringBuilder();
            for (Cfg.EffectSpec s : list) {
                boolean has = pl.hasPotionEffect(s.type);
                if (sb.length() > 0) sb.append("、");
                sb.append(s.display()).append(has ? "✓" : "等待施加");
            }
            return sb.toString();
        }
    }

    // =====================================================================
    // 计时与提醒（ReminderTask）
    // =====================================================================

    /**
     * 周期任务（每 5 秒）：
     * - 累计在线（含按配置计入的下线时段）达到 delay-minutes → 弹阵营选择；
     * - 玩家在其他 UI（打开容器/被外部插件标记）中 → 本轮跳过，结束后自动补弹；
     * - 点“稍后再说”不标记完成，每 reminder-interval 分钟再提醒，允许一直不选；
     * - 每约 60 秒把在线增量落库并滑动锚点（掉线不清零、崩溃少丢一分钟）。
     */
    static final class ReminderTask {
        private final StarFaction p;
        private volatile BukkitRunnable task;
        private int tick;

        ReminderTask(StarFaction p) {
            this.p = p;
        }

        void start() {
            stop();
            task = new BukkitRunnable() {
                @Override
                public void run() {
                    loop();
                }
            };
            task.runTaskTimer(p, 100L, 100L); // 5 秒
        }

        void stop() {
            BukkitRunnable t = task;
            if (t != null) {
                try {
                    t.cancel();
                } catch (Throwable ignore) {
                }
                task = null;
            }
        }

        private void loop() {
            try {
                long now = System.currentTimeMillis();
                for (Player pl : Bukkit.getOnlinePlayers()) {
                    Row r = p.db == null ? null : p.db.cachedRow(pl.getUniqueId());
                    if (r == null) continue;                  // 还没从库里加载完
                    if (r.faction != null) continue;          // 已选过阵营，永不弹窗
                    long total = r.onlineSeconds + p.activeSessionSec(pl.getUniqueId(), now);
                    if (total < p.cfg.delaySeconds) continue; // 前 N 分钟零打扰
                    if (p.isPromptDeferred(pl)) continue;     // 正在其他 UI，延后
                    if (now - p.lastPromptMs(pl) < p.cfg.reminderMs) continue;
                    p.chooser.prompt(pl, false);
                }
                if (++tick % 12 == 0) {                       // 约 60 秒保存一次
                    p.saveOnlineProgress(now);
                }
            } catch (Throwable t) {
                p.getLogger().warning("[星穹旅驿] 提醒任务异常(已忽略，下一轮继续)：" + t);
            }
        }
    }

    // =====================================================================
    // 选择流程 + 双端界面 + 命令（Chooser）
    // =====================================================================

    /**
     * 阵营选择核心：
     * - Java 端：可点击聊天消息（ClickEvent 执行命令），两阵营按钮 + 稍后再说，不用书本不用 GUI；
     * - 基岩端：Class.forName 检测 Geyser，存在则反射构造 SimpleForm，不可用则降级聊天（不报错）；
     * - 选择瞬间同步完成：发基础物资 → 按人数差发劣势补偿（有上限）→ 异步写库 → 立即打身份四层 →
     *   立即按所在世界时间施加 buff。
     */
    static final class Chooser implements CommandExecutor {
        private final StarFaction p;

        enum BedrockMode { UNKNOWN, NO_GEYSER, FORM_BROKEN, FORM_OK }

        private volatile BedrockMode bedrock = BedrockMode.UNKNOWN;
        private volatile int formFailures;
        private volatile boolean formWarnLogged;

        Chooser(StarFaction p) {
            this.p = p;
        }

        void copyBedrockState(Chooser old) {
            this.bedrock = old.bedrock;
        }

        // ---------------- 基岩端检测 ----------------

        void detectBedrock() {
            if (bedrock != BedrockMode.UNKNOWN && bedrock != BedrockMode.NO_GEYSER) return;
            try {
                Class<?> geyser = Class.forName("org.geysermc.geyser.GeyserImpl");
                Class<?> form = Class.forName("org.geysermc.cumulus.form.SimpleForm");
                if (geyser != null && form != null) {
                    bedrock = BedrockMode.FORM_OK;
                    p.checkOk("基岩端界面", "检测到 Geyser，使用表单弹窗（反射失败会自动降级聊天）");
                } else {
                    bedrock = BedrockMode.NO_GEYSER;
                    p.checkOk("基岩端界面", "未检测到 Geyser（纯 Java 服）");
                }
            } catch (ClassNotFoundException e) {
                bedrock = BedrockMode.NO_GEYSER;
                p.checkOk("基岩端界面", "未检测到 Geyser（纯 Java 服），统一使用聊天弹窗");
            } catch (Throwable t) {
                bedrock = BedrockMode.FORM_BROKEN;
                p.checkDegraded("基岩端界面", "Geyser 检测异常，基岩端降级为聊天命令：" + t);
            }
        }

        // ---------------- 弹窗 ----------------

        /** 弹阵营选择窗（自动/手动共用）。force=true 时忽略“其他UI延迟”。 */
        void prompt(Player pl, boolean force) {
            Database d = p.db;
            Row r = d == null ? null : d.cachedRow(pl.getUniqueId());
            if (r != null && r.faction != null) {
                pl.sendMessage("§e[星穹旅驿] 你已选择【" + factionLabel(r.faction) + "】，改阵营请用 /faction switch <dawn|dusk>。");
                return;
            }
            if (!force && p.isPromptDeferred(pl)) return;
            detectBedrock();
            p.recordPrompt(pl);
            if (bedrock == BedrockMode.FORM_OK && tryBedrockForm(pl)) return;
            sendChatPrompt(pl);
        }

        /** Java 端 & 降级路径：可点击聊天消息。 */
        private void sendChatPrompt(Player pl) {
            Cfg c = p.cfg;
            TextComponent head = new TextComponent("§6§l「星穹旅驿」§r§7 星辰已就位，请选出你归属的阵营（点击按钮或输入命令均可）：");
            pl.spigot().sendMessage(head);

            TextComponent dawn = new TextComponent(" §6▶ [黎明 · " + c.dawnWorld + "]  白昼加速采掘、饱和无忧");
            dawn.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/faction choose dawn"));
            dawn.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                    new BaseComponent[]{new TextComponent("§6点击加入【黎明】")}));
            pl.spigot().sendMessage(dawn);

            TextComponent dusk = new TextComponent(" §5▶ [暮夜 · " + c.duskWorld + "]  黑夜加速采掘、视野无碍");
            dusk.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/faction choose dusk"));
            dusk.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                    new BaseComponent[]{new TextComponent("§5点击加入【暮夜】")}));
            pl.spigot().sendMessage(dusk);

            TextComponent later = new TextComponent(" §7○ [稍后再说]  继续自由游玩，稍后会再次提醒（也可随时 /faction choose）");
            later.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/faction later"));
            later.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                    new BaseComponent[]{new TextComponent("§7暂不选择")}));
            pl.spigot().sendMessage(later);
        }

        // ---------------- 基岩端 SimpleForm（全反射，失败即降级） ----------------

        private boolean tryBedrockForm(Player pl) {
            try {
                Class<?> geyserImplC = Class.forName("org.geysermc.geyser.GeyserImpl");
                Object impl = geyserImplC.getMethod("getInstance").invoke(null);
                if (impl == null) return fail(pl, "GeyserImpl.getInstance()=null");
                Object connMgr = callNoArg(impl, "connectionManager", "getConnectionManager");
                if (connMgr == null) return fail(pl, "connectionManager 不可用");
                Object session = findSession(connMgr, pl);
                if (session == null) return false; // 该玩家不是基岩会话：正常走聊天，不算失败

                Class<?> simpleFormC = Class.forName("org.geysermc.cumulus.form.SimpleForm");
                Object builder = newFormBuilder(simpleFormC);
                if (builder == null) return fail(pl, "SimpleForm 构造器缺失");

                callOne(builder, "title", "星穹旅驿 · 阵营选择");
                callOne(builder, "content", buildFormText(pl));
                boolean dataButtons = callButtonWith(builder, p.cfg.dawnPrefix + " · " + p.cfg.dawnWorld + "（黎明）", "DAWN")
                        & callButtonWith(builder, p.cfg.duskPrefix + " · " + p.cfg.duskWorld + "（暮夜）", "DUSK")
                        & callButtonWith(builder, "稍后再说", "LATER");
                if (!dataButtons) {
                    callButtonPlain(builder, p.cfg.dawnPrefix + "（黎明）");
                    callButtonPlain(builder, p.cfg.duskPrefix + "（暮夜）");
                    callButtonPlain(builder, "稍后再说");
                }
                // responder：点击后在回调里识别按钮并执行命令；Geyser 可能从非主线程回调 → 一律调度回主线程
                Consumer<Object> handler = form -> onFormSubmit(pl, form);
                if (!callResponder(builder, handler)) return fail(pl, "SimpleForm.responder 不可用");

                Method buildM = builder.getClass().getMethod("build");
                Object form = buildM.invoke(builder);
                if (!sendForm(session, form)) return fail(pl, "表单发送通道缺失(showForm/sendForm/FormUtils)");
                formFailures = 0;
                return true;
            } catch (Throwable t) {
                return fail(pl, "表单流程异常：" + t);
            }
        }

        private String buildFormText(Player pl) {
            long total = Math.max(0, p.totalOnlineSec(pl.getUniqueId()));
            return "冒险者 " + pl.getName() + "，累计在线 " + (total / 60) + " 分钟，抵达星穹旅驿。\n"
                    + "黎明（世界「" + p.cfg.dawnWorld + "」白天）：急迫 I + 饱和\n"
                    + "暮夜（世界「" + p.cfg.duskWorld + "」黑夜）：急迫 I + 夜视\n"
                    + "阵营仅影响采集/生存增益，不改变战斗。";
        }

        private void onFormSubmit(Player pl, Object form) {
            String cmd = null;
            try {
                Object data = tryCallNoArg(form, "clickedButtonData");
                if (data instanceof String s && !s.isEmpty()) {
                    if ("DAWN".equalsIgnoreCase(s)) cmd = "faction choose dawn";
                    else if ("DUSK".equalsIgnoreCase(s)) cmd = "faction choose dusk";
                    // LATER：稍后再说，不执行选择
                } else {
                    Object id = tryCallNoArg(form, "clickedButtonId");
                    if (id instanceof Number n) {
                        int v = n.intValue(); // Cumulus 的 clickedButtonId 为 1 基
                        if (v == 1) cmd = "faction choose dawn";
                        else if (v == 2) cmd = "faction choose dusk";
                    } else {
                        Object raw = tryCallNoArg(form, "clickedButtonRawId");
                        if (raw instanceof Number n) {
                            int v = n.intValue(); // raw 为 0 基
                            if (v == 0) cmd = "faction choose dawn";
                            else if (v == 1) cmd = "faction choose dusk";
                        }
                    }
                }
            } catch (Throwable ignore) {
            }
            final String fcmd = cmd;
            Bukkit.getScheduler().runTask(p, () -> {
                if (pl.isOnline()) {
                    if (fcmd != null) pl.performCommand(fcmd);
                    else pl.sendMessage("§7[星穹旅驿] 好的，随时可用 /faction choose 打开选择界面。");
                }
            });
        }

        private boolean fail(Player pl, String why) {
            formFailures++;
            if (!formWarnLogged && formFailures >= 2) {
                formWarnLogged = true;
                bedrock = BedrockMode.FORM_BROKEN;
                p.checkDegraded("基岩端界面", "Geyser 表单不可用，已降级为聊天命令：" + why);
            }
            return false;
        }

        // ---------------- 反射小工具 ----------------

        private static Object callNoArg(Object target, String... names) {
            for (String n : names) {
                try {
                    return target.getClass().getMethod(n).invoke(target);
                } catch (Throwable ignore) {
                }
            }
            return null;
        }

        private static Object tryCallNoArg(Object target, String n) {
            try {
                return target.getClass().getMethod(n).invoke(target);
            } catch (Throwable t) {
                return null;
            }
        }

        private static void callOne(Object target, String name, String arg) {
            try {
                target.getClass().getMethod(name, String.class).invoke(target, arg);
            } catch (Throwable ignore) {
            }
        }

        private static Object newFormBuilder(Class<?> simpleFormC) {
            String[] creators = {"builder", "create"};
            for (String n : creators) {
                try {
                    Method m = simpleFormC.getMethod(n);
                    Object o = m.invoke(null);
                    if (o != null) return o;
                } catch (Throwable ignore) {
                }
                try {
                    Method m = simpleFormC.getMethod(n, String.class);
                    Object o = m.invoke(null, "");
                    if (o != null) return o;
                } catch (Throwable ignore) {
                }
            }
            // 兜底：任何静态方法返回名含 Builder 的无参方法
            for (Method m : simpleFormC.getMethods()) {
                if (java.lang.reflect.Modifier.isStatic(m.getModifiers())
                        && m.getParameterCount() == 0
                        && m.getReturnType().getSimpleName().contains("Builder")) {
                    try {
                        Object o = m.invoke(null);
                        if (o != null) return o;
                    } catch (Throwable ignore) {
                    }
                }
            }
            return null;
        }

        private static boolean callButtonWith(Object builder, String text, String data) {
            try {
                builder.getClass().getMethod("button", String.class, String.class).invoke(builder, text, data);
                return true;
            } catch (Throwable t) {
                return false;
            }
        }

        private static void callButtonPlain(Object builder, String text) {
            try {
                builder.getClass().getMethod("button", String.class).invoke(builder, text);
            } catch (Throwable ignore) {
            }
        }

        private static boolean callResponder(Object builder, Consumer<Object> handler) {
            try {
                builder.getClass().getMethod("responder", Consumer.class).invoke(builder, handler);
                return true;
            } catch (Throwable t) {
                return false;
            }
        }

        private static Object findSession(Object connMgr, Player pl) {
            for (Method m : connMgr.getClass().getMethods()) {
                if (!m.getName().equals("getSession") || m.getParameterCount() != 1) continue;
                Class<?> pt = m.getParameterTypes()[0];
                try {
                    Object s;
                    if (pt.isAssignableFrom(Player.class)) s = m.invoke(connMgr, pl);
                    else if (pt == UUID.class) s = m.invoke(connMgr, pl.getUniqueId());
                    else continue;
                    if (s != null) return s;
                } catch (Throwable ignore) {
                }
            }
            return null;
        }

        private static boolean sendForm(Object session, Object form) {
            for (String n : new String[]{"showForm", "sendForm"}) {
                for (Method m : session.getClass().getMethods()) {
                    if (m.getName().equals(n) && m.getParameterCount() == 1
                            && m.getParameterTypes()[0].isInstance(form)) {
                        try {
                            m.invoke(session, form);
                            return true;
                        } catch (Throwable ignore) {
                        }
                    }
                }
            }
            try {
                Class<?> formUtils = Class.forName("org.geysermc.geyser.util.FormUtils");
                for (Method m : formUtils.getMethods()) {
                    if (m.getName().equals("sendForm") && m.getParameterCount() == 2) {
                        m.invoke(null, session, form);
                        return true;
                    }
                }
            } catch (Throwable ignore) {
            }
            return false;
        }

        // ---------------- 选择执行 ----------------

        /**
         * 执行阵营选择/切换。
         * isSwitch=true 时走冷却校验并清空 compensate_tier；admin=true 时跳过冷却。
         * 全程主线程同步：发物资 → 算补偿发物资 → 异步写库 → 立即身份四层 → 立即 buff 判定。
         */
        boolean doChoose(Player pl, Faction f, boolean isSwitch, boolean admin) {
            Database d = p.db;
            if (d == null) {
                pl.sendMessage("§c[星穹旅驿] 数据模块未就绪，请稍后再试。");
                return false;
            }
            String uuid = pl.getUniqueId().toString();
            Row r = d.ensureRow(uuid);
            synchronized (r) {
                if (r.faction == f) {
                    pl.sendMessage("§e[星穹旅驿] 你已经是【" + factionLabel(f) + "】阵营了。");
                    return false;
                }
                if (r.faction != null) {
                    if (!isSwitch && !admin) {
                        // 首次选择命令但已有阵营：引导走 switch（防误点，也让冷却语义统一）
                        pl.sendMessage("§e[星穹旅驿] 你已加入【" + factionLabel(r.faction)
                                + "§e】，改阵营请使用 §f/faction switch <dawn|dusk>§e。");
                        return false;
                    }
                    // —— 换阵营：冷却 + 清空补偿记录（双重防薅）——
                    if (!admin) {
                        long cd = p.cfg.switchCooldownMs;
                        if (cd > 0 && r.chosenAt > 0) {
                            long elapsed = System.currentTimeMillis() - r.chosenAt;
                            if (elapsed < cd) {
                                pl.sendMessage("§c[星穹旅驿] 换阵营冷却中，还需等待 " + humanMs(cd - elapsed)
                                        + "（可用 /faction info 查看）。");
                                return false;
                            }
                        }
                    }
                    r.compensateTier = 0; // 换阵营立刻清空档位记录
                }

                // —— 先算补偿（基于当前在线人数，不含本人）——
                int tier = p.balance.tierForJoin(f, pl.getUniqueId());
                List<ItemStack> tierKit = tier > 0 ? p.balance.kitForTier(tier) : List.of();

                // —— 发基础物资（必发，配置来源）——
                p.giveKit(pl, p.cfg.starterKit, "基础物资");
                if (tier > 0 && !tierKit.isEmpty()) {
                    p.giveKit(pl, tierKit, "劣势补偿·第" + tier + "档");
                }

                // —— 写状态 + 异步落库（uuid/faction/时间/补偿档位，在线时长已在计时字段内）——
                r.faction = f;
                r.chosenAt = System.currentTimeMillis();
                r.compensateTier = Math.max(0, tier);
                d.saveAsync(uuid, r);

                // —— 立即重建身份四层 ——
                Identity i = p.identity;
                if (i != null) i.apply(pl, f);

                // —— 立即按所在世界时间施加 buff（先清掉对侧阵营的残留效果再评估）——
                BuffManager b = p.buffs;
                if (b != null) {
                    b.clearAll(pl);
                    b.checkPlayer(pl);
                }

                int[] cnt = p.balance.countOnline(f, null);
                pl.sendMessage("§a[星穹旅驿] 选择完成：欢迎加入【" + factionLabel(f) + "】！当前在线人数 黎明" + cnt[0] + "/暮夜" + cnt[1]
                        + (tier > 0 ? "，已按劣势补偿发放第" + tier + "档物资。" : "。"));
                p.getLogger().info("[星穹旅驿] " + pl.getName() + " " + (isSwitch ? "切换" : "选择") + "阵营=" + f.name()
                        + " 补偿档=" + tier + (admin ? " (管理员)" : "")
                        + " 在线人数 黎明" + cnt[0] + "/暮夜" + cnt[1]);
                return true;
            }
        }

        String factionLabel(Faction f) {
            return (f == Faction.DAWN ? p.cfg.dawnColor.toString() + p.cfg.dawnPrefix : p.cfg.duskColor + p.cfg.duskPrefix)
                    + ChatColor.RESET.toString();
        }

        static String humanMs(long ms) {
            long s = ms / 1000;
            long d = s / 86400;
            s %= 86400;
            long h = s / 3600;
            s %= 3600;
            long m = s / 60;
            StringBuilder sb = new StringBuilder();
            if (d > 0) sb.append(d).append("天");
            if (h > 0) sb.append(h).append("小时");
            if (m > 0) sb.append(m).append("分钟");
            if (sb.length() == 0) sb.append(s).append("秒");
            return sb.toString();
        }

        // ---------------- 命令 ----------------

        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            return handle(sender, label, args);
        }

        boolean handle(CommandSender sender, String label, String[] args) {
            String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "help";
            switch (sub) {
                case "choose": {
                    if (!(sender instanceof Player pl)) {
                        sender.sendMessage("§c[星穹旅驿] 该命令需玩家执行。控制台请用 /faction set <玩家> <dawn|dusk>。");
                        return true;
                    }
                    if (args.length >= 2) {
                        Faction f = Faction.byName(args[1]);
                        if (f == null) {
                            pl.sendMessage("§c用法：/faction choose <dawn|dusk>");
                            return true;
                        }
                        doChoose(pl, f, false, false);
                        return true;
                    }
                    prompt(pl, true);
                    return true;
                }
                case "switch": {
                    if (!(sender instanceof Player pl)) {
                        sender.sendMessage("§c该命令需玩家执行。");
                        return true;
                    }
                    if (args.length < 2) {
                        pl.sendMessage("§c用法：/faction switch <dawn|dusk>");
                        return true;
                    }
                    Faction f = Faction.byName(args[1]);
                    if (f == null) {
                        pl.sendMessage("§c用法：/faction switch <dawn|dusk>");
                        return true;
                    }
                    Database d = p.db;
                    Row r = d == null ? null : d.cachedRow(pl.getUniqueId());
                    if (r == null) {
                        pl.sendMessage("§e[星穹旅驿] 数据还在加载，请稍候几秒再试。");
                        return true;
                    }
                    if (r.faction == null) {
                        // 还没选过阵营：直接按首次选择处理，避免“switch 前置 choose”的割裂体验
                        pl.sendMessage("§7[星穹旅驿] 你还没有阵营，已按首次选择处理。");
                        doChoose(pl, f, false, false);
                        return true;
                    }
                    doChoose(pl, f, true, false);
                    return true;
                }
                case "later": {
                    if (!(sender instanceof Player pl)) return true;
                    pl.sendMessage("§7[星穹旅驿] 好的，随时可用 §f/" + label + " choose§7 打开选择界面（不影响自由游玩）。");
                    return true;
                }
                case "info": {
                    if (!(sender instanceof Player pl)) {
                        sender.sendMessage("§c该命令需玩家执行。");
                        return true;
                    }
                    info(pl);
                    return true;
                }
                case "reload": {
                    if (!sender.hasPermission("starfaction.reload")) {
                        sender.sendMessage("§c权限不足（需要 starfaction.reload）。");
                        return true;
                    }
                    if (sender instanceof Player) {
                        p.getLogger().info("[星穹旅驿] " + sender.getName() + " 执行了 /faction reload");
                    }
                    p.reloadAll();
                    sender.sendMessage("§a[星穹旅驿] 配置已重载，自检结果见控制台日志。");
                    return true;
                }
                case "set": {
                    if (!sender.hasPermission("starfaction.admin")) {
                        sender.sendMessage("§c权限不足（需要 starfaction.admin）。");
                        return true;
                    }
                    if (args.length < 3) {
                        sender.sendMessage("§c用法：/faction set <玩家> <dawn|dusk>");
                        return true;
                    }
                    Player target = Bukkit.getPlayerExact(args[1]);
                    if (target == null) {
                        sender.sendMessage("§c玩家 §f" + args[1] + " §c不在线（强制设置需目标在线，便于同时发物资与重建身份）。");
                        return true;
                    }
                    Faction f = Faction.byName(args[2]);
                    if (f == null) {
                        sender.sendMessage("§c用法：/faction set <玩家> <dawn|dusk>");
                        return true;
                    }
                    // 管理员路径：视为“切换”（清旧记录）且不受冷却限制
                    doChoose(target, f, true, true);
                    sender.sendMessage("§a已强制 §f" + target.getName() + " §a→ §f" + args[2].toLowerCase(Locale.ROOT) + "§a。");
                    return true;
                }
                default: {
                    sender.sendMessage("§6[星穹旅驿] 阵营命令：");
                    sender.sendMessage("§f/" + label + " choose §7- 打开阵营选择界面");
                    sender.sendMessage("§f/" + label + " switch <dawn|dusk> §7- 更换阵营（受冷却）");
                    sender.sendMessage("§f/" + label + " info §7- 查看我的阵营/buff状态/双方人数");
                    if (sender.hasPermission("starfaction.reload")) sender.sendMessage("§f/" + label + " reload §7- 重载配置");
                    if (sender.hasPermission("starfaction.admin")) sender.sendMessage("§f/" + label + " set <玩家> <dawn|dusk> §7- 强制设置");
                    return true;
                }
            }
        }

        private void info(Player pl) {
            Database d = p.db;
            if (d == null) {
                pl.sendMessage("§c[星穹旅驿] 数据模块未就绪。");
                return;
            }
            Row r = d.cachedRow(pl.getUniqueId());
            if (r == null) {
                pl.sendMessage("§e[星穹旅驿] 数据加载中，请稍候…");
                return;
            }
            SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT);
            long total = Math.max(0, p.totalOnlineSec(pl.getUniqueId()));
            pl.sendMessage("§6§l[星穹旅驿] 阵营信息");
            if (r.faction == null) {
                pl.sendMessage("§7阵营：§f未选择（§e/" + "faction choose" + "§7 可随时打开界面，不影响游玩）");
            } else {
                pl.sendMessage("§7阵营：" + factionLabel(r.faction));
                pl.sendMessage("§7选择时间：§f" + fmt.format(new Date(r.chosenAt)));
                pl.sendMessage("§7上次补偿档位：§f" + (r.compensateTier > 0 ? "第" + r.compensateTier + "档（换阵营会清空）" : "无"));
                long cd = p.cfg.switchCooldownMs;
                if (cd > 0 && r.chosenAt > 0) {
                    long rest = r.chosenAt + cd - System.currentTimeMillis();
                    pl.sendMessage("§7换阵营冷却：§f" + (rest > 0 ? "剩余 " + humanMs(rest) : "已解除"));
                } else {
                    pl.sendMessage("§7换阵营冷却：§f无（配置为0=永不锁定）");
                }
            }
            pl.sendMessage("§7累计在线：§f" + (total / 3600) + "小时" + (total % 3600 / 60) + "分钟"
                    + (p.cfg.countOffline ? "§7(含下线时段)" : "§7(仅在线时段)"));
            int[] cnt = p.balance.countOnline(null, null);
            pl.sendMessage("§7在线已选人数：§6黎明 " + cnt[0] + " §r/ §5暮夜 " + cnt[1]);
            BuffManager b = p.buffs;
            pl.sendMessage("§7当前buff：§f" + (b == null ? "buff模块未启用" : b.describeFor(pl)));
            if (d.memoryOnly()) {
                pl.sendMessage("§c当前为内存存储（数据库降级），重启后数据会丢失，请联系管理员。");
            }
        }

        // ---------------- Tab 补全 ----------------

        List<String> tab(CommandSender sender, String[] args) {
            List<String> out = new ArrayList<>();
            if (args.length == 1) {
                String a = args[0].toLowerCase(Locale.ROOT);
                for (String s : new String[]{"choose", "switch", "later", "info", "reload", "set"}) {
                    if (s.startsWith(a)) out.add(s);
                }
            } else if (args.length == 2) {
                String a = args[0].toLowerCase(Locale.ROOT);
                String b = args[1].toLowerCase(Locale.ROOT);
                if (a.equals("choose") || a.equals("switch")) {
                    for (String s : new String[]{"dawn", "dusk"}) if (s.startsWith(b)) out.add(s);
                } else if (a.equals("set")) {
                    for (Player pl : Bukkit.getOnlinePlayers()) {
                        if (pl.getName().toLowerCase(Locale.ROOT).startsWith(b)) out.add(pl.getName());
                    }
                }
            } else if (args.length == 3 && args[0].equalsIgnoreCase("set")) {
                String b = args[2].toLowerCase(Locale.ROOT);
                for (String s : new String[]{"dawn", "dusk"}) if (s.startsWith(b)) out.add(s);
            }
            return out;
        }
    }

    // =====================================================================
    // 事件监听（PlayerListener）
    // =====================================================================

    /**
     * 事件层：
     * - join：挂会话锚 → 按配置累加下线时段 → 异步从库读记录 → 有阵营则重建身份四层+立即buff；无阵营交给提醒任务；
     * - quit：会话增量累加落库、记录下线锚点、清理队伍；
     * - 换世界：重建身份（跨世界计分板/缓存刷新）+ 立即按新世界时间评估 buff；
     * - 聊天：AsyncPlayerChatEvent setFormat 实现 [阵营] 前缀（方案说明见 README）。
     * 全部 handler 包 try/catch（并 ignoreExceptions=true 双保险），异常只影响该次显示。
     */
    static final class PlayerListener implements Listener {
        private final StarFaction p;

        PlayerListener(StarFaction p) {
            this.p = p;
        }

        @EventHandler
        public void onJoin(PlayerJoinEvent e) {
            try {
                Player pl = e.getPlayer();
                UUID id = pl.getUniqueId();
                long now = System.currentTimeMillis();
                p.sessionStart.put(id, now);

                long offlineAdd = 0L;
                Long ref = p.getPdcLong(pl, p.keyOnlineRef);
                if (p.cfg.countOffline && ref != null && ref > 0 && now > ref) {
                    offlineAdd = (now - ref) / 1000L; // 下线时段是否计入由配置决定
                }
                p.setPdcLong(pl, p.keyOnlineRef, now);
                final long add = offlineAdd;
                final Database d = p.db;
                if (d == null) return;
                d.loadAsync(id.toString(), row -> {
                    // BUGFIX: 异步回调到达时玩家可能已退出；用离线 Player 引用会污染计分板，
                    // 且 setGlowing 失败会把 Identity.glowingBroken 置真，导致全局发光永久关闭。
                    Player cur = Bukkit.getPlayer(id);
                    if (cur == null || !cur.isOnline()) return;
                    if (add > 0) row.onlineSeconds += add;
                    if (row.faction != null) {
                        // 老玩家：join 直接从库重读并重建四层（跨 Velocity 后端服靠每服 join 重建）
                        Identity i = p.identity;
                        BuffManager b = p.buffs;
                        if (i != null) i.apply(cur, row.faction);
                        if (b != null) b.checkPlayer(cur);
                    }
                    // 未选阵营者不在此弹窗，交给 ReminderTask（保证“累计在线满阈值”判定一致）
                });
            } catch (Throwable t) {
                p.getLogger().warning("[星穹旅驿] join 处理异常(已忽略)：" + t);
            }
        }

        @EventHandler
        public void onQuit(PlayerQuitEvent e) {
            try {
                Player pl = e.getPlayer();
                UUID id = pl.getUniqueId();
                long now = System.currentTimeMillis();
                Long st = p.sessionStart.remove(id);
                p.lastPromptAt.remove(id);
                Row r = p.db.cachedRow(id);
                if (r != null) {
                    if (st != null) {
                        long delta = (now - st) / 1000L;
                        if (delta > 0) r.onlineSeconds += delta; // quit 写库，掉线不清零
                    }
                    p.db.saveAsync(id.toString(), r);
                }
                p.setPdcLong(pl, p.keyOnlineRef, now);
                p.identity.clear(pl);
            } catch (Throwable t) {
                p.getLogger().warning("[星穹旅驿] quit 处理异常(已忽略)：" + t);
            }
        }

        @EventHandler
        public void onWorldChange(PlayerChangedWorldEvent e) {
            try {
                Player pl = e.getPlayer();
                Row r = p.db.cachedRow(pl.getUniqueId());
                if (r == null || r.faction == null) return;
                p.identity.apply(pl, r.faction);  // 换世界刷新身份（部分端计分板需重挂）
                p.buffs.checkPlayer(pl);           // 立即按新世界时间评估 buff（跑错世界立刻消失）
            } catch (Throwable t) {
                p.getLogger().warning("[星穹旅驿] 换世界刷新异常(已忽略)：" + t);
            }
        }

        @EventHandler
        public void onChat(AsyncPlayerChatEvent e) {
            try {
                if (!p.cfg.chatPrefixEnabled) return;
                Player pl = e.getPlayer();
                Row r = p.db == null ? null : p.db.cachedRow(pl.getUniqueId());
                if (r == null || r.faction == null) return;
                Cfg c = p.cfg;
                String prefix = (r.faction == Faction.DAWN
                        ? c.dawnColor.toString() + "[" + c.dawnPrefix + "]"
                        : c.duskColor + "[" + c.duskPrefix + "]") + ChatColor.RESET + " ";
                // 前缀里的 % 转义，避免 format 串非法；%1$s=玩家名 %2$s=消息
                e.setFormat(prefix.replace("%", "%%") + e.getFormat());
            } catch (Throwable ignore) {
                // 聊天是高频路径：失败就用原格式，绝不影响消息发出
            }
        }
    }
}
