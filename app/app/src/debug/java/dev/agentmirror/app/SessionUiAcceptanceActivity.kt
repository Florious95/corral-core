package dev.agentmirror.app

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.TextFieldValue
import dev.agentmirror.app.conn.*
import dev.agentmirror.app.session.*
import dev.agentmirror.app.ui.theme.AppTheme
import dev.agentmirror.app.workspace.*
import dev.agentmirror.app.ui.model.SessionStatus

/** Debug-only local production SessionScreen fixture; never merged into release. */
class SessionUiAcceptanceActivity : ComponentActivity() {
    private lateinit var fixture: DebugSessionFixture
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        fixture = createDebugSessionFixture()
        render()
    }
    private fun render() = setContent {
        AppTheme {
            SessionScreen(viewModel=fixture.vm,name=fixture.name,onBack={fixture.back++},overlaySessions=fixture.viewRows,favoriteRows=fixture.favorites,onOpenOverlaySession={r,n->fixture.selected=r to n})
        }
    }
    override fun onDestroy() { if (::fixture.isInitialized) fixture.dispose(); super.onDestroy() }
    private fun createDebugSessionFixture(): DebugSessionFixture {
        val transport=LocalSessionTransport(); val manager=ConnectionManager(ConnectionConfig("ws://debug.invalid","debug"),TransportFactory{transport}); manager.start()
        val vm=SessionViewModel(manager,AttachmentUploader{_,_->UploadOutcome.Failure("debug")},null,"current",40,120); return DebugSessionFixture(vm,manager,transport)
    }
    private class DebugSessionFixture(val vm:SessionViewModel,val manager:ConnectionManager,val transport:LocalSessionTransport) {
        val name="acceptance-current"; val favorites=listOf(FavoriteRow("current","","",1,true,ref="current"),FavoriteRow("online","","",2,true,ref="online"),FavoriteRow("offline","","",3,false,ref="offline")); val viewRows=emptyList<L2Entry>(); var selected:Pair<String,String>?=null; var back=0
        fun dispose(){vm.dispose();manager.stop()}
    }
    private class LocalSessionTransport:WebSocketTransport { private var listener:TransportListener?=null; override var isOpen=false; private set; override fun start(l:TransportListener){listener=l;isOpen=true;l.onOpen()}; override fun sendText(text:String)=isOpen; override fun sendBinary(bytes:ByteArray)=isOpen; override fun close(reason:String){isOpen=false;listener?.onClosed(1000,reason)} }
}
