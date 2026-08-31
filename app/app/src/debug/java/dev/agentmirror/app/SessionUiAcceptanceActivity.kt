package dev.agentmirror.app

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.agentmirror.app.conn.AuthAckFrame
import dev.agentmirror.app.conn.ConnectionConfig
import dev.agentmirror.app.conn.ConnectionManager
import dev.agentmirror.app.conn.FrameCodec
import dev.agentmirror.app.conn.InputAckFrame
import dev.agentmirror.app.conn.InputFrame
import dev.agentmirror.app.conn.InputKey
import dev.agentmirror.app.conn.TransportFactory
import dev.agentmirror.app.conn.TransportListener
import dev.agentmirror.app.conn.WebSocketTransport
import dev.agentmirror.app.session.AttachmentUploader
import dev.agentmirror.app.session.SessionScreen
import dev.agentmirror.app.session.SessionViewModel
import dev.agentmirror.app.session.UploadOutcome
import dev.agentmirror.app.termview.TermSurfaceView
import dev.agentmirror.app.ui.theme.SessionChromeColors
import dev.agentmirror.app.ui.theme.SharedPreferencesTermThemeStore
import dev.agentmirror.app.ui.theme.TermPalette
import dev.agentmirror.app.ui.theme.TermSchemeCatalog
import dev.agentmirror.app.workspace.FavoriteRow
import dev.agentmirror.app.workspace.L2Entry
import dev.agentmirror.app.workspace.L2Status

/** Debug-only, in-memory fixture for the real production [SessionScreen]. */
class SessionUiAcceptanceActivity : ComponentActivity() {
    private lateinit var fixture: DebugSessionFixture
    private var sourceSha = ""
    private var fixtureKind = "full"
    private var entry = "favorites"
    private var revision by mutableIntStateOf(0)

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        resetFixture(
            intent.getStringExtra("session_ui_fixture") ?: "full",
            intent.getStringExtra("entry") ?: "favorites",
            intent.getStringExtra("source_sha").orEmpty(),
        )
        setContent {
            revision
            val f = fixture
            Box(
                Modifier
                    .fillMaxSize()
                    .semantics { testTagsAsResourceId = true },
            ) {
                // Fixture replacement is a new debug scenario: reset remembered shell mode without
                // changing production SessionScreen's normal in-process mode persistence.
                key(f) {
                    SessionScreen(
                        viewModel = f.vm,
                        name = f.currentName,
                        onBack = { f.backCount++; changed() },
                        overlaySessions = f.viewRows,
                        favoriteRows = f.favorites,
                        onOpenSwitcher = {
                            f.viewOpenCount++
                            f.vm.openOverlay()
                            changed()
                        },
                        onOpenOverlaySession = { ref, name ->
                            if (f.viewRows.any { it.ref == ref }) f.viewSelections += ref to name
                            else f.favoriteSelections += ref to name
                            f.currentRef = ref
                            f.currentName = name
                            changed()
                        },
                    )
                }
                val stateText = snapshot().asSemanticState()
                Text(
                    text = stateText,
                    color = Color.Transparent,
                    fontSize = 1.sp,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .size(1.dp)
                        .testTag("session-ui-state")
                        .semantics { contentDescription = stateText },
                )
                LaunchedEffect(f) { changed() }
            }
        }
    }

    /** Replaces only the local VM/transport/seed and never touches ServiceWire or a service. */
    fun resetFixture(kind: String, route: String, sha: String) {
        require(kind == "full" || kind == "empty") { "fixture must be full|empty" }
        require(route == "favorites" || route == "ordinary") { "entry must be favorites|ordinary" }
        fixtureKind = kind
        entry = route
        sourceSha = sha
        if (::fixture.isInitialized) fixture.dispose()
        fixture = createDebugSessionFixture(this, kind) { changed() }
        changed()
    }

    /** Writes the real terminal theme preference and recomposes the existing screen/AndroidView. */
    fun selectThemeForTest(familyId: String) {
        require(TermSchemeCatalog.families.any { it.id == familyId }) { "unknown theme: $familyId" }
        SharedPreferencesTermThemeStore(this).apply {
            saveLight(familyId)
            saveDark(familyId)
        }
        fixture.themeId = familyId
        changed()
    }

    fun snapshot(): DebugSessionSnapshot {
        val terminals = ArrayList<TermSurfaceView>()
        fun collect(group: ViewGroup) {
            for (i in 0 until group.childCount) {
                when (val child = group.getChildAt(i)) {
                    is TermSurfaceView -> terminals += child
                    is ViewGroup -> collect(child)
                }
            }
        }
        collect(window.decorView as ViewGroup)
        val terminal = terminals.singleOrNull()
        val dark = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        val scheme = TermPalette.of(dark)
        return DebugSessionSnapshot(
            sourceSha = sourceSha,
            sourceValid = SOURCE_SHA.matches(sourceSha),
            fixture = fixtureKind,
            entry = entry,
            currentRef = fixture.currentRef,
            currentName = fixture.currentName,
            favoriteRefs = fixture.favorites.map { it.ref },
            viewRefs = fixture.viewRows.map { it.ref },
            favoriteSelections = fixture.favoriteSelections.toList(),
            viewSelections = fixture.viewSelections.toList(),
            keyValues = fixture.transport.inputFrames.flatMap { it.keys }.map { it.wire },
            inputTexts = fixture.transport.inputFrames.map { it.text },
            overlayOpen = fixture.vm.overlayOpen,
            viewOpenCount = fixture.viewOpenCount,
            backCount = fixture.backCount,
            terminalCount = terminals.size,
            terminalIdentity = terminal?.let(System::identityHashCode) ?: 0,
            presenterIdentity = terminal?.presenter?.let(System::identityHashCode) ?: 0,
            terminalRef = terminal?.sessionRef.orEmpty(),
            themeId = fixture.themeId,
            schemeId = scheme.source,
            chrome = SessionChromeColors.from(scheme).asStateString(),
        )
    }

    override fun onDestroy() {
        if (::fixture.isInitialized) fixture.dispose()
        super.onDestroy()
    }

    private fun changed() {
        if (Looper.myLooper() == Looper.getMainLooper()) revision++
        else runOnUiThread { revision++ }
    }

    data class DebugSessionSnapshot(
        val sourceSha: String,
        val sourceValid: Boolean,
        val fixture: String,
        val entry: String,
        val currentRef: String,
        val currentName: String,
        val favoriteRefs: List<String>,
        val viewRefs: List<String>,
        val favoriteSelections: List<Pair<String, String>>,
        val viewSelections: List<Pair<String, String>>,
        val keyValues: List<String>,
        val inputTexts: List<String>,
        val overlayOpen: Boolean,
        val viewOpenCount: Int,
        val backCount: Int,
        val terminalCount: Int,
        val terminalIdentity: Int,
        val presenterIdentity: Int,
        val terminalRef: String,
        val themeId: String,
        val schemeId: String,
        val chrome: String,
    ) {
        fun asSemanticState(): String =
            "session-ui-state source_sha=$sourceSha source_valid=$sourceValid fixture=$fixture entry=$entry " +
                "package=dev.agentmirror.app component=dev.agentmirror.app/.SessionUiAcceptanceActivity " +
                "current_ref=$currentRef current_name=$currentName favorite_refs=${favoriteRefs.joinToString(",")} " +
                "view_refs=${viewRefs.joinToString(",")} favorite_selected=${favoriteSelections.joinToString(",") { it.first }} " +
                "view_selected=${viewSelections.joinToString(",") { it.first }} keys=${keyValues.joinToString(",")} " +
                "inputs=${inputTexts.size} overlay=$overlayOpen view_open_count=$viewOpenCount back=$backCount terminal_count=$terminalCount " +
                "terminal_id=$terminalIdentity presenter_id=$presenterIdentity terminal_ref=$terminalRef " +
                "theme=$themeId scheme=$schemeId chrome=$chrome"
    }

    private companion object {
        val SOURCE_SHA = Regex("^[0-9a-f]{40}$")
    }
}

private class DebugSessionFixture(
    val vm: SessionViewModel,
    val manager: ConnectionManager,
    val transport: LocalSessionTransport,
    val favorites: List<FavoriteRow>,
    val viewRows: List<L2Entry>,
) {
    var currentRef = CURRENT_REF
    var currentName = "acceptance-current"
    val favoriteSelections = mutableListOf<Pair<String, String>>()
    val viewSelections = mutableListOf<Pair<String, String>>()
    var viewOpenCount = 0
    var backCount = 0
    var themeId = "vesper"

    fun dispose() {
        vm.dispose()
        manager.stop()
    }
}

private fun createDebugSessionFixture(
    context: android.content.Context,
    kind: String,
    onChanged: () -> Unit,
): DebugSessionFixture {
    context.applicationContext // keep construction explicitly scoped to this debug Activity.
    val transport = LocalSessionTransport(onChanged)
    val manager = ConnectionManager(
        ConnectionConfig("ws://debug.invalid", "debug-token"),
        TransportFactory { transport },
    )
    manager.start()
    transport.open()
    transport.deliverText(FrameCodec.encode(AuthAckFrame(ok = true)))
    val vm = SessionViewModel(
        manager,
        AttachmentUploader { _, _ -> UploadOutcome.Failure("unused debug fixture") },
        baseUrl = null,
        ref = CURRENT_REF,
        initialRows = 40,
        initialCols = 120,
    )
    val favorites = if (kind == "empty") {
        listOf(favorite(CURRENT_REF, "acceptance-current", online = true, order = 1))
    } else {
        listOf(
            favorite(CURRENT_REF, "acceptance-current", true, 1),
            favorite("online", "online-favorite", true, 2),
            favorite("offline", "offline-favorite", false, 3),
            favorite("long-1", "first-very-long-favorite-session-name-for-horizontal-scroll", true, 4),
            favorite("long-2", "second-very-long-favorite-session-name-for-horizontal-scroll", true, 5),
        )
    }
    val viewRows = listOf(
        view("view-a", "workspace-view-a"),
        view(if (kind == "empty") "must-not-render" else "view-b", "workspace-view-b"),
    )
    return DebugSessionFixture(vm, manager, transport, favorites, viewRows)
}

private fun favorite(ref: String, name: String, online: Boolean, order: Long) = FavoriteRow(
    sessionName = name,
    windowIndex = order.toString(),
    windowName = name,
    addedAt = order,
    isOnline = online,
    ref = ref,
)

private fun view(ref: String, name: String) = L2Entry(
    ref = ref,
    name = name,
    title = name,
    rows = 40,
    cols = 120,
    status = L2Status.IDLE,
    cwd = "/debug/workspace",
    sessionName = name,
    windowIndex = "1",
    windowName = name,
)

private class LocalSessionTransport(private val onChanged: () -> Unit) : WebSocketTransport {
    private var listener: TransportListener? = null
    override var isOpen = false
        private set
    val inputFrames = mutableListOf<InputFrame>()

    override fun start(listener: TransportListener) {
        this.listener = listener
    }

    fun open() {
        isOpen = true
        listener?.onOpen()
    }

    fun deliverText(text: String) {
        listener?.onText(text)
    }

    override fun sendText(text: String): Boolean {
        if (!isOpen) return false
        val frame = FrameCodec.decode(text)
        if (frame is InputFrame) {
            inputFrames += frame
            onChanged()
            Handler(Looper.getMainLooper()).post {
                if (isOpen) deliverText(FrameCodec.encode(InputAckFrame(frame.reqId, ok = true)))
            }
        }
        return true
    }

    override fun sendBinary(bytes: ByteArray): Boolean = isOpen

    override fun close(reason: String) {
        if (!isOpen) return
        isOpen = false
        listener?.onClosed(1000, reason)
    }
}

private fun SessionChromeColors.asStateString(): String = listOf(
    page,
    surface,
    text,
    accent,
    success,
    interrupt,
).joinToString(",") { (it.toArgb().toLong() and 0xffffffffL).toString(16) }

private const val CURRENT_REF = "current"
