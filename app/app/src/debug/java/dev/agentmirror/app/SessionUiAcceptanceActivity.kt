package dev.agentmirror.app

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import dev.agentmirror.app.conn.*
import dev.agentmirror.app.session.*
import dev.agentmirror.app.ui.theme.AppTheme
import dev.agentmirror.app.workspace.*

/** Debug-only local production SessionScreen fixture; never merged into release. */
class SessionUiAcceptanceActivity : ComponentActivity() {
    private lateinit var fixture: DebugSessionFixture
    private var sourceSha = ""
    private var fixtureKind = "full"
    private var entry = "favorites"
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        sourceSha = intent.getStringExtra("source_sha").orEmpty()
        fixtureKind = intent.getStringExtra("session_ui_fixture") ?: "full"
        entry = intent.getStringExtra("entry") ?: "favorites"
        resetFixture(fixtureKind, entry, sourceSha)
    }
    fun resetFixture(kind: String, route: String, sha: String) { fixtureKind=kind; entry=route; sourceSha=sha; if (::fixture.isInitialized) fixture.dispose(); fixture=createDebugSessionFixture(); render() }
    fun snapshot(): DebugSessionSnapshot = DebugSessionSnapshot(sourceSha, fixtureKind, entry, fixture.vm.ref, fixture.selected, fixture.back, fixture.transport.sentText.size)
    fun selectThemeForTest(familyId: String) { fixture.themeId=familyId; render() }
    private fun render() = setContent {
        AppTheme { Column {
            SessionScreen(viewModel=fixture.vm,name=fixture.name,onBack={fixture.back++},overlaySessions=fixture.viewRows,favoriteRows=fixture.favorites,onOpenOverlaySession={r,n->fixture.selected=r to n})
            Text("session-ui source_sha=$sourceSha fixture=$fixtureKind entry=$entry current_ref=${fixture.vm.ref} selected=${fixture.selected} back=${fixture.back} sent=${fixture.transport.sentText.size} theme=${fixture.themeId}", modifier=androidx.compose.ui.Modifier.semantics { contentDescription="session-ui-state" })
        } }
    }
    override fun onDestroy() { if (::fixture.isInitialized) fixture.dispose(); super.onDestroy() }
    private fun createDebugSessionFixture(): DebugSessionFixture { val t=LocalSessionTransport(); val m=ConnectionManager(ConnectionConfig("ws://debug.invalid","debug"),TransportFactory{t}); m.start(); val vm=SessionViewModel(m,AttachmentUploader{_,_->UploadOutcome.Failure("debug")},null,"current",40,120); return DebugSessionFixture(vm,m,t) }
    data class DebugSessionSnapshot(val sourceSha:String,val fixture:String,val entry:String,val currentRef:String,val selected:Pair<String,String>?,val back:Int,val sent:Int)
    private class DebugSessionFixture(val vm:SessionViewModel,val manager:ConnectionManager,val transport:LocalSessionTransport) { val name="acceptance-current"; val favorites=listOf(FavoriteRow("current","","",1,true,ref="current"),FavoriteRow("online","","",2,true,ref="online"),FavoriteRow("offline","","",3,false,ref="offline")); val viewRows=emptyList<L2Entry>(); var selected:Pair<String,String>?=null; var back=0; var themeId="default"; fun dispose(){vm.dispose();manager.stop()} }
    private class LocalSessionTransport:WebSocketTransport { private var listener:TransportListener?=null; override var isOpen=false; private set; val sentText=mutableListOf<String>(); override fun start(l:TransportListener){listener=l;isOpen=true;l.onOpen()}; override fun sendText(text:String)=isOpen.also{if(it)sentText+=text}; override fun sendBinary(bytes:ByteArray)=isOpen; override fun close(reason:String){isOpen=false;listener?.onClosed(1000,reason)} }
}
