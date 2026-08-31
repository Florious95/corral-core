package dev.agentmirror.app

/** Debug-only frozen acceptance state and classified 18-item boundary table. */
object SessionUiAcceptanceContract {
    data class Negative(val id: Int, val text: String, val disposition: String, val layer: String)
    val negative18 = listOf(
        Negative(1,"slash+Tab predictive echo","unchanged","static"),Negative(2,"Esc-Esc remote rollback echo","unchanged","static"),Negative(3,"focus expands input multiline","required","compose"),Negative(4,"attachment/send inside input","required","compose"),Negative(5,"hotkey row","required","compose"),Negative(6,"upload/sent overlay overlap fix","unchanged","static"),Negative(7,"CLI/theme visibility","required","compose"),Negative(8,"top-right 查看 dropdown","reused dock_view","compose"),Negative(9,"swipe closes 查看","unchanged","static"),Negative(10,"new session sorting","forbidden","model"),Negative(11,"Provider icon in first column","forbidden","compose"),Negative(12,"long-press favorite","forbidden","compose"),Negative(13,"close session/CLI","forbidden","ui-protocol"),Negative(14,"create new Agent","forbidden","ui-protocol"),Negative(15,"Provider command configuration","forbidden","ui-protocol"),Negative(16,"favorites Provider icon/long-press close","forbidden","static"),Negative(17,"status wording 运行","unchanged","static"),Negative(18,"foreground reconnect","unchanged","static"),
    )
    init { check(negative18.map { it.id }.toSet().size == 18) }
}
