/*
 * Copyright 2026 AgentMirror Project Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.agentmirror.app.workspace

import dev.agentmirror.app.conn.AuthAckFrame
import dev.agentmirror.app.conn.ConnectionState
import dev.agentmirror.app.conn.ListingFrame
import dev.agentmirror.app.conn.Workspace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 刷新模型纯 JVM 单测（ledger.refresh-and-contract.v2 t.refresh-impl）。
 *
 * 用户 2026-08-15 裁定（docs/rulings 同名文件第四节「刷新模型」）三条不变量：
 * 1. 进入一级（工作区列表）/二级（会话列表）即自动刷一次全量列表；
 * 2. 手指下滑手动刷；
 * 3. 零周期性自动刷新（禁令，不是"频率低一点"）。
 *
 * 本类锁定 ViewModel 层行为：[WorkspaceViewModel.refresh] 是进入/下拉共用的刷新入口——
 * 置刷新中标记并发出一次全量列表请求（[requestList] 注入 spy，避免依赖真实 manager）；
 * 新 [ListingFrame] 到达后刷新标记复位。Compose 屏的进入即刷/下拉绑定见 [WorkspaceRefreshTest]。
 *
 * 红测先行：修复前 VM 无 refresh()/refreshing/requestList → 编译失败即红。
 */
class WorkspaceViewModelRefreshTest {

    /** 含一个工作区的最小 listing。 */
    private fun listing(): ListingFrame = ListingFrame(
        reqId = 1,
        seq = 42,
        workspaces = listOf(
            Workspace(
                cwd = "/proj/a",
                sessionCount = 1,
            ),
        ),
    )

    @Test
    fun refresh_issuesOneListRequest_andFlipsRefreshing() {
        var requested = 0
        val vm = WorkspaceViewModel(requestList = { requested++ })
        vm.onConnectionStateChanged(ConnectionState.READY)
        vm.onFrame(listing())

        // 触发前：未处于刷新中。
        assertFalse("初始不得处于刷新中", vm.refreshing.value)

        // 进入/下拉共用的刷新入口：置刷新中 + 发出一次全量列表请求。
        vm.refresh()

        assertEquals("refresh() 必须发出一次全量列表请求", 1, requested)
        assertTrue("刷新请求在途时必须标记为刷新中", vm.refreshing.value)

        // conn 层新的全量 listing 到达 → 刷新完成，标记复位。
        vm.onFrame(listing())
        assertFalse("新 listing 到达后刷新标记必须复位", vm.refreshing.value)
    }

    @Test
    fun incomingListing_settlesRefreshing() {
        val vm = WorkspaceViewModel(requestList = {})
        vm.onConnectionStateChanged(ConnectionState.READY)
        vm.onFrame(listing())

        vm.refresh()
        assertTrue("刷新发起后必须标记为刷新中", vm.refreshing.value)

        vm.onFrame(listing())
        assertFalse("收到新 listing 后必须复位", vm.refreshing.value)
    }

    @Test
    fun unrelatedFrame_doesNotSettleRefreshing() {
        var requested = 0
        val vm = WorkspaceViewModel(requestList = { requested++ })
        vm.onConnectionStateChanged(ConnectionState.READY)

        vm.refresh()
        assertTrue("刷新发起后必须标记为刷新中", vm.refreshing.value)

        // 无关帧（auth_ack）不是刷新响应，不得复位刷新标记。
        vm.onFrame(AuthAckFrame(ok = true))
        assertTrue("无关帧不得改变刷新态", vm.refreshing.value)

        vm.onFrame(listing())
        assertFalse("新 listing 到达才复位刷新态", vm.refreshing.value)
    }

    @Test
    fun multipleRefreshInvocations_eachIssueRequest() {
        var requested = 0
        val vm = WorkspaceViewModel(requestList = { requested++ })
        vm.onConnectionStateChanged(ConnectionState.READY)

        vm.refresh()
        vm.refresh()
        assertEquals("两次 refresh() 必须发出两次全量列表请求", 2, requested)
    }
}
