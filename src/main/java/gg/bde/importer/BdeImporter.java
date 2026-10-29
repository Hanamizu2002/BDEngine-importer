package gg.bde.importer;

import com.destroystokyo.paper.event.server.ServerTickEndEvent;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityFactory;
import org.bukkit.entity.EntitySnapshot;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

public final class BdeImporter extends JavaPlugin implements Listener {
    private Limits limits;
    private ThreadPoolExecutor worker;
    private EntityFactory factory;
    private Job job;
    private double lastTickMillis;
    private long nextRequest;

    private record Prepared(List<EntitySnapshot> parts, EntitySnapshot root, boolean full) {
    }

    @Override
    public void onEnable() {
        if (!Bukkit.getMinecraftVersion().equals("26.3")) {
            getLogger().severe("当前服务端版本不在允许的范围内");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        if (!loadLimits()) return;
        factory = getServer().getEntityFactory();
        worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(1), runnable -> {
            Thread thread = new Thread(runnable, "BDEImporter-download");
            thread.setDaemon(true);
            thread.setPriority(Thread.MIN_PRIORITY);
            return thread;
        });
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getScheduler().runTaskTimer(this, this::tick, 1, limits.stepTicks());
    }

    private boolean loadLimits() {
        try {
            saveDefaultConfig();
            YamlConfiguration config = new YamlConfiguration();
            config.load(new File(getDataFolder(), "config.yml"));
            limits = Limits.from(config);
            return true;
        } catch (IOException | InvalidConfigurationException | IllegalArgumentException e) {
            getLogger().severe("配置无效，插件已停用：" + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return false;
        }
    }

    @EventHandler
    public void tickEnd(ServerTickEndEvent event) {
        lastTickMillis = event.getTickDuration();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        Player player = commandPlayer(sender);
        if (player == null) return true;
        if (args.length == 0 || args.length == 1 && args[0].equalsIgnoreCase("help")) {
            sendHelp(player, label);
        } else if (args.length == 1 && args[0].equalsIgnoreCase("cancel")) {
            cancel(player);
        } else if (isSpawn(args)) {
            startImport(player, args[1]);
        } else {
            player.sendMessage("命令或模型 ID 无效；模型 ID 必须是六位数字。");
            sendHelp(player, label);
        }
        return true;
    }

    static boolean isSpawn(String[] args) {
        return args.length == 2 && args[0].equalsIgnoreCase("spawn") && args[1].matches("[0-9]{6}");
    }

    private Player commandPlayer(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("此命令只允许玩家执行");
            return null;
        }
        if (!player.hasPermission("bde.spawn")) {
            player.sendMessage("你缺少 bde.spawn 权限");
            return null;
        }
        return player;
    }

    private static void sendHelp(CommandSender sender, String label) {
        sender.sendMessage("BDEImporter · 命令帮助");
        sender.sendMessage("/" + label + " spawn <六位发布ID> — 在当前位置导入静态模型");
        sender.sendMessage("/" + label + " cancel — 取消自己的当前导入并清理已生成实体");
        sender.sendMessage("/" + label + " help — 查看帮助");
        sender.sendMessage("需要 bde.spawn 权限；完成后的模型请用 Axiom 编辑或删除。");
    }

    private void cancel(Player player) {
        if (job == null || !job.owner.equals(player.getUniqueId())) player.sendMessage("你没有正在进行的导入。");
        else job.fail("导入被取消，正在清理已有实体");
    }

    private void startImport(Player player, String id) {
        if (job != null) {
            player.sendMessage("服务器已有导入或清理任务，请等待它完成。");
            return;
        }
        if (System.nanoTime() < nextRequest) {
            player.sendMessage("请求过于频繁，请稍后重试");
            return;
        }
        Location target = player.getLocation();
        target.setYaw(0);
        target.setPitch(0);
        String tag = "bde_" + UUID.randomUUID().toString().replace("-", "");
        String version = Bukkit.getMinecraftVersion();
        try {
            Future<Prepared> future = worker.submit(() -> prepare(BdeClient.fetch(id, tag, limits), version));
            job = new Job(player.getUniqueId(), target, tag, future);
            nextRequest = System.nanoTime() + limits.cooldownNanos();
            player.sendMessage("正在异步下载并校验模型；通过后将在你执行命令的位置分批生成");
        } catch (RejectedExecutionException e) {
            player.sendMessage("下载线程仍在结束上一个请求，请稍后重试");
        }
    }

    private Prepared prepare(String json, String version) throws IOException {
        ModelParser.Model model = new ModelParser(limits).parse(json, version);
        List<EntitySnapshot> snapshots = new ArrayList<>(model.parts().size());
        // 快照准备不访问世界；实体创建留在主线程。
        for (String snbt : model.parts()) {
            if (Thread.currentThread().isInterrupted()) throw new IOException("导入已取消");
            try {
                snapshots.add(factory.createEntitySnapshot(snbt));
            } catch (IllegalArgumentException e) {
                throw new IOException("展示实体数据无效，请检查模型后重新导出", e);
            }
        }
        EntitySnapshot root = factory.createEntitySnapshot("{id:\"minecraft:block_display\",view_range:" + limits.viewRange() + "f}");
        return new Prepared(List.copyOf(snapshots), root, model.full());
    }

    private void tick() {
        if (job == null) return;
        try {
            job.step();
        } catch (Exception e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            String message = cause instanceof IOException ? cause.getMessage() : "生成失败，请查看服务器日志";
            if (!(cause instanceof IOException)) getLogger().log(Level.WARNING, "BDE import failed", cause);
            job.fail(message);
        }
    }

    private final class Job {
        private final UUID owner;
        private final Location target;
        private final World world;
        private final String tag;
        private final Future<Prepared> future;
        private final long started = System.nanoTime();
        private final List<Entity> created = new ArrayList<>();
        private Prepared prepared;
        private Entity root;
        private int index;
        private boolean cleaning;
        private boolean ticket;

        Job(UUID owner, Location target, String tag, Future<Prepared> future) {
            this.owner = owner;
            this.target = target;
            this.world = target.getWorld();
            this.tag = tag;
            this.future = future;
        }

        void step() throws Exception {
            if (cleaning) {
                if (!created.isEmpty()) removeLast();
                if (created.isEmpty()) finish();
                return;
            }
            Player player = activePlayer();
            if (lastTickMillis > limits.busyTickMillis()) return;
            if (prepared == null) {
                if (!future.isDone()) return;
                prepared = future.get(); // 只读取已完成的结果，避免阻塞主线程。
                holdTargetChunk();
                player.sendMessage("生成中，请勿进行其他操作");
                return;
            }
            if (root == null) {
                root = spawn(prepared.root());
                root.addScoreboardTag("bde_root");
                return;
            }
            if (!root.isValid() || root.getWorld() != world || root.getLocation().distanceSquared(target) > 0.0001) {
                fail("导入期间根实体被移动或删除");
                return;
            }
            if (index < prepared.parts().size()) {
                Entity part = spawn(prepared.parts().get(index));
                if (!root.addPassenger(part)) throw new IOException("展示实体分组被阻止");
                index++;
                return;
            }
            player.sendMessage("模型导入完成，可使用 Axiom 实体工具编辑"
                    + (prepared.full() ? " 仅导入静态外观，未执行动画或碰撞箱命令" : ""));
            finish();
        }

        private Player activePlayer() throws IOException {
            Player player = Bukkit.getPlayer(owner);
            if (player == null || !player.isOnline() || !player.hasPermission("bde.spawn") || player.getWorld() != world)
                throw new IOException("玩家离线、切换世界或失去权限，导入已取消");
            if (System.nanoTime() - started > limits.jobNanos()) throw new IOException("导入超时");
            return player;
        }

        private void holdTargetChunk() throws IOException {
            if (!world.isChunkLoaded(target.getBlockX() >> 4, target.getBlockZ() >> 4))
                throw new IOException("原位置的区块已经卸载，请稍后重试");
            if (!world.getWorldBorder().isInside(target)) throw new IOException("不能在世界边界外生成");
            Chunk chunk = world.getChunkAt(target);
            if (!chunk.isEntitiesLoaded()) throw new IOException("原位置的实体尚未加载，请稍后重试");
            int capacity = limits.chunkCapacity(prepared.parts().size());
            int displayCount = 0;
            for (Entity entity : chunk.getEntities()) {
                if (entity instanceof Display && ++displayCount > capacity)
                    throw new IOException("此区块展示实体部件总数过多");
            }
            world.addPluginChunkTicket(target.getBlockX() >> 4, target.getBlockZ() >> 4, BdeImporter.this);
            ticket = true;
        }

        private void removeLast() {
            created.getLast().remove();
            created.removeLast();
        }

        private Entity spawn(EntitySnapshot snapshot) throws IOException {
            Entity entity = snapshot.createEntity(world);
            created.add(entity);
            entity.setPersistent(true);
            entity.setGravity(false);
            entity.setInvulnerable(true);
            entity.addScoreboardTag(tag);
            if (!entity.spawnAt(target, CreatureSpawnEvent.SpawnReason.CUSTOM) || !entity.isValid()) {
                throw new IOException("展示实体生成被服务器或其他插件阻止");
            }
            return entity;
        }

        void fail(String message) {
            if (cleaning) return;
            cleaning = true;
            future.cancel(true);
            Player player = Bukkit.getPlayer(owner);
            if (player != null) player.sendMessage(message);
        }

        void finish() {
            if (ticket)
                world.removePluginChunkTicket(target.getBlockX() >> 4, target.getBlockZ() >> 4, BdeImporter.this);
            job = null;
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!(sender instanceof Player) || !sender.hasPermission("bde.spawn") || args.length != 1) return List.of();
        return complete(args[0]);
    }

    static List<String> complete(String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        return List.of("spawn", "cancel", "help").stream().filter(value -> value.startsWith(lower)).toList();
    }

    @Override
    public void onDisable() {
        if (worker != null) worker.shutdownNow();
        if (job != null) {
            job.future.cancel(true);
            // 禁用后无法分批调度，只清理当前未完成任务。
            while (!job.created.isEmpty()) job.removeLast();
            job.finish();
        }
    }
}
