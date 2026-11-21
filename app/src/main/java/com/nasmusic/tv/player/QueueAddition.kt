package com.nasmusic.tv.player

import com.nasmusic.tv.data.model.Song

/**
 * 入队去重口径的唯一真相源。
 *
 * 所有「加入队列」入口（单曲 ☰/✓、批量加入、随心听续播、遥控、搜索单曲）
 * 最终都经 PlayerManager.addToQueue 调用这里的 compute，避免各入口自造一套过滤：
 * 历史上 addSongsToQueue 按 id、搜索批量按 (artist,title) 两套口径并存，
 * 同一批歌走不同入口会得到不同队列内容。
 *
 * 规则：按 id 排除已在队列中的歌曲，对候选自身也去重（保留首次出现），保持原顺序。
 * 这样队列不会因「加入队列」产生重复 id，UI 的 isInQueue 布尔才与队列实际内容一致。
 */
object QueueAddition {
    fun compute(existing: List<Song>, candidates: List<Song>): List<Song> {
        val existingIds = existing.map { it.id }.toHashSet()
        val seen = HashSet<String>()
        return candidates.filter { song -> song.id !in existingIds && seen.add(song.id) }
    }
}
