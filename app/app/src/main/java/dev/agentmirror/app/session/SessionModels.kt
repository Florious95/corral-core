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

package dev.agentmirror.app.session

/**
 * 会话页领域模型（纯 JVM，可单测）。
 *
 * 输入与附件两条动作管线（003 发送必达 + 图片附件）共享的取值与状态定义。
 * 上传结果与输入回执都是「可见成功或明确失败」——静默失效是最高罪。
 */

/** 一次附件上传：字节 + 展示名 + MIME（图片走 multipart HTTP，协议 §8）。 */
data class Attachment(
    val name: String,
    val mimeType: String,
    val bytes: ByteArray,
)

/** 附件上传的结果（协议 §8：成功返回主机绝对路径，失败给人类可读原因）。 */
sealed interface UploadOutcome {
    /** 服务端已把文件落盘主机；[path] 为绝对路径，作为 input.text 注入。 */
    data class Success(val path: String) : UploadOutcome

    /** 上传失败，[reason] 明确报错（静默失效猎杀）。 */
    data class Failure(val reason: String) : UploadOutcome
}

/**
 * 附件上传抽象：可注入的 IO 缝隙。
 *
 * 生产用 [HttpUrlConnectionUploader]（同端口 `POST /upload`，协议 §8）；
 * 单测注入假实现断言 path 插入光标处与失败报错。
 */
fun interface AttachmentUploader {
    fun upload(baseUrl: String, attachment: Attachment): UploadOutcome
}

/** 发送回执状态机（003 发送必达：成功/失败都可见）。 */
sealed interface InputStatus {
    /** 无在途发送。 */
    data object Idle : InputStatus

    /** input 帧已送出，等待 input_ack（或超时）。 */
    data object Sending : InputStatus

    /** input_ack ok：字节已进面板（瞬时态，UI 短暂展示后收起）。 */
    data object Sent : InputStatus

    /** input_ack fail / 超时 / 未就绪：输入框保留内容 + 明确报错。 */
    data class Failed(val message: String) : InputStatus
}

/** 附件上传状态机。 */
sealed interface UploadStatus {
    data object Idle : UploadStatus
    data object Uploading : UploadStatus

    /** 已拿到主机路径并注入输入框（瞬时态）。 */
    data class Success(val path: String) : UploadStatus

    data class Failed(val message: String) : UploadStatus
}
