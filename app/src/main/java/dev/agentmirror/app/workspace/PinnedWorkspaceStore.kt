/*
 * Copyright 2026 AgentMirror Project Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.agentmirror.app.workspace

import android.content.Context
import androidx.core.content.edit

/** 一级工作区置顶状态的落盘抽象。工作区身份使用服务端权威 cwd。 */
interface PinnedWorkspaceStore {
    fun load(): Set<String>

    fun save(cwds: Set<String>)
}

/** 单测与默认构造使用的内存实现。 */
class MemoryPinnedWorkspaceStore(
    initial: Set<String> = emptySet(),
) : PinnedWorkspaceStore {
    private val lock = Any()
    private var items = initial.filter(String::isNotEmpty).toSet()

    override fun load(): Set<String> = synchronized(lock) { items.toSet() }

    override fun save(cwds: Set<String>) {
        synchronized(lock) { items = cwds.filter(String::isNotEmpty).toSet() }
    }
}

/** SharedPreferences 持久化；StringSet 足够表达无序的置顶集合。 */
class SharedPreferencesPinnedWorkspaceStore(context: Context) : PinnedWorkspaceStore {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun load(): Set<String> = prefs.getStringSet(KEY_CWDS, emptySet())
        .orEmpty()
        .filter(String::isNotEmpty)
        .toSet()

    override fun save(cwds: Set<String>) {
        prefs.edit { putStringSet(KEY_CWDS, cwds.filter(String::isNotEmpty).toSet()) }
    }

    private companion object {
        const val PREFS_NAME = "workspace_pins"
        const val KEY_CWDS = "cwds"
    }
}
