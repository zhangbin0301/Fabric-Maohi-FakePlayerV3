package com.maohi.fakeplayer.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.maohi.fakeplayer.SavedPlayer;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 假人数据持久化(V5.20:从 VirtualPlayerManager 提取)
 *
 * 职责:
 *   - 管理 ./mods/.metadata.bin 文件 IO
 *   - Gson 序列化 SavedPlayer 列表
 *   - dirty 标记 + 异步保存 + 原子写入
 *   - 容量上限裁剪(按 totalPlaytime 排序删旧的)
 *
 * 不持有 knownPlayers / nameToUuidIndex 映射本身—— VPM 仍是这两个 map 的 owner,
 * PlayerStorage 接收它们作为参数,做纯函数式操作。
 */
public final class PlayerStorage {

	private static final Path DATA_PATH = Paths.get("./mods/.metadata.bin");
	private static final Gson GSON = new GsonBuilder().enableComplexMapKeySerialization().create();

	private final ReentrantLock saveLock = new ReentrantLock();
	private volatile boolean dirty = false;
	private long lastSaveTime = System.currentTimeMillis();

	public void markDirty() { dirty = true; }
	public boolean isDirty() { return dirty; }
	public long getLastSaveTime() { return lastSaveTime; }

	/**
	 * 异步保存:dirty 才触发,不阻塞调用方。
	 * 2.70 性能优化的语义保留。
	 */
	public void saveAsync(Map<UUID, SavedPlayer> knownPlayers) {
		if (!dirty) return;
		CompletableFuture.runAsync(() -> saveSync(knownPlayers));
	}

	/**
	 * 同步保存:原子写入(.tmp → atomic move)。
	 * 2.73 锁保护:防止多个异步任务冲突。
	 * V5.54: 异常路径兜底删 .tmp,避免残留(GSON 序列化失败 / ATOMIC_MOVE 不支持 / 磁盘满 等)。
	 */
	public void saveSync(Map<UUID, SavedPlayer> knownPlayers) {
		if (!dirty || !saveLock.tryLock()) return;
		Path tempPath = DATA_PATH.resolveSibling(DATA_PATH.getFileName() + ".tmp");
		try {
			Files.createDirectories(DATA_PATH.getParent());
			try (Writer w = Files.newBufferedWriter(tempPath, StandardCharsets.UTF_8)) {
				GSON.toJson(new ArrayList<>(knownPlayers.values()), w);
			}
			Files.move(tempPath, DATA_PATH, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			dirty = false;
			lastSaveTime = System.currentTimeMillis();
		} catch (Throwable e) {
			LoggerFactory.getLogger("Server thread").error("Failed to save player data", e);
			// V5.54: 失败兜底 — 删残留 .tmp 防累积。move 成功路径不进 catch,正常被消费掉,无需删。
			try { Files.deleteIfExists(tempPath); } catch (IOException ignored) {}
		} finally {
			saveLock.unlock();
		}
	}

	/**
	 * 从磁盘加载到给定 map 中。
	 * 2.74 鲁棒性校验:跳过非法/损坏数据(uuid/name 为 null)。
	 * V5.54: 启动时顺手清理上次 crash 残留的 .tmp 文件,避免在 mods/ 下累积。
	 */
	public void load(Map<UUID, SavedPlayer> knownPlayers) {
		Path tempPath = DATA_PATH.resolveSibling(DATA_PATH.getFileName() + ".tmp");
		try { Files.deleteIfExists(tempPath); } catch (IOException ignored) {}
		if (!Files.exists(DATA_PATH)) return;
		try (Reader r = Files.newBufferedReader(DATA_PATH, StandardCharsets.UTF_8)) {
			List<SavedPlayer> list = GSON.fromJson(r, new TypeToken<List<SavedPlayer>>(){}.getType());
			if (list != null) {
				for (SavedPlayer sp : list) {
					if (sp != null && sp.uuid != null && sp.name != null) {
						knownPlayers.put(sp.uuid, sp);
					}
				}
			}
		} catch (IOException e) {
			LoggerFactory.getLogger("Server thread").warn("Player data load failed: {}", e.getMessage());
		}
	}

	/**
	 * V3.3 容量上限裁剪:超过 limit 时删除 totalPlaytime 最小的若干条目。
	 * 同步更新 nameIndex,标记 dirty。
	 */
	public void enforceLimit(Map<UUID, SavedPlayer> knownPlayers, Map<String, UUID> nameIndex, int limit) {
		if (limit <= 0 || knownPlayers.size() <= limit) return;
		// m5 fix:先收集要删的 key,再批量 remove(避免 stream 中修改 ConcurrentHashMap)
		List<Map.Entry<UUID, SavedPlayer>> toRemove = knownPlayers.entrySet().stream()
			.sorted((a, b) -> Long.compare(a.getValue().totalPlaytime, b.getValue().totalPlaytime))
			.limit(knownPlayers.size() - limit)
			.toList();
		for (Map.Entry<UUID, SavedPlayer> entry : toRemove) {
			nameIndex.remove(entry.getValue().name);
			knownPlayers.remove(entry.getKey());
		}
		dirty = true;
		LoggerFactory.getLogger("Server thread").debug("Player cache trimmed to {}", limit);
	}
}
