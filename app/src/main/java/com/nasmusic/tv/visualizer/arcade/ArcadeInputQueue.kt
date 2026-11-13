package com.nasmusic.tv.visualizer.arcade

/**
 * 跨线程输入通路（方案 §4.8 / §4.9 W9）。
 *
 * ## 为什么是环形队列
 * 键回调在 **main 线程**，sim tick 在 **GL 线程**（`onDrawFrame`）。
 * 键频（每秒最多几十次）远低于帧率 ⇒ 有界队列足够。
 * ⚠️ 但满队列时 `offer` 会推进 `head`（丢最旧），与 GL 端 `drainStart` 构成
 * `head` 双写者 ⇒ 用同一把 `lock` 保护 `head`/`tail`/`slots` 的全部读写，
 * 保持「丢最旧」语义不变（`@Volatile` 只保证可见性，不保证读-改-写原子）。
 *
 * ## 容量与溢出
 * 容量 32；满了**丢最旧**（`head` 前进一格）—— 丢旧不丢新，
 * 因为用户刚按下的那一下才是意图所在。
 *
 * ## 本次只传一个语义
 * `start`（确认键 ⇒ 打断 demo 回标题）。其余键**不进队列**（方向键仍归舞台）。
 */
class ArcadeInputQueue(capacity: Int = 32) {

    private val cap = if (capacity < 2) 2 else capacity
    private val slots = IntArray(cap)

    /** 保护 `head`/`tail`/`slots` 的读-改-写（`head` 有 main / GL 双写者）。 */
    private val lock = Any()

    @Volatile
    private var head = 0

    @Volatile
    private var tail = 0

    /** main 线程调用：入队一个事件码（[EVENT_START]） */
    fun offer(event: Int) {
        synchronized(lock) {
            val next = (tail + 1) % cap
            if (next == head) {
                // 满 ⇒ 丢最旧
                head = (head + 1) % cap
            }
            slots[tail] = event
            tail = next
        }
    }

    /** GL 线程调用：取走全部事件，返回值是否含「确认键」 */
    fun drainStart(): Boolean = synchronized(lock) {
        var sawStart = false
        while (head != tail) {
            val e = slots[head]
            head = (head + 1) % cap
            if (e == EVENT_START) sawStart = true
        }
        sawStart
    }

    /** 当前积压条数（仅诊断/单测用） */
    fun size(): Int = synchronized(lock) {
        if (tail >= head) tail - head else cap - head + tail
    }

    fun clear() {
        synchronized(lock) {
            head = 0
            tail = 0
        }
    }

    companion object {
        const val EVENT_START = 1
    }
}
