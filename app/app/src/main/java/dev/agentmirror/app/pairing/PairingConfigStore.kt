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

package dev.agentmirror.app.pairing

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import dev.agentmirror.app.diag.DiagLog
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 配对配置持久化抽象（纯 JVM 可注入假；生产用 [SharedPreferencesPairingConfigStore]）。
 */
interface PairingConfigStore {
    /** 读取已配对配置；无则 null（首启判定：null → 配对页）。 */
    fun load(): PairingConfig?

    /** 保存配对配置。 */
    fun save(config: PairingConfig)

    /** 清除（重新配对时可选）。 */
    fun clear()
}

/**
 * SharedPreferences 持久化实现。
 *
 * token 仍沿用既有 SharedPreferences 存储（历史欠账）；TS authkey 不能扩大这项风险：
 * 它以 Android Keystore 内 AES-GCM 密钥加密后才写入 prefs，磁盘上只出现 nonce+密文。
 * 解密失败按配置不可用处理（[load] 返回 null），绝不降级回明文。任何情况下 token / key
 * 都不得进入日志（协议 §2.1/§9 红线）。
 */
class SharedPreferencesPairingConfigStore(context: Context) : PairingConfigStore {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private var secretCipher: PairingSecretCipher = AndroidKeyStorePairingSecretCipher

    /** JVM 单测注入假加密器；生产构造只走 Android Keystore 实现。 */
    internal constructor(context: Context, secretCipher: PairingSecretCipher) : this(context) {
        this.secretCipher = secretCipher
    }

    override fun load(): PairingConfig? {
        val url = prefs.getString(KEY_URL, "").orEmpty()
        val token = prefs.getString(KEY_TOKEN, null)?.takeIf { it.isNotBlank() } ?: return null
        val hostId = prefs.getString(KEY_HOST_ID, null)?.takeIf { HostRouter.isValidHostId(it) }
        val storedLegacyUrl = prefs.getString(KEY_LEGACY_BOOTSTRAP_URL, null)
        val legacyBootstrapUrl = storedLegacyUrl ?:
            if (prefs.getInt(KEY_SCHEMA_VERSION, 0) < SCHEMA_VERSION && hostId == null) url.takeIf { it.isNotBlank() }
            else null
        if (url.isBlank() && hostId == null && legacyBootstrapUrl.isNullOrBlank()) return null
        val tsAuthKey = loadTsAuthKey() ?: return null
        // 凭据脱敏前置（registerSecret 坑一：注册前窗口）：token 刚从 prefs 读出的那一刻
        // 就注册，把「值在内存」到「registerSecret 生效」的窗口压到零。tsAuthKey 在
        // TsnetWire.ensureStarted 入口已注册；这里补 token（URL 若带 userinfo 由结构兜底拦）。
        DiagLog.registerSecret(token)
        tsAuthKey.takeIf { it.isNotEmpty() }?.let(DiagLog::registerSecret)
        return PairingConfig(
            url = url,
            token = token,
            tsAuthKey = tsAuthKey,
            hostId = hostId,
            port = prefs.getInt(KEY_PORT, 0).takeIf { it in 1..65535 },
            tsNodeId = prefs.getString(KEY_TS_NODE_ID, null),
            name = prefs.getString(KEY_NAME, null),
            legacyBootstrapUrl = legacyBootstrapUrl,
            lastTsUrl = prefs.getString(KEY_LAST_TS_URL, null),
            lastLanUrl = prefs.getString(KEY_LAST_LAN_URL, null),
            scanHints = prefs.getStringSet(KEY_SCAN_HINTS, emptySet()).orEmpty().toList(),
        )
    }

    override fun save(config: PairingConfig) {
        // 先完成加密再打开 editor：Keystore 失败时不留下 url/token 已更新、key 未更新的半配置。
        // KTX edit {}（ApplySharedPref/UseKtx stage3 #13）：默认 apply（异步落盘），保存路径
        // 不要求同步持久化——失败静默由下次读判断，语义与 apply 一致。
        val encryptedAuthKey = config.tsAuthKey.takeIf { it.isNotEmpty() }?.let(secretCipher::encrypt)
        prefs.edit {
            putInt(KEY_SCHEMA_VERSION, SCHEMA_VERSION)
            putString(KEY_URL, config.url)
            putString(KEY_TOKEN, config.token)
            if (config.hostId == null) remove(KEY_HOST_ID) else putString(KEY_HOST_ID, config.hostId)
            if (config.port == null) remove(KEY_PORT) else putInt(KEY_PORT, config.port)
            if (config.tsNodeId == null) remove(KEY_TS_NODE_ID) else putString(KEY_TS_NODE_ID, config.tsNodeId)
            if (config.name == null) remove(KEY_NAME) else putString(KEY_NAME, config.name)
            if (config.legacyBootstrapUrl == null) remove(KEY_LEGACY_BOOTSTRAP_URL) else putString(KEY_LEGACY_BOOTSTRAP_URL, config.legacyBootstrapUrl)
            if (config.lastTsUrl == null) remove(KEY_LAST_TS_URL) else putString(KEY_LAST_TS_URL, config.lastTsUrl)
            if (config.lastLanUrl == null) remove(KEY_LAST_LAN_URL) else putString(KEY_LAST_LAN_URL, config.lastLanUrl)
            putStringSet(KEY_SCAN_HINTS, config.scanHints.toSet())
            // 清掉前席曾使用的明文键；即使它存在，下一次成功保存也会完成迁移。
            remove(KEY_TS_AUTHKEY_LEGACY)
            if (encryptedAuthKey == null) {
                remove(KEY_TS_AUTHKEY_ENCRYPTED)
            } else {
                putString(KEY_TS_AUTHKEY_ENCRYPTED, encryptedAuthKey)
            }
        }
    }

    override fun clear() {
        // KTX edit {}（UseKtx stage3 #14）：清除路径无同步语义要求。
        prefs.edit {
            remove(KEY_SCHEMA_VERSION)
            remove(KEY_URL)
            remove(KEY_TOKEN)
            remove(KEY_HOST_ID)
            remove(KEY_PORT)
            remove(KEY_TS_NODE_ID)
            remove(KEY_NAME)
            remove(KEY_LEGACY_BOOTSTRAP_URL)
            remove(KEY_LAST_TS_URL)
            remove(KEY_LAST_LAN_URL)
            remove(KEY_SCAN_HINTS)
            remove(KEY_TS_AUTHKEY_ENCRYPTED)
            remove(KEY_TS_AUTHKEY_LEGACY)
        }
    }

    /**
     * 读取密文 key；前席/狗粮版若留下明文键则原地一次性迁移。迁移提交失败或密文损坏时
     * 返回 null，让整个配对配置失效并要求重新配对，绝不把无法保护的 key 当成功配置使用。
     */
    private fun loadTsAuthKey(): String? {
        val encrypted = prefs.getString(KEY_TS_AUTHKEY_ENCRYPTED, null)
        if (encrypted != null) return runCatching { secretCipher.decrypt(encrypted) }.getOrNull()

        val legacy = prefs.getString(KEY_TS_AUTHKEY_LEGACY, null) ?: return ""
        val migrated = runCatching { secretCipher.encrypt(legacy) }.getOrNull()
        if (migrated == null) {
            // Keystore 不可用时整份配置失效；只删 key 会让下次 load 把残余 url/token
            // 误认成合法 LAN 配置，静默丢失原 tailnet 语义。
            discardConfigAfterMigrationFailure()
            return null
        }
        val committed = prefs.edit()
            .putString(KEY_TS_AUTHKEY_ENCRYPTED, migrated)
            .remove(KEY_TS_AUTHKEY_LEGACY)
            .commit()
        if (!committed) {
            // 原子迁移提交失败后尽力清掉整份配置；load 仍失败关闭，不降级使用旧值。
            discardConfigAfterMigrationFailure()
            return null
        }
        return legacy
    }

    /** 失败迁移不能留下可被后续 load 当作 LAN 配置的 url/token 半配置。 */
    private fun discardConfigAfterMigrationFailure() {
        // KTX edit(commit = true)（ApplySharedPref/UseKtx stage3 #12/#15）：**必须同步落盘**——
        // apply() 是异步调度，若进程在写盘前被杀，遗留的 url/token 会被下次 load() 误读为
        // 合法 LAN 配置（load 只在 key 缺失时回 ""，url/token 残留即误配对）。这里删配置是
        // 迁移失败的收敛动作，同步提交保证失败即清、进程死亡不残留；KTX 的 commit 参数是
        // lint 认可的在需要同步语义时保留 commit 的写法（不触发 ApplySharedPref）。
        prefs.edit(commit = true) {
            remove(KEY_SCHEMA_VERSION)
            remove(KEY_URL)
            remove(KEY_TOKEN)
            remove(KEY_HOST_ID)
            remove(KEY_PORT)
            remove(KEY_TS_NODE_ID)
            remove(KEY_NAME)
            remove(KEY_LEGACY_BOOTSTRAP_URL)
            remove(KEY_LAST_TS_URL)
            remove(KEY_LAST_LAN_URL)
            remove(KEY_SCAN_HINTS)
            remove(KEY_TS_AUTHKEY_ENCRYPTED)
            remove(KEY_TS_AUTHKEY_LEGACY)
        }
    }

    private companion object {
        const val PREFS_NAME = "pairing_config"
        const val KEY_SCHEMA_VERSION = "schema_version"
        const val SCHEMA_VERSION = 2
        const val KEY_URL = "url"
        const val KEY_TOKEN = "token"
        const val KEY_HOST_ID = "host_id"
        const val KEY_PORT = "port"
        const val KEY_TS_NODE_ID = "ts_node_id"
        const val KEY_NAME = "name"
        const val KEY_LEGACY_BOOTSTRAP_URL = "legacy_bootstrap_url"
        const val KEY_LAST_TS_URL = "last_ts_url"
        const val KEY_LAST_LAN_URL = "last_lan_url"
        const val KEY_SCAN_HINTS = "scan_hints"
        const val KEY_TS_AUTHKEY_ENCRYPTED = "ts_authkey_encrypted"
        const val KEY_TS_AUTHKEY_LEGACY = "ts_authkey"
    }
}

/** authkey 封装接缝：生产用 Keystore，单测用确定性假件且不触 AndroidKeyStore。 */
internal interface PairingSecretCipher {
    fun encrypt(plainText: String): String
    fun decrypt(cipherText: String): String
}

/**
 * Android Keystore AES-GCM 封装。密钥不可导出；prefs 中格式为 Base64(iv长度|iv|密文+tag)。
 * 异常只由密码学/存储元数据产生，消息中没有传入明文，调用方也不记录异常。
 */
private object AndroidKeyStorePairingSecretCipher : PairingSecretCipher {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "agentmirror.pairing.ts_authkey"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    override fun encrypt(plainText: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        val iv = cipher.iv
        require(iv.size in 1..255) { "invalid AES-GCM IV length" }
        val payload = ByteArray(1 + iv.size + encrypted.size)
        payload[0] = iv.size.toByte()
        System.arraycopy(iv, 0, payload, 1, iv.size)
        System.arraycopy(encrypted, 0, payload, 1 + iv.size, encrypted.size)
        return Base64.encodeToString(payload, Base64.NO_WRAP)
    }

    override fun decrypt(cipherText: String): String {
        val payload = Base64.decode(cipherText, Base64.NO_WRAP)
        require(payload.isNotEmpty()) { "empty encrypted authkey" }
        val ivSize = payload[0].toInt() and 0xff
        require(ivSize > 0 && payload.size > 1 + ivSize) { "invalid encrypted authkey" }
        val iv = payload.copyOfRange(1, 1 + ivSize)
        val encrypted = payload.copyOfRange(1 + ivSize, payload.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        return cipher.doFinal(encrypted).toString(Charsets.UTF_8)
    }

    /** 读取既有不可导出密钥；首次保存时原子生成。 */
    @Synchronized
    private fun key(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return generator.generateKey()
    }
}
