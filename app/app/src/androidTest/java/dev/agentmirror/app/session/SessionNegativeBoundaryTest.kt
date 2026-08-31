package dev.agentmirror.app.session

import dev.agentmirror.app.SessionUiAcceptanceContract

/** Test-only dispatcher producing one observed result for each classified boundary ID. */
class SessionNegativeBoundaryTest {
    data class Result(val id: Int, val actualMatches: Int, val actualEmissions: Int)
    fun dispatch(uiMatches: Map<Int, Int>, emissions: Map<Int, Int>): List<Result> =
        SessionUiAcceptanceContract.negative18.map { row -> Result(row.id, uiMatches[row.id] ?: 0, emissions[row.id] ?: 0) }
}
