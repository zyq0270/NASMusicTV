package com.nasmusic.tv.visualizer.arcade

/**
 * 街机合集内**单个游戏**的契约（方案 `docs/archive/e54-contra-porting-plan.md` §4.3）。
 *
 * ## 为什么要这层抽象
 * 本效果的显示名就是「街机合集」（[com.nasmusic.tv.data.model.VisualizerTheme.ARCADE_GAMES]），
 * 本次只装一个游戏（魂斗罗）。合集扩容（第二个及以后）**不另立效果**，
 * 只往 [ArcadeGameManager] 的注册表里加一项 ⇒ 状态机、渲染器、输入通路都不用改。
 *
 * ## 零分配纪律（⛔ 硬约束）
 * `VisualizerRenderer.kt` 的「绘制循环零分配」原则同样适用于 sim 层：
 * - [step] 的 [input] 与 [snapshot] 都是**复用单例**，调用方每步覆写字段，⛔ 不得跨步持有；
 * - 实现类内部不得每帧 `new` 集合 / `data class`（静态定长数组 + 原地写字段）。
 */
interface ArcadeGame {

    /** 稳定标识（`"contra"`）—— 合集扩容后用于持久化「上次玩的是哪个」 */
    val id: String

    /** 显示名（标题页用） */
    val title: String

    /** 内部像素坐标宽（**不是**屏幕像素；缩放由渲染层负责） */
    val logicalWidth: Int

    /** 内部像素坐标高 */
    val logicalHeight: Int

    /**
     * 固定步推进一帧。`step` **无返回值、不改容器**，只写 [snapshot]
     * ⇒ 便于单测断言「同帧输入 ⇒ 同帧状态」。
     */
    fun step(input: GameInput, snapshot: GameSnapshot)

    /** 重置到初始状态；同 seed ⇒ 同序列（bot 的确定性前提） */
    fun reset(seed: Long)
}

/**
 * 一帧的输入位（**复用单例**）。
 *
 * ⚠️ **与音乐解耦**：音乐帧可以推入控制器，但⛔ 不驱动游戏逻辑
 * （C6「音乐链接完全独立」）；本类只承载「按键语义」。
 */
class GameInput {

    var left: Boolean = false
    var right: Boolean = false
    var up: Boolean = false
    var down: Boolean = false
    var jump: Boolean = false
    var shoot: Boolean = false
    var start: Boolean = false

    /** 每帧开头复位；只保留**本帧**按下的位（边沿语义由调用方给出） */
    fun clear() {
        left = false
        right = false
        up = false
        down = false
        jump = false
        shoot = false
        start = false
    }
}

/** 一帧的状态快照（见 [ArcadeGame.step]；由具体游戏定义字段） */
interface GameSnapshot

/**
 * 合集注册表。本次只注册 1 个游戏；多游戏切换后续接入**不改状态机**。
 */
class ArcadeGameManager(games: List<ArcadeGame>) {

    /** 注册顺序即合集内的顺序（后续上下键切换按此序走） */
    val ordered: List<ArcadeGame> = games.toList()

    private val byId: Map<String, ArcadeGame> = games.associateBy { it.id }

    val size: Int get() = byId.size

    fun get(id: String): ArcadeGame? = byId[id]

    /** 本次交付只有一台游戏；合集扩容后改为「从 [ordered] 里按序取」 */
    fun first(): ArcadeGame? = ordered.firstOrNull()
}
