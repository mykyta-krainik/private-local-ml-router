package com.example.privacyrouter.execution

data class FunctionCall(
    val function: String,
    val args: Map<String, Any?>,
)

sealed class ActionResult {
    data class Success(val description: String) : ActionResult()
    data class Failure(val reason: String) : ActionResult()
    data class Unknown(val function: String) : ActionResult()
}

sealed class ExecutionResult {
    data class Text(val body: String) : ExecutionResult()
    data class Action(val result: ActionResult) : ExecutionResult()
    data class Error(val message: String) : ExecutionResult()
}
